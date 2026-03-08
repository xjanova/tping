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
 * Puzzle CAPTCHA solver — v1.2.28
 *
 * Slide strategy (3 tiers):
 *   1. PRIMARY: Tap slider handle → delay → slow swipe to target
 *      - Tap "activates" the slider handle (ACTION_DOWN + UP)
 *      - 300ms delay lets the handle register
 *      - Slow swipe (long duration) drags to target
 *   2. FALLBACK: Very slow single swipe (no pre-tap)
 *      - Extra-long duration so the slider has time to register the initial touch
 *   3. LAST RESORT: Press-hold-drag (continuation chains)
 *      - Phase 1: hold finger at slider 300ms (willContinue=true)
 *      - Phase 2: drag to target (willContinue=false)
 *      - Only used if both tier 1 and 2 fail
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

        // === Blind-drag offsets ===
        val blindPercentages = floatArrayOf(0.30f, 0.50f, 0.70f, 0.20f, 0.60f, 0.40f, 0.80f)
        val blindFixedOffsets = intArrayOf(200, 350, 500, 150, 450, 300, 550)
        var blindIndex = 0
        var consecutiveGestureFailures = 0
        var swipeTier = 1  // 1=tap+swipe, 2=slow swipe, 3=press-hold-drag

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
            if (dragDist < 10) {
                DiagnosticReporter.logCaptcha(
                    "Skip: dist=${"%.0f".format(dragDist)}",
                    "mode=$dragMode"
                )
                status("⚠ ระยะน้อยเกินไป (${"%.0f".format(dragDist)}px)")
                continue
            }

            // === Execute swipe ===
            val swipeModeName = when (swipeTier) {
                1 -> "tap+swipe"
                2 -> "slow-swipe"
                else -> "press-hold-drag"
            }
            status("ครั้งที่ $attempt: เลื่อนสไลด์ ($dragMode+$swipeModeName, ${dragDist.toInt()}px)...")

            val swipeResult: String = when (swipeTier) {
                1 -> doTapThenSwipe(service, sliderX, sliderY, targetX, sliderY, dragDist)
                2 -> doSlowSwipe(service, sliderX, sliderY, targetX, sliderY, dragDist)
                else -> doPressHoldDrag(service, sliderX, sliderY, targetX, sliderY,
                    (dragDist * 3f).toLong().coerceIn(800, 3000))
            }

            Log.d(
                TAG,
                "Swipe result=$swipeResult, mode=$dragMode+$swipeModeName, " +
                    "attempt=$attempt, dist=${"%.0f".format(dragDist)}, target=${targetX.toInt()}"
            )
            DiagnosticReporter.logCaptcha(
                "Swipe attempt=$attempt",
                "result=$swipeResult, mode=$dragMode+$swipeModeName, " +
                    "dist=${"%.0f".format(dragDist)}, target=${targetX.toInt()}, " +
                    "trackW=${trackWidth.toInt()}"
            )

            if (swipeResult != "completed") {
                consecutiveGestureFailures++
                status("⚠ gesture: $swipeResult (fail #$consecutiveGestureFailures, tier $swipeTier)")
                DiagnosticReporter.logCaptcha(
                    "Swipe failed",
                    "result=$swipeResult, consecutiveFails=$consecutiveGestureFailures, tier=$swipeTier"
                )

                // Escalate to next tier after 2 consecutive failures
                if (consecutiveGestureFailures >= 2 && swipeTier < 3) {
                    swipeTier++
                    consecutiveGestureFailures = 0
                    status("🔄 เปลี่ยนเป็นโหมด ${when(swipeTier) { 2 -> "slow-swipe"; else -> "press-hold-drag" }}...")
                    doWarmupTap(service, screenW, screenH)
                    delay(300)
                    continue
                }

                // After 2 failures on last tier → give up
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
    // === TIER 1: TAP-THEN-SWIPE (Primary) ===
    // ============================================================

    /**
     * Tap the slider handle first to "activate" it, then slow swipe to target.
     *
     * Many slider CAPTCHAs need the handle to register ACTION_DOWN before
     * they start tracking drag. A quick tap wakes up the handle, then
     * the slow swipe drags it to the target position.
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

        // Step 3: Slow swipe from slider to target
        // Use long duration: ~4ms per pixel, min 1500ms, max 4000ms
        val swipeDuration = (dragDist * 4f).toLong().coerceIn(1500, 4000)
        Log.d(TAG, "TapThenSwipe: swipe to (${endX.toInt()},${endY.toInt()}), dur=${swipeDuration}ms")

        val result = CompletableDeferred<String>()
        service.swipeGesture(startX, startY, endX, endY, swipeDuration) { success ->
            result.complete(if (success) "completed" else "cancelled")
        }
        return withTimeoutOrNull(swipeDuration + 5000) { result.await() } ?: "timeout"
    }

    // ============================================================
    // === TIER 2: VERY SLOW SWIPE (Fallback) ===
    // ============================================================

    /**
     * Single continuous swipe with extra-long duration.
     * The slow speed gives the slider time to register the initial touch
     * and start tracking the drag, even without a pre-tap.
     */
    private suspend fun doSlowSwipe(
        service: TpingAccessibilityService,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        dragDist: Float
    ): String {
        // Very slow: ~6ms per pixel, min 2000ms, max 5000ms
        val duration = (dragDist * 6f).toLong().coerceIn(2000, 5000)
        Log.d(TAG, "SlowSwipe: (${startX.toInt()},${startY.toInt()})→(${endX.toInt()},${endY.toInt()}), dur=${duration}ms")

        val result = CompletableDeferred<String>()
        service.swipeGesture(startX, startY, endX, endY, duration) { success ->
            result.complete(if (success) "completed" else "cancelled")
        }
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
