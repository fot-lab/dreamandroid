package io.github.dreamandroid.local

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.dreamandroid.local.data.GenerationTask
import io.github.dreamandroid.local.data.TaskStatus
import io.github.dreamandroid.local.service.QueueRepository
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprehensive crash investigation: Task progress display in Queue tab.
 *
 * Scenario:
 * 1. Launch app → navigate to Generate tab → set prompt "hello world"
 * 2. Inject first fake task into QueueRepository (simulating "Add to Queue" via
 *    fake backend → QueueRepository is the endpoint where progress data lands)
 * 3. Navigate to Queue tab → verify task appears as PENDING (no crash)
 * 4. Simulate backend progress: update task to PROCESSING with step-by-step
 *    progress (10% → 30% → 50% → 70% → 90%) — THIS IS WHERE CRASH IS EXPECTED
 * 5. Mark first task as COMPLETED
 * 6. Navigate back to Generate → inject second fake task
 * 7. Navigate to Queue → simulate second task progress updates
 * 8. Capture crash via logcat / thread exception handler
 *
 * The fake HTTP service is simulated by directly calling QueueRepository methods
 * (addBatch, updateTaskProgress, markTaskProcessing, markTaskComplete) — the
 * exact same calls that GenerationWorker makes after receiving SSE events from
 * the C++ backend. This bypasses the need for an actual HTTP server while
 * exercising the identical code path that triggers the crash.
 *
 * Run on emulator:
 *   ./gradlew :app:connectedAndroidTest --tests "io.github.dreamandroid.local.QueueProgressCrashTest.*"
 */
@RunWith(AndroidJUnit4::class)
class QueueProgressCrashTest {

    companion object {
        private const val TAG = "QueueProgCrash"
        private const val TIMEOUT_MS = 20_000L
        private const val STEP_DELAY_MS = 300L
    }

    private lateinit var device: UiDevice
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var repo: QueueRepository

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
            Log.e(TAG, "CRASH STACKTRACE:\n${throwable.stackTraceToString()}")
            capturedCrash = throwable
            // Re-throw so test fails visibly
            originalHandler?.uncaughtException(thread, throwable)
        }

        // Clean DB before test
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as DreamAndroidApplication
        kotlinx.coroutines.runBlocking {
            app.database.taskDao().deleteAllQueue()
            Log.i(TAG, "Cleaned all queue tasks from DB")
        }
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
    // Phase 1: Single task — progress display crash
    // ═══════════════════════════════════════════════════════════

    @Test
    fun phase1_singleTask_progressDisplayCrash() {
        Log.i(TAG, "━━━ Phase 1: Single task progress display ━━━")

        // ── 1a: Launch app ──
        launchApp()

        // ── 1b: Navigate to Generate tab ──
        navigateToTab("Generate")

        // ── 1c: Try to type "hello world" into the prompt field ──
        // The prompt TextField has the hint "Enter prompt..." (R.string.prompt_hint)
        tryTypePrompt("hello world")

        // ── 1d: Inject first fake task (simulating backend receiving add-to-queue) ──
        Log.i(TAG, "Phase 1: Injecting first fake task via QueueRepository.addBatch()...")
        val batchId1 = injectFakeTask(
            prompt = "hello world",
            modelId = "fake-model-1",
            seed = "42",
        )
        Log.i(TAG, "Phase 1: First task injected, batchId=$batchId1")
        logQueueState("after first injection")

        // ── 1e: Navigate to Queue tab → check PENDING display ──
        navigateToTab("Queue")
        Thread.sleep(2000)
        assertAppAlive("after navigating to Queue with PENDING task")

        // ── 1f: Simulate backend progress updates ──
        // Mark as PROCESSING, then feed progress 10%→30%→50%→70%→90%
        Log.i(TAG, "Phase 1: Simulating backend progress (10%→90%)...")
        val tasks1 = repo.tasks.value
        assertTrue("No tasks in queue after injection", tasks1.isNotEmpty())
        val taskId1 = tasks1.first().id
        Log.i(TAG, "Phase 1: Task id=$taskId1, current status=${tasks1.first().status}")

        // Mark PROCESSING
        repo.markTaskProcessing(taskId1)
        Thread.sleep(STEP_DELAY_MS)
        Log.i(TAG, "Phase 1: Task marked PROCESSING, tasks=${repo.tasks.value.map { "${it.id}:${it.status}:${it.progress}" }}")
        assertAppAlive("after markTaskProcessing")

        // Feed progress
        val progressSteps = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
        for ((idx, pct) in progressSteps.withIndex()) {
            repo.updateTaskProgress(taskId1, pct)
            Thread.sleep(STEP_DELAY_MS)
            Log.i(TAG, "Phase 1: Progress ${(pct * 100).toInt()}% — alive=${capturedCrash == null}")
            if (capturedCrash != null) {
                fail("CRASH at progress ${(pct * 100).toInt()}%: ${capturedCrash!!.javaClass.simpleName}: ${capturedCrash!!.message}\n${capturedCrash!!.stackTraceToString()}")
            }
        }
        Log.i(TAG, "Phase 1: All progress steps sent without crash")

        // ── 1g: Force Queue recomposition by tab navigation ──
        navigateToTab("Models")
        Thread.sleep(1000)
        navigateToTab("Queue")
        Thread.sleep(3000)
        assertAppAlive("after re-navigating to Queue with PROCESSING + progress")

        // ── 1h: Mark first task COMPLETED ──
        repo.markTaskComplete(taskId1, bitmapPath = null, seed = 42L)
        Thread.sleep(STEP_DELAY_MS)
        Log.i(TAG, "Phase 1: Task marked COMPLETED")
        assertAppAlive("after markTaskComplete")

        // Force recomposition
        navigateToTab("Models")
        Thread.sleep(1000)
        navigateToTab("Queue")
        Thread.sleep(3000)
        assertAppAlive("after viewing COMPLETED task in Queue")

        Log.i(TAG, "━━━ Phase 1: COMPLETED — no crash detected ━━━")
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 2: Second task — progress display after first completed
    // ═══════════════════════════════════════════════════════════

    @Test
    fun phase2_secondTask_progressAfterFirstCompleted() {
        Log.i(TAG, "━━━ Phase 2: Second task progress after first completed ━━━")

        launchApp()
        navigateToTab("Generate")
        tryTypePrompt("hello world")

        // Inject and complete first task
        val batchId1 = injectFakeTask("hello world", "fake-model-2a", "100")
        val taskId1 = repo.tasks.value.first().id
        repo.markTaskProcessing(taskId1)
        repo.updateTaskProgress(taskId1, 0.5f)
        Thread.sleep(STEP_DELAY_MS)
        repo.markTaskComplete(taskId1, null, 100L)
        Thread.sleep(STEP_DELAY_MS)
        Log.i(TAG, "Phase 2: First task completed, tasks.size=${repo.tasks.value.size}")

        // Navigate to Queue to view completed task
        navigateToTab("Queue")
        Thread.sleep(2000)
        assertAppAlive("after viewing first completed task in Queue")

        // Go back to Generate, inject second task
        navigateToTab("Generate")
        Thread.sleep(1000)

        val batchId2 = injectFakeTask("hello world second", "fake-model-2b", "200")
        val taskId2 = repo.tasks.value.last().id
        Log.i(TAG, "Phase 2: Second task injected, id=$taskId2, tasks.size=${repo.tasks.value.size}")
        logQueueState("after second injection")

        // Navigate to Queue
        navigateToTab("Queue")
        Thread.sleep(2000)
        assertAppAlive("after navigating to Queue with 2 tasks (1 completed + 1 pending)")

        // Simulate progress on second task
        repo.markTaskProcessing(taskId2)
        Thread.sleep(STEP_DELAY_MS)
        Log.i(TAG, "Phase 2: Second task marked PROCESSING")

        val progressSteps2 = listOf(0.1f, 0.2f, 0.35f, 0.5f, 0.65f, 0.8f, 0.95f)
        for ((idx, pct) in progressSteps2.withIndex()) {
            repo.updateTaskProgress(taskId2, pct)
            Thread.sleep(STEP_DELAY_MS)
            Log.i(TAG, "Phase 2: Progress ${(pct * 100).toInt()}% — alive=${capturedCrash == null}")
            if (capturedCrash != null) {
                fail("CRASH on second task at progress ${(pct * 100).toInt()}%: ${capturedCrash!!.javaClass.simpleName}: ${capturedCrash!!.message}\n${capturedCrash!!.stackTraceToString()}")
            }
        }

        // Force recomposition
        navigateToTab("Models")
        Thread.sleep(1000)
        navigateToTab("Queue")
        Thread.sleep(3000)
        assertAppAlive("after second task progress + re-navigation")

        // Complete second task
        repo.markTaskComplete(taskId2, null, 200L)
        Thread.sleep(STEP_DELAY_MS)
        assertAppAlive("after second task complete")

        Log.i(TAG, "━━━ Phase 2: COMPLETED — no crash detected ━━━")
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 3: Rapid progress updates while viewing Queue
    // ═══════════════════════════════════════════════════════════

    @Test
    fun phase3_rapidProgress_whileViewingQueue() {
        Log.i(TAG, "━━━ Phase 3: Rapid progress while viewing Queue ━━━")

        launchApp()
        navigateToTab("Generate")
        tryTypePrompt("hello world rapid test")

        // Inject task
        injectFakeTask("hello world rapid test", "fake-model-3", "300")
        val taskId = repo.tasks.value.first().id

        // Navigate to Queue FIRST
        navigateToTab("Queue")
        Thread.sleep(2000)
        assertAppAlive("after navigating to Queue with PENDING task (Phase 3)")

        // Now start processing while user is viewing Queue
        repo.markTaskProcessing(taskId)
        Thread.sleep(STEP_DELAY_MS)
        Log.i(TAG, "Phase 3: Task PROCESSING while viewing Queue")

        // Rapid progress updates (simulating fast SSE events)
        val rapidSteps = (1..20).map { it / 20f }
        for ((idx, pct) in rapidSteps.withIndex()) {
            repo.updateTaskProgress(taskId, pct)
            // Only small delay — rapid updates
            Thread.sleep(100)
            if (idx % 5 == 0) {
                Log.i(TAG, "Phase 3: Rapid progress ${(pct * 100).toInt()}% — alive=${capturedCrash == null}")
            }
            if (capturedCrash != null) {
                fail("CRASH during rapid progress at ${(pct * 100).toInt()}%: ${capturedCrash!!.javaClass.simpleName}: ${capturedCrash!!.message}\n${capturedCrash!!.stackTraceToString()}")
            }
        }

        Thread.sleep(2000)
        assertAppAlive("after rapid progress updates while viewing Queue")

        // Complete
        repo.markTaskComplete(taskId, null, 300L)
        Thread.sleep(1000)
        assertAppAlive("after rapid task complete while viewing Queue")

        Log.i(TAG, "━━━ Phase 3: COMPLETED — no crash detected ━━━")
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 4: Tab switching during progress
    // ═══════════════════════════════════════════════════════════

    @Test
    fun phase4_tabSwitching_duringProgress() {
        Log.i(TAG, "━━━ Phase 4: Tab switching during progress ━━━")

        launchApp()
        navigateToTab("Generate")
        tryTypePrompt("tab switch test")

        injectFakeTask("tab switch test", "fake-model-4", "400")
        val taskId = repo.tasks.value.first().id

        repo.markTaskProcessing(taskId)
        repo.updateTaskProgress(taskId, 0.2f)
        Thread.sleep(STEP_DELAY_MS)

        // Navigate to Queue
        navigateToTab("Queue")
        Thread.sleep(1000)
        assertAppAlive("Phase 4: Queue tab with 20% progress")

        // Switch away and back rapidly (stress test recomposition)
        val tabs = listOf("Models", "Queue", "Generate", "Queue", "Gallery", "Queue")
        for ((idx, tab) in tabs.withIndex()) {
            navigateToTab(tab)
            Thread.sleep(500)
            // Update progress during tab switches
            val pct = ((idx + 2) * 10f) / 100f
            repo.updateTaskProgress(taskId, pct.coerceAtMost(1f))
            Log.i(TAG, "Phase 4: Tab=$tab progress=${(pct * 100).toInt()}% alive=${capturedCrash == null}")
            if (capturedCrash != null) {
                fail("CRASH during tab switch to '$tab': ${capturedCrash!!.javaClass.simpleName}: ${capturedCrash!!.message}")
            }
        }

        // End on Queue with complete
        repo.markTaskComplete(taskId, null, 400L)
        Thread.sleep(1000)
        assertAppAlive("Phase 4: after tab switch stress + completion")

        Log.i(TAG, "━━━ Phase 4: COMPLETED — no crash detected ━━━")
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 5: Preloaded tasks with progress on cold launch
    // ═══════════════════════════════════════════════════════════

    @Test
    fun phase5_preloadedProgress_onColdLaunch() {
        Log.i(TAG, "━━━ Phase 5: Preloaded tasks with progress on cold launch ━━━")

        // Clean DB
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as DreamAndroidApplication
        kotlinx.coroutines.runBlocking {
            app.database.taskDao().deleteAllQueue()
        }

        // Insert tasks directly into Room with PROCESSING status + progress
        val taskId1 = java.util.UUID.randomUUID().toString()
        val taskId2 = java.util.UUID.randomUUID().toString()
        val batchGroupId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val task1 = io.github.dreamandroid.local.data.db.TaskEntity(
            id = taskId1,
            taskType = io.github.dreamandroid.local.data.db.TaskEntity.TYPE_QUEUE,
            modelId = "preload-model-5a",
            prompt = "preloaded task 1 - progress test",
            negativePrompt = "",
            steps = 20,
            cfg = 7f,
            seed = 5001L,
            width = 512,
            height = 512,
            denoiseStrength = null,
            useOpenCL = false,
            sampler = "euler",
            timestamp = now,
            batchGroupId = batchGroupId,
            batchIndex = 0,
            effectiveWidth = 512,
            effectiveHeight = 512,
            aspectRatio = "1:1",
            status = "PROCESSING",
            resultSeed = null,
            errorMessage = null,
            progress = 0.45f,
        )
        val task2 = io.github.dreamandroid.local.data.db.TaskEntity(
            id = taskId2,
            taskType = io.github.dreamandroid.local.data.db.TaskEntity.TYPE_QUEUE,
            modelId = "preload-model-5b",
            prompt = "preloaded task 2 - progress test",
            negativePrompt = "",
            steps = 30,
            cfg = 5f,
            seed = 5002L,
            width = 512,
            height = 768,
            denoiseStrength = null,
            useOpenCL = false,
            sampler = "dpm",
            timestamp = now + 1,
            batchGroupId = batchGroupId,
            batchIndex = 1,
            effectiveWidth = 512,
            effectiveHeight = 768,
            aspectRatio = "2:3",
            status = "PROCESSING",
            resultSeed = null,
            errorMessage = null,
            progress = 0.72f,
        )

        kotlinx.coroutines.runBlocking {
            app.database.taskDao().insertAll(listOf(task1, task2))
            Log.i(TAG, "Phase 5: Preloaded ${app.database.taskDao().getQueueAll().size} tasks into Room DB with progress")
        }

        // Cold launch — QueueRepository.restoreFromDb() will reset PROCESSING→PENDING
        // and progress→0f. We want to test if the app crashes during this restore.
        launchApp()
        Thread.sleep(3000)

        navigateToTab("Queue")
        Thread.sleep(3000)
        assertAppAlive("Phase 5: after cold launch with preloaded progress tasks")

        // Inject new task with fresh progress simulation
        injectFakeTask("cold launch fresh task", "fake-model-5c", "503")
        val newTaskId = repo.tasks.value.last().id
        repo.markTaskProcessing(newTaskId)
        repo.updateTaskProgress(newTaskId, 0.3f)
        Thread.sleep(STEP_DELAY_MS)
        repo.updateTaskProgress(newTaskId, 0.6f)
        Thread.sleep(STEP_DELAY_MS)
        repo.updateTaskProgress(newTaskId, 0.9f)
        Thread.sleep(STEP_DELAY_MS)

        // Re-navigate to trigger recomposition
        navigateToTab("Models")
        Thread.sleep(1000)
        navigateToTab("Queue")
        Thread.sleep(3000)
        assertAppAlive("Phase 5: after fresh progress on preloaded DB")

        // Clean
        kotlinx.coroutines.runBlocking {
            app.database.taskDao().deleteAllQueue()
        }

        Log.i(TAG, "━━━ Phase 5: COMPLETED — no crash detected ━━━")
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private fun launchApp() {
        Log.i(TAG, "Launching MainActivity...")
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            Log.i(TAG, "MainActivity launched successfully")
        } catch (e: Exception) {
            fail("MainActivity launch crashed: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
        }
        device.waitForIdle()
        Thread.sleep(3000)

        // Get QueueRepository
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as DreamAndroidApplication
        repo = app.queueRepository
        Log.i(TAG, "QueueRepository obtained, tasks.size=${repo.tasks.value.size}")
    }

    private fun navigateToTab(tabName: String) {
        Log.i(TAG, "Navigating to tab: '$tabName'...")
        try {
            val tab = device.wait(Until.findObject(By.text(tabName)), TIMEOUT_MS)
            assertNotNull("Tab '$tabName' not found in NavigationBar", tab)
            tab.click()
            Log.i(TAG, "Tab '$tabName' clicked")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            fail("Could not click tab '$tabName': ${e.javaClass.simpleName}: ${e.message}")
        }
        device.waitForIdle()
        Thread.sleep(1500)
    }

    private fun tryTypePrompt(text: String) {
        // Try to find the prompt TextField and type into it.
        // The prompt TextField uses hint from R.string.prompt_hint
        // Fallback: try contentDescription "Prompt" or the editable text field
        try {
            // Approach 1: Look for a focused or editable text field
            val textFields = device.findObjects(By.clazz("android.widget.EditText"))
            if (textFields != null && textFields.isNotEmpty()) {
                val tf = textFields[0]
                tf.click()
                Thread.sleep(300)
                tf.clear()
                tf.text = text
                Log.i(TAG, "Typed '$text' into EditText field")
            } else {
                // Approach 2: Try by text selection / search
                // The compose TextField has semantics; try content description
                val promptField = device.wait(Until.findObject(By.desc("Prompt")), 3000)
                if (promptField != null) {
                    promptField.click()
                    Thread.sleep(300)
                    promptField.text = text
                    Log.i(TAG, "Typed '$text' into prompt field (by desc)")
                } else {
                    // Approach 3: The TextField may have no content description.
                    // For Compose, try clicking the main content area and using
                    // the keyboard. If we can't find it, we still inject the
                    // task directly — the prompt field exercise is secondary.
                    Log.w(TAG, "Could not find prompt TextField; continuing without typing")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryTypePrompt failed (non-fatal): ${e.message}")
        }
        device.waitForIdle()
        Thread.sleep(500)
        // Dismiss the soft keyboard — crucial: the open keyboard covers the
        // NavigationBar, preventing UiAutomator from finding tab labels by text.
        try {
            device.pressBack()
            Log.i(TAG, "Keyboard dismissed after typing")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dismiss keyboard: ${e.message}")
        }
        Thread.sleep(300)
    }

    /**
     * Inject a fake task into QueueRepository.
     * This simulates what happens when the backend receives an "add to queue" request
     * and the GenerationWorker calls QueueRepository.addBatch().
     */
    private fun injectFakeTask(
        prompt: String,
        modelId: String = "fake-test-model",
        seed: String = "42",
    ): String {
        return try {
            repo.addBatch(
                modelId = modelId,
                prompt = prompt,
                negativePrompt = "",
                steps = 20,
                cfg = 7.0f,
                seed = seed,
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
            fail("injectFakeTask failed: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
            throw e  // unreachable — fail() throws, but satisfies Kotlin type inference (String vs Unit)
        }
    }

    private fun logQueueState(label: String) {
        val tasks = repo.tasks.value
        Log.i(TAG, "Queue state [$label]: tasks.size=${tasks.size}")
        tasks.forEach { t ->
            Log.i(TAG, "  id=${t.id.take(8)}... status=${t.status} progress=${t.progress} prompt=${t.prompt.take(40)}")
        }
    }

    private fun assertAppAlive(context: String) {
        if (capturedCrash != null) {
            fail("App CRASHED $context: ${capturedCrash!!.javaClass.simpleName}: ${capturedCrash!!.message}\n${capturedCrash!!.stackTraceToString()}")
        }
        try {
            // Quick health check: try reading queue state
            repo.tasks.value.size
        } catch (e: Exception) {
            fail("App appears dead $context: ${e.javaClass.simpleName}: ${e.message}")
        }
        Log.i(TAG, "App alive check: OK ($context)")
    }
}
