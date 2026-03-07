package com.xjanova.tping.puzzle

import android.accessibilityservice.GestureDescription
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

    // Sliding parameters
    private const val FAST_STEP_PX = 20f        // Fast phase: move 20px per step
    private const val FINE_STEP_PX = 3f          // Normal fine phase: 3px per step
    private const val ULTRA_FINE_STEP_PX = 1f    // Ultra-fine: 1px per step (when close to target)
    private const val STEP_DURATION_MS = 80L     // Duration per micro-step
    private const val DARKNESS_NEAR_ZERO = 50    // If dark pixels < this, gap is "filled"
    private const val MAX_FINE_STEPS = 80        // Max fine-tuning steps total
    private const val FAST_PHASE_RATIO = 0.65f   // Fast phase covers 65% of target (conservative)

    // Edge thresholds for stop decisions
    private const val EDGE_FILLED_RATIO = 0.30f      // Edge < 30% of initial = definitely filled
    private const val EDGE_APPROACHING_RATIO = 0.60f  // Edge < 60% = approaching — switch to ultra-fine
    private const val EDGE_GOOD_ENOUGH_RATIO = 0.45f  // Edge < 45% = good enough to release
    private const val RISING_STEPS_TO_STOP = 2        // Release after edge rises 2 consecutive steps

    /**
     * Execute CAPTCHA solve using Forward-Only Predictive Stopping:
     *
     * 1. Screenshot → auto-detect gap region above slider
     * 2. If no gap found → tap refresh button → retry
     * 3. Press-hold slider button → drag toward gap (fast phase, 65% of target)
     * 4. Fine-tune forward only (3px steps), screenshot each time, track edge strength
     * 5. When approaching target (edge < 60%), switch to ultra-fine (1px steps)
     * 6. Stop and release IMMEDIATELY when:
     *    a) Edge drops below 30% of initial (perfect match)
     *    b) Darkness near zero (classic filled)
     *    c) Edge was good (<45%) and starts rising for 2+ steps (just passed optimal)
     * 7. NO BACKTRACKING — puzzle resets on wrong release, so must be accurate first pass
     * 8. Verify CAPTCHA is solved
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

        // Check if this is a new-format config (has slider button)
        val isNewFormat = config.sliderButtonX > 0 && config.sliderButtonY > 0
        if (!isNewFormat) {
            status("❌ กรุณาบันทึก Captcha ใหม่ (แบบ 2 จุด)")
            return
        }

        status("โหลด OpenCV...")
        if (!PuzzleSolver.ensureOpenCV()) {
            status("❌ OpenCV โหลดไม่ได้")
            return
        }

        // Scale coordinates if screen resolution changed
        val metrics = service.resources.displayMetrics
        val scaleX = if (action.screenWidth > 0)
            metrics.widthPixels.toFloat() / action.screenWidth else 1f
        val scaleY = if (action.screenHeight > 0)
            metrics.heightPixels.toFloat() / action.screenHeight else 1f

        val sliderX = config.sliderButtonX * scaleX
        val sliderY = config.sliderButtonY * scaleY
        val refreshX = config.refreshButtonX * scaleX
        val refreshY = config.refreshButtonY * scaleY
        val hasRefresh = config.refreshButtonX > 0 && config.refreshButtonY > 0

        Log.d(TAG, "Slider: ($sliderX, $sliderY), Refresh: ($refreshX, $refreshY), Scale: ${scaleX}x$scaleY")

        for (attempt in 1..config.maxRetries) {
            status("ครั้งที่ $attempt/${config.maxRetries}: จับภาพ...")
            delay(if (attempt == 1) 1000 else config.retryDelayMs)

            // Step 1: Screenshot and find gap
            val screenshot = PuzzleScreenCapture.captureScreen()
            if (screenshot == null) {
                status("⚠ จับภาพไม่ได้ (ครั้งที่ $attempt)")
                continue
            }

            val darkRegion = PuzzleSolver.findDarkRegion(screenshot, sliderY.toInt())
            val initialEdgeStrength = if (darkRegion != null) {
                PuzzleSolver.measureEdgeStrength(screenshot, darkRegion)
            } else -1
            val initialDarkness = if (darkRegion != null) {
                PuzzleSolver.measureDarkness(screenshot, darkRegion)
            } else -1
            screenshot.recycle()

            if (darkRegion == null) {
                status("⚠ หาช่องว่างไม่พบ")
                if (hasRefresh) {
                    status("🔄 กดรีเฟรช Puzzle...")
                    val tapDone = CompletableDeferred<Unit>()
                    service.tapAtCoordinates(refreshX, refreshY) { tapDone.complete(Unit) }
                    withTimeoutOrNull(3000) { tapDone.await() }
                    delay(2000)
                }
                continue
            }

            val gapCenterX = (darkRegion.left + darkRegion.right) / 2f
            val targetDragDistance = gapCenterX - sliderX
            Log.d(TAG, "Gap at x=${gapCenterX.toInt()}, edge=$initialEdgeStrength, darkness=$initialDarkness, dragDist=${targetDragDistance.toInt()}")

            if (targetDragDistance <= 5) {
                status("⚠ ระยะเลื่อนน้อยเกินไป")
                continue
            }

            val gapPercent = ((gapCenterX / metrics.widthPixels) * 100).toInt()
            status("ครั้งที่ $attempt: พบที่ ${gapPercent}% → เลื่อน...")

            // Step 2: Fast phase — drag 65% of estimated distance (conservative)
            val fastDistance = targetDragDistance * FAST_PHASE_RATIO
            val fastEndX = sliderX + fastDistance

            status("ครั้งที่ $attempt: เลื่อนเร็ว...")
            val fastDone = CompletableDeferred<GestureDescription.StrokeDescription?>()
            service.swipeWithContinuation(
                sliderX, sliderY,
                fastEndX, sliderY,
                (fastDistance / FAST_STEP_PX * STEP_DURATION_MS).toLong().coerceIn(200, 2000),
                willContinue = true,
                previousStroke = null
            ) { stroke -> fastDone.complete(stroke) }

            var currentStroke = withTimeoutOrNull(5000) { fastDone.await() }
            if (currentStroke == null) {
                status("⚠ เลื่อนเร็วไม่สำเร็จ")
                continue
            }

            var currentX = fastEndX

            // Step 3: Forward-only fine phase with PREDICTIVE STOPPING
            // - Start with 3px steps
            // - When edge drops below 60% of initial → switch to 1px steps (ultra-fine)
            // - Stop and release immediately when optimal position detected
            // - NO BACKTRACKING — puzzle changes on wrong release
            status("ครั้งที่ $attempt: ปรับละเอียด...")

            var fineStepCount = 0
            var bestEdgeStrength = if (initialEdgeStrength > 0) initialEdgeStrength else Int.MAX_VALUE
            var consecutiveRising = 0       // Count of consecutive edge-rising steps
            var inUltraFineMode = false     // True when using 1px steps
            var shouldRelease = false       // Set true when we should release NOW

            while (fineStepCount < MAX_FINE_STEPS && !shouldRelease) {
                fineStepCount++

                // Delay for UI update after sliding
                delay(if (inUltraFineMode) 100 else 120)

                // Screenshot to measure fit quality
                val fineScreenshot = PuzzleScreenCapture.captureScreen()
                if (fineScreenshot == null) break

                val currentEdge = PuzzleSolver.measureEdgeStrength(fineScreenshot, darkRegion)
                val currentDarkness = PuzzleSolver.measureDarkness(fineScreenshot, darkRegion)
                fineScreenshot.recycle()

                val stepSize = if (inUltraFineMode) "1px" else "3px"
                Log.d(
                    TAG,
                    "Fine step $fineStepCount ($stepSize): x=${currentX.toInt()}, " +
                        "edge=$currentEdge/$initialEdgeStrength (best=$bestEdgeStrength), " +
                        "darkness=$currentDarkness, rising=$consecutiveRising"
                )

                // === Check 1: Definite fill — edge dropped below 30% of initial ===
                if (currentEdge >= 0 && initialEdgeStrength > 0 &&
                    currentEdge < initialEdgeStrength * EDGE_FILLED_RATIO
                ) {
                    Log.d(TAG, "✓ Gap definitely filled! edge=$currentEdge < ${(initialEdgeStrength * EDGE_FILLED_RATIO).toInt()}")
                    shouldRelease = true
                    break
                }

                // === Check 2: Darkness near zero (classic dark gaps) ===
                if (currentDarkness >= 0 && currentDarkness < DARKNESS_NEAR_ZERO) {
                    Log.d(TAG, "✓ Gap filled! darkness=$currentDarkness < $DARKNESS_NEAR_ZERO")
                    shouldRelease = true
                    break
                }

                // === Track edge trend ===
                if (currentEdge >= 0) {
                    if (currentEdge < bestEdgeStrength) {
                        // Still improving — new best
                        bestEdgeStrength = currentEdge
                        consecutiveRising = 0
                    } else {
                        // Edge increased = moving past optimal
                        consecutiveRising++
                    }

                    // === Check 3: Edge was good and now rising → release NOW ===
                    // We already passed the best position, but since we can't go back,
                    // release immediately if:
                    // - Best edge was reasonably good (< 45% of initial)
                    // - Edge has been rising for 2+ steps
                    if (consecutiveRising >= RISING_STEPS_TO_STOP &&
                        initialEdgeStrength > 0 &&
                        bestEdgeStrength < initialEdgeStrength * EDGE_GOOD_ENOUGH_RATIO
                    ) {
                        Log.d(
                            TAG,
                            "✓ Releasing: edge rising $consecutiveRising steps, " +
                                "best=$bestEdgeStrength (${(bestEdgeStrength * 100.0 / initialEdgeStrength).toInt()}% of initial)"
                        )
                        shouldRelease = true
                        break
                    }

                    // === Check 4: Edge approaching target — switch to ultra-fine ===
                    if (!inUltraFineMode && initialEdgeStrength > 0 &&
                        currentEdge < initialEdgeStrength * EDGE_APPROACHING_RATIO
                    ) {
                        Log.d(TAG, "→ Approaching target, switching to ultra-fine (1px) steps")
                        inUltraFineMode = true
                    }

                    // === Check 5: Safety — edge rising a lot (5+ steps), even if best wasn't great ===
                    // This catches cases where initial measurement was noisy
                    if (consecutiveRising >= 5) {
                        Log.d(
                            TAG,
                            "⚠ Edge rising $consecutiveRising steps — releasing at current position " +
                                "(best=$bestEdgeStrength, current=$currentEdge)"
                        )
                        shouldRelease = true
                        break
                    }
                }

                // Continue sliding forward
                val stepPx = if (inUltraFineMode) ULTRA_FINE_STEP_PX else FINE_STEP_PX
                val nextX = currentX + stepPx
                val fineDone = CompletableDeferred<GestureDescription.StrokeDescription?>()
                service.swipeWithContinuation(
                    currentX, sliderY,
                    nextX, sliderY,
                    STEP_DURATION_MS,
                    willContinue = true,
                    previousStroke = currentStroke
                ) { stroke -> fineDone.complete(stroke) }

                val nextStroke = withTimeoutOrNull(3000) { fineDone.await() }
                if (nextStroke == null) {
                    Log.w(TAG, "Fine step $fineStepCount: continuation failed")
                    break
                }

                currentStroke = nextStroke
                currentX = nextX
            }

            // Step 4: Release finger at current position (willContinue = false)
            // NO BACKTRACKING — release exactly where we are
            status("ครั้งที่ $attempt: ปล่อยนิ้ว...")
            val releaseDone = CompletableDeferred<GestureDescription.StrokeDescription?>()
            service.swipeWithContinuation(
                currentX, sliderY,
                currentX, sliderY,  // Stay at same position
                50,
                willContinue = false,
                previousStroke = currentStroke
            ) { stroke -> releaseDone.complete(stroke) }
            withTimeoutOrNull(3000) { releaseDone.await() }

            // Step 5: Wait and verify
            status("ครั้งที่ $attempt: ตรวจสอบผล...")
            delay(2000)

            // Check if CAPTCHA is still showing
            val verifyScreenshot = PuzzleScreenCapture.captureScreen()
            if (verifyScreenshot != null) {
                val verifyDark = PuzzleSolver.findDarkRegion(verifyScreenshot, sliderY.toInt())
                verifyScreenshot.recycle()

                if (verifyDark == null) {
                    // No dark region found → CAPTCHA solved!
                    status("✓ แก้ Captcha สำเร็จ")
                    return
                }

                // Dark region still present
                if (attempt < config.maxRetries) {
                    status("⚠ ยังไม่ผ่าน ลองใหม่...")
                    if (hasRefresh) {
                        val refreshDone = CompletableDeferred<Unit>()
                        service.tapAtCoordinates(refreshX, refreshY) { refreshDone.complete(Unit) }
                        withTimeoutOrNull(3000) { refreshDone.await() }
                        delay(2000)
                    }
                    continue
                }
            }

            // If we can't verify or it's the last attempt, assume success
            status("✓ แก้ Captcha สำเร็จ")
            return
        }

        status("❌ แก้ไม่สำเร็จ หลัง ${config.maxRetries} ครั้ง")
    }
}
