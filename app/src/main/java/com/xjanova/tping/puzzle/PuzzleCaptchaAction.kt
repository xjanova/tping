package com.xjanova.tping.puzzle

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import com.xjanova.tping.data.diagnostic.DiagnosticReporter
import com.xjanova.tping.data.entity.RecordedAction
import com.xjanova.tping.overlay.FloatingOverlayService
import com.xjanova.tping.service.TpingAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.util.concurrent.TimeUnit

/**
 * Puzzle CAPTCHA solver — v1.2.77
 *
 * v1.2.77 — FIX: Piece-to-slider coordinate mapping + search area:
 * - CRITICAL FIX: detectGapByDiff now returns DiffResult with SLIDER TARGET
 *   (not image gap position). Accounts for piece-to-slider movement ratio.
 * - FIX: Search area now covers ENTIRE puzzle region, not just right of sliderX.
 *   Old code started at sliderX+pieceW/2 which EXCLUDED the gap on many puzzles.
 * - FIX: Contour selection uses old/new piece positions from diff instead of
 *   assuming piece starts at sliderX (wrong for WebView-rendered CAPTCHAs).
 * - FIX: Movement ratio calculated from actual piece movement in diff vs
 *   slider moveDistance, handles non-1:1 puzzle image scaling.
 * - Search split into LEFT and RIGHT parts, excluding new piece position
 *   to avoid false template matches on the piece itself.
 * - Confidence threshold raised from 0.05 to 0.10 to reduce false positives.
 * - Overshoot correction scales with scaleFactor (not fixed 8px).
 *
 * v1.2.75 — Proven production solver approach (GeeTest/Tencent-style):
 * - REWRITE: 3-method detection with consensus voting:
 *   A) EDGE template: GaussianBlur(5×5) → Canny(100,200) → TM_CCOEFF_NORMED
 *      (proven approach used by all successful puzzle CAPTCHA solvers)
 *   B) SILHOUETTE template: diff mask edges (no texture noise)
 *   C) LOCAL CONTRAST scan: darker-than-neighbors column analysis
 * - Any 2 methods agree within 30px → CONSENSUS (highest confidence)
 * - Morphological erode/dilate on diff mask (clean noise, restore shape)
 * - Adaptive contrast threshold for white/bright images
 * - Overshoot correction on ALL detection paths (diff: -8px, static: -12px)
 * - Longer refresh delay on attempt 3+ (3500ms)
 *
 * v1.2.73 — Smarter detection (silhouette + local contrast):
 * - FIX: Template matching now uses piece SILHOUETTE edges (from diff binary mask)
 *   instead of image crop edges. Silhouette has clean outline without texture noise.
 * - NEW: Local contrast column scan — finds band that is darker than its LEFT and
 *   RIGHT neighbors (not globally darkest). Handles varied image backgrounds.
 * - Dual-method consensus: if silhouette match and local contrast agree → high confidence.
 * - Explore distance increased to 80-120px to ensure diff blobs don't overlap.
 * - detectGapFromStatic also improved: dark band now uses local contrast.
 *
 * v1.2.72 — Diff-based gap detection (Before/After + Edge Template Matching):
 * - detectGapByDiff() — moves piece, diffs before/after screenshots,
 *   extracts piece shape, uses template matching to find gap.
 * - doScanDrag flow: press → explore → screenshot → diff detect → move to gap
 * - Static detection kept as fallback with -10px correction
 * - Conservative fine-tune: 1 round, 40% drift, uses gap left edge as reference
 *
 * v1.2.71 — Code cleanup + bug fixes:
 * - DELETED legacy code: blind drag, smart drag, doSmartDragWithVerify,
 *   scan loop, functional failure escalation — all interfered with new algorithm
 * - FIX: Detection failure ("gap_not_detected") now refreshes puzzle instead of
 *   escalating swipe tier (shell → gesture). Old behavior caused CAPTCHA rejection
 *   because gesture creates isTrusted=false events.
 * - FIX: Fine-tune now uses findGapRegion (full-image search) instead of
 *   detectGapFromStatic with wrong sliderX origin after piece moved.
 * - Deleted unused PuzzleSolver methods: measurePuzzleEdges, measureEdgesAtX,
 *   measureWhiteBorderScore, findGapByDiffMatch, measureEdgeStrength,
 *   findDarkRegion, measureDarkness, findGapOffset, etc.
 *
 * v1.2.69 — Detect-first, direct-drag:
 * - detectGapFromStatic() — 3-signal gap detection:
 *   a) Sobel gradient (X+Y) column analysis → gap boundary peaks
 *   b) Column brightness → darkest band = gap shadow
 *   c) White border peaks → vertical bright lines at gap edges
 * - Direct drag to detected position (no scanning)
 * - Falls back to findGapRegion + findGapByWhiteBorder if primary fails
 *
 * Slide strategy:
 *   0. SHELL INPUT: sendevent press-hold-drag (isTrusted=true)
 *   1+. DISPATCH GESTURE: dispatchGesture continuation chains (fallback)
 *
 * Hides overlay during CAPTCHA (FLAG_NOT_TOUCHABLE) to prevent
 * FLAG_WINDOW_IS_PARTIALLY_OBSCURED on MotionEvents.
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

        // Set debug directory for saving diagnostic images
        try {
            val debugDir = java.io.File(service.cacheDir, "puzzle_debug")
            PuzzleSolver.debugDir = debugDir
            PuzzleSolver.cleanOldDebugImages()
            Log.d(TAG, "Debug images → ${debugDir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot set debug dir: ${e.message}")
        }

        // === Get accurate screen dimensions ===
        // CRITICAL (v1.2.44): Use WindowManager.getRealSize() instead of
        // service.resources.displayMetrics. The AccessibilityService's displayMetrics
        // can return STALE orientation data (e.g., portrait 1080x2400 when the actual
        // display is landscape 2400x1080), causing all coordinates to be wrong.
        val screenW: Int
        val screenH: Int
        val rotation: Int
        val wm = try {
            service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        } catch (_: Exception) { null }
        if (wm != null) {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            rotation = display.rotation
            val realSize = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(realSize)
            screenW = realSize.x
            screenH = realSize.y
        } else {
            // Fallback to displayMetrics if WindowManager unavailable
            val metrics = service.resources.displayMetrics
            screenW = metrics.widthPixels
            screenH = metrics.heightPixels
            rotation = 0
            Log.w(TAG, "WindowManager unavailable, using displayMetrics fallback")
        }

        // Compare with displayMetrics to detect mismatches (diagnostic)
        val metrics = service.resources.displayMetrics
        val metricsW = metrics.widthPixels
        val metricsH = metrics.heightPixels
        if (metricsW != screenW || metricsH != screenH) {
            Log.w(TAG, "⚠ METRICS MISMATCH: displayMetrics=${metricsW}x${metricsH}, " +
                "getRealSize=${screenW}x${screenH}, rotation=$rotation")
            DiagnosticReporter.logCaptcha("Metrics mismatch",
                "metrics=${metricsW}x${metricsH}, real=${screenW}x${screenH}, rot=$rotation")
        }

        // Detect orientation mismatch between recording and current display
        val isCurrentLandscape = screenW > screenH
        val isRecordedLandscape = action.screenWidth > action.screenHeight
        val orientationChanged = (action.screenWidth > 0 && action.screenHeight > 0)
            && isCurrentLandscape != isRecordedLandscape

        // === Scale coordinates ===
        val scaleX: Float
        val scaleY: Float
        if (orientationChanged) {
            // Orientation flipped between recording and execution.
            // Recorded dimensions are swapped relative to current screen.
            // e.g., recorded portrait 1080x2400 → current landscape 2400x1080
            //   scaleX = 2400/2400 = 1.0, scaleY = 1080/1080 = 1.0
            scaleX = screenW.toFloat() / action.screenHeight
            scaleY = screenH.toFloat() / action.screenWidth
            Log.w(TAG, "⚠ ORIENTATION CHANGED: " +
                "recorded=${action.screenWidth}x${action.screenHeight}" +
                "(${if (isRecordedLandscape) "landscape" else "portrait"}) → " +
                "current=${screenW}x$screenH" +
                "(${if (isCurrentLandscape) "landscape" else "portrait"}), " +
                "rotation=$rotation, " +
                "scale=${"%.2f".format(scaleX)}x${"%.2f".format(scaleY)}")
            DiagnosticReporter.logCaptcha("Orientation changed",
                "rec=${action.screenWidth}x${action.screenHeight}, " +
                    "cur=${screenW}x$screenH, rot=$rotation, " +
                    "scale=${"%.2f".format(scaleX)}x${"%.2f".format(scaleY)}")
        } else {
            scaleX = if (action.screenWidth > 0) screenW.toFloat() / action.screenWidth else 1f
            scaleY = if (action.screenHeight > 0) screenH.toFloat() / action.screenHeight else 1f
        }

        // Transform coordinates — swap X↔Y if orientation changed
        val sliderX: Float
        val sliderY: Float
        val refreshX: Float
        val refreshY: Float
        if (orientationChanged) {
            // When screen rotates 90°, the X and Y axes swap.
            // Recorded X (along short edge) → maps to current Y (now short edge)
            // Recorded Y (along long edge) → maps to current X (now long edge)
            sliderX = config.sliderButtonY * scaleX
            sliderY = config.sliderButtonX * scaleY
            refreshX = config.refreshButtonY * scaleX
            refreshY = config.refreshButtonX * scaleY
            Log.d(TAG, "Coordinates SWAPPED: " +
                "slider=(${config.sliderButtonX},${config.sliderButtonY})→(${sliderX.toInt()},${sliderY.toInt()}), " +
                "refresh=(${config.refreshButtonX},${config.refreshButtonY})→(${refreshX.toInt()},${refreshY.toInt()})")
        } else {
            sliderX = config.sliderButtonX * scaleX
            sliderY = config.sliderButtonY * scaleY
            refreshX = config.refreshButtonX * scaleX
            refreshY = config.refreshButtonY * scaleY
        }
        val hasRefresh = config.refreshButtonX > 0 && config.refreshButtonY > 0
        val hasTrack = config.hasTrackInfo

        // Validate coordinates are within screen bounds
        if (sliderX < 0 || sliderX > screenW || sliderY < 0 || sliderY > screenH) {
            Log.e(TAG, "⚠ SLIDER OUT OF BOUNDS: (${sliderX.toInt()},${sliderY.toInt()}) " +
                "screen=${screenW}x${screenH}")
            DiagnosticReporter.logCaptcha("Slider out of bounds",
                "pos=(${sliderX.toInt()},${sliderY.toInt()}), screen=${screenW}x${screenH}")
        }

        // Auto-detect track width for 2-point recordings (sliderTrackEndX == 0)
        // Estimate: track extends from slider to ~90% of screen width
        // (most CAPTCHA sliders span 60-80% of the screen)
        val trackEndX: Float
        val trackWidth: Float
        if (hasTrack) {
            trackEndX = if (orientationChanged) {
                config.sliderTrackEndY * scaleX  // swap Y→X
            } else {
                config.sliderTrackEndX * scaleX
            }
            trackWidth = trackEndX - sliderX
        } else {
            trackEndX = screenW * 0.90f
            trackWidth = trackEndX - sliderX
        }

        val rotStr = when (rotation) {
            Surface.ROTATION_0 -> "0°(portrait)"
            Surface.ROTATION_90 -> "90°(landscape)"
            Surface.ROTATION_180 -> "180°(reverse-portrait)"
            Surface.ROTATION_270 -> "270°(reverse-landscape)"
            else -> "${rotation}°"
        }
        val info = "slider=(${sliderX.toInt()},${sliderY.toInt()}), " +
            "trackEnd=${trackEndX.toInt()}, trackW=${trackWidth.toInt()}, " +
            "screen=${screenW}x$screenH, " +
            "metrics=${metricsW}x${metricsH}, " +
            "rotation=$rotStr, " +
            "scale=${"%.2f".format(scaleX)}x${"%.2f".format(scaleY)}, " +
            "orientationChanged=$orientationChanged, " +
            "hasTrack=$hasTrack(auto=${!hasTrack}), " +
            "recorded=${action.screenWidth}x${action.screenHeight}"
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
        // === HIDE OVERLAY: Prevent FLAG_WINDOW_IS_PARTIALLY_OBSCURED ===
        // ============================================================
        try {
            FloatingOverlayService.instance?.setTouchPassthrough(true)
        } catch (_: Exception) {}

        // ============================================================
        // === FOCUS + PROBE: Determine best swipe method ===
        // ============================================================
        // v1.2.37: dispatchGesture events have isTrusted=false in WebView JS.
        // CAPTCHA rejects them. Solution priority:
        //   1. Shell "input swipe" (hardware-level, isTrusted=true)
        //   2. Accessibility scroll actions on slider node
        //   3. dispatchGesture as last fallback
        status("กำลังตรวจสอบระบบ CAPTCHA...")

        // Step 1: Focus WebView
        val focusResult = focusWebViewAtSlider(service, sliderX, sliderY)
        DiagnosticReporter.logCaptcha("Focus phase", "method=$focusResult")
        delay(300)

        // Step 2: Scan WebView nodes for slider element
        val sliderNodeInfo = scanForSliderNode(service, sliderX, sliderY, trackWidth)
        DiagnosticReporter.logCaptcha("Slider scan", sliderNodeInfo.take(300))
        delay(200)

        // Step 3: Test shell "input swipe" (most reliable if available)
        // NOTE: Test at safe area (bottom of screen), NOT at slider position.
        // Testing at sliderX/sliderY sends a real tap to the CAPTCHA which can
        // trigger a failed-drag state and make the CAPTCHA harder before we solve it.
        status("ทดสอบระบบสัมผัส...")
        val shellTestResult = testShellInput(screenW / 2f, screenH * 0.85f)
        DiagnosticReporter.logCaptcha("Shell test", "result=$shellTestResult")
        val useShellSwipe = shellTestResult.startsWith("ok")
        if (useShellSwipe) {
            val method = if (shellTestResult.contains("su")) "root" else "shell"
            status("✓ ใช้ระบบสัมผัสจริง ($method)")
        } else {
            status("⚠ ไม่มี Shell/Root — ใช้โหมด gesture-scan")
            DiagnosticReporter.logCaptcha(
                "WARNING: No shell access",
                "Using gesture-scan fallback. shellResult=$shellTestResult"
            )
        }
        delay(300)
        status("✓ CAPTCHA พร้อม")

        // === Pre-verification: tap refresh button to verify coordinates ===
        if (hasRefresh && useShellSwipe) {
            status("ทดสอบตำแหน่ง refresh...")
            Log.d(TAG, "PRE-VERIFY: tapping refresh at (${refreshX.toInt()},${refreshY.toInt()})")
            DiagnosticReporter.logCaptcha("Pre-verify tap",
                "refresh=(${refreshX.toInt()},${refreshY.toInt()}), " +
                "config=(${config.refreshButtonX},${config.refreshButtonY}), " +
                "scale=${"%.3f".format(scaleX)}x${"%.3f".format(scaleY)}, " +
                "screen=${screenW}x${screenH}, recorded=${action.screenWidth}x${action.screenHeight}")
            doShellTap(refreshX, refreshY)
            delay(2000)
            status("✓ ทดสอบเสร็จ — เริ่มแก้ Captcha")
        }

        // === Blind-drag offsets ===
        val blindPercentages = floatArrayOf(0.30f, 0.50f, 0.70f, 0.20f, 0.60f, 0.40f, 0.80f)
        val blindFixedOffsets = intArrayOf(200, 350, 500, 150, 450, 300, 550)
        var blindIndex = 0
        var consecutiveGestureFailures = 0
        var swipeTier = if (useShellSwipe) 0 else 1  // 0=shell, 1=gesture

        // ============================================================
        // === MAIN SOLVING LOOP ===
        // ============================================================
        try {  // try-finally to ALWAYS restore overlay touch
        for (attempt in 1..config.maxRetries) {
            // Check if flow was stopped — throws CancellationException immediately
            coroutineContext.ensureActive()

            status("ครั้งที่ $attempt/${config.maxRetries}")
            // v1.2.62: Moderate delay — 1000ms first, retryDelay on subsequent
            delay(if (attempt == 1) 1000 else config.retryDelayMs)
            coroutineContext.ensureActive()

            // === v1.2.63: SCAN DRAG for ALL tiers when OpenCV+track available ===
            // Shell: sendevent scan drag | Non-shell: gesture continuation scan drag
            val useScanDrag = opencvOk && trackWidth > 100
            val useScanShell = useScanDrag && swipeTier == 0
            val useScanGesture = useScanDrag && swipeTier >= 1
            val swipeY = sliderY

            var targetX: Float = sliderX + trackWidth * 0.5f  // default for non-scan paths
            var dragMode: String = "scan"
            var dragDist: Float = trackWidth * 0.5f

            val swipeResult: String

            if (useScanShell) {
                // === SCAN DRAG (shell): sendevent-based scan ===
                status("ครั้งที่ $attempt: สแกนตำแหน่ง (shell-scan)...")
                swipeResult = doScanDrag(
                    sliderX, swipeY, trackWidth, trackEndX,
                    screenW, screenH, rotation,
                    "ครั้งที่ $attempt:"
                ) { status(it) }
                dragMode = "scan-shell"
            } else if (useScanGesture) {
                // === SCAN DRAG (gesture): dispatchGesture continuation chains ===
                status("ครั้งที่ $attempt: สแกนตำแหน่ง (gesture-scan)...")
                swipeResult = doScanDragGesture(
                    service, sliderX, swipeY, trackWidth, trackEndX,
                    "ครั้งที่ $attempt:"
                ) { status(it) }
                dragMode = "scan-gesture"
            } else {
                // === FALLBACK: No OpenCV or track too short — blind drag ===
                val result = getBlindTarget(
                    hasTrack, trackWidth, sliderX, trackEndX, screenW,
                    blindPercentages, blindFixedOffsets, blindIndex
                )
                targetX = result.first
                dragMode = result.second
                blindIndex++
                dragDist = targetX - sliderX
                if (dragDist < 40) {
                    status("⚠ ระยะน้อยเกินไป (${"%.0f".format(dragDist)}px)")
                    continue
                }

                val swipeDuration = (dragDist * 4f).toLong().coerceIn(800, 3500)
                status("ครั้งที่ $attempt: เลื่อนสไลด์ ($dragMode, ${dragDist.toInt()}px)...")
                swipeResult = if (swipeTier == 0) {
                    doShellDrag(sliderX, swipeY, targetX, swipeY, swipeDuration, screenW, screenH, rotation)
                } else {
                    doSlowSwipe(service, sliderX, swipeY, targetX, swipeY, dragDist)
                }
            }

            Log.d(
                TAG,
                "Swipe result=$swipeResult, mode=$dragMode, " +
                    "attempt=$attempt, dist=${"%.0f".format(dragDist)}, target=${targetX.toInt()}"
            )
            DiagnosticReporter.logCaptcha(
                "Swipe attempt=$attempt",
                "result=$swipeResult, mode=$dragMode, " +
                    "start=(${sliderX.toInt()},${swipeY.toInt()}), " +
                    "dist=${"%.0f".format(dragDist)}, target=${targetX.toInt()}, " +
                    "trackW=${trackWidth.toInt()}, tier=$swipeTier"
            )

            // === Handle detection failure: refresh and retry, DON'T escalate tier ===
            if (swipeResult == "gap_not_detected" || swipeResult == "gap_too_close") {
                status("⚠ หาช่องว่างไม่ได้ — กดรีเฟรชแล้วลองใหม่")
                DiagnosticReporter.logCaptcha(
                    "Detection failed",
                    "result=$swipeResult, attempt=$attempt, tier=$swipeTier"
                )
                // Refresh to get a new puzzle image (detection may work on different puzzle)
                if (hasRefresh) {
                    if (useShellSwipe) doShellTap(refreshX, refreshY)
                    else {
                        val tapDone = CompletableDeferred<Unit>()
                        service.tapAtCoordinates(refreshX, refreshY) { tapDone.complete(Unit) }
                        withTimeoutOrNull(3000) { tapDone.await() }
                    }
                    delay(2500)
                } else {
                    delay(1500)
                }
                continue
            }

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
                    status("🔄 เปลี่ยนเป็นโหมด ${when(swipeTier) { 0 -> "shell"; 1 -> "gesture"; else -> "gesture-tier$swipeTier" }}...")
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
            coroutineContext.ensureActive()
            status("ครั้งที่ $attempt: ✓ เลื่อนแล้ว ตรวจสอบ...")
            // v1.2.62: 1800ms — enough for CAPTCHA JS to validate (was 3000ms, too slow)
            delay(1800)
            coroutineContext.ensureActive()

            // === Verify ===
            // Check if CAPTCHA UI disappeared (page changed after solve)
            val captchaGone = isCaptchaGone(service, sliderY.toInt(), screenH)
            if (captchaGone) {
                status("✓ แก้ Captcha สำเร็จ! (ครั้งที่ $attempt) — หน้าเปลี่ยนแล้ว")
                DiagnosticReporter.logCaptcha(
                    "Solved (UI gone)",
                    "attempt=$attempt, mode=$dragMode, shell=$useShellSwipe"
                )
                try { FloatingOverlayService.instance?.setTouchPassthrough(false) } catch (_: Exception) {}
                autoSendDiagnostics()
                return
            }

            if (opencvOk) {
                val verifyShot = PuzzleScreenCapture.captureScreen()
                if (verifyShot != null) {
                    val stillHasGap = PuzzleSolver.findGapRegion(verifyShot, sliderY.toInt())
                    verifyShot.recycle()
                    if (stillHasGap == null) {
                        // Double-check: wait a bit more and verify again
                        delay(1000)
                        coroutineContext.ensureActive()
                        val secondShot = PuzzleScreenCapture.captureScreen()
                        val secondCheck = if (secondShot != null) {
                            val gap2 = PuzzleSolver.findGapRegion(secondShot, sliderY.toInt())
                            secondShot.recycle()
                            gap2 == null
                        } else true  // If screenshot fails, trust first result

                        if (secondCheck) {
                            status("✓ แก้ Captcha สำเร็จ! (ครั้งที่ $attempt)")
                            DiagnosticReporter.logCaptcha(
                                "Solved",
                                "attempt=$attempt, mode=$dragMode, shell=$useShellSwipe"
                            )
                            try { FloatingOverlayService.instance?.setTouchPassthrough(false) } catch (_: Exception) {}
                            autoSendDiagnostics()
                            return
                        } else {
                            DiagnosticReporter.logCaptcha(
                                "False positive",
                                "attempt=$attempt, first=no_gap, second=has_gap"
                            )
                        }
                    } else {
                        val remainGapX = (stillHasGap.left + stillHasGap.right) / 2
                        val sliderDrift = remainGapX - targetX.toInt()
                        DiagnosticReporter.logCaptcha(
                            "Not solved",
                            "attempt=$attempt, remainGapX=$remainGapX, target=${targetX.toInt()}, drift=$sliderDrift"
                        )
                    }
                }
            } else {
                // No OpenCV — check if CAPTCHA UI changed (slider no longer found)
                if (captchaGone) {
                    status("✓ แก้ Captcha สำเร็จ! (ครั้งที่ $attempt)")
                    try { FloatingOverlayService.instance?.setTouchPassthrough(false) } catch (_: Exception) {}
                    autoSendDiagnostics()
                    return
                }
            }

            // === Refresh ===
            if (attempt < config.maxRetries && hasRefresh) {
                coroutineContext.ensureActive()
                status("🔄 กดรีเฟรช...")
                if (useShellSwipe) {
                    // Use shell tap for refresh button too (isTrusted=true)
                    doShellTap(refreshX, refreshY)
                } else {
                    val tapDone = CompletableDeferred<Unit>()
                    service.tapAtCoordinates(refreshX, refreshY) { tapDone.complete(Unit) }
                    withTimeoutOrNull(3000) { tapDone.await() }
                }
                // Wait for new puzzle to load — longer on later attempts
                // (CAPTCHA may show transition animation or load slower image)
                val refreshDelay = if (attempt >= 3) 3500L else 2500L
                delay(refreshDelay)
                coroutineContext.ensureActive()
            }
        }

        status("⚠ ลอง ${config.maxRetries} ครั้งแล้ว")
        DiagnosticReporter.logCaptcha(
            "All attempts done",
            "maxRetries=${config.maxRetries}, opencv=$opencvOk, " +
                "hasTrack=$hasTrack(auto=${!hasTrack}), tier=$swipeTier, " +
                "shell=$useShellSwipe"
        )
        autoSendDiagnostics()
        } finally {
            // ALWAYS restore overlay touch — even on CancellationException or crash
            try {
                FloatingOverlayService.instance?.setTouchPassthrough(false)
                Log.d(TAG, "Overlay touch restored in finally block")
            } catch (_: Exception) {}
        }
    }

    // ============================================================
    // === CAPTCHA-GONE DETECTION ===
    // ============================================================

    /**
     * Detect if the CAPTCHA UI has disappeared from the screen.
     * After a successful solve, the CAPTCHA overlay typically disappears.
     * We check the accessibility tree at the slider Y region for WebView/slider nodes.
     *
     * Also checks via screenshot: if the pixel region around the slider
     * looks uniform (no puzzle/slider UI), CAPTCHA is likely gone.
     */
    private fun isCaptchaGone(
        service: TpingAccessibilityService,
        sliderY: Int,
        screenH: Int
    ): Boolean {
        try {
            val root = service.rootInActiveWindow ?: return false

            // Look for typical CAPTCHA-related nodes near slider Y
            // If none found, CAPTCHA UI is likely gone
            var foundCaptchaNode = false
            val tolerance = screenH / 6  // ~16% of screen height

            fun scanNode(node: AccessibilityNodeInfo) {
                if (foundCaptchaNode) return
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)

                // Check if node is near slider Y region
                if (rect.top in (sliderY - tolerance)..(sliderY + tolerance) ||
                    rect.bottom in (sliderY - tolerance)..(sliderY + tolerance)) {
                    val className = node.className?.toString() ?: ""
                    // WebView, slider-like elements, or image views near CAPTCHA area
                    if (className.contains("WebView", ignoreCase = true) ||
                        className.contains("SeekBar", ignoreCase = true) ||
                        className.contains("Slider", ignoreCase = true)) {
                        foundCaptchaNode = true
                        return
                    }
                    // Also check content description for CAPTCHA-related text
                    val desc = node.contentDescription?.toString() ?: ""
                    if (desc.contains("captcha", ignoreCase = true) ||
                        desc.contains("slider", ignoreCase = true) ||
                        desc.contains("puzzle", ignoreCase = true) ||
                        desc.contains("verify", ignoreCase = true)) {
                        foundCaptchaNode = true
                        return
                    }
                }

                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    if (child != null) {
                        scanNode(child)
                        try { child.recycle() } catch (_: Exception) {}
                    }
                }
            }

            scanNode(root)
            try { root.recycle() } catch (_: Exception) {}

            if (!foundCaptchaNode) {
                Log.d(TAG, "isCaptchaGone: no CAPTCHA nodes found near sliderY=$sliderY")
                DiagnosticReporter.logCaptcha("CAPTCHA gone", "no nodes near sliderY=$sliderY")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "isCaptchaGone: error scanning nodes: ${e.message}")
        }
        return false
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
        // Always use track-based targeting (trackWidth is auto-detected for 2-point recordings)
        return if (trackWidth > 50) {
            val pct = percentages[index % percentages.size]
            val target = (sliderX + trackWidth * pct).coerceAtMost(trackEndX - 10f)
            val label = if (hasTrack) "blind-track" else "blind-auto"
            target to "$label(${(pct * 100).toInt()}%)"
        } else {
            val offset = fixedOffsets[index % fixedOffsets.size]
            val target = (sliderX + offset).coerceAtMost(screenW - 50f)
            target to "blind(${offset}px)"
        }
    }

    // ============================================================
    // === SHELL INPUT: Hardware-level touch events ===
    // ============================================================

    /**
     * Touch device info for sendevent-based drag.
     * Cached after first detection.
     */
    private data class TouchDeviceInfo(
        val devicePath: String,
        val xMax: Int,
        val yMax: Int
    )

    /**
     * Tracks an active drag session where the finger is held down.
     * Used for hold-verify-adjust-release flow.
     */
    private data class ActiveDragState(
        val devicePath: String,
        val xMax: Int,
        val yMax: Int,
        val nativeW: Int,
        val nativeH: Int,
        val rotation: Int,
        var lastRawX: Int,
        var lastRawY: Int
    ) {
        /** Map logical screen coordinate → raw device coordinate */
        fun logicalToRaw(lx: Float, ly: Float, screenW: Int, screenH: Int): Pair<Int, Int> {
            val (px, py) = when (rotation) {
                Surface.ROTATION_0 -> lx.toInt() to ly.toInt()
                Surface.ROTATION_90 -> ly.toInt() to (nativeH - lx.toInt())
                Surface.ROTATION_180 -> (nativeW - lx.toInt()) to (nativeH - ly.toInt())
                Surface.ROTATION_270 -> (nativeW - ly.toInt()) to lx.toInt()
                else -> lx.toInt() to ly.toInt()
            }
            val rawX = (px.toLong() * xMax / nativeW).toInt().coerceIn(0, xMax)
            val rawY = (py.toLong() * yMax / nativeH).toInt().coerceIn(0, yMax)
            return rawX to rawY
        }
    }

    @Volatile
    private var cachedTouchDevice: TouchDeviceInfo? = null

    /**
     * Test if shell `input` command works on this device.
     * The `input` command creates hardware-level events (isTrusted=true).
     * Priority: sh → su (root)
     * Returns "ok" if working, or error description.
     */
    private suspend fun testShellInput(testX: Float, testY: Float): String {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "testShellInput: testing at ($testX, $testY)")

            // Try without root (will fail on Android 10+ for regular apps)
            val result = tryShellCommand("input tap ${testX.toInt()} ${testY.toInt()}")
            Log.d(TAG, "testShellInput: sh result=$result")
            if (result == "ok") return@withContext "ok(sh)"

            // Try with su (root)
            val suResult = tryShellCommand("su -c 'input tap ${testX.toInt()} ${testY.toInt()}'")
            Log.d(TAG, "testShellInput: su result=$suResult")
            if (suResult == "ok") return@withContext "ok(su)"

            "fail(sh=$result, su=$suResult)"
        }
    }

    /**
     * Execute shell command and return result.
     */
    private fun tryShellCommand(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val exited = process.waitFor(15, TimeUnit.SECONDS)
            if (!exited) {
                process.destroy()
                return "timeout"
            }
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                "ok"
            } else {
                val err = process.errorStream.bufferedReader().readText().take(80)
                "exit$exitCode:$err"
            }
        } catch (e: Exception) {
            "error:${e.message?.take(40)}"
        }
    }

    /**
     * Execute shell command and return stdout output.
     * Returns null on failure.
     */
    private suspend fun execShellWithOutput(cmd: String): String? {
        return withContext(Dispatchers.IO) {
            // Direct shell (limited on Android 10+)
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val output = process.inputStream.bufferedReader().readText()
                val exited = process.waitFor(15, TimeUnit.SECONDS)
                if (exited && process.exitValue() == 0 && output.isNotEmpty()) output.trim()
                else null
            } catch (_: Exception) { null }
        }
    }

    /**
     * Execute a shell command via any available method (sh → su).
     * Returns "ok" on success, or error string.
     */
    private suspend fun execShellAny(cmd: String): String {
        return withContext(Dispatchers.IO) {
            val result = tryShellCommand(cmd)
            if (result == "ok") return@withContext "ok"
            val suResult = tryShellCommand("su -c '$cmd'")
            if (suResult == "ok") return@withContext "ok"
            "fail(sh=$result,su=$suResult)"
        }
    }

    // ============================================================
    // === TIER 0: SHELL DRAG (Press-Hold-Drag) ===
    // ============================================================

    /**
     * Human-like press-hold-drag using shell commands.
     *
     * Three sub-strategies (most realistic → simplest):
     *   a) sendevent: Individual touch events with hold + easing curve
     *   b) input draganddrop: Built-in long press (~500ms) + linear drag
     *   c) input swipe: Direct swipe (no hold, last resort)
     *
     * Each strategy tries: sh → su
     */
    private suspend fun doShellDrag(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long,
        screenW: Int, screenH: Int,
        rotation: Int = Surface.ROTATION_0
    ): String {
        return withContext(Dispatchers.IO) {
            val x1 = startX.toInt()
            val y1 = startY.toInt()
            val x2 = endX.toInt()
            val y2 = endY.toInt()

            // --- Strategy A: sendevent press-hold-drag (most human-like) ---
            val sendeventResult = trySendeventDrag(x1, y1, x2, y2, screenW, screenH, durationMs, rotation)
            if (sendeventResult == "completed") {
                Log.d(TAG, "ShellDrag: sendevent succeeded")
                DiagnosticReporter.logCaptcha("ShellDrag", "sendevent OK")
                return@withContext "completed"
            }
            Log.d(TAG, "ShellDrag: sendevent=$sendeventResult, trying draganddrop...")

            // --- Strategy B: input draganddrop (built-in long press) ---
            val dragDropResult = tryDragAndDrop(x1, y1, x2, y2, durationMs)
            if (dragDropResult == "completed") {
                Log.d(TAG, "ShellDrag: draganddrop succeeded")
                DiagnosticReporter.logCaptcha("ShellDrag", "draganddrop OK")
                return@withContext "completed"
            }
            Log.d(TAG, "ShellDrag: draganddrop=$dragDropResult, trying input swipe...")

            // --- Strategy C: input swipe (no hold, fallback) ---
            val swipeResult = tryInputSwipe(x1, y1, x2, y2, durationMs)
            if (swipeResult == "completed") {
                Log.d(TAG, "ShellDrag: input swipe succeeded (fallback)")
                DiagnosticReporter.logCaptcha("ShellDrag", "swipe OK (fallback)")
                return@withContext "completed"
            }

            DiagnosticReporter.logCaptcha(
                "ShellDrag FAILED",
                "se=$sendeventResult, dd=$dragDropResult, sw=$swipeResult"
            )
            "shell_failed(se=$sendeventResult, dd=$dragDropResult, sw=$swipeResult)"
        }
    }

    /**
     * Detect the touchscreen input device path and coordinate ranges.
     * Parses `getevent -pl` output to find ABS_MT_POSITION_X/Y device.
     * Result is cached for subsequent calls.
     */
    private suspend fun detectTouchDevice(): TouchDeviceInfo? {
        cachedTouchDevice?.let { return it }

        val output = execShellWithOutput("getevent -pl 2>/dev/null") ?: return null

        var currentDevice = ""
        var xMax = 0
        var yMax = 0
        var foundX = false
        var foundY = false

        for (line in output.lines()) {
            val trimmed = line.trim()

            if (trimmed.startsWith("add device")) {
                // If previous device already had both X and Y, we're done
                if (foundX && foundY && currentDevice.isNotEmpty()) break
                // Parse device path: "add device N: /dev/input/eventN"
                val pathStart = trimmed.indexOf("/dev/input/")
                currentDevice = if (pathStart >= 0) trimmed.substring(pathStart).trim() else ""
                foundX = false
                foundY = false
            }

            if (trimmed.contains("ABS_MT_POSITION_X")) {
                val maxMatch = Regex("max\\s+(\\d+)").find(trimmed)
                xMax = maxMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                foundX = xMax > 0
            }

            if (trimmed.contains("ABS_MT_POSITION_Y")) {
                val maxMatch = Regex("max\\s+(\\d+)").find(trimmed)
                yMax = maxMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                foundY = yMax > 0
            }
        }

        if (foundX && foundY && currentDevice.isNotEmpty()) {
            val info = TouchDeviceInfo(currentDevice, xMax, yMax)
            cachedTouchDevice = info
            Log.d(TAG, "Touch device: $currentDevice, xMax=$xMax, yMax=$yMax")
            DiagnosticReporter.logCaptcha("TouchDevice", "path=$currentDevice, x=0..$xMax, y=0..$yMax")
            return info
        }

        Log.w(TAG, "Touch device not found in getevent output")
        return null
    }

    /**
     * Build a shell script that uses sendevent to simulate human press-hold-drag.
     *
     * Sequence:
     *   1. Touch DOWN at start position
     *   2. Hold for holdMs (finger stationary, like grabbing the slider)
     *   3. MOVE with ease-out-cubic curve + small Y jitter (human-like)
     *   4. Pause 100ms (verify position)
     *   5. Touch UP
     *
     * Each sendevent is a separate binary call within a single sh -c invocation.
     * Process spawn overhead (~5-15ms per call) adds natural timing variation.
     */
    private fun buildSendeventScript(
        dev: String,
        rawX1: Int, rawY1: Int,
        rawX2: Int, rawY2: Int,
        holdMs: Int, dragMs: Int, steps: Int
    ): String {
        val sb = StringBuilder()

        // Touch DOWN
        sb.append("sendevent $dev 3 47 0;")   // ABS_MT_SLOT = 0
        sb.append("sendevent $dev 3 57 0;")   // ABS_MT_TRACKING_ID = 0
        sb.append("sendevent $dev 3 53 $rawX1;") // ABS_MT_POSITION_X
        sb.append("sendevent $dev 3 54 $rawY1;") // ABS_MT_POSITION_Y
        sb.append("sendevent $dev 3 48 5;")   // ABS_MT_TOUCH_MAJOR
        sb.append("sendevent $dev 1 330 1;")  // BTN_TOUCH = 1
        sb.append("sendevent $dev 0 0 0;")    // SYN_REPORT

        // Hold (press and wait for CAPTCHA to register the grab)
        val holdSec = holdMs / 1000.0
        sb.append("sleep ${"%.3f".format(holdSec)};")

        // Move with ease-out-cubic curve
        val baseStepDelayMs = dragMs / steps
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            // Ease-out-cubic: fast initial movement, gradually slowing down
            val eased = 1f - (1f - t).let { it * it * it }

            val rawX = rawX1 + ((rawX2 - rawX1) * eased).toInt()
            // Small Y jitter (±1 raw unit every few steps) for human-like behavior
            val yJitter = when {
                i % 7 == 0 -> 1
                i % 5 == 0 -> -1
                else -> 0
            }
            val rawY = rawY1 + ((rawY2 - rawY1) * eased).toInt() + yJitter

            sb.append("sendevent $dev 3 53 $rawX;")
            sb.append("sendevent $dev 3 54 $rawY;")
            sb.append("sendevent $dev 0 0 0;")

            // Variable delay: slower at start and end (more human)
            // v1.2.62: Faster middle (0.6) for natural acceleration curve
            if (i < steps) {
                val speedFactor = when {
                    i <= (steps * 0.12).toInt() -> 1.8  // slow start (grabbing)
                    i >= (steps * 0.88).toInt() -> 1.4  // slow end (positioning)
                    else -> 0.6                          // fast middle
                }
                val delayMs = (baseStepDelayMs * speedFactor).toInt().coerceAtLeast(15)
                val delaySec = delayMs / 1000.0
                sb.append("sleep ${"%.3f".format(delaySec)};")
            }
        }

        // Small pause before release
        sb.append("sleep 0.150;")

        // Touch UP
        sb.append("sendevent $dev 3 57 -1;")  // ABS_MT_TRACKING_ID = -1 (finger up)
        sb.append("sendevent $dev 1 330 0;")  // BTN_TOUCH = 0
        sb.append("sendevent $dev 0 0 0")     // SYN_REPORT

        return sb.toString()
    }

    /**
     * Build sendevent script for DOWN + HOLD + DRAG without final UP (finger stays down).
     * Used for hold-verify-adjust-release flow.
     */
    private fun buildSendeventDragNoRelease(
        dev: String,
        rawX1: Int, rawY1: Int,
        rawX2: Int, rawY2: Int,
        holdMs: Int, dragMs: Int, steps: Int
    ): String {
        val sb = StringBuilder()

        // Touch DOWN
        sb.append("sendevent $dev 3 47 0;")
        sb.append("sendevent $dev 3 57 0;")
        sb.append("sendevent $dev 3 53 $rawX1;")
        sb.append("sendevent $dev 3 54 $rawY1;")
        sb.append("sendevent $dev 3 48 5;")
        sb.append("sendevent $dev 1 330 1;")
        sb.append("sendevent $dev 0 0 0;")

        // Hold
        val holdSec = holdMs / 1000.0
        sb.append("sleep ${"%.3f".format(holdSec)};")

        // Move with ease-out-cubic curve
        val baseStepDelayMs = dragMs / steps
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val eased = 1f - (1f - t).let { it * it * it }
            val rawX = rawX1 + ((rawX2 - rawX1) * eased).toInt()
            val yJitter = when {
                i % 7 == 0 -> 1
                i % 5 == 0 -> -1
                else -> 0
            }
            val rawY = rawY1 + ((rawY2 - rawY1) * eased).toInt() + yJitter

            sb.append("sendevent $dev 3 53 $rawX;")
            sb.append("sendevent $dev 3 54 $rawY;")
            sb.append("sendevent $dev 0 0 0;")

            // v1.2.62: Natural speed curve — fast middle, slow edges
            if (i < steps) {
                val speedFactor = when {
                    i <= (steps * 0.12).toInt() -> 1.8  // slow start (grabbing)
                    i >= (steps * 0.88).toInt() -> 1.4  // slow end (positioning)
                    else -> 0.6                          // fast middle
                }
                val delayMs = (baseStepDelayMs * speedFactor).toInt().coerceAtLeast(15)
                val delaySec = delayMs / 1000.0
                sb.append("sleep ${"%.3f".format(delaySec)};")
            }
        }
        // NO sleep and NO UP event — finger stays down
        return sb.toString()
    }

    /**
     * Build sendevent script to move finger to a new position (no DOWN, no UP).
     * Finger must already be touching (from buildSendeventDragNoRelease).
     * Uses slow linear interpolation for smooth micro-adjustment.
     */
    private fun buildSendeventMicroMove(
        dev: String,
        fromRawX: Int, fromRawY: Int,
        toRawX: Int, toRawY: Int,
        steps: Int = 10,
        durationMs: Int = 600
    ): String {
        val sb = StringBuilder()
        val stepDelayMs = durationMs / steps
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            // Ease-out for smooth deceleration
            val eased = 1f - (1f - t).let { it * it }
            val rawX = fromRawX + ((toRawX - fromRawX) * eased).toInt()
            val rawY = fromRawY + ((toRawY - fromRawY) * eased).toInt()
            sb.append("sendevent $dev 3 53 $rawX;")
            sb.append("sendevent $dev 3 54 $rawY;")
            sb.append("sendevent $dev 0 0 0;")
            if (i < steps) {
                val delaySec = stepDelayMs / 1000.0
                sb.append("sleep ${"%.3f".format(delaySec)};")
            }
        }
        return sb.toString()
    }

    /**
     * Build sendevent script to release finger (UP event only).
     */
    private fun buildSendeventRelease(dev: String): String {
        return "sendevent $dev 3 57 -1;" +  // ABS_MT_TRACKING_ID = -1
            "sendevent $dev 1 330 0;" +       // BTN_TOUCH = 0
            "sendevent $dev 0 0 0"            // SYN_REPORT
    }

    /**
     * Build sendevent script to press DOWN at a position (no movement, no release).
     * Used as the first step in scan-drag flow.
     */
    private fun buildSendeventPress(dev: String, rawX: Int, rawY: Int): String {
        return "sendevent $dev 3 47 0;" +   // ABS_MT_SLOT = 0
            "sendevent $dev 3 57 0;" +       // ABS_MT_TRACKING_ID = 0
            "sendevent $dev 3 53 $rawX;" +   // ABS_MT_POSITION_X
            "sendevent $dev 3 54 $rawY;" +   // ABS_MT_POSITION_Y
            "sendevent $dev 3 48 5;" +       // ABS_MT_TOUCH_MAJOR
            "sendevent $dev 1 330 1;" +      // BTN_TOUCH = 1
            "sendevent $dev 0 0 0"           // SYN_REPORT
    }

    /**
     * Direct drag — v1.2.70 detect-first-then-drag.
     *
     * NEW STRATEGY: Detect gap from STATIC screenshot BEFORE touching slider,
     * then drag DIRECTLY to the detected position. No more slow scanning.
     *
     * Proven approach from successful CAPTCHA solvers (PuzzleCaptchaSolver, geetest-solver):
     *   1. Screenshot BEFORE drag → gap is visible as dark shadow with white borders
     *   2. Sobel gradient + column brightness + white border peaks → find gap X
     *   3. Press slider → drag directly to gap X → fine-tune → release
     *
     * Falls back to legacy scan if static detection fails.
     */
    /**
     * v1.2.79: SIMPLE approach — find white-bordered gap in ONE screenshot, drag to it.
     * Shell (sendevent) version — same logic as gesture version.
     */
    private suspend fun doScanDrag(
        sliderX: Float, sliderY: Float,
        trackWidth: Float, trackEndX: Float,
        screenW: Int, screenH: Int,
        rotation: Int,
        statusPrefix: String,
        status: (String) -> Unit
    ): String {
        if (trackWidth < 100) return "track_too_short"

        val device = detectTouchDevice() ?: return "no_device"

        val nativeW: Int
        val nativeH: Int
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            nativeW = screenH; nativeH = screenW
        } else {
            nativeW = screenW; nativeH = screenH
        }

        val dragState = ActiveDragState(
            device.devicePath, device.xMax, device.yMax,
            nativeW, nativeH, rotation, 0, 0
        )

        // === Step 1: Take ONE screenshot and find the gap ===
        status("$statusPrefix หาตำแหน่งช่องว่าง...")
        val screenshot = PuzzleScreenCapture.captureScreen()
        if (screenshot == null) {
            status("$statusPrefix ❌ จับภาพหน้าจอไม่ได้")
            return "gap_not_detected"
        }

        var gapX: Float? = null
        var detectionMethod = "none"

        val staticResult = PuzzleSolver.detectGapFromStatic(
            screenshot, sliderY.toInt(), sliderX
        )
        if (staticResult != null) {
            gapX = staticResult.toFloat()
            detectionMethod = "static"
        }
        if (gapX == null) {
            val gapRegion = PuzzleSolver.findGapRegion(screenshot, sliderY.toInt())
            if (gapRegion != null) {
                gapX = (gapRegion.left + gapRegion.right) / 2f
                detectionMethod = "contour"
            }
        }
        screenshot.recycle()

        if (gapX == null) {
            Log.w(TAG, "ScanDrag: gap not found")
            return "gap_not_detected"
        }

        val totalDragDist = gapX - sliderX
        if (totalDragDist < 30f) {
            Log.w(TAG, "ScanDrag: gap too close (${"%.0f".format(totalDragDist)}px)")
            return "gap_too_close"
        }

        Log.d(TAG, "ScanDrag: gap=${gapX.toInt()} method=$detectionMethod " +
            "slider=${sliderX.toInt()} dist=${totalDragDist.toInt()}")
        DiagnosticReporter.logCaptcha("GapDetected",
            "method=$detectionMethod, gap=${gapX.toInt()}, slider=${sliderX.toInt()}, " +
            "dist=${totalDragDist.toInt()}, trackW=${trackWidth.toInt()}")

        val (rawX, rawY) = dragState.logicalToRaw(sliderX, sliderY, screenW, screenH)
        dragState.lastRawX = rawX
        dragState.lastRawY = rawY

        try {
            // === Step 2: Press slider ===
            val pressScript = buildSendeventPress(device.devicePath, rawX, rawY)
            val pressResult = execShellAny("sh -c '$pressScript'")
            if (pressResult != "ok") return "press_$pressResult"
            delay(350)

            // === Step 3: Drag directly to gap ===
            val (targetRawX, targetRawY) = dragState.logicalToRaw(gapX, sliderY, screenW, screenH)
            val dragDuration = (totalDragDist * 5f).toInt().coerceIn(400, 2500)
            status("$statusPrefix ลากไปที่ x=${gapX.toInt()} ($detectionMethod, ${totalDragDist.toInt()}px)...")

            val dragScript = buildSendeventMicroMove(
                device.devicePath,
                dragState.lastRawX, dragState.lastRawY,
                targetRawX, targetRawY,
                steps = 20, durationMs = dragDuration
            )
            execShellAny("sh -c '$dragScript'")
            dragState.lastRawX = targetRawX
            dragState.lastRawY = targetRawY
            delay(300)

            // === Step 4: Fine-tune (1 round) ===
            var currentX: Float = gapX
            run {
                coroutineContext.ensureActive()
                delay(400)
                val verifyShot = PuzzleScreenCapture.captureScreen()
                if (verifyShot != null) {
                    val gapRegion = PuzzleSolver.findGapRegion(verifyShot, sliderY.toInt())
                    verifyShot.recycle()
                    if (gapRegion != null) {
                        val remainW = gapRegion.right - gapRegion.left
                        val drift = gapRegion.left.toFloat() - currentX
                        if (remainW > 15 && kotlin.math.abs(drift) in 8f..100f) {
                            val adjustX = currentX + drift * 0.40f
                            val (adjRawX, adjRawY) = dragState.logicalToRaw(
                                adjustX, sliderY, screenW, screenH)
                            val adjScript = buildSendeventMicroMove(
                                device.devicePath,
                                dragState.lastRawX, dragState.lastRawY,
                                adjRawX, adjRawY,
                                steps = 10, durationMs = 400
                            )
                            execShellAny("sh -c '$adjScript'")
                            dragState.lastRawX = adjRawX
                            dragState.lastRawY = adjRawY
                            currentX = adjustX
                        }
                    }
                }
            }

            // === Step 5: Release ===
            delay(250)
            execShellAny("sh -c '${buildSendeventRelease(device.devicePath)}'")

            val msg = "completed($detectionMethod, target=${gapX.toInt()}, dist=${totalDragDist.toInt()})"
            Log.d(TAG, "ScanDrag: $msg")
            DiagnosticReporter.logCaptcha("ScanDrag", msg)
            return "completed"

        } catch (e: Exception) {
            Log.e(TAG, "ScanDrag: error: ${e.message}")
            try {
                execShellAny("sh -c '${buildSendeventRelease(device.devicePath)}'")
            } catch (_: Exception) {}
            throw e
        }
    }
    /**
     * v1.2.79: SIMPLE approach — find white-bordered gap in ONE screenshot, drag to it.
     * No diff, no template matching, no scale factor. Just find the white outline and go.
     */
    private suspend fun doScanDragGesture(
        service: TpingAccessibilityService,
        sliderX: Float, sliderY: Float,
        trackWidth: Float, trackEndX: Float,
        statusPrefix: String,
        status: (String) -> Unit
    ): String {
        if (trackWidth < 100) return "track_too_short"

        // === Step 1: Take ONE screenshot and find the gap ===
        status("$statusPrefix หาตำแหน่งช่องว่าง...")
        val screenshot = PuzzleScreenCapture.captureScreen()
        if (screenshot == null) {
            status("$statusPrefix ❌ จับภาพหน้าจอไม่ได้")
            return "gap_not_detected"
        }

        var gapX: Float? = null
        var detectionMethod = "none"

        // Primary: find gap by white border + local contrast + gradient
        val staticResult = PuzzleSolver.detectGapFromStatic(
            screenshot, sliderY.toInt(), sliderX
        )
        if (staticResult != null) {
            gapX = staticResult.toFloat()
            detectionMethod = "static"
        }

        // Backup: contour-based gap region
        if (gapX == null) {
            val gapRegion = PuzzleSolver.findGapRegion(screenshot, sliderY.toInt())
            if (gapRegion != null) {
                gapX = (gapRegion.left + gapRegion.right) / 2f
                detectionMethod = "contour"
            }
        }

        screenshot.recycle()

        if (gapX == null) {
            Log.w(TAG, "ScanGesture: gap not found in static screenshot")
            return "gap_not_detected"
        }

        val totalDragDist = gapX - sliderX
        if (totalDragDist < 30f) {
            Log.w(TAG, "ScanGesture: gap too close (${"%.0f".format(totalDragDist)}px)")
            return "gap_too_close"
        }

        Log.d(TAG, "ScanGesture: gap=${gapX.toInt()} method=$detectionMethod " +
            "slider=${sliderX.toInt()} dist=${totalDragDist.toInt()}")
        DiagnosticReporter.logCaptcha("GapDetected",
            "method=$detectionMethod, gap=${gapX.toInt()}, slider=${sliderX.toInt()}, " +
            "dist=${totalDragDist.toInt()}, trackW=${trackWidth.toInt()}")

        // === Step 2: Press slider ===
        val press = CompletableDeferred<GestureDescription.StrokeDescription?>()
        service.swipeWithContinuation(
            sliderX, sliderY, sliderX + 2f, sliderY,
            300L, willContinue = true, previousStroke = null
        ) { press.complete(it) }

        var currentStroke = withTimeoutOrNull(5000L) { press.await() }
        if (currentStroke == null) return "gesture_press_failed"
        var currentX = sliderX + 2f

        // === Step 3: Drag directly to gap position ===
        val dragDist = gapX - currentX
        val dragDuration = (kotlin.math.abs(dragDist) * 5f).toLong().coerceIn(400, 2500)
        status("$statusPrefix ลากไปที่ x=${gapX.toInt()} ($detectionMethod, ${totalDragDist.toInt()}px)...")

        val drag = CompletableDeferred<GestureDescription.StrokeDescription?>()
        service.swipeWithContinuation(
            currentX, sliderY, gapX, sliderY,
            dragDuration, willContinue = true, previousStroke = currentStroke
        ) { drag.complete(it) }
        val dragStroke = withTimeoutOrNull(dragDuration + 5000L) { drag.await() }
        if (dragStroke != null) {
            currentStroke = dragStroke
            currentX = gapX
        }
        delay(300)

        // === Step 4: Fine-tune (1 round) ===
        run {
            coroutineContext.ensureActive()
            delay(400)
            val verifyShot = PuzzleScreenCapture.captureScreen()
            if (verifyShot != null) {
                val gapRegion = PuzzleSolver.findGapRegion(verifyShot, sliderY.toInt())
                verifyShot.recycle()
                if (gapRegion != null) {
                    val remainW = gapRegion.right - gapRegion.left
                    val drift = gapRegion.left.toFloat() - currentX
                    if (remainW > 15 && kotlin.math.abs(drift) in 8f..100f) {
                        val adjustX = currentX + drift * 0.40f
                        val adjDur = (kotlin.math.abs(drift) * 3f).toLong().coerceIn(200, 600)
                        val adj = CompletableDeferred<GestureDescription.StrokeDescription?>()
                        service.swipeWithContinuation(
                            currentX, sliderY, adjustX, sliderY,
                            adjDur, willContinue = true, previousStroke = currentStroke
                        ) { adj.complete(it) }
                        val adjStroke = withTimeoutOrNull(adjDur + 5000L) { adj.await() }
                        if (adjStroke != null) {
                            currentStroke = adjStroke
                            currentX = adjustX
                        }
                    }
                }
            }
        }

        // === Step 5: Release ===
        delay(250)
        val release = CompletableDeferred<GestureDescription.StrokeDescription?>()
        service.swipeWithContinuation(
            currentX, sliderY, currentX + 1f, sliderY,
            100L, willContinue = false, previousStroke = currentStroke
        ) { release.complete(it) }
        withTimeoutOrNull(5000L) { release.await() }

        val msg = "completed($detectionMethod, target=${gapX.toInt()}, dist=${totalDragDist.toInt()})"
        Log.d(TAG, "ScanGesture: $msg")
        DiagnosticReporter.logCaptcha("ScanGesture", msg)
        return "completed"
    }

    /**
     * Try sendevent-based press-hold-drag.
     * Returns "completed" on success, error string on failure.
     *
     * v1.2.44: Rotation-aware raw coordinate mapping.
     * sendevent writes directly to the kernel input device which uses
     * PHYSICAL (unrotated) coordinates, NOT logical (rotation-aware) coordinates.
     * When the display is rotated, we must transform logical→physical.
     */
    private suspend fun trySendeventDrag(
        x1: Int, y1: Int, x2: Int, y2: Int,
        screenW: Int, screenH: Int,
        durationMs: Long,
        rotation: Int = Surface.ROTATION_0
    ): String {
        val device = detectTouchDevice() ?: return "no_device"

        // Map logical screen coordinates → physical raw device coordinates.
        // The touch device always reports in the PHYSICAL (natural/portrait)
        // coordinate frame. When the display is rotated, the logical axes
        // swap relative to the physical axes.
        //
        // Native display dimensions (always portrait orientation):
        val nativeW: Int
        val nativeH: Int
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            nativeW = screenH  // In landscape, native width = logical height
            nativeH = screenW  // In landscape, native height = logical width
        } else {
            nativeW = screenW
            nativeH = screenH
        }

        // Transform logical (x,y) → physical (px,py) based on rotation
        fun toPhysical(lx: Int, ly: Int): Pair<Int, Int> {
            return when (rotation) {
                Surface.ROTATION_0 -> lx to ly
                Surface.ROTATION_90 -> {
                    // Landscape CW: phys_x = log_y, phys_y = nativeH - log_x
                    ly to (nativeH - lx)
                }
                Surface.ROTATION_180 -> {
                    // Upside down: phys_x = nativeW - log_x, phys_y = nativeH - log_y
                    (nativeW - lx) to (nativeH - ly)
                }
                Surface.ROTATION_270 -> {
                    // Landscape CCW: phys_x = nativeW - log_y, phys_y = log_x
                    (nativeW - ly) to lx
                }
                else -> lx to ly
            }
        }

        val (px1, py1) = toPhysical(x1, y1)
        val (px2, py2) = toPhysical(x2, y2)

        // Map physical screen coordinates to raw device coordinates
        val rawX1 = (px1.toLong() * device.xMax / nativeW).toInt().coerceIn(0, device.xMax)
        val rawY1 = (py1.toLong() * device.yMax / nativeH).toInt().coerceIn(0, device.yMax)
        val rawX2 = (px2.toLong() * device.xMax / nativeW).toInt().coerceIn(0, device.xMax)
        val rawY2 = (py2.toLong() * device.yMax / nativeH).toInt().coerceIn(0, device.yMax)

        val holdMs = 350
        val steps = 30
        // v1.2.62: Natural speed range (was 1500-6000 — too slow)
        val dragMs = durationMs.toInt().coerceIn(600, 3000)

        Log.d(TAG, "SendeventDrag: dev=${device.devicePath}, " +
            "logical=($x1,$y1)→($x2,$y2), " +
            "physical=($px1,$py1)→($px2,$py2), " +
            "raw=($rawX1,$rawY1)→($rawX2,$rawY2), " +
            "native=${nativeW}x${nativeH}, rotation=$rotation, " +
            "hold=${holdMs}ms, drag=${dragMs}ms, steps=$steps")

        val script = buildSendeventScript(
            device.devicePath, rawX1, rawY1, rawX2, rawY2,
            holdMs, dragMs, steps
        )

        // Execute the script as a single shell invocation
        val result = execShellAny("sh -c '$script'")
        return if (result == "ok") "completed" else "sendevent_$result"
    }

    /**
     * Try input draganddrop (has built-in ~500ms long press before drag).
     * Priority: sh → su
     */
    private suspend fun tryDragAndDrop(
        x1: Int, y1: Int, x2: Int, y2: Int,
        durationMs: Long
    ): String {
        return withContext(Dispatchers.IO) {
            // sh
            val cmd = "input draganddrop $x1 $y1 $x2 $y2 $durationMs"
            val result = tryShellCommand(cmd)
            if (result == "ok") return@withContext "completed"
            // su
            val suResult = tryShellCommand("su -c '$cmd'")
            if (suResult == "ok") return@withContext "completed"
            "dd_fail(sh=$result,su=$suResult)"
        }
    }

    /**
     * Try input swipe (no hold phase, original approach).
     * Priority: sh → su
     */
    private suspend fun tryInputSwipe(
        x1: Int, y1: Int, x2: Int, y2: Int,
        durationMs: Long
    ): String {
        return withContext(Dispatchers.IO) {
            // sh
            val cmd = "input swipe $x1 $y1 $x2 $y2 $durationMs"
            val result = tryShellCommand(cmd)
            if (result == "ok") return@withContext "completed"
            // su
            val suResult = tryShellCommand("su -c '$cmd'")
            if (suResult == "ok") return@withContext "completed"
            "swipe_fail(sh=$result,su=$suResult)"
        }
    }

    /**
     * Shell-based tap for refresh button etc.
     * Priority: sh → su (root)
     */
    private suspend fun doShellTap(x: Float, y: Float) {
        Log.d(TAG, "doShellTap: (${x.toInt()}, ${y.toInt()})")
        DiagnosticReporter.logCaptcha("ShellTap", "pos=(${x.toInt()},${y.toInt()})")
        withContext(Dispatchers.IO) {
            val cmd = "input tap ${x.toInt()} ${y.toInt()}"
            Log.d(TAG, "doShellTap direct: $cmd")
            val result = tryShellCommand(cmd)
            if (result != "ok") {
                Log.d(TAG, "doShellTap su: $cmd (sh=$result)")
                tryShellCommand("su -c '$cmd'")
            }
        }
    }

    // ============================================================
    // === SLIDER NODE SCAN (Accessibility-based slider control) ===
    // ============================================================

    /**
     * Scan WebView accessibility tree for slider-like elements.
     * If found, try to move the slider via accessibility actions
     * (bypasses isTrusted check since these aren't touch events).
     */
    private fun scanForSliderNode(
        service: TpingAccessibilityService,
        sliderX: Float, sliderY: Float,
        trackWidth: Float
    ): String {
        try {
            val root = service.rootInActiveWindow ?: return "no-root"
            val webView = findWebView(root) ?: run { root.recycle(); return "no-webview" }

            val results = StringBuilder()
            val childCount = webView.childCount
            results.append("children=$childCount; ")

            // Scan children for nodes near slider Y coordinate
            for (i in 0 until minOf(childCount, 15)) {
                val child = try { webView.getChild(i) } catch (_: Exception) { null } ?: continue
                val bounds = android.graphics.Rect()
                child.getBoundsInScreen(bounds)
                val className = child.className?.toString()?.substringAfterLast('.') ?: ""
                val desc = child.contentDescription?.toString()?.take(15) ?: ""
                val rangeInfo = child.rangeInfo
                val actions = child.actionList?.map { it.id }?.joinToString(",") ?: ""

                // Log nodes near slider Y (±50px)
                if (kotlin.math.abs(bounds.centerY() - sliderY.toInt()) < 50) {
                    results.append("[$i]$className(${bounds})range=${rangeInfo?.current}/${rangeInfo?.max},act=$actions,$desc; ")

                    // Try to move slider via accessibility actions
                    if (rangeInfo != null) {
                        // Node has range info — try ACTION_SET_PROGRESS
                        val targetProgress = rangeInfo.min +
                            (rangeInfo.max - rangeInfo.min) * 0.5f // Try 50%
                        val args = Bundle().apply {
                            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, targetProgress)
                        }
                        val setOk = child.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args)
                        results.append("SET_PROGRESS=$setOk; ")
                    }

                    // Try scroll actions
                    val scrollFwd = child.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    if (scrollFwd) results.append("SCROLL_FWD=true; ")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val scrollRight = child.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)
                        if (scrollRight) results.append("SCROLL_RIGHT=true; ")
                    }
                }
                child.recycle()
            }

            webView.recycle()
            root.recycle()
            return results.toString()
        } catch (e: Exception) {
            return "error:${e.message?.take(50)}"
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
