package com.xjanova.tping.puzzle

import android.accessibilityservice.GestureDescription
import android.graphics.Rect
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
    private const val FAST_STEP_PX = 20f      // Fast phase: move 20px per step
    private const val FINE_STEP_PX = 3f        // Fine phase: move 3px per step (was 4)
    private const val STEP_DURATION_MS = 80L   // Duration per micro-step
    private const val DARKNESS_NEAR_ZERO = 50  // If dark pixels < this, gap is "filled"
    private const val MAX_FINE_STEPS = 60      // Max fine-tuning steps (was 40)
    private const val FAST_PHASE_RATIO = 0.70f // Fast phase covers 70% of target (was 80%)

    /**
     * Execute CAPTCHA solve using Visual Feedback Sliding with min-tracking:
     *
     * 1. Screenshot → auto-detect gap region above slider
     * 2. If no gap found → tap refresh button → retry
     * 3. Press-hold slider button → drag toward gap (fast phase, 70% of target)
     * 4. Fine-tune: drag in 3px increments, screenshot each time, track edge strength
     * 5. Track the position with MINIMUM edge strength (= best fit)
     * 6. If overshoot detected (edge rising 5+ steps after min) → backtrack to best position
     * 7. Release finger at best position
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

            // Step 2: Fast phase — drag 70% of estimated distance (conservative)
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

            // Step 3: Fine phase with MIN-TRACKING
            // Slide forward in small steps, track the position where edge strength is lowest
            // (= puzzle piece best aligns with gap). If we overshoot, backtrack to that position.
            status("ครั้งที่ $attempt: ปรับละเอียด...")

            var fineStepCount = 0
            var minEdgeStrength = if (initialEdgeStrength > 0) initialEdgeStrength else Int.MAX_VALUE
            var minEdgeX = currentX           // X position where edge was lowest
            var stepsAfterMin = 0             // Steps since we saw the minimum
            var confirmedOvershoot = false     // True if we confirmed we passed the best position
            var definitelyFilled = false       // True if edge dropped below 30% (perfect match)

            while (fineStepCount < MAX_FINE_STEPS) {
                fineStepCount++

                // Small delay for the UI to update after sliding
                delay(120)

                // Screenshot to measure fit quality
                val fineScreenshot = PuzzleScreenCapture.captureScreen()
                if (fineScreenshot == null) break

                val currentEdge = PuzzleSolver.measureEdgeStrength(fineScreenshot, darkRegion)
                val currentDarkness = PuzzleSolver.measureDarkness(fineScreenshot, darkRegion)
                fineScreenshot.recycle()

                Log.d(
                    TAG,
                    "Fine step $fineStepCount: x=${currentX.toInt()}, edge=$currentEdge/$initialEdgeStrength" +
                        " (min=$minEdgeStrength@${minEdgeX.toInt()}), darkness=$currentDarkness, afterMin=$stepsAfterMin"
                )

                // === Check 1: Definite fill — edge dropped below 30% of initial ===
                if (currentEdge >= 0 && initialEdgeStrength > 0 &&
                    currentEdge < initialEdgeStrength * 0.30
                ) {
                    Log.d(TAG, "Gap definitely filled! edge=$currentEdge < ${(initialEdgeStrength * 0.30).toInt()}")
                    definitelyFilled = true
                    break
                }

                // === Check 2: Darkness near zero (classic dark gaps) ===
                if (currentDarkness >= 0 && currentDarkness < DARKNESS_NEAR_ZERO) {
                    Log.d(TAG, "Gap filled! darkness=$currentDarkness < $DARKNESS_NEAR_ZERO")
                    definitelyFilled = true
                    break
                }

                // === Track minimum edge position ===
                if (currentEdge >= 0) {
                    if (currentEdge < minEdgeStrength) {
                        // New minimum found
                        minEdgeStrength = currentEdge
                        minEdgeX = currentX
                        stepsAfterMin = 0
                    } else {
                        stepsAfterMin++
                    }
                }

                // === Check 3: Overshoot — 5+ steps past minimum with edge significantly higher ===
                if (stepsAfterMin >= 5 && initialEdgeStrength > 0 &&
                    minEdgeStrength < initialEdgeStrength * 0.75
                ) {
                    Log.d(
                        TAG,
                        "Overshoot confirmed: minEdge=$minEdgeStrength at x=${minEdgeX.toInt()}, " +
                            "now at x=${currentX.toInt()} ($stepsAfterMin steps past min)"
                    )
                    confirmedOvershoot = true
                    break
                }

                // === Check 4: Way past target — safety stop ===
                if (stepsAfterMin >= 10) {
                    Log.d(TAG, "10 steps past minimum — safety stop")
                    confirmedOvershoot = minEdgeStrength < initialEdgeStrength * 0.85
                    break
                }

                // Continue sliding forward by FINE_STEP_PX
                val nextX = currentX + FINE_STEP_PX
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

            // Step 3b: BACKTRACK to minimum edge position if we overshot
            if (confirmedOvershoot && !definitelyFilled && minEdgeX < currentX) {
                val backDistance = currentX - minEdgeX
                Log.d(TAG, "Backtracking ${backDistance.toInt()}px to best position x=${minEdgeX.toInt()}")
                status("ครั้งที่ $attempt: ย้อนกลับ ${backDistance.toInt()}px...")

                val backDone = CompletableDeferred<GestureDescription.StrokeDescription?>()
                service.swipeWithContinuation(
                    currentX, sliderY,
                    minEdgeX, sliderY,
                    (backDistance / FINE_STEP_PX * STEP_DURATION_MS).toLong().coerceIn(50, 1500),
                    willContinue = true,
                    previousStroke = currentStroke
                ) { stroke -> backDone.complete(stroke) }

                val backStroke = withTimeoutOrNull(3000) { backDone.await() }
                if (backStroke != null) {
                    currentStroke = backStroke
                    currentX = minEdgeX
                    Log.d(TAG, "Backtracked to x=${currentX.toInt()}")
                } else {
                    Log.w(TAG, "Backtrack failed, releasing at current position")
                }
            }

            // Step 4: Release finger (willContinue = false)
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
