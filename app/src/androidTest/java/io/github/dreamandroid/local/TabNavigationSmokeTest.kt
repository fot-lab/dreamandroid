package io.github.dreamandroid.local

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tab navigation smoke test — verifies all 5 bottom NavigationBar tabs
 * are reachable without crash, and captures a screenshot of each.
 *
 * Run on emulator:
 *   ./gradlew :app:connectedAndroidTest --tests "io.github.dreamandroid.local.TabNavigationSmokeTest.*"
 */
@RunWith(AndroidJUnit4::class)
class TabNavigationSmokeTest {

    companion object {
        private const val TAG = "TabNavSmoke"
        private const val TIMEOUT_MS = 15_000L
    }

    private lateinit var device: UiDevice
    private lateinit var scenario: ActivityScenario<MainActivity>

    private var capturedCrash: Throwable? = null
    private val originalHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        Log.i(TAG, "========== setUp() ==========")

        // Install crash capturer
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "CAPTURED CRASH in thread ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}")
            capturedCrash = throwable
            originalHandler?.uncaughtException(thread, throwable)
        }

        // Launch app
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            Log.i(TAG, "MainActivity launched")
        } catch (e: Exception) {
            fail("MainActivity launch crashed: ${e.javaClass.simpleName}: ${e.message}")
        }
        device.waitForIdle()
        Thread.sleep(3000)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        try {
            Log.i(TAG, "tearDown: capturedCrash=${capturedCrash?.javaClass?.simpleName}")
            scenario.close()
        } catch (_: Exception) {}
        Log.i(TAG, "========== tearDown() ==========")
    }

    // ═══════════════════════════════════════════════════════════
    // Navigate all 5 tabs, verify no crash, capture screenshots
    // ═══════════════════════════════════════════════════════════

    @Test
    fun navigateAllTabs_smokeTest() {
        Log.i(TAG, "━━━ Tab Navigation Smoke Test ━━━")

        // The NavigationBar labels come from string resources:
        // nav_models="Models"  nav_queue="Queue"  nav_generate="Generate"
        // nav_upscale="Upscale"  nav_browse="Gallery"
        val tabs = listOf("Models", "Queue", "Generate", "Upscale", "Gallery")

        for ((idx, tabName) in tabs.withIndex()) {
            Log.i(TAG, "[${idx + 1}/${tabs.size}] Navigating to '$tabName'...")

            navigateToTab(tabName)
            Thread.sleep(1500)
            assertAppAlive("after navigating to $tabName tab")

            captureScreenshot(tabName)
            Log.i(TAG, "[${idx + 1}/${tabs.size}] '$tabName' OK")
        }

        Log.i(TAG, "━━━ All ${tabs.size} tabs navigated successfully ━━━")
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    /** Find the NavigationBar item by its displayed text label and click it. */
    private fun navigateToTab(tabName: String) {
        try {
            val tab = device.wait(Until.findObject(By.text(tabName)), TIMEOUT_MS)
            assertNotNull("Tab '$tabName' not found in NavigationBar", tab)
            tab.click()
            Log.i(TAG, "  Clicked tab: $tabName")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            fail("Could not click tab '$tabName': ${e.javaClass.simpleName}: ${e.message}")
        }
        device.waitForIdle()
    }

    /** Save a screenshot of the current screen to /sdcard/tab_XXX.png */
    private fun captureScreenshot(tabName: String) {
        try {
            val file = File("/sdcard/tab_$tabName.png")
            // Remove stale file if exists
            file.delete()
            val ok = device.takeScreenshot(file)
            if (ok) {
                Log.i(TAG, "  Screenshot saved: ${file.absolutePath} (${file.length()} bytes)")
            } else {
                Log.w(TAG, "  Screenshot: takeScreenshot returned false for $tabName")
            }
        } catch (e: Exception) {
            Log.w(TAG, "  Screenshot error for $tabName: ${e.message}")
        }
    }

    /** Fail if the previously installed crash capturer caught a throwable. */
    private fun assertAppAlive(context: String) {
        if (capturedCrash != null) {
            fail("App CRASHED $context: ${capturedCrash!!.javaClass.simpleName}: ${capturedCrash!!.message}\n${capturedCrash!!.stackTraceToString()}")
        }
    }
}
