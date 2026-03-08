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
 * Puzzle CAPTCHA solver — v1.2.25
 *
 * Key design decisions:
 *   1. WARM-UP gesture at neutral position before solving (gesture system needs kick-start)
 *   2. Primary: single swipe with track-aware positioning
 *   3. Fallback: gradual slide using continuation chains (keeps finger down)
 *   4. OpenCV gap detection → verify after each attempt
 *
 * Why warm-up is needed:
 *   Android AccessibilityService gesture dispatch sometimes fails silently on the first gesture
 *   after a period of inactivity. A micro-swipe at a safe position "wakes up" the system.
 *   v1.2.22 had testGestureDispatch which accidentally served this purpose → worked.
 *   v1.2.24 removed it → slider stopped moving.
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
        // === WARM-UP GESTURE ===
        // Micro-swipe at a neutral position to wake up the gesture system.
        // Position: top-center of screen (status bar area) — safe, no UI interaction.
        // ============================================================
        status("ตรวจสอบระบบ gesture...")
        var warmupOk = doWarmup(service, screenW)
        if (!warmupOk) {
            DiagnosticReporter.logCaptcha("Warmup 1 failed", "retrying...")
            delay(500)
            warmupOk = doWarmup(service, screenW)
            if (!warmupOk) {
                status("❌ ระบบ gesture ไม่พร้อม! ลอง ปิด/เปิด Accessibility Service")
                DiagnosticReporter.logCaptcha("CRITICAL: warmup failed", "both attempts failed")
                autoSendDiagnostics()
                return
            }
        }
        status("✓ ระบบ gesture พร้อม")
        delay(500)

        // === Blind-drag offsets (when OpenCV can't find gap) ===
        val blindPercentages = floatArrayOf(0.30f, 0.50f, 0.70f, 0.20f, 0.60f, 0.40f, 0.80f)
        val blindFixedOffsets = intArrayOf(200, 350, 500, 150, 450, 300, 550)
        var blindIndex = 0
        var consecutiveGestureFailures = 0
        var useGradualMode = false

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
                        status("ครั้งที่ $attempt: เลื่อน ${(targetX - sliderX).toInt()}px ($dragMode)")
                    }
                } else {
                    val result = getBlindTarget(
                        hasTrack, trackWidth, sliderX, trackEndX, screenW,
                        blindPercentages, blindFixedOffsets, blindIndex
                    )
                    targetX = result.first
                    dragMode = result.second
                    blindIndex++
                    status("ครั้งที่ $attempt: เลื่อน ${(targetX - sliderX).toInt()}px ($dragMode)")
                }
            } else {
                val result = getBlindTarget(
                    hasTrack, trackWidth, sliderX, trackEndX, screenW,
                    blindPercentages, blindFixedOffsets, blindIndex
                )
                targetX = result.first
                dragMode = result.second
                blindIndex++
                status("ครั้งที่ $attempt: เลื่อน ${(targetX - sliderX).toInt()}px ($dragMode)")
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
            val swipeMode = if (useGradualMode) "gradual" else "single"
            status("ครั้งที่ $attempt: เลื่อนสไลด์ ($dragMode+$swipeMode, ${dragDist.toInt()}px)...")

            val swipeResult: String = if (useGradualMode) {
                doGradualSwipe(service, sliderX, sliderY, targetX, sliderY, duration)
            } else {
                doSwipe(service, sliderX, sliderY, targetX, sliderY, duration)
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
                    "result=$swipeResult, consecutiveFails=$consecutiveGestureFailures, gradual=$useGradualMode"
                )

                // After 2 single-swipe failures → switch to gradual mode
                if (!useGradualMode && consecutiveGestureFailures >= 2) {
                    status("🔄 เปลี่ยนเป็นโหมดเลื่อนทีละน้อย...")
                    useGradualMode = true
                    // Re-warmup before gradual mode
                    doWarmup(service, screenW)
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
                "hasTrack=$hasTrack, gradual=$useGradualMode"
        )
        autoSendDiagnostics()
    }

    // ============================================================
    // === WARM-UP ===
    // ============================================================

    /**
     * Warm up the gesture dispatch system with a micro-swipe at a neutral position.
     * Returns true if the gesture completed successfully.
     */
    private suspend fun doWarmup(service: TpingAccessibilityService, screenW: Int): Boolean {
        Log.d(TAG, "Warmup: micro-swipe at top center")
        val result = CompletableDeferred<Boolean>()
        // 1-pixel micro-swipe at top-center (status bar area — won't trigger any UI action)
        service.swipeGesture(
            screenW / 2f, 3f,
            screenW / 2f + 1f, 3f,
            50
        ) { success ->
            Log.d(TAG, "Warmup result: $success")
            result.complete(success)
        }
        return withTimeoutOrNull(3000) { result.await() } ?: false
    }

    // ============================================================
    // === SINGLE SWIPE ===
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
    // === GRADUAL SWIPE (continuation chains) ===
    // ============================================================

    /**
     * Execute a gradual swipe using continuation chains — finger stays pressed down
     * between segments. More human-like and sometimes more reliable than single swipe.
     *
     * Breaks the total distance into small ~40px segments, each dispatched as a
     * continuation of the previous stroke (willContinue=true), with the last segment
     * releasing (willContinue=false).
     */
    private suspend fun doGradualSwipe(
        service: TpingAccessibilityService,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        totalDurationMs: Long
    ): String {
        val totalDist = endX - startX
        if (totalDist < 10) return "skip"

        val numSegments = (totalDist / 40f).toInt().coerceIn(3, 20)
        val segmentDist = totalDist / numSegments
        val segmentDuration = (totalDurationMs / numSegments).coerceAtLeast(60)

        Log.d(
            TAG,
            "Gradual swipe: $numSegments segments, " +
                "segDist=${segmentDist.toInt()}, segDur=$segmentDuration"
        )

        var currentX = startX
        var previousStroke: GestureDescription.StrokeDescription? = null

        for (i in 0 until numSegments) {
            val isLast = (i == numSegments - 1)
            val nextX = if (isLast) endX else (currentX + segmentDist)

            val segResult = CompletableDeferred<GestureDescription.StrokeDescription?>()
            service.swipeWithContinuation(
                currentX, startY,
                nextX, startY,
                segmentDuration,
                willContinue = !isLast,
                previousStroke = previousStroke
            ) { stroke -> segResult.complete(stroke) }

            val stroke = withTimeoutOrNull(segmentDuration + 5000) { segResult.await() }
            if (stroke == null) {
                Log.e(TAG, "Gradual swipe failed at segment $i/$numSegments")
                return if (i == 0) "dispatch_failed" else "partial_$i"
            }

            previousStroke = stroke
            currentX = nextX
        }

        return "completed"
    }

    // ============================================================
    // === BLIND TARGETS ===
    // ============================================================

    /**
     * Get blind target position based on whether we have track info.
     * With track: use % of track width (more accurate coverage)
     * Without track: use fixed pixel offsets
     */
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

    /** Auto-send diagnostics in background (fire & forget) */
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
