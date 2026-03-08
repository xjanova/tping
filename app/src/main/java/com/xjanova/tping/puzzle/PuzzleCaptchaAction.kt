package com.xjanova.tping.puzzle

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.xjanova.tping.data.diagnostic.DiagnosticReporter
import com.xjanova.tping.data.entity.RecordedAction
import com.xjanova.tping.service.TpingAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Puzzle CAPTCHA solver — uses simple single-swipe gestures.
 *
 * Strategy:
 *   1. Take screenshot → detect gap with OpenCV
 *   2. Single swipe from slider button → gap position (or blind offset)
 *   3. Wait → verify → refresh → retry if needed
 *
 * Uses swipeGesture (simple press-drag-release in one gesture)
 * NOT swipeWithContinuation (continuation chains break on many devices).
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
            DiagnosticReporter.logCaptcha("Config parse error", "inputText=${action.inputText?.take(200)}, error=${e.message}")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            status("❌ ต้อง Android 11+")
            DiagnosticReporter.logCaptcha("Android < 11", "SDK=${Build.VERSION.SDK_INT}")
            return
        }

        if (config.sliderButtonX <= 0 || config.sliderButtonY <= 0) {
            status("❌ กรุณาบันทึก Captcha ใหม่ (แบบ 2 จุด)")
            DiagnosticReporter.logCaptcha("Old format config", "sliderX=${config.sliderButtonX}, sliderY=${config.sliderButtonY}")
            return
        }

        // === OpenCV ===
        status("โหลด OpenCV...")
        val opencvOk = PuzzleSolver.ensureOpenCV()
        if (!opencvOk) {
            status("⚠ OpenCV ไม่พร้อม — ใช้โหมดเลื่อนอัตโนมัติ")
            DiagnosticReporter.logCaptcha("OpenCV load failed", "blind-drag mode")
        }

        // === Scale coordinates ===
        val metrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels
        val scaleX = if (action.screenWidth > 0) screenW.toFloat() / action.screenWidth else 1f
        val scaleY = if (action.screenHeight > 0) metrics.heightPixels.toFloat() / action.screenHeight else 1f

        val sliderX = config.sliderButtonX * scaleX
        val sliderY = config.sliderButtonY * scaleY
        val refreshX = config.refreshButtonX * scaleX
        val refreshY = config.refreshButtonY * scaleY
        val hasRefresh = config.refreshButtonX > 0 && config.refreshButtonY > 0

        val info = "slider=(${sliderX.toInt()},${sliderY.toInt()}), screen=${screenW}x${metrics.heightPixels}, scale=${"%.2f".format(scaleX)}x${"%.2f".format(scaleY)}, raw=(${config.sliderButtonX},${config.sliderButtonY}), recorded=${action.screenWidth}x${action.screenHeight}"
        Log.d(TAG, info)
        DiagnosticReporter.logCaptcha("Captcha start", info)

        // Blind-drag offsets — pixels to drag RIGHT from slider position
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
                        Log.d(TAG, "Gap found at x=${targetX.toInt()} ($pct%), gap=$gap")
                        status("ครั้งที่ $attempt: พบช่องว่างที่ $pct%")
                    } else {
                        // Gap not found — use blind offset
                        val offset = blindOffsets[blindIndex % blindOffsets.size]
                        blindIndex++
                        targetX = (sliderX + offset).coerceAtMost(screenW - 50f)
                        dragMode = "blind(gap-not-found)"
                        Log.d(TAG, "Gap not found → blind offset=$offset → targetX=${targetX.toInt()}")
                        status("ครั้งที่ $attempt: เลื่อน ${offset}px")
                    }
                } else {
                    // Screenshot failed — use blind offset
                    val offset = blindOffsets[blindIndex % blindOffsets.size]
                    blindIndex++
                    targetX = (sliderX + offset).coerceAtMost(screenW - 50f)
                    dragMode = "blind(screenshot-fail)"
                    Log.d(TAG, "Screenshot failed → blind offset=$offset")
                    status("ครั้งที่ $attempt: เลื่อน ${offset}px")
                }
            } else {
                // OpenCV not available — use blind offset
                val offset = blindOffsets[blindIndex % blindOffsets.size]
                blindIndex++
                targetX = (sliderX + offset).coerceAtMost(screenW - 50f)
                dragMode = "blind(no-opencv)"
                status("ครั้งที่ $attempt: เลื่อน ${offset}px")
            }

            val dragDist = targetX - sliderX
            if (dragDist < 10) {
                Log.w(TAG, "Drag distance too small: $dragDist (sliderX=$sliderX, targetX=$targetX)")
                DiagnosticReporter.logCaptcha("Skip: distance=${"%.0f".format(dragDist)}", "mode=$dragMode, attempt=$attempt")
                status("⚠ ระยะเลื่อนน้อยเกินไป")
                continue
            }

            // === Single swipe: slider → target ===
            // Duration: slower = more human-like, 600-2000ms based on distance
            val duration = (dragDist * 2.5f).toLong().coerceIn(600, 2000)
            Log.d(TAG, "SWIPE: ($sliderX,$sliderY) → ($targetX,$sliderY), dist=${"%.0f".format(dragDist)}, dur=${duration}ms, mode=$dragMode")
            status("ครั้งที่ $attempt: เลื่อนสไลด์...")

            val swipeDone = CompletableDeferred<Unit>()
            service.swipeGesture(sliderX, sliderY, targetX, sliderY, duration) {
                swipeDone.complete(Unit)
            }

            val ok = withTimeoutOrNull(10000) { swipeDone.await() }
            if (ok == null) {
                Log.w(TAG, "Swipe gesture timed out!")
                DiagnosticReporter.logCaptcha("Swipe timeout", "attempt=$attempt, mode=$dragMode")
                status("⚠ gesture timeout")
                continue
            }
            Log.d(TAG, "Swipe completed: mode=$dragMode, attempt=$attempt")

            // === Verify ===
            status("ครั้งที่ $attempt: ตรวจสอบ...")
            delay(2500)

            if (opencvOk) {
                val verifyShot = PuzzleScreenCapture.captureScreen()
                if (verifyShot != null) {
                    val stillHasGap = PuzzleSolver.findDarkRegion(verifyShot, sliderY.toInt())
                    verifyShot.recycle()
                    if (stillHasGap == null) {
                        status("✓ แก้ Captcha สำเร็จ!")
                        DiagnosticReporter.logCaptcha("Solved", "attempt=$attempt, mode=$dragMode")
                        return
                    }
                    Log.d(TAG, "Still has gap after swipe — trying again")
                }
            }

            // === Refresh and retry ===
            if (attempt < config.maxRetries) {
                if (hasRefresh) {
                    status("🔄 กดรีเฟรช...")
                    val tapDone = CompletableDeferred<Unit>()
                    service.tapAtCoordinates(refreshX, refreshY) { tapDone.complete(Unit) }
                    withTimeoutOrNull(3000) { tapDone.await() }
                    delay(2000)
                }
            }
        }

        // All attempts exhausted — assume last one might have worked
        status("⚠ ลอง ${config.maxRetries} ครั้งแล้ว")
        DiagnosticReporter.logCaptcha("All attempts done", "maxRetries=${config.maxRetries}, opencv=$opencvOk, $info")
    }
}
