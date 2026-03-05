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
        action: RecordedAction
    ) {
        Log.d(TAG, "=== SOLVE_CAPTCHA START ===")
        Log.d(TAG, "inputText=${action.inputText}")
        Log.d(TAG, "bounds=L${action.boundsLeft} T${action.boundsTop} R${action.boundsRight} B${action.boundsBottom}")
        Log.d(TAG, "screen=${action.screenWidth}x${action.screenHeight}, gameMode=${action.isGameMode}")

        val config: PuzzleConfig = try {
            gson.fromJson(action.inputText, PuzzleConfig::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PuzzleConfig: ${e.message}")
            return
        }
        Log.d(TAG, "Config parsed: puzzle=(${config.puzzleLeft},${config.puzzleTop})-(${config.puzzleRight},${config.puzzleBottom})")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "SOLVE_CAPTCHA requires API 30+ (current: ${Build.VERSION.SDK_INT}), skipping")
            return
        }

        Log.d(TAG, "Initializing OpenCV...")
        if (!PuzzleSolver.ensureOpenCV()) {
            Log.e(TAG, "OpenCV not available, skipping")
            return
        }
        Log.d(TAG, "OpenCV OK")

        // Calculate scale factors
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

        // Scale slider bar bounds
        val sliderLeft = action.boundsLeft * scaleX
        val sliderRight = action.boundsRight * scaleX
        val sliderCenterY = (action.boundsTop + action.boundsBottom) / 2f * scaleY

        Log.d(TAG, "Scaled puzzle=(${scaledPuzzleLeft},${scaledPuzzleTop})-(${scaledPuzzleRight},${scaledPuzzleBottom})")
        Log.d(TAG, "Slider: left=$sliderLeft, right=$sliderRight, centerY=$sliderCenterY")

        for (attempt in 1..config.maxRetries) {
            Log.d(TAG, "--- Attempt $attempt/${config.maxRetries} ---")

            // Wait for CAPTCHA to render
            delay(if (attempt == 1) 500 else config.retryDelayMs)

            // Capture screenshot
            Log.d(TAG, "Capturing screenshot...")
            val screenshot = PuzzleScreenCapture.captureScreen()
            if (screenshot == null) {
                Log.w(TAG, "Screenshot capture FAILED on attempt $attempt")
                continue
            }
            Log.d(TAG, "Screenshot OK: ${screenshot.width}x${screenshot.height}")

            // Crop puzzle region
            val puzzleBitmap = PuzzleScreenCapture.cropRegion(
                screenshot,
                scaledPuzzleLeft, scaledPuzzleTop,
                scaledPuzzleRight, scaledPuzzleBottom
            )
            screenshot.recycle()

            if (puzzleBitmap == null) {
                Log.w(TAG, "Crop FAILED on attempt $attempt")
                continue
            }
            Log.d(TAG, "Crop OK: ${puzzleBitmap.width}x${puzzleBitmap.height}")

            // Analyze with OpenCV
            Log.d(TAG, "Analyzing with method=${config.analyzeMethod}...")
            val gapOffsetX = PuzzleSolver.findGapOffset(puzzleBitmap, config.analyzeMethod)
            val puzzleWidth = puzzleBitmap.width
            puzzleBitmap.recycle()

            if (gapOffsetX < 0) {
                Log.w(TAG, "Gap detection FAILED on attempt $attempt")
                continue
            }
            Log.d(TAG, "Gap found at X=$gapOffsetX (puzzleWidth=$puzzleWidth)")

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

            Log.d(TAG, "Swipe: startX=$startX → endX=$endX, centerY=$sliderCenterY, " +
                    "distance=$swipeDistance, jitter=$jitter, duration=${config.swipeDurationMs}ms")

            // Execute swipe
            val swipeDone = CompletableDeferred<Unit>()
            service.swipeGesture(
                startX, sliderCenterY,
                endX, sliderCenterY,
                config.swipeDurationMs
            ) { swipeDone.complete(Unit) }

            val swipeResult = withTimeoutOrNull(5000) { swipeDone.await() }
            if (swipeResult == null) {
                Log.w(TAG, "Swipe timed out on attempt $attempt")
            } else {
                Log.d(TAG, "Swipe completed on attempt $attempt")
            }

            // Wait for CAPTCHA to process
            delay(1000)

            Log.d(TAG, "=== SOLVE_CAPTCHA DONE (attempt $attempt) ===")
            return
        }

        Log.w(TAG, "=== SOLVE_CAPTCHA FAILED after ${config.maxRetries} attempts ===")
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
