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
     * 1. Parse config → 2. Screenshot → 3. Crop → 4. Analyze → 5. Swipe → 6. Retry
     */
    suspend fun execute(
        service: TpingAccessibilityService,
        action: RecordedAction,
        callback: () -> Unit
    ) {
        val config: PuzzleConfig = try {
            gson.fromJson(action.inputText, PuzzleConfig::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PuzzleConfig: ${e.message}")
            callback()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "SOLVE_CAPTCHA requires API 30+, skipping")
            callback()
            return
        }

        if (!PuzzleSolver.ensureOpenCV()) {
            Log.e(TAG, "OpenCV not available, skipping")
            callback()
            return
        }

        // Calculate scale factors
        val metrics = service.resources.displayMetrics
        val scaleX = if (action.screenWidth > 0)
            metrics.widthPixels.toFloat() / action.screenWidth else 1f
        val scaleY = if (action.screenHeight > 0)
            metrics.heightPixels.toFloat() / action.screenHeight else 1f

        // Scale puzzle region
        val scaledPuzzleLeft = (config.puzzleLeft * scaleX).toInt()
        val scaledPuzzleTop = (config.puzzleTop * scaleY).toInt()
        val scaledPuzzleRight = (config.puzzleRight * scaleX).toInt()
        val scaledPuzzleBottom = (config.puzzleBottom * scaleY).toInt()

        // Scale slider bar bounds
        val sliderLeft = action.boundsLeft * scaleX
        val sliderRight = action.boundsRight * scaleX
        val sliderCenterY = (action.boundsTop + action.boundsBottom) / 2f * scaleY

        for (attempt in 1..config.maxRetries) {
            Log.d(TAG, "CAPTCHA solve attempt $attempt/${config.maxRetries}")

            // Wait for CAPTCHA to render
            delay(if (attempt == 1) 500 else config.retryDelayMs)

            // Capture screenshot
            val screenshot = PuzzleScreenCapture.captureScreen()
            if (screenshot == null) {
                Log.w(TAG, "Screenshot capture failed on attempt $attempt")
                continue
            }

            // Crop puzzle region
            val puzzleBitmap = PuzzleScreenCapture.cropRegion(
                screenshot,
                scaledPuzzleLeft, scaledPuzzleTop,
                scaledPuzzleRight, scaledPuzzleBottom
            )
            screenshot.recycle()

            if (puzzleBitmap == null) {
                Log.w(TAG, "Crop failed on attempt $attempt")
                continue
            }

            // Analyze with OpenCV
            val gapOffsetX = PuzzleSolver.findGapOffset(puzzleBitmap, config.analyzeMethod)
            val puzzleWidth = puzzleBitmap.width
            puzzleBitmap.recycle()

            if (gapOffsetX < 0) {
                Log.w(TAG, "Gap detection failed on attempt $attempt")
                continue
            }

            // Calculate swipe distance
            val sliderWidth = (sliderRight - sliderLeft).toInt()
            val swipeDistance = calculateSwipeDistance(
                gapOffsetX, puzzleWidth, sliderWidth, config.sliderPaddingPx
            )

            // Add jitter on retries
            val jitter = if (attempt > 1) ((-8..8).random()) else 0

            val startX = sliderLeft + config.sliderPaddingPx
            val endX = (startX + swipeDistance + jitter)
                .coerceAtMost(sliderRight - config.sliderPaddingPx)

            Log.d(TAG, "Swipe: gapX=$gapOffsetX, puzzleW=$puzzleWidth, " +
                    "sliderW=$sliderWidth, distance=$swipeDistance, jitter=$jitter")

            // Execute swipe
            val swipeDone = CompletableDeferred<Unit>()
            service.swipeGesture(
                startX, sliderCenterY,
                endX, sliderCenterY,
                config.swipeDurationMs
            ) { swipeDone.complete(Unit) }

            withTimeoutOrNull(3000) { swipeDone.await() }

            // Wait for CAPTCHA to process
            delay(1000)

            Log.d(TAG, "Swipe completed on attempt $attempt")
            callback()
            return
        }

        Log.w(TAG, "CAPTCHA solve failed after ${config.maxRetries} attempts")
        callback()
    }

    fun calculateSwipeDistance(
        gapOffsetX: Int,
        puzzleWidth: Int,
        sliderWidth: Int,
        sliderPaddingPx: Int = 20
    ): Int {
        if (puzzleWidth <= 0) return 0
        val effectiveSliderWidth = sliderWidth - (2 * sliderPaddingPx)
        return ((gapOffsetX.toFloat() / puzzleWidth) * effectiveSliderWidth).toInt()
    }
}
