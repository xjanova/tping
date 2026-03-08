package com.xjanova.tping.puzzle

import android.accessibilityservice.GestureDescription
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.xjanova.tping.data.diagnostic.DiagnosticReporter
import com.xjanova.tping.data.entity.RecordedAction
import com.xjanova.tping.service.TpingAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object PuzzleCaptchaAction {

    private const val TAG = "PuzzleCaptcha"
    private val gson = Gson()

    // Sliding parameters
    private const val FAST_STEP_PX = 20f
    private const val FINE_STEP_PX = 3f
    private const val ULTRA_FINE_STEP_PX = 1f
    private const val STEP_DURATION_MS = 80L
    private const val DARKNESS_NEAR_ZERO = 50
    private const val MAX_FINE_STEPS = 80
    private const val FAST_PHASE_RATIO = 0.65f

    // Edge thresholds for stop decisions
    private const val EDGE_FILLED_RATIO = 0.30f
    private const val EDGE_APPROACHING_RATIO = 0.60f
    private const val EDGE_GOOD_ENOUGH_RATIO = 0.45f
    private const val RISING_STEPS_TO_STOP = 2

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
            status("❌ Config ผิดพลาด: ${e.message?.take(50)}")
            Log.e(TAG, "Failed to parse PuzzleConfig: ${e.message}")
            DiagnosticReporter.logCaptcha("Config parse error", "inputText=${action.inputText?.take(200)}, error=${e.message}")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            status("❌ ต้อง Android 11+")
            DiagnosticReporter.logCaptcha("Android < 11", "SDK=${Build.VERSION.SDK_INT}")
            return
        }

        val isNewFormat = config.sliderButtonX > 0 && config.sliderButtonY > 0
        if (!isNewFormat) {
            status("❌ กรุณาบันทึก Captcha ใหม่ (แบบ 2 จุด)")
            DiagnosticReporter.logCaptcha("Old format config", "sliderX=${config.sliderButtonX}, sliderY=${config.sliderButtonY}")
            return
        }

        status("โหลด OpenCV...")
        val opencvOk = PuzzleSolver.ensureOpenCV()
        if (!opencvOk) {
            Log.w(TAG, "OpenCV failed — will use blind-drag mode")
            status("⚠ OpenCV ไม่พร้อม — ใช้โหมดเลื่อนอัตโนมัติ")
            DiagnosticReporter.logCaptcha("OpenCV load failed", "Falling back to blind-drag mode")
        }

        // Scale coordinates if screen resolution changed
        val metrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val scaleX = if (action.screenWidth > 0)
            screenW.toFloat() / action.screenWidth else 1f
        val scaleY = if (action.screenHeight > 0)
            screenH.toFloat() / action.screenHeight else 1f

        val sliderX = config.sliderButtonX * scaleX
        val sliderY = config.sliderButtonY * scaleY
        val refreshX = config.refreshButtonX * scaleX
        val refreshY = config.refreshButtonY * scaleY
        val hasRefresh = config.refreshButtonX > 0 && config.refreshButtonY > 0

        val coordInfo = "slider=(${sliderX.toInt()},${sliderY.toInt()}), refresh=(${refreshX.toInt()},${refreshY.toInt()}), " +
            "scale=${scaleX}x$scaleY, screen=${screenW}x$screenH, " +
            "recorded=${action.screenWidth}x${action.screenHeight}, " +
            "configRaw=(${config.sliderButtonX},${config.sliderButtonY})"
        Log.d(TAG, coordInfo)
        DiagnosticReporter.logCaptcha("Captcha start", coordInfo)

        // Blind-drag offsets (pixels to drag RIGHT from slider position)
        // Use absolute pixel offsets instead of screen % to avoid issues
        // when slider is in the middle of the screen
        val blindDragOffsets = intArrayOf(200, 350, 500, 150, 450)
        var blindDragIndex = 0

        for (attempt in 1..config.maxRetries) {
            status("ครั้งที่ $attempt/${config.maxRetries}: จับภาพ...")
            delay(if (attempt == 1) 1500 else config.retryDelayMs)

            // Step 1: Screenshot and find gap (if OpenCV available)
            var darkRegion: android.graphics.Rect? = null
            var initialEdgeStrength = -1
            var initialDarkness = -1
            var useBlindDrag = !opencvOk

            if (opencvOk) {
                val screenshot = PuzzleScreenCapture.captureScreen()
                if (screenshot == null) {
                    status("⚠ จับภาพไม่ได้ — ใช้เลื่อนอัตโนมัติ")
                    Log.w(TAG, "Screenshot failed at attempt $attempt")
                    DiagnosticReporter.logCaptcha("Screenshot failed", "attempt=$attempt")
                    useBlindDrag = true
                } else {
                    darkRegion = PuzzleSolver.findDarkRegion(screenshot, sliderY.toInt())
                    if (darkRegion != null) {
                        initialEdgeStrength = PuzzleSolver.measureEdgeStrength(screenshot, darkRegion)
                        initialDarkness = PuzzleSolver.measureDarkness(screenshot, darkRegion)
                        Log.d(TAG, "Gap found: $darkRegion, edge=$initialEdgeStrength, darkness=$initialDarkness")
                    } else {
                        Log.w(TAG, "Gap not found at attempt $attempt — falling back to blind drag")
                        useBlindDrag = true
                    }
                    screenshot.recycle()
                }
            }

            // === BLIND DRAG FALLBACK ===
            if (useBlindDrag) {
                val dragOffset = blindDragOffsets[blindDragIndex % blindDragOffsets.size]
                blindDragIndex++
                val targetX = (sliderX + dragOffset).coerceAtMost(screenW - 50f)
                val dragDistance = targetX - sliderX

                Log.d(TAG, "Blind drag attempt $attempt: sliderX=$sliderX, offset=$dragOffset, targetX=$targetX, dragDist=$dragDistance")

                if (dragDistance <= 10) {
                    // This should never happen with offset-based approach, but log it
                    status("⚠ ครั้งที่ $attempt: ระยะเลื่อนน้อยเกินไป (${"%.0f".format(dragDistance)}px)")
                    DiagnosticReporter.logCaptcha("Blind drag skip", "attempt=$attempt, dist=$dragDistance, sliderX=$sliderX, targetX=$targetX")
                    continue
                }

                status("ครั้งที่ $attempt: เลื่อน ${dragOffset}px...")

                // Use simple swipeGesture (most reliable, no continuation)
                val dragDone = CompletableDeferred<Unit>()
                val duration = (dragDistance / 10f * STEP_DURATION_MS).toLong().coerceIn(400, 3000)
                Log.d(TAG, "Dispatching swipeGesture: ($sliderX,$sliderY) → ($targetX,$sliderY), duration=${duration}ms")

                service.swipeGesture(
                    sliderX, sliderY,
                    targetX, sliderY,
                    duration
                ) { dragDone.complete(Unit) }

                val completed = withTimeoutOrNull(8000) { dragDone.await() }
                if (completed == null) {
                    status("⚠ เลื่อนไม่สำเร็จ (timeout)")
                    Log.w(TAG, "Blind drag gesture timed out")
                    DiagnosticReporter.logCaptcha("Blind drag timeout", "attempt=$attempt")
                    continue
                }
                Log.d(TAG, "Blind drag gesture completed for attempt $attempt")

                // Wait and verify
                status("ครั้งที่ $attempt: ตรวจสอบผล...")
                delay(2500)

                if (opencvOk) {
                    val verifyScreenshot = PuzzleScreenCapture.captureScreen()
                    if (verifyScreenshot != null) {
                        val verifyDark = PuzzleSolver.findDarkRegion(verifyScreenshot, sliderY.toInt())
                        verifyScreenshot.recycle()
                        if (verifyDark == null) {
                            status("✓ แก้ Captcha สำเร็จ")
                            DiagnosticReporter.logCaptcha("Captcha solved (blind drag)", "attempt=$attempt, offset=${dragOffset}px")
                            return
                        }
                    }
                }

                // Refresh and try next position
                if (hasRefresh && attempt < config.maxRetries) {
                    status("🔄 กดรีเฟรช...")
                    val tapDone = CompletableDeferred<Unit>()
                    service.tapAtCoordinates(refreshX, refreshY) { tapDone.complete(Unit) }
                    withTimeoutOrNull(3000) { tapDone.await() }
                    delay(2000)
                }
                continue
            }

            // === SMART DRAG (gap detected) ===
            val gapCenterX = (darkRegion!!.left + darkRegion.right) / 2f
            val targetDragDistance = gapCenterX - sliderX
            Log.d(TAG, "Gap at x=${gapCenterX.toInt()}, edge=$initialEdgeStrength, darkness=$initialDarkness, dragDist=${targetDragDistance.toInt()}")

            if (targetDragDistance <= 5) {
                status("⚠ ระยะเลื่อนน้อยเกินไป (${targetDragDistance.toInt()}px)")
                DiagnosticReporter.logCaptcha("Smart drag skip - distance too small", "dist=$targetDragDistance, gap=$gapCenterX, slider=$sliderX")
                continue
            }

            val gapPercent = ((gapCenterX / screenW) * 100).toInt()
            status("ครั้งที่ $attempt: พบที่ ${gapPercent}% → เลื่อน...")

            // Step 2: Fast phase — drag 65% of estimated distance
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
                Log.w(TAG, "Fast phase gesture cancelled/timed out")
                DiagnosticReporter.logCaptcha("Smart drag fast phase failed", "attempt=$attempt, fastEndX=$fastEndX")
                continue
            }

            var currentX = fastEndX

            // Step 3: Forward-only fine phase with PREDICTIVE STOPPING
            status("ครั้งที่ $attempt: ปรับละเอียด...")

            var fineStepCount = 0
            var bestEdgeStrength = if (initialEdgeStrength > 0) initialEdgeStrength else Int.MAX_VALUE
            var consecutiveRising = 0
            var inUltraFineMode = false
            var shouldRelease = false

            while (fineStepCount < MAX_FINE_STEPS && !shouldRelease) {
                fineStepCount++
                delay(if (inUltraFineMode) 100 else 120)

                val fineScreenshot = PuzzleScreenCapture.captureScreen()
                if (fineScreenshot == null) {
                    Log.w(TAG, "Fine step $fineStepCount: screenshot failed")
                    break
                }

                val currentEdge = PuzzleSolver.measureEdgeStrength(fineScreenshot, darkRegion)
                val currentDarkness = PuzzleSolver.measureDarkness(fineScreenshot, darkRegion)
                fineScreenshot.recycle()

                val stepSize = if (inUltraFineMode) "1px" else "3px"
                Log.d(TAG, "Fine step $fineStepCount ($stepSize): x=${currentX.toInt()}, edge=$currentEdge/$initialEdgeStrength (best=$bestEdgeStrength), darkness=$currentDarkness, rising=$consecutiveRising")

                if (currentEdge >= 0 && initialEdgeStrength > 0 &&
                    currentEdge < initialEdgeStrength * EDGE_FILLED_RATIO) {
                    Log.d(TAG, "✓ Gap definitely filled!")
                    shouldRelease = true; break
                }

                if (currentDarkness >= 0 && currentDarkness < DARKNESS_NEAR_ZERO) {
                    Log.d(TAG, "✓ Gap filled by darkness!")
                    shouldRelease = true; break
                }

                if (currentEdge >= 0) {
                    if (currentEdge < bestEdgeStrength) {
                        bestEdgeStrength = currentEdge; consecutiveRising = 0
                    } else {
                        consecutiveRising++
                    }
                    if (consecutiveRising >= RISING_STEPS_TO_STOP &&
                        initialEdgeStrength > 0 &&
                        bestEdgeStrength < initialEdgeStrength * EDGE_GOOD_ENOUGH_RATIO) {
                        Log.d(TAG, "✓ Releasing: edge rising, best=$bestEdgeStrength")
                        shouldRelease = true; break
                    }
                    if (!inUltraFineMode && initialEdgeStrength > 0 &&
                        currentEdge < initialEdgeStrength * EDGE_APPROACHING_RATIO) {
                        inUltraFineMode = true
                    }
                    if (consecutiveRising >= 5) {
                        Log.d(TAG, "⚠ Edge rising too much — releasing")
                        shouldRelease = true; break
                    }
                }

                val stepPx = if (inUltraFineMode) ULTRA_FINE_STEP_PX else FINE_STEP_PX
                val nextX = currentX + stepPx
                val fineDone = CompletableDeferred<GestureDescription.StrokeDescription?>()
                service.swipeWithContinuation(
                    currentX, sliderY, nextX, sliderY,
                    STEP_DURATION_MS, willContinue = true, previousStroke = currentStroke
                ) { stroke -> fineDone.complete(stroke) }

                val nextStroke = withTimeoutOrNull(3000) { fineDone.await() }
                if (nextStroke == null) {
                    Log.w(TAG, "Fine step $fineStepCount: continuation failed")
                    break
                }
                currentStroke = nextStroke
                currentX = nextX
            }

            // Step 4: Release finger
            status("ครั้งที่ $attempt: ปล่อยนิ้ว...")
            val releaseDone = CompletableDeferred<GestureDescription.StrokeDescription?>()
            service.swipeWithContinuation(
                currentX, sliderY, currentX, sliderY,
                50, willContinue = false, previousStroke = currentStroke
            ) { stroke -> releaseDone.complete(stroke) }
            withTimeoutOrNull(3000) { releaseDone.await() }

            // Step 5: Wait and verify
            status("ครั้งที่ $attempt: ตรวจสอบผล...")
            delay(2000)

            val verifyScreenshot = PuzzleScreenCapture.captureScreen()
            if (verifyScreenshot != null) {
                val verifyDark = PuzzleSolver.findDarkRegion(verifyScreenshot, sliderY.toInt())
                verifyScreenshot.recycle()

                if (verifyDark == null) {
                    status("✓ แก้ Captcha สำเร็จ")
                    DiagnosticReporter.logCaptcha("Captcha solved (smart drag)", "attempt=$attempt, gap=${gapPercent}%")
                    return
                }

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

            status("✓ แก้ Captcha สำเร็จ")
            DiagnosticReporter.logCaptcha("Captcha assumed solved (unverified)", "attempt=$attempt")
            return
        }

        status("❌ แก้ไม่สำเร็จ หลัง ${config.maxRetries} ครั้ง")
        DiagnosticReporter.logCaptcha(
            "Captcha failed after ${config.maxRetries} attempts",
            "opencv=$opencvOk, $coordInfo"
        )
    }
}
