package io.github.dreamandroid.local

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import io.github.dreamandroid.local.data.HistoryMigration
import io.github.dreamandroid.local.data.MigrationState
import io.github.dreamandroid.local.data.db.AppDatabase
import io.github.dreamandroid.local.service.QueueRepository
import io.github.dreamandroid.local.service.backend.BackendManager
import io.github.dreamandroid.local.service.backend.BackendService
import io.github.dreamandroid.local.service.backend.RuntimeDirPreparer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DreamAndroidApplication : Application(), ComponentCallbacks2 {

    companion object {
        private const val TAG = "DreamAndroidApp"
    }

    // ── Coroutine Scopes ──

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Dependencies (initialized lazily) ──

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    val backendManager: BackendManager by lazy {
        try {
            BackendManager(this).also {
                // Pre-warm runtime dir
                appScope.launch {
                    try {
                        RuntimeDirPreparer.prepare(this@DreamAndroidApplication)
                    } catch (_: Exception) {
                        // Non-fatal; will retry on first backend use
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "BackendManager initialization failed", e)
            throw IllegalStateException("BackendManager not available", e)
        }
    }

    /**
     * Unified HTTP middleware for UI layer.
     * Screen Composables and ViewModels MUST use [backendService] instead of
     * directly accessing [backendManager] for all backend communication.
     */
    val backendService: BackendService by lazy {
        try {
            BackendService(backendManager)
        } catch (e: Exception) {
            Log.e(TAG, "BackendService initialization failed", e)
            throw IllegalStateException("BackendService not available", e)
        }
    }

    /** Process-wide singleton queue repository, shared between UI and WorkManager Worker. */
    val queueRepository: QueueRepository
        get() = QueueRepository.getInstance(this)

    // ── Migration State ──

    private val _migrationState = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val migrationState: StateFlow<MigrationState> = _migrationState.asStateFlow()

    private var migrationJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        // ── P0: Pre-warm backend service chain (BKND-PROC-0007) ──
        // If initialization fails, backend features degrade gracefully
        // instead of crashing during ViewModel construction.
        try {
            backendService
        } catch (e: Exception) {
            Log.e(TAG, "BackendService init failed, backend features unavailable", e)
        }

        // ── P0: Register shutdown hook to kill C++ backend on JVM normal exit ──
        Runtime.getRuntime().addShutdownHook(Thread({
            runCatching { backendManager.stopProcessImmediate() }
        }, "BackendShutdownHook"))

        // ── P0/P1: Register crash handler to kill C++ backend on uncaught exception ──
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            runCatching { backendManager.stopProcessImmediate() }
            // Re-throw to original handler for crash dialog, then kill process
            originalHandler?.uncaughtException(thread, throwable)
            if (originalHandler == null) {
                throwable.printStackTrace()
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        startMigration()
    }

    private fun startMigration() {
        migrationJob?.cancel()
        migrationJob = appScope.launch {
            try {
                if (HistoryMigration.isDone(this@DreamAndroidApplication)) {
                    _migrationState.value = MigrationState.NotNeeded
                    return@launch
                }
                HistoryMigration.migrate(
                    this@DreamAndroidApplication,
                    database,
                    _migrationState,
                )
            } catch (e: Throwable) {
                _migrationState.value = MigrationState.Failed(e)
            }
        }
    }

    fun retryMigration() {
        _migrationState.value = MigrationState.Idle
        startMigration()
    }

    fun skipMigration() {
        migrationJob?.cancel()
        appScope.launch {
            try {
                HistoryMigration.markDoneExternal(this@DreamAndroidApplication)
            } catch (_: Throwable) {
                // ignore — UI will still proceed
            }
            _migrationState.value = MigrationState.Done
        }
    }

    // ── P1: Memory pressure response ──

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                // Moderate pressure: recycle cached bitmaps
                Log.i(TAG, "onTrimMemory: TRIM_MEMORY_RUNNING_MODERATE — recycling completed bitmaps")
                queueRepository.recycleCompletedBitmaps()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // Low memory: recycle bitmaps + clear QNN lib cache
                Log.w(TAG, "onTrimMemory: TRIM_MEMORY_RUNNING_LOW — aggressive cleanup")
                queueRepository.recycleCompletedBitmaps()
                RuntimeDirPreparer.cleanup(this)
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // Critical pressure: recycle bitmaps but NEVER auto-stop backend.
                // Backend lifecycle is managed manually by the user; auto-stop
                // would kill queue processing mid-flight.
                Log.wtf(TAG, "onTrimMemory: TRIM_MEMORY_RUNNING_CRITICAL — recycling bitmaps (backend untouched)")
                queueRepository.recycleCompletedBitmaps()
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // App moved to background: release optional resources
                Log.i(TAG, "onTrimMemory: TRIM_MEMORY_UI_HIDDEN — releasing caches")
                queueRepository.recycleCompletedBitmaps()
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // Graceful shutdown: kill C++ process, cancel coroutines
        runCatching { backendManager.stopProcessImmediate() }
        appScope.cancel()
    }
}
