package io.github.dreamandroid.local

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.dreamandroid.local.data.db.TaskEntity
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Debug test to isolate Queue screen crash when tasks exist.
 *
 * Scenario:
 * 1. Launch app → navigate to Queue tab (empty queue) → verify no crash
 * 2. Inject a dummy task via QueueRepository.addBatch()
 * 3. Wait for recomposition → observe crash or success
 *
 * Run on emulator:
 *   ./gradlew :app:connectedAndroidTest --tests "io.github.dreamandroid.local.QueueCrashDebugTest.*"
 */
@RunWith(AndroidJUnit4::class)
class QueueCrashDebugTest {

    companion object {
        private const val TAG = "QueueCrashDbg"
        private const val TIMEOUT_MS = 15_000L
    }

    private lateinit var device: UiDevice
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        Log.i(TAG, "========== QueueCrashDebugTest.setUp() ==========")
    }

    @After
    fun tearDown() {
        try {
            scenario.close()
        } catch (_: Exception) {}
        Log.i(TAG, "========== QueueCrashDebugTest.tearDown() ==========")
    }

    /**
     * Phase 1: Launch app, navigate to Queue tab with empty queue → verify no crash.
     */
    @Test
    fun phase1_emptyQueue_noCrash() {
        Log.i(TAG, "--- Phase 1: Launching MainActivity ---")
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            Log.i(TAG, "Phase 1: MainActivity launched successfully")
        } catch (e: Exception) {
            fail("Phase 1 FAILED: MainActivity launch crashed: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
        }

        // Wait for compose to render
        device.waitForIdle()
        Thread.sleep(2000)

        // Navigate to Queue tab by clicking "Queue" in bottom navigation
        Log.i(TAG, "Phase 1: Clicking Queue tab...")
        try {
            val queueTab = device.wait(Until.findObject(By.text("Queue")), TIMEOUT_MS)
            assertNotNull("Queue tab button not found in NavigationBar", queueTab)
            queueTab.click()
            Log.i(TAG, "Phase 1: Queue tab clicked")
        } catch (e: Exception) {
            fail("Phase 1 FAILED: Could not click Queue tab: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Wait for recomposition
        device.waitForIdle()
        Thread.sleep(3000)

        // The fact that we got here without a crash is already a pass for Phase 1
        Log.i(TAG, "Phase 1: COMPLETED - No crash on empty queue. App survived Queue tab navigation.")
    }

    /**
     * Phase 2: After Phase 1, inject a task via addBatch and observe.
     * Note: This runs after Phase 1 in the same scenario.
     */
    @Test
    fun phase2_injectTaskAndObserve() {
        Log.i(TAG, "--- Phase 2: Launching MainActivity ---")
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            Log.i(TAG, "Phase 2: MainActivity launched successfully")
        } catch (e: Exception) {
            fail("Phase 2 FAILED: MainActivity launch crashed: ${e.javaClass.simpleName}: ${e.message}")
        }

        device.waitForIdle()
        Thread.sleep(2000)

        // Get the application and QueueRepository
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as DreamAndroidApplication
        val repo = app.queueRepository
        Log.i(TAG, "Phase 2: QueueRepository obtained, current tasks.size=${repo.tasks.value.size}")

        // Navigate to Queue tab first (empty queue)
        Log.i(TAG, "Phase 2: Clicking Queue tab (should be empty)...")
        try {
            val queueTab = device.wait(Until.findObject(By.text("Queue")), TIMEOUT_MS)
            assertNotNull("Queue tab button not found", queueTab)
            queueTab.click()
        } catch (e: Exception) {
            fail("Phase 2 FAILED: Could not click Queue tab: ${e.message}")
        }

        device.waitForIdle()
        Thread.sleep(3000)

        Log.i(TAG, "Phase 2: Empty queue verified, now injecting dummy task via addBatch()...")

        // Inject a dummy task via QueueRepository.addBatch()
        // This updates both in-memory StateFlow and Room DB
        val batchId = try {
            repo.addBatch(
                modelId = "test-model-debug",
                prompt = "Debug test prompt - crash investigation",
                negativePrompt = "",
                steps = 20,
                cfg = 7.0f,
                seed = "42",
                width = 512,
                height = 512,
                effectiveWidth = 512,
                effectiveHeight = 512,
                denoiseStrength = 0.6f,
                useOpenCL = false,
                sampler = "euler",
                aspectRatio = "1:1",
                count = 1,
            )
        } catch (e: Exception) {
            fail("Phase 2 FAILED: addBatch() threw: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
        }

        Log.i(TAG, "Phase 2: addBatch() succeeded, batchId=$batchId")
        Log.i(TAG, "Phase 2: tasks.size=${repo.tasks.value.size}, tasks=${repo.tasks.value.map { it.id }}")

        // Wait for recomposition
        device.waitForIdle()
        Log.i(TAG, "Phase 2: Waiting for compose recomposition after task injection...")
        Thread.sleep(3000)

        // Try to read the queue state after injection
        val tasksAfterInject = repo.tasks.value
        Log.i(TAG, "Phase 2: After injection - tasks.size=${tasksAfterInject.size}")

        if (tasksAfterInject.isEmpty()) {
            Log.w(TAG, "Phase 2: WARNING - tasks list is still empty after injection. StateFlow may not have updated.")
        } else {
            Log.i(TAG, "Phase 2: Tasks present: ${tasksAfterInject.map { "id=${it.id}, status=${it.status}" }}")
        }

        val batchGroups = repo.getBatchGroups()
        Log.i(TAG, "Phase 2: batchGroups.size=${batchGroups.size}")
        batchGroups.forEach { group ->
            Log.i(TAG, "Phase 2:   group: id=${group.batchGroupId}, count=${group.count}, prompt=${group.prompt.take(50)}")
        }

        // Re-navigate to Queue tab to trigger recomposition with tasks
        Log.i(TAG, "Phase 2: Re-navigating to Queue tab with tasks present...")
        try {
            // Click another tab first then back to Queue to force recomposition
            val modelsTab = device.wait(Until.findObject(By.text("Models")), TIMEOUT_MS)
            modelsTab?.click()
            Thread.sleep(1000)
            val queueTab = device.wait(Until.findObject(By.text("Queue")), TIMEOUT_MS)
            queueTab?.click()
        } catch (e: Exception) {
            Log.w(TAG, "Phase 2: Tab navigation threw: ${e.message}")
        }

        device.waitForIdle()
        Thread.sleep(5000)

        // Check if the app is still alive (no crash)
        Log.i(TAG, "Phase 2: COMPLETED - app is still alive after task injection + navigation")
        Log.i(TAG, "Phase 2: If you see this message, the crash did NOT reproduce during this test run.")
        Log.i(TAG, "Phase 2: Check logcat for 'QueueDbg' and 'AndroidRuntime' for any crash traces.")
    }

    /**
     * Phase 3: Pre-load a task into Room DB before launching, then launch and navigate to Queue.
     * This simulates the scenario where tasks already exist in the database on app restart.
     */
    @Test
    fun phase3_preloadedTask_launchAndObserve() {
        Log.i(TAG, "--- Phase 3: Pre-loading task into Room DB before launch ---")

        // Get app context and DAO
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as DreamAndroidApplication

        // CRITICAL: Delete all existing queue tasks first for clean state
        val dao = app.database.taskDao()
        kotlinx.coroutines.runBlocking {
            dao.deleteAllQueue()
            Log.i(TAG, "Phase 3: Deleted all existing queue tasks from DB")
        }

        // Insert a dummy task directly into Room
        val taskId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val dummyTask = TaskEntity(
            id = taskId,
            taskType = TaskEntity.TYPE_QUEUE,
            modelId = "test-model-debug-3",
            prompt = "Phase3 preloaded task - crash debug",
            negativePrompt = "",
            steps = 20,
            cfg = 7.0f,
            seed = 12345L,
            width = 512,
            height = 512,
            denoiseStrength = null,
            useOpenCL = false,
            sampler = "euler",
            timestamp = now,
            batchGroupId = java.util.UUID.randomUUID().toString(),
            batchIndex = 0,
            effectiveWidth = 512,
            effectiveHeight = 512,
            aspectRatio = "1:1",
            status = "PENDING",
            resultSeed = null,
            errorMessage = null,
            progress = 0f,
        )

        kotlinx.coroutines.runBlocking {
            dao.insert(dummyTask)
            Log.i(TAG, "Phase 3: Inserted dummy task id=$taskId into Room DB")

            // Verify insertion
            val queueTasks = dao.getQueueAll()
            Log.i(TAG, "Phase 3: DB queue tasks count=${queueTasks.size}")
            queueTasks.forEach { t ->
                Log.i(TAG, "Phase 3:   DB task: id=${t.id}, status=${t.status}, prompt=${t.prompt.take(30)}")
            }
        }

        // Now launch the app - QueueRepository will restore from DB on init
        Log.i(TAG, "Phase 3: Launching MainActivity (QueueRepository will restore from DB)...")
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            Log.i(TAG, "Phase 3: MainActivity launched successfully")
        } catch (e: Exception) {
            fail("Phase 3 FAILED: App launch crashed (possibly during QueueRepository restore): ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
        }

        device.waitForIdle()
        Thread.sleep(3000)

        // Navigate to Queue tab
        Log.i(TAG, "Phase 3: Navigating to Queue tab with preloaded task...")
        try {
            val queueTab = device.wait(Until.findObject(By.text("Queue")), TIMEOUT_MS)
            assertNotNull("Queue tab not found", queueTab)
            queueTab.click()
            Log.i(TAG, "Phase 3: Queue tab clicked")
        } catch (e: Exception) {
            fail("Phase 3 FAILED: Could not click Queue tab: ${e.message}")
        }

        device.waitForIdle()
        Thread.sleep(5000)

        // Check if app is still alive
        Log.i(TAG, "Phase 3: COMPLETED - app survived Queue tab navigation with preloaded task")

        // Clean up after test
        kotlinx.coroutines.runBlocking {
            dao.deleteAllQueue()
            Log.i(TAG, "Phase 3: Cleaned up test data from DB")
        }
    }
}
