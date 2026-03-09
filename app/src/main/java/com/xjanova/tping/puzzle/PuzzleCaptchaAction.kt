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
 * Puzzle CAPTCHA solver — v1.2.32
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
        // === FOCUS: Give WebView input focus via AccessibilityNodeInfo ===
        // ============================================================
        // Modern WebView (auto-updated) requires VIEW focus before forwarding
        // dispatchGesture touch events to web content JavaScript.
        // User confirmed: manual finger touch first → bot works after.
        // Solution: find WebView node → ACTION_FOCUS + ACTION_CLICK to activate it.
        status("กำลังเปิดใช้งาน CAPTCHA...")
        val focusResult = focusWebViewAtSlider(service, sliderX, sliderY)
        DiagnosticReporter.logCaptcha("Focus phase done", "method=$focusResult, sliderAt=(${sliderX.toInt()},${sliderY.toInt()})")
        if (focusResult == "none") {
            Log.w(TAG, "Could not find WebView to focus — gesture may not work")
        }
        delay(800)

        // Also do a tap gesture on the CAPTCHA area as backup activation
        val captchaTapY = (sliderY - 150f).coerceAtLeast(50f)
        val captchaTapX = sliderX + (if (trackWidth > 50) trackWidth * 0.3f else 100f)
        val focusTap = CompletableDeferred<Boolean>()
        service.swipeGesture(captchaTapX, captchaTapY, captchaTapX + 1f, captchaTapY, 150) { success ->
            focusTap.complete(success)
        }
        withTimeoutOrNull(3000) { focusTap.await() }
        delay(500)
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
     * Single continuous swipe at slow human speed with natural path.
     * ACTION_DOWN must land exactly on the slider handle for the CAPTCHA
     * JavaScript to start tracking the drag — never start before the handle.
     *
     * Key: duration must be long enough for CAPTCHA JS to track (>1s).
     * Path includes slight Y jitter and initial "grip" pause to mimic human drag.
     */
    private suspend fun doSlowSwipe(
        service: TpingAccessibilityService,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        dragDist: Float
    ): String {
        // Slow human drag: ~4ms per pixel, 800-3000ms
        // (1.5ms was too fast — CAPTCHA JS couldn't track the drag)
        val duration = (dragDist * 4f).toLong().coerceIn(800, 3000)

        // Build human-like path: initial grip + gradual drag with Y jitter
        val path = android.graphics.Path().apply {
            moveTo(startX, startY)
            // Phase 1: "grip" — tiny movements near start (~15% of path)
            lineTo(startX + 2f, startY + 1f)
            lineTo(startX + 5f, startY - 0.5f)
            lineTo(startX + 8f, startY + 0.5f)
            // Phase 2: drag to target with slight sine-wave Y jitter
            val dragStart = startX + 8f
            val remaining = endX - dragStart
            val steps = 8
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val x = dragStart + remaining * t
                val yJitter = (kotlin.math.sin(t * Math.PI * 2.5) * 2.0).toFloat()
                lineTo(x, startY + yJitter)
            }
        }
        Log.d(TAG, "SlowSwipe: (${startX.toInt()},${startY.toInt()})→(${endX.toInt()},${endY.toInt()}), dur=${duration}ms, dist=${dragDist.toInt()}")

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val result = CompletableDeferred<String>()
        service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { result.complete("completed") }
            override fun onCancelled(g: GestureDescription?) { result.complete("cancelled") }
        }, null)
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
     * Find the WebView (or any view) at the slider position and give it focus.
     * Modern Android WebView only forwards dispatchGesture touch events to
     * JavaScript after the WebView has received VIEW focus.
     *
     * Returns: "node-focus", "node-click", "tree-focus", or "none"
     */
    private fun focusWebViewAtSlider(
        service: TpingAccessibilityService,
        sliderX: Float,
        sliderY: Float
    ): String {
        try {
            val root = service.rootInActiveWindow ?: run {
                Log.w(TAG, "focusWebView: rootInActiveWindow is null")
                return "none"
            }

            // Strategy 1: Find node at slider coordinates via tree traversal
            val targetNode = findNodeAtPosition(root, sliderX.toInt(), sliderY.toInt())
            if (targetNode != null) {
                val className = targetNode.className?.toString() ?: ""
                Log.d(TAG, "focusWebView: found node '$className' at slider position")

                // Try ACTION_FOCUS first (gives view input focus)
                val focused = targetNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                if (focused) {
                    Log.d(TAG, "focusWebView: ACTION_FOCUS succeeded on '$className'")
                    targetNode.recycle()
                    root.recycle()
                    return "node-focus"
                }

                // Try ACTION_CLICK (triggers View.performClick → might initialize touch handling)
                val clicked = targetNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    Log.d(TAG, "focusWebView: ACTION_CLICK succeeded on '$className'")
                    targetNode.recycle()
                    root.recycle()
                    return "node-click"
                }

                targetNode.recycle()
            }

            // Strategy 2: Find any WebView in the tree and focus it
            val webView = findWebView(root)
            if (webView != null) {
                val className = webView.className?.toString() ?: ""
                Log.d(TAG, "focusWebView: found WebView '$className' in tree")
                webView.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                webView.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                webView.recycle()
                root.recycle()
                return "tree-focus"
            }

            root.recycle()
            return "none"
        } catch (e: Exception) {
            Log.e(TAG, "focusWebView error: ${e.message}")
            return "none"
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
