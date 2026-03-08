package com.xjanova.tping.puzzle

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.xjanova.tping.data.diagnostic.DiagnosticReporter
import com.xjanova.tping.data.entity.RecordedAction
import com.xjanova.tping.service.TpingAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Puzzle CAPTCHA solver — simple single-swipe approach.
 *
 * 1. Screenshot → detect gap (OpenCV) or use blind offset
 * 2. Single swipe from slider → target
 * 3. Verify → refresh → retry
 */
object PuzzleCaptchaAction {

    private const val TAG = "PuzzleCaptcha"
    private val gson = Gson()

    suspend fun execute(
        service: TpingAccessibilityService,
        action: RecordedAction,
        statusCallback: ((String) -> Unit)? = null
    ) {
        fun status(msg: String) {
            Log.d(TAG, msg)
            statusCallback?.invoke(msg)
        }

        status("เริ่มแก้ Captcha...")

        // === Parse config ===
        val config: PuzzleConfig = try {
            gson.fromJson(action.inputText, PuzzleConfig::class.java)
        } catch (e: Exception) {
            status("❌ Config ผิดพลาด: ${e.message?.take(50)}")
            DiagnosticReporter.logCaptcha("Config parse error", "error=${e.message}")
            autoSendDiagnostics()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            status("❌ ต้อง Android 11+")
            return
        }

        if (config.sliderButtonX <= 0 || config.sliderButtonY <= 0) {
            status("❌ กรุณาบันทึก Captcha ใหม่ (แบบ 2 จุด)")
            DiagnosticReporter.logCaptcha("Bad config", "sliderX=${config.sliderButtonX}, sliderY=${config.sliderButtonY}")
            autoSendDiagnostics()
            return
        }

        // === OpenCV ===
        val opencvOk = PuzzleSolver.ensureOpenCV()
        if (!opencvOk) {
            status("⚠ OpenCV ไม่พร้อม — ใช้โหมดเลื่อนอัตโนมัติ")
            DiagnosticReporter.logCaptcha("OpenCV failed", "blind-drag mode")
        } else {
            status("✓ OpenCV พร้อม")
        }

        // === Scale coordinates ===
        val metrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val scaleX = if (action.screenWidth > 0) screenW.toFloat() / action.screenWidth else 1f
        val scaleY = if (action.screenHeight > 0) screenH.toFloat() / action.screenHeight else 1f

        val sliderX = config.sliderButtonX * scaleX
        val sliderY = config.sliderButtonY * scaleY
        val refreshX = config.refreshButtonX * scaleX
        val refreshY = config.refreshButtonY * scaleY
        val hasRefresh = config.refreshButtonX > 0 && config.refreshButtonY > 0

        val info = "slider=(${sliderX.toInt()},${sliderY.toInt()}), screen=${screenW}x$screenH, " +
            "scale=${"%.2f".format(scaleX)}x${"%.2f".format(scaleY)}, " +
            "raw=(${config.sliderButtonX},${config.sliderButtonY}), " +
            "recorded=${action.screenWidth}x${action.screenHeight}"
        Log.d(TAG, info)
        DiagnosticReporter.logCaptcha("Captcha start", info)

        // === TEST: Verify gesture dispatch works ===
        status("ทดสอบ gesture...")
        val testResult = testGestureDispatch(service, sliderX, sliderY)
        DiagnosticReporter.logCaptcha("Gesture test", "result=$testResult")
        if (testResult == "dispatch_failed") {
            status("❌ ระบบ gesture ไม่ทำงาน!")
            DiagnosticReporter.logCaptcha("CRITICAL: dispatchGesture returns false", info)
            autoSendDiagnostics()
            return
        }
        status("✓ gesture ทำงาน ($testResult)")
        delay(500)

        // Blind-drag offsets
        val blindOffsets = intArrayOf(200, 350, 500, 150, 450)
        var blindIndex = 0

        for (attempt in 1..config.maxRetries) {
            status("ครั้งที่ $attempt/${config.maxRetries}")
            delay(if (attempt == 1) 1500 else config.retryDelayMs)

            // === Determine target X ===
            var targetX: Float
            var dragMode: String

            if (opencvOk) {
                val screenshot = PuzzleScreenCapture.captureScreen()
                if (screenshot != null) {
                    val gap = PuzzleSolver.findDarkRegion(screenshot, sliderY.toInt())
                    screenshot.recycle()
                    if (gap != null) {
                        targetX = (gap.left + gap.right) / 2f
                        dragMode = "smart"
                        val pct = ((targetX / screenW) * 100).toInt()
                        status("ครั้งที่ $attempt: พบช่องว่างที่ $pct%")
                    } else {
                        val offset = blindOffsets[blindIndex % blindOffsets.size]; blindIndex++
                        targetX = (sliderX + offset).coerceAtMost(screenW - 50f)
                        dragMode = "blind(no-gap)"
                        status("ครั้งที่ $attempt: เลื่อน ${offset}px")
                    }
                } else {
                    val offset = blindOffsets[blindIndex % blindOffsets.size]; blindIndex++
                    targetX = (sliderX + offset).coerceAtMost(screenW - 50f)
                    dragMode = "blind(no-screenshot)"
                    status("ครั้งที่ $attempt: เลื่อน ${offset}px")
                }
            } else {
                val offset = blindOffsets[blindIndex % blindOffsets.size]; blindIndex++
                targetX = (sliderX + offset).coerceAtMost(screenW - 50f)
                dragMode = "blind(no-opencv)"
                status("ครั้งที่ $attempt: เลื่อน ${offset}px")
            }

            val dragDist = targetX - sliderX
            if (dragDist < 10) {
                DiagnosticReporter.logCaptcha("Skip: dist=${"%.0f".format(dragDist)}", "mode=$dragMode")
                status("⚠ ระยะน้อยเกินไป (${"%.0f".format(dragDist)}px)")
                continue
            }

            // === Execute swipe ===
            val duration = (dragDist * 2.5f).toLong().coerceIn(600, 2000)
            status("ครั้งที่ $attempt: เลื่อนสไลด์ ($dragMode)...")

            val swipeResult = doSwipe(service, sliderX, sliderY, targetX, sliderY, duration)

            Log.d(TAG, "Swipe result=$swipeResult, mode=$dragMode, attempt=$attempt, dist=${"%.0f".format(dragDist)}")
            DiagnosticReporter.logCaptcha("Swipe attempt=$attempt", "result=$swipeResult, mode=$dragMode, dist=${"%.0f".format(dragDist)}, target=${targetX.toInt()}")

            if (swipeResult == "dispatch_failed") {
                status("❌ gesture dispatch ล้มเหลว!")
                continue
            } else if (swipeResult == "cancelled") {
                status("⚠ gesture ถูกยกเลิก")
                continue
            } else if (swipeResult == "timeout") {
                status("⚠ gesture หมดเวลา")
                continue
            }

            // swipeResult == "completed"
            status("ครั้งที่ $attempt: ✓ เลื่อนแล้ว ตรวจสอบ...")
            delay(2500)

            // === Verify ===
            if (opencvOk) {
                val verifyShot = PuzzleScreenCapture.captureScreen()
                if (verifyShot != null) {
                    val stillHasGap = PuzzleSolver.findDarkRegion(verifyShot, sliderY.toInt())
                    verifyShot.recycle()
                    if (stillHasGap == null) {
                        status("✓ แก้ Captcha สำเร็จ!")
                        DiagnosticReporter.logCaptcha("Solved", "attempt=$attempt, mode=$dragMode")
                        autoSendDiagnostics()
                        return
                    }
                }
            }

            // === Refresh ===
            if (attempt < config.maxRetries && hasRefresh) {
                status("🔄 กดรีเฟรช...")
                val tapDone = CompletableDeferred<Unit>()
                service.tapAtCoordinates(refreshX, refreshY) { tapDone.complete(Unit) }
                withTimeoutOrNull(3000) { tapDone.await() }
                delay(2000)
            }
        }

        status("⚠ ลอง ${config.maxRetries} ครั้งแล้ว")
        DiagnosticReporter.logCaptcha("All attempts done", "maxRetries=${config.maxRetries}, opencv=$opencvOk")
        autoSendDiagnostics()
    }

    /**
     * Test that gesture dispatch works by sending a tiny tap at the slider position.
     * Returns: "completed", "cancelled", "dispatch_failed", or "timeout"
     */
    private suspend fun testGestureDispatch(
        service: TpingAccessibilityService,
        x: Float, y: Float
    ): String {
        val result = CompletableDeferred<String>()
        service.swipeGesture(x, y, x + 1f, y, 50) { success ->
            result.complete(if (success) "completed" else "cancelled_or_failed")
        }
        return withTimeoutOrNull(5000) { result.await() } ?: "timeout"
    }

    /**
     * Execute a swipe and return the result.
     * Returns: "completed", "cancelled", "dispatch_failed", or "timeout"
     */
    private suspend fun doSwipe(
        service: TpingAccessibilityService,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long
    ): String {
        val result = CompletableDeferred<String>()
        service.swipeGesture(startX, startY, endX, endY, durationMs) { success ->
            result.complete(if (success) "completed" else "cancelled")
        }
        return withTimeoutOrNull(durationMs + 5000) { result.await() } ?: "timeout"
    }

    /** Auto-send diagnostics in background (fire & forget) */
    private fun autoSendDiagnostics() {
        try {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    DiagnosticReporter.sendReport()
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }
}
