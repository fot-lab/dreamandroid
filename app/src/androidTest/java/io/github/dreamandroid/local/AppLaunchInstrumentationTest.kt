package io.github.dreamandroid.local

import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup smoke tests running on arm64-v8a emulator in CI.
 *
 * Covers:
 * - DATA-STOR-0003: RecordRepository → Room DB init on main thread
 * - BKND-PROC-0007: BackendManager heavy init chain from ViewModel constructors
 * - CORO-EXEC-0003: runBlocking in by lazy blocking main thread
 * - LCLE-MEMO-0002: Lazy-init patterns with absent dependencies
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchInstrumentationTest {

    @Test
    fun applicationContext_isDreamAndroidApplication() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertTrue(
            "Application must be DreamAndroidApplication, got ${app.javaClass.simpleName}",
            app is DreamAndroidApplication
        )
    }

    @Test
    fun application_onCreate_completedWithoutCrash() {
        // targetContext.applicationContext internally triggers Application.onCreate().
        // If it throws, this test never reaches the assertion.
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application
        assertNotNull("Application context should not be null after onCreate()", app)
    }

    @Test
    fun mainActivity_launchesWithoutCrash() {
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertNotNull("MainActivity should not be null", activity)
                    assertTrue("Activity should not be finishing", !activity.isFinishing)
                }
            }
        } catch (e: Exception) {
            fail("MainActivity launch crashed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Test
    fun criticalDependencies_resolveWithoutCrash() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as DreamAndroidApplication

        try {
            // DATA-STOR-0003: AppDatabase.get() on main thread
            val db = app.database
            assertNotNull("AppDatabase should not be null", db)
            assertNotNull(
                "openHelper should not be null (confirms Room DB opened successfully)",
                db.openHelper
            )
        } catch (e: Exception) {
            fail("database lazy-init crashed: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            // BKND-PROC-0007: BackendManager → BackendService chain
            val svc = app.backendService
            assertNotNull("BackendService should not be null", svc)
        } catch (e: Exception) {
            fail("backendService lazy-init crashed: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            // LCLE-MEMO-0002: QueueRepository singleton init
            val repo = app.queueRepository
            assertNotNull("QueueRepository should not be null", repo)
        } catch (e: Exception) {
            fail("queueRepository init crashed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
