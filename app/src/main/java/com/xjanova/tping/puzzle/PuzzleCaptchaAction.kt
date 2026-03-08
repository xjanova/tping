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
 * Puzzle CAPTCHA solver — v1.2.26
 *
 * Key changes from v1.2.25:
 *   1. PRIMARY mode = press-hold-drag (continuation chains) — simulates human finger
 *   2. Warm-up uses tap (touch-down + release) at center screen, not micro-swipe
 *   3. Press phase: hold finger at slider 300ms before dragging
 *   4. Single swipe is FALLBACK (not primary)
 *
 * Why press-hold-drag:
 *   Many slider implementations require ACTION_DOWN to "grab" the handle first,
 *   then ACTION_MOVE to drag. A single Path swipe may not properly "grab" the slider
 *   handle. By using continuation chains with an initial hold phase, we ensure the
 *   slider registers the touch before the drag begins.
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
        var useSingleSwipeMode = false  // Start with press-hold-drag, fallback to single swipe

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
            val duration = (dragDist * 2.5f).toLong().coerceIn(600, 2500)
            val swipeMode = if (useSingleSwipeMode) "single" else "press-hold-drag"
            status("ครั้งที่ $attempt: เลื่อนสไลด์ ($dragMode+$swipeMode, ${dragDist.toInt()}px)...")

            val swipeResult: String = if (useSingleSwipeMode) {
                doSwipe(service, sliderX, sliderY, targetX, sliderY, duration)
            } else {
                doPressHoldDrag(service, sliderX, sliderY, targetX, sliderY, duration)
            }

            Log.d(
                TAG,
                "Swipe result=$swipeResult, mode=$dragMode+$swipeMode, " +
                    "attempt=$attempt, dist=${"%.0f".format(dragDist)}, target=${targetX.toInt()}"
            )
            DiagnosticReporter.logCaptcha(
                "Swipe attempt=$attempt",
                "result=$swipeResult, mode=$dragMode+$swipeMode, " +
                    "dist=${"%.0f".format(dragDist)}, target=${targetX.toInt()}, " +
                    "trackW=${trackWidth.toInt()}"
            )

            if (swipeResult != "completed") {
                consecutiveGestureFailures++
                status("⚠ gesture: $swipeResult (fail #$consecutiveGestureFailures)")
                DiagnosticReporter.logCaptcha(
                    "Swipe failed",
                    "result=$swipeResult, consecutiveFails=$consecutiveGestureFailures, single=$useSingleSwipeMode"
                )

                // After 2 press-hold-drag failures → switch to single swipe
                if (!useSingleSwipeMode && consecutiveGestureFailures >= 2) {
                    status("🔄 เปลี่ยนเป็นโหมด single swipe...")
                    useSingleSwipeMode = true
                    doWarmupTap(service, screenW, screenH)
                    delay(300)
                    continue
                }

                // After 4 total failures → give up
                if (consecutiveGestureFailures >= 4) {
                    status("❌ ระบบ gesture ไม่ทำงาน! ลอง ปิด/เปิด Accessibility Service")
                    DiagnosticReporter.logCaptcha(
                        "CRITICAL: gesture broken",
                        "$consecutiveGestureFailures consecutive failures, aborting"
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
                            "attempt=$attempt, mode=$dragMode+$swipeMode"
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
                "hasTrack=$hasTrack, single=$useSingleSwipeMode"
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
    // === PRESS-HOLD-DRAG (Primary mode) ===
    // ============================================================

    /**
     * Execute a press-hold-drag using continuation chains.
     *
     * Phase 1: Touch down at slider, tiny movement (2px), hold 300ms  →  willContinue=true
     * Phase 2: Drag from slider to target, calculated duration          →  willContinue=false (release)
     *
     * This simulates how a human drags: press the slider handle, pause briefly,
     * then drag to target position. Many slider implementations need this touch-down
     * "grab" phase before accepting drag movements.
     */
    private suspend fun doPressHoldDrag(
        service: TpingAccessibilityService,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        totalDurationMs: Long
    ): String {
        val dragDist = endX - startX
        if (dragDist < 10) return "skip"

        // Phase 1: Press and hold at slider position (tiny 2px movement to register as gesture)
        val holdDuration = 300L
        Log.d(TAG, "PressHoldDrag: Phase 1 — press at (${startX.toInt()},${startY.toInt()}), hold ${holdDuration}ms")

        val phase1Result = CompletableDeferred<GestureDescription.StrokeDescription?>()
        service.swipeWithContinuation(
            startX, startY,
            startX + 2f, startY,
            holdDuration,
            willContinue = true,
            previousStroke = null
        ) { stroke -> phase1Result.complete(stroke) }

        val holdStroke = withTimeoutOrNull(holdDuration + 5000) { phase1Result.await() }
        if (holdStroke == null) {
            Log.e(TAG, "PressHoldDrag: Phase 1 (press) failed!")
            return "press_failed"
        }
        Log.d(TAG, "PressHoldDrag: Phase 1 OK — finger down on slider")

        // Phase 2: Drag from current position to target
        val dragDuration = (totalDurationMs - holdDuration).coerceAtLeast(400)
        Log.d(TAG, "PressHoldDrag: Phase 2 — drag to (${endX.toInt()},${endY.toInt()}), dur=${dragDuration}ms")

        val phase2Result = CompletableDeferred<GestureDescription.StrokeDescription?>()
        service.swipeWithContinuation(
            startX + 2f, startY,
            endX, endY,
            dragDuration,
            willContinue = false,  // Release finger
            previousStroke = holdStroke
        ) { stroke -> phase2Result.complete(stroke) }

        val dragStroke = withTimeoutOrNull(dragDuration + 5000) { phase2Result.await() }
        if (dragStroke == null) {
            Log.e(TAG, "PressHoldDrag: Phase 2 (drag) failed!")
            return "drag_failed"
        }
        Log.d(TAG, "PressHoldDrag: Phase 2 OK — drag completed")

        return "completed"
    }

    // ============================================================
    // === SINGLE SWIPE (Fallback) ===
    // ============================================================

    /**
     * Execute a single continuous swipe.
     * Returns: "completed", "cancelled", or "timeout"
     */
    private suspend fun doSwipe(
        service: TpingAccessibilityService,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long
    ): String {
        val result = CompletableDeferred<String>()
        service.swipeGesture(startX, startY, endX, endY, durationMs) { success ->
            result.complete(if (success) "completed" else "cancelled")
        }
        return withTimeoutOrNull(durationMs + 5000) { result.await() } ?: "timeout"
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
