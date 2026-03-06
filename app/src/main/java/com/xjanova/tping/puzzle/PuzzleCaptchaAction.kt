package com.xjanova.tping.puzzle

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.xjanova.tping.data.entity.RecordedAction
import com.xjanova.tping.service.TpingAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object PuzzleCaptchaAction {

    private const val TAG = "PuzzleCaptcha"
    private val gson = Gson()

    /**
     * Execute the full CAPTCHA solve sequence:
     * 1. Parse config → 2. Screenshot → 3. Crop → 4. Analyze → 5. Swipe → 6. Verify/Retry
     *
     * Supports two slider modes:
     * - New (3-step): sliderButtonX/Y in PuzzleConfig — direct pixel drag from button position
     * - Legacy (4-step): slider bar bounds in RecordedAction.bounds* — ratio-based calculation
     *
     * @param statusCallback optional callback to report progress to the UI
     */
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
        Log.d(TAG, "inputText=${action.inputText}")
        Log.d(TAG, "bounds=L${action.boundsLeft} T${action.boundsTop} R${action.boundsRight} B${action.boundsBottom}")
        Log.d(TAG, "screen=${action.screenWidth}x${action.screenHeight}")

        val config: PuzzleConfig = try {
            gson.fromJson(action.inputText, PuzzleConfig::class.java)
        } catch (e: Exception) {
            status("❌ Config ผิดพลาด")
            Log.e(TAG, "Failed to parse PuzzleConfig: ${e.message}")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            status("❌ ต้อง Android 11+")
            return
        }

        status("โหลด OpenCV...")
        if (!PuzzleSolver.ensureOpenCV()) {
            status("❌ OpenCV โหลดไม่ได้")
            return
        }

        // Calculate scale factors for different screen resolutions
        val metrics = service.resources.displayMetrics
        val scaleX = if (action.screenWidth > 0)
            metrics.widthPixels.toFloat() / action.screenWidth else 1f
        val scaleY = if (action.screenHeight > 0)
            metrics.heightPixels.toFloat() / action.screenHeight else 1f
        Log.d(TAG, "Scale: ${scaleX}x$scaleY (current=${metrics.widthPixels}x${metrics.heightPixels})")

        // Scale puzzle region
        val scaledPuzzleLeft = (config.puzzleLeft * scaleX).toInt()
        val scaledPuzzleTop = (config.puzzleTop * scaleY).toInt()
        val scaledPuzzleRight = (config.puzzleRight * scaleX).toInt()
        val scaledPuzzleBottom = (config.puzzleBottom * scaleY).toInt()

        // Determine slider mode: new (button point) vs legacy (bar bounds)
        val useNewSlider = config.sliderButtonX > 0 && config.sliderButtonY > 0
        val scaledSliderButtonX = config.sliderButtonX * scaleX
        val scaledSliderButtonY = config.sliderButtonY * scaleY

        // Legacy slider bar bounds (from old 4-step recordings)
        val legacySliderLeft = action.boundsLeft * scaleX
        val legacySliderRight = action.boundsRight * scaleX
        val legacySliderCenterY = (action.boundsTop + action.boundsBottom) / 2f * scaleY

        Log.d(TAG, "Scaled puzzle=(${scaledPuzzleLeft},${scaledPuzzleTop})-(${scaledPuzzleRight},${scaledPuzzleBottom})")
        if (useNewSlider) {
            Log.d(TAG, "Slider button: ($scaledSliderButtonX, $scaledSliderButtonY)")
        } else {
            Log.d(TAG, "Legacy slider: left=$legacySliderLeft, right=$legacySliderRight, centerY=$legacySliderCenterY")
        }

        // Validate bounds
        if (scaledPuzzleRight <= scaledPuzzleLeft || scaledPuzzleBottom <= scaledPuzzleTop) {
            status("❌ พื้นที่ Puzzle ไม่ถูกต้อง")
            return
        }
        if (!useNewSlider && legacySliderRight <= legacySliderLeft) {
            status("❌ พื้นที่ Slider ไม่ถูกต้อง")
            return
        }

        for (attempt in 1..config.maxRetries) {
            status("ครั้งที่ $attempt/${config.maxRetries}: จับภาพ...")

            // Wait for CAPTCHA to render
            delay(if (attempt == 1) 800 else config.retryDelayMs)

            // Capture screenshot
            val screenshot = PuzzleScreenCapture.captureScreen()
            if (screenshot == null) {
                status("⚠ จับภาพไม่ได้ (ครั้งที่ $attempt)")
                continue
            }
            Log.d(TAG, "Screenshot: ${screenshot.width}x${screenshot.height}")

            // Crop puzzle region
            val puzzleBitmap = PuzzleScreenCapture.cropRegion(
                screenshot,
                scaledPuzzleLeft, scaledPuzzleTop,
                scaledPuzzleRight, scaledPuzzleBottom
            )
            screenshot.recycle()

            if (puzzleBitmap == null) {
                status("⚠ ครอปภาพไม่ได้")
                continue
            }
            Log.d(TAG, "Crop: ${puzzleBitmap.width}x${puzzleBitmap.height}")

            // Analyze with OpenCV
            status("ครั้งที่ $attempt: วิเคราะห์...")
            val gapOffsetX = PuzzleSolver.findGapOffset(puzzleBitmap, config.analyzeMethod)
            val puzzleWidth = puzzleBitmap.width
            puzzleBitmap.recycle()

            if (gapOffsetX < 0) {
                status("⚠ หาช่องว่างไม่พบ (ครั้งที่ $attempt)")
                continue
            }

            val gapPercent = (gapOffsetX * 100 / puzzleWidth)
            status("ครั้งที่ $attempt: พบที่ ${gapPercent}% → เลื่อน...")
            Log.d(TAG, "Gap at X=$gapOffsetX ($gapPercent% of $puzzleWidth)")

            // Calculate swipe coordinates based on slider mode
            val startX: Float
            val startY: Float
            val endX: Float

            if (useNewSlider) {
                // New method: direct pixel mapping from button position
                // gapOffsetX is in cropped-bitmap pixels = current-screen pixels
                // (because puzzle region was cropped from screenshot at current resolution)
                startX = scaledSliderButtonX
                startY = scaledSliderButtonY
                val jitter = if (attempt > 1) ((-8..8).random()).toFloat() else 0f
                endX = startX + gapOffsetX + jitter
                Log.d(TAG, "New swipe: button($startX,$startY) + gap=$gapOffsetX + jitter=$jitter → endX=$endX")
            } else {
                // Legacy method: ratio-based calculation from slider bar bounds
                val sliderWidth = (legacySliderRight - legacySliderLeft)
                val swipeDistance = calculateSwipeDistance(
                    gapOffsetX, puzzleWidth, sliderWidth, config.sliderPaddingPx.toFloat()
                )
                val jitter = if (attempt > 1) ((-10..10).random()).toFloat() else 0f
                startX = legacySliderLeft + config.sliderPaddingPx
                startY = legacySliderCenterY
                endX = (startX + swipeDistance + jitter)
                    .coerceIn(legacySliderLeft + config.sliderPaddingPx, legacySliderRight - config.sliderPaddingPx)
                Log.d(TAG, "Legacy swipe: $startX -> $endX, Y=$startY, dist=$swipeDistance, jitter=$jitter")
            }

            // Execute swipe gesture (press-hold-drag-release)
            val swipeDone = CompletableDeferred<Unit>()
            service.swipeGesture(
                startX, startY,
                endX, startY,
                config.swipeDurationMs
            ) { swipeDone.complete(Unit) }

            val swipeResult = withTimeoutOrNull(5000) { swipeDone.await() }
            if (swipeResult == null) {
                status("⚠ เลื่อนไม่สำเร็จ (timeout)")
                continue
            }

            status("ครั้งที่ $attempt: ตรวจสอบผล...")

            // Wait for CAPTCHA server to verify
            delay(1500)

            // Verify: check if CAPTCHA is still showing
            val verifyScreenshot = PuzzleScreenCapture.captureScreen()
            if (verifyScreenshot != null) {
                val verifyBitmap = PuzzleScreenCapture.cropRegion(
                    verifyScreenshot,
                    scaledPuzzleLeft, scaledPuzzleTop,
                    scaledPuzzleRight, scaledPuzzleBottom
                )
                verifyScreenshot.recycle()

                if (verifyBitmap != null) {
                    val verifyGap = PuzzleSolver.findGapOffset(verifyBitmap, config.analyzeMethod)
                    verifyBitmap.recycle()

                    if (verifyGap >= 0 && attempt < config.maxRetries) {
                        status("⚠ ยังไม่ผ่าน ลองใหม่...")
                        continue
                    }
                }
            }

            status("✓ แก้ Captcha สำเร็จ")
            return
        }

        status("❌ แก้ไม่สำเร็จ หลัง ${config.maxRetries} ครั้ง")
    }

    /** Legacy ratio-based swipe distance calculation (for old 4-step recordings) */
    fun calculateSwipeDistance(
        gapOffsetX: Int,
        puzzleWidth: Int,
        sliderWidth: Float,
        sliderPaddingPx: Float = 30f
    ): Float {
        if (puzzleWidth <= 0) return 0f
        val effectiveSliderWidth = sliderWidth - (2 * sliderPaddingPx)
        return (gapOffsetX.toFloat() / puzzleWidth) * effectiveSliderWidth
    }
}
