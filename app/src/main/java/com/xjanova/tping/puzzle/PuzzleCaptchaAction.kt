package com.xjanova.tping.puzzle

import android.accessibilityservice.GestureDescription
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
 * Puzzle CAPTCHA solver — v1.2.36
 *
 * Slide strategy (3 tiers):
 *   1. PRIMARY: Direct slow swipe from slider to target
 *      - Single continuous gesture, long duration so slider registers initial touch
 *      - Most reliable for WebView-based CAPTCHAs
 *   2. FALLBACK: Tap slider handle → delay → slow swipe to target
 *      - Tap "activates" the slider handle first
 *      - Only used if direct swipe fails
 *   3. LAST RESORT: Press-hold-drag (continuation chains)
 *      - Phase 1: hold finger at slider 300ms (willContinue=true)
 *      - Phase 2: drag to target (willContinue=false)
 *      - Only used if both tier 1 and 2 fail
 *
 * Escalation: tiers escalate on "functional failure" — when gesture completes
 * but the slider didn't actually move (gap position unchanged).
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
            status("❌ กรุณาบันทึก Captcha ใหม่")
            DiagnosticReporter.logCaptcha(
                "Bad config",
                "sliderX=${config.sliderButtonX}, sliderY=${config.sliderButtonY}"
            )
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
        val trackEndX = config.sliderTrackEndX * scaleX
        val refreshX = config.refreshButtonX * scaleX
        val refreshY = config.refreshButtonY * scaleY
        val hasRefresh = config.refreshButtonX > 0 && config.refreshButtonY > 0
        val hasTrack = config.hasTrackInfo
        val trackWidth = if (hasTrack) (trackEndX - sliderX) else 0f

        val info = "slider=(${sliderX.toInt()},${sliderY.toInt()}), " +
            "trackEnd=${trackEndX.toInt()}, trackW=${trackWidth.toInt()}, " +
            "screen=${screenW}x$screenH, " +
            "scale=${"%.2f".format(scaleX)}x${"%.2f".format(scaleY)}, " +
            "hasTrack=$hasTrack, recorded=${action.screenWidth}x${action.screenHeight}"
        Log.d(TAG, info)
        DiagnosticReporter.logCaptcha("Captcha start", info)

        // ============================================================
        // === WARM-UP: Tap at screen center to wake gesture system ===
        // ============================================================
        status("ตรวจสอบระบบ gesture...")
        var warmupOk = doWarmupTap(service, screenW, screenH)
        if (!warmupOk) {
            DiagnosticReporter.logCaptcha("Warmup tap 1 failed", "retrying with swipe...")
            delay(500)
            // Retry with swipe warm-up
            warmupOk = doWarmupSwipe(service, screenW, screenH)
            if (!warmupOk) {
                status("❌ ระบบ gesture ไม่พร้อม! ลอง ปิด/เปิด Accessibility Service")
                DiagnosticReporter.logCaptcha("CRITICAL: warmup failed", "both tap+swipe failed")
                autoSendDiagnostics()
                return
            }
        }
        status("✓ ระบบ gesture พร้อม")
        delay(600)

        // ============================================================
        // === FOCUS + ACTIVATION: Wake up WebView for gesture input ===
        // ============================================================
        // Problem: dispatchGesture touch events don't reach CAPTCHA JavaScript.
        // User confirmed: manual finger touch first → bot works after.
        // v1.2.36: Enhanced activation:
        //   1. Search ALL windows for WebView (not just active window)
        //   2. Log WebView details + children for diagnostics
        //   3. Sequential activation taps, each FULLY AWAITED before next
        //      (v1.2.35 bug: focus tap got stuck → Tier 1 CANCELLED)
        status("กำลังเปิดใช้งาน CAPTCHA...")

        // Step 1: Focus WebView via AccessibilityNodeInfo (search all windows)
        val focusResult = focusWebViewAtSlider(service, sliderX, sliderY)
        DiagnosticReporter.logCaptcha("Focus phase", "method=$focusResult, sliderAt=(${sliderX.toInt()},${sliderY.toInt()})")
        delay(300)

        // Step 2: Activation taps — wake up WebView touch handling
        // Each tap is fully dispatched + awaited (no overlapping gestures!)
        val activationResults = mutableListOf<String>()

        // Tap 1: CAPTCHA image area (dismiss any "click to start" overlay)
        val imgTapX = sliderX + (if (trackWidth > 50) trackWidth * 0.4f else 150f)
        val imgTapY = (sliderY - 180f).coerceAtLeast(100f)
        activationResults.add("img:" + doActivationTap(service, imgTapX, imgTapY, 250))
        delay(350)

        // Tap 2: Directly on slider handle (300ms press to trigger touch init)
        activationResults.add("handle:" + doActivationTap(service, sliderX, sliderY, 300))
        delay(350)

        // Tap 3: Slightly right of handle (redundancy)
        activationResults.add("handle2:" + doActivationTap(service, sliderX + 15f, sliderY, 200))
        delay(300)

        DiagnosticReporter.logCaptcha("Activation taps", activationResults.joinToString(", "))
        status("✓ CAPTCHA พร้อม")

        // === Blind-drag offsets ===
        val blindPercentages = floatArrayOf(0.30f, 0.50f, 0.70f, 0.20f, 0.60f, 0.40f, 0.80f)
        val blindFixedOffsets = intArrayOf(200, 350, 500, 150, 450, 300, 550)
        var blindIndex = 0
        var consecutiveGestureFailures = 0
        var functionalFailures = 0  // gesture "completed" but slider didn't move
        var swipeTier = 1  // 1=slow-swipe, 2=tap+swipe, 3=press-hold-drag

        // ============================================================
        // === MAIN SOLVING LOOP ===
        // ============================================================
        for (attempt in 1..config.maxRetries) {
            status("ครั้งที่ $attempt/${config.maxRetries}")
            delay(if (attempt == 1) 1500 else config.retryDelayMs)

            // === Determine target X ===
            var targetX: Float
            var dragMode: String

            if (opencvOk) {
                val screenshot = PuzzleScreenCapture.captureScreen()
                if (screenshot != null) {
                    val gap = PuzzleSolver.findGapRegion(screenshot, sliderY.toInt())
                    screenshot.recycle()
                    if (gap != null) {
                        val gapCenterX = (gap.left + gap.right) / 2f
                        if (hasTrack && trackWidth > 50) {
                            val dragDistance = gapCenterX - sliderX
                            targetX = sliderX + dragDistance.coerceIn(20f, trackWidth - 10f)
                            dragMode = "smart-track"
                            val pct = ((dragDistance / trackWidth) * 100).toInt()
                            status("ครั้งที่ $attempt: พบช่องว่าง track=$pct%")
                        } else {
                            targetX = gapCenterX
                            dragMode = "smart"
                            val pct = ((gapCenterX / screenW) * 100).toInt()
                            status("ครั้งที่ $attempt: พบช่องว่างที่ $pct%")
                        }
                    } else {
                        val result = getBlindTarget(
                            hasTrack, trackWidth, sliderX, trackEndX, screenW,
                            blindPercentages, blindFixedOffsets, blindIndex
                        )
                        targetX = result.first
                        dragMode = result.second
                        blindIndex++
                    }
                } else {
                    val result = getBlindTarget(
                        hasTrack, trackWidth, sliderX, trackEndX, screenW,
                        blindPercentages, blindFixedOffsets, blindIndex
                    )
                    targetX = result.first
                    dragMode = result.second
                    blindIndex++
                }
            } else {
                val result = getBlindTarget(
                    hasTrack, trackWidth, sliderX, trackEndX, screenW,
                    blindPercentages, blindFixedOffsets, blindIndex
                )
                targetX = result.first
                dragMode = result.second
                blindIndex++
            }

            val dragDist = targetX - sliderX
            if (dragDist < 40) {
                DiagnosticReporter.logCaptcha(
                    "Skip: dist=${"%.0f".format(dragDist)}",
                    "mode=$dragMode, too short (<40px)"
                )
                // If OpenCV found gap too close to slider, use blind target instead
                if (dragMode.startsWith("smart") && hasTrack && trackWidth > 50) {
                    val result = getBlindTarget(
                        hasTrack, trackWidth, sliderX, trackEndX, screenW,
                        blindPercentages, blindFixedOffsets, blindIndex
                    )
                    targetX = result.first
                    dragMode = result.second + "(fallback)"
                    blindIndex++
                    val newDist = targetX - sliderX
                    if (newDist < 40) {
                        status("⚠ ระยะน้อยเกินไป (${"%.0f".format(newDist)}px)")
                        continue
                    }
                } else {
                    status("⚠ ระยะน้อยเกินไป (${"%.0f".format(dragDist)}px)")
                    continue
                }
            }

            // === Execute swipe ===
            // Small Y jitter on retries to improve slider handle hit detection
            val yOffset = when {
                functionalFailures == 1 -> -4f
                functionalFailures >= 2 -> 4f
                else -> 0f
            }
            val swipeY = sliderY + yOffset
            val swipeModeName = when (swipeTier) {
                1 -> "slow-swipe"
                2 -> "tap+swipe"
                else -> "press-hold-drag"
            }
            status("ครั้งที่ $attempt: เลื่อนสไลด์ ($dragMode+$swipeModeName, ${dragDist.toInt()}px)...")

            val swipeResult: String = when (swipeTier) {
                1 -> doSlowSwipe(service, sliderX, swipeY, targetX, swipeY, dragDist)
                2 -> doTapThenSwipe(service, sliderX, swipeY, targetX, swipeY, dragDist)
                else -> doPressHoldDrag(service, sliderX, swipeY, targetX, swipeY,
                    (dragDist * 5f).toLong().coerceIn(1200, 4000))
            }

            Log.d(
                TAG,
                "Swipe result=$swipeResult, mode=$dragMode+$swipeModeName, " +
                    "attempt=$attempt, dist=${"%.0f".format(dragDist)}, target=${targetX.toInt()}"
            )
            DiagnosticReporter.logCaptcha(
                "Swipe attempt=$attempt",
                "result=$swipeResult, mode=$dragMode+$swipeModeName, " +
                    "start=(${sliderX.toInt()},${swipeY.toInt()}), end=(${targetX.toInt()},${swipeY.toInt()}), " +
                    "dist=${"%.0f".format(dragDist)}, target=${targetX.toInt()}, " +
                    "trackW=${trackWidth.toInt()}, yOffset=${"%.0f".format(yOffset)}, " +
                    "dur=${when(swipeTier) { 1 -> (dragDist * 4f).toLong().coerceIn(800, 3000); 2 -> (dragDist * 3.5f).toLong().coerceIn(700, 2500); else -> (dragDist * 5f).toLong().coerceIn(1200, 4000) }}ms, " +
                    "tier=$swipeTier, funcFails=$functionalFailures"
            )

            if (swipeResult != "completed") {
                consecutiveGestureFailures++
                status("⚠ gesture: $swipeResult (fail #$consecutiveGestureFailures, tier $swipeTier)")
                DiagnosticReporter.logCaptcha(
                    "Swipe failed",
                    "result=$swipeResult, consecutiveFails=$consecutiveGestureFailures, tier=$swipeTier"
                )

                // "cancelled" = hard OS rejection → escalate IMMEDIATELY to next tier
                // Other failures (timeout, etc.) → escalate after 2 consecutive
                val escalateThreshold = if (swipeResult == "cancelled") 1 else 2

                if (consecutiveGestureFailures >= escalateThreshold && swipeTier < 3) {
                    swipeTier++
                    consecutiveGestureFailures = 0
                    functionalFailures = 0
                    status("🔄 เปลี่ยนเป็นโหมด ${when(swipeTier) { 2 -> "tap+swipe"; else -> "press-hold-drag" }}...")
                    doWarmupTap(service, screenW, screenH)
                    delay(300)
                    continue
                }

                // After threshold failures on last tier → give up
                if (consecutiveGestureFailures >= 2 && swipeTier >= 3) {
                    status("❌ ระบบ gesture ไม่ทำงาน! ลอง ปิด/เปิด Accessibility Service")
                    DiagnosticReporter.logCaptcha(
                        "CRITICAL: gesture broken",
                        "all tiers exhausted, $consecutiveGestureFailures failures at tier $swipeTier"
                    )
                    autoSendDiagnostics()
                    return
                }

                delay(1000)
                continue
            }
            consecutiveGestureFailures = 0

            // === Swipe completed ===
            status("ครั้งที่ $attempt: ✓ เลื่อนแล้ว ตรวจสอบ...")
            delay(2500)

            // === Verify ===
            if (opencvOk) {
                val verifyShot = PuzzleScreenCapture.captureScreen()
                if (verifyShot != null) {
                    val stillHasGap = PuzzleSolver.findGapRegion(verifyShot, sliderY.toInt())
                    verifyShot.recycle()
                    if (stillHasGap == null) {
                        status("✓ แก้ Captcha สำเร็จ! (ครั้งที่ $attempt)")
                        DiagnosticReporter.logCaptcha(
                            "Solved",
                            "attempt=$attempt, mode=$dragMode+$swipeModeName"
                        )
                        autoSendDiagnostics()
                        return
                    } else {
                        val remainGapX = (stillHasGap.left + stillHasGap.right) / 2
                        DiagnosticReporter.logCaptcha(
                            "Not solved",
                            "attempt=$attempt, remainGapX=$remainGapX, target=${targetX.toInt()}"
                        )

                        // Functional failure: gesture "completed" but slider didn't move
                        // (gap is still near the target position = slider never dragged)
                        val gapDelta = kotlin.math.abs(remainGapX - targetX).toInt()
                        if (gapDelta < 30) {
                            functionalFailures++
                            DiagnosticReporter.logCaptcha(
                                "Functional failure",
                                "tier=$swipeTier, funcFails=$functionalFailures, gapDelta=$gapDelta"
                            )
                            // Escalate tier if gesture keeps "completing" but slider doesn't move
                            if (functionalFailures >= 2 && swipeTier < 3) {
                                swipeTier++
                                functionalFailures = 0
                                status("🔄 สไลด์ไม่ขยับ — เปลี่ยนโหมดเป็น ${when(swipeTier) { 2 -> "tap+swipe"; else -> "press-hold-drag" }}")
                                doWarmupTap(service, screenW, screenH)
                                delay(300)
                            }
                        }
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
        DiagnosticReporter.logCaptcha(
            "All attempts done",
            "maxRetries=${config.maxRetries}, opencv=$opencvOk, " +
                "hasTrack=$hasTrack, tier=$swipeTier"
        )
        autoSendDiagnostics()
    }

    // ============================================================
    // === WARM-UP: Tap ===
    // ============================================================

    /**
     * Warm up gesture system with a quick tap at safe position (center-bottom area).
     * Using tap instead of swipe to be more universal across devices.
     */
    private suspend fun doWarmupTap(
        service: TpingAccessibilityService,
        screenW: Int,
        screenH: Int
    ): Boolean {
        val tapX = screenW / 2f
        val tapY = screenH * 0.7f  // 70% down — safe area, below most UI
        Log.d(TAG, "Warmup: tap at ($tapX, $tapY)")
        val result = CompletableDeferred<Boolean>()
        service.swipeGesture(tapX, tapY, tapX + 1f, tapY, 100) { success ->
            Log.d(TAG, "Warmup tap result: $success")
            result.complete(success)
        }
        return withTimeoutOrNull(3000) { result.await() } ?: false
    }

    /**
     * Alternative warm-up with swipe gesture at center of screen.
     */
    private suspend fun doWarmupSwipe(
        service: TpingAccessibilityService,
        screenW: Int,
        screenH: Int
    ): Boolean {
        val centerX = screenW / 2f
        val centerY = screenH / 2f
        Log.d(TAG, "Warmup: swipe at center ($centerX, $centerY)")
        val result = CompletableDeferred<Boolean>()
        service.swipeGesture(centerX, centerY, centerX + 20f, centerY, 200) { success ->
            Log.d(TAG, "Warmup swipe result: $success")
            result.complete(success)
        }
        return withTimeoutOrNull(3000) { result.await() } ?: false
    }

    // ============================================================
    // === ACTIVATION TAP (for WebView wake-up) ===
    // ============================================================

    /**
     * Dispatch a single tap gesture and WAIT for completion.
     * Unlike the v1.2.35 approach (fire-and-forget via swipeGesture),
     * this checks dispatchGesture return value and properly awaits
     * the result to prevent overlapping gestures.
     *
     * Returns: "ok", "cancelled", "rejected" (dispatchGesture=false), or "timeout"
     */
    private suspend fun doActivationTap(
        service: TpingAccessibilityService,
        x: Float, y: Float,
        durationMs: Long
    ): String {
        val path = android.graphics.Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val result = CompletableDeferred<String>()
        val dispatched = service.dispatchGesture(gesture,
            object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { result.complete("ok") }
                override fun onCancelled(g: GestureDescription?) { result.complete("cancelled") }
            }, null)
        if (!dispatched) return "rejected"
        return withTimeoutOrNull(durationMs + 3000) { result.await() } ?: "timeout"
    }

    // ============================================================
    // === TIER 2: TAP-THEN-SWIPE (Fallback) ===
    // ============================================================

    /**
     * Tap the slider handle first to "activate" it, then slow swipe to target.
     *
     * Some slider CAPTCHAs need the handle to register ACTION_DOWN before
     * they start tracking drag. A quick tap wakes up the handle, then
     * the slow swipe drags it to the target position.
     *
     * NOTE: The pre-tap can interfere with some CAPTCHAs (touchUp releases handle).
     * Use direct slow swipe (tier 1) as primary approach.
     */
    private suspend fun doTapThenSwipe(
        service: TpingAccessibilityService,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        dragDist: Float
    ): String {
        // Step 1: Tap on slider handle (150ms touch)
        Log.d(TAG, "TapThenSwipe: tap at (${startX.toInt()},${startY.toInt()})")
        val tapDone = CompletableDeferred<Boolean>()
        service.swipeGesture(startX, startY, startX + 1f, startY, 150) { success ->
            tapDone.complete(success)
        }
        val tapOk = withTimeoutOrNull(3000) { tapDone.await() } ?: false
        if (!tapOk) {
            Log.e(TAG, "TapThenSwipe: tap failed")
            return "tap_failed"
        }

        // Step 2: Wait for handle to register
        delay(300)

        // Step 3: Swipe from slider to target — slow enough for JS to track
        val swipeDuration = (dragDist * 3.5f).toLong().coerceIn(700, 2500)
        Log.d(TAG, "TapThenSwipe: swipe to (${endX.toInt()},${endY.toInt()}), dur=${swipeDuration}ms")

        val result = CompletableDeferred<String>()
        service.swipeGesture(startX, startY, endX, endY, swipeDuration) { success ->
            result.complete(if (success) "completed" else "cancelled")
        }
        return withTimeoutOrNull(swipeDuration + 5000) { result.await() } ?: "timeout"
    }

    // ============================================================
    // === TIER 1: DIRECT SLOW SWIPE (Primary) ===
    // ============================================================

    /**
     * Single continuous swipe at slow human speed.
     * v1.2.36: Simplified to straight-line path (complex multi-segment paths
     * caused CANCELLED on v1.2.35). Also checks dispatchGesture return value.
     *
     * Key: duration must be long enough for CAPTCHA JS to track (>1s).
     */
    private suspend fun doSlowSwipe(
        service: TpingAccessibilityService,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        dragDist: Float
    ): String {
        // Slow human drag: ~4ms per pixel, 1000-4000ms
        val duration = (dragDist * 4f).toLong().coerceIn(1000, 4000)

        // Simple straight-line path (complex paths caused CANCELLED on Vivo V2144)
        val path = android.graphics.Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        Log.d(TAG, "SlowSwipe: (${startX.toInt()},${startY.toInt()})→(${endX.toInt()},${endY.toInt()}), dur=${duration}ms, dist=${dragDist.toInt()}")

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val result = CompletableDeferred<String>()
        val dispatched = service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { result.complete("completed") }
            override fun onCancelled(g: GestureDescription?) { result.complete("cancelled") }
        }, null)
        if (!dispatched) return "rejected"
        return withTimeoutOrNull(duration + 5000) { result.await() } ?: "timeout"
    }

    // ============================================================
    // === TIER 3: PRESS-HOLD-DRAG (Last Resort) ===
    // ============================================================

    /**
     * Press-hold-drag using continuation chains.
     * Phase 1: Touch down + hold 300ms (willContinue=true)
     * Phase 2: Drag to target (willContinue=false)
     *
     * NOTE: Continuation chains are unreliable on some devices/ROMs.
     * This is the last resort after tap+swipe and slow swipe fail.
     */
    private suspend fun doPressHoldDrag(
        service: TpingAccessibilityService,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        totalDurationMs: Long
    ): String {
        val dragDist = endX - startX
        if (dragDist < 10) return "skip"

        val holdDuration = 300L
        Log.d(TAG, "PressHoldDrag: Phase 1 — press at (${startX.toInt()},${startY.toInt()})")

        val phase1Result = CompletableDeferred<GestureDescription.StrokeDescription?>()
        service.swipeWithContinuation(
            startX, startY, startX + 2f, startY,
            holdDuration, willContinue = true, previousStroke = null
        ) { stroke -> phase1Result.complete(stroke) }

        val holdStroke = withTimeoutOrNull(holdDuration + 5000) { phase1Result.await() }
        if (holdStroke == null) return "press_failed"

        val dragDuration = (totalDurationMs - holdDuration).coerceAtLeast(400)
        Log.d(TAG, "PressHoldDrag: Phase 2 — drag to (${endX.toInt()},${endY.toInt()})")

        val phase2Result = CompletableDeferred<GestureDescription.StrokeDescription?>()
        service.swipeWithContinuation(
            startX + 2f, startY, endX, endY,
            dragDuration, willContinue = false, previousStroke = holdStroke
        ) { stroke -> phase2Result.complete(stroke) }

        val dragStroke = withTimeoutOrNull(dragDuration + 5000) { phase2Result.await() }
        return if (dragStroke != null) "completed" else "drag_failed"
    }

    // ============================================================
    // === BLIND TARGETS ===
    // ============================================================

    private fun getBlindTarget(
        hasTrack: Boolean,
        trackWidth: Float,
        sliderX: Float,
        trackEndX: Float,
        screenW: Int,
        percentages: FloatArray,
        fixedOffsets: IntArray,
        index: Int
    ): Pair<Float, String> {
        return if (hasTrack && trackWidth > 50) {
            val pct = percentages[index % percentages.size]
            val target = (sliderX + trackWidth * pct).coerceAtMost(trackEndX - 10f)
            target to "blind-track(${(pct * 100).toInt()}%)"
        } else {
            val offset = fixedOffsets[index % fixedOffsets.size]
            val target = (sliderX + offset).coerceAtMost(screenW - 50f)
            target to "blind(${offset}px)"
        }
    }

    // ============================================================
    // === WEBVIEW FOCUS (Critical for modern WebView) ===
    // ============================================================

    /**
     * Find WebView in ALL windows and give it focus.
     * v1.2.36: Searches service.windows (not just rootInActiveWindow) because
     * the CAPTCHA WebView might be in a different window (dialog, popup, etc.).
     * Also logs window info and WebView children for diagnostics.
     *
     * Returns: "winN" (found in window N), "active-fallback", or "none"
     */
    private fun focusWebViewAtSlider(
        service: TpingAccessibilityService,
        sliderX: Float,
        sliderY: Float
    ): String {
        try {
            // Log all windows for diagnostics
            val windows = try { service.windows } catch (_: Exception) { null }
            if (windows != null) {
                val wInfo = windows.joinToString("; ") { w ->
                    "id=${w.id},type=${w.type},layer=${w.layer}"
                }
                DiagnosticReporter.logCaptcha("Windows", wInfo.take(300))

                // Search ALL windows for WebView
                for (window in windows) {
                    val root = try { window.root } catch (_: Exception) { null } ?: continue
                    val webView = findWebView(root)
                    if (webView != null) {
                        val className = webView.className?.toString() ?: ""
                        val bounds = android.graphics.Rect()
                        webView.getBoundsInScreen(bounds)
                        val childCount = webView.childCount

                        DiagnosticReporter.logCaptcha("WebView found",
                            "class=$className, bounds=$bounds, children=$childCount, " +
                                "winId=${window.id}, winType=${window.type}")

                        // Try all focus methods
                        val f1 = webView.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                        val f2 = webView.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        val f3 = webView.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

                        DiagnosticReporter.logCaptcha("WebView focus",
                            "FOCUS=$f1, CLICK=$f2, A11Y_FOCUS=$f3")

                        // Log first-level children (DOM elements exposed to accessibility)
                        val childInfo = StringBuilder()
                        for (i in 0 until minOf(childCount, 8)) {
                            val child = try { webView.getChild(i) } catch (_: Exception) { null } ?: continue
                            val cClass = child.className?.toString()?.substringAfterLast('.') ?: ""
                            val cBounds = android.graphics.Rect()
                            child.getBoundsInScreen(cBounds)
                            val cDesc = child.contentDescription?.toString()?.take(20) ?: ""
                            childInfo.append("[$i]$cClass($cBounds)$cDesc; ")
                            child.recycle()
                        }
                        if (childInfo.isNotEmpty()) {
                            DiagnosticReporter.logCaptcha("WebView children",
                                childInfo.toString().take(300))
                        }

                        webView.recycle()
                        root.recycle()
                        return "win${window.id}"
                    }
                    root.recycle()
                }
            }

            // Fallback: use rootInActiveWindow
            val root = service.rootInActiveWindow ?: return "no-root"
            val webView = findWebView(root)
            if (webView != null) {
                webView.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                webView.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                webView.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                webView.recycle()
                root.recycle()
                return "active-fallback"
            }
            root.recycle()
            return "none"
        } catch (e: Exception) {
            Log.e(TAG, "focusWebView error: ${e.message}")
            return "error:${e.message?.take(30)}"
        }
    }

    /**
     * Find a node at the given screen coordinates by traversing the accessibility tree.
     * Returns the deepest (most specific) node whose bounds contain the point.
     */
    private fun findNodeAtPosition(
        node: android.view.accessibility.AccessibilityNodeInfo,
        x: Int, y: Int
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null

        // Check children — prefer deepest match
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childResult = findNodeAtPosition(child, x, y)
            if (childResult != null) return childResult
            child.recycle()
        }

        // No child matched — this node is the deepest match
        return android.view.accessibility.AccessibilityNodeInfo.obtain(node)
    }

    /**
     * Find any WebView node in the accessibility tree.
     */
    private fun findWebView(
        node: android.view.accessibility.AccessibilityNodeInfo
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        if (className.contains("WebView", ignoreCase = true)) {
            return android.view.accessibility.AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findWebView(child)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    // ============================================================
    // === DIAGNOSTICS ===
    // ============================================================

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
