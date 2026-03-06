package com.xjanova.tping.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xjanova.tping.data.entity.ActionType
import com.xjanova.tping.data.entity.RecordedAction
import com.xjanova.tping.overlay.FloatingOverlayService
import com.xjanova.tping.recorder.ActionRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TpingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TpingA11y"
        var instance: TpingAccessibilityService? = null
            private set

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _isPlaying = MutableStateFlow(false)
        val isPlaying: StateFlow<Boolean> = _isPlaying

        fun setRecording(value: Boolean) { _isRecording.value = value }
        fun setPlaying(value: Boolean) { _isPlaying.value = value }
    }

    private val recorder = ActionRecorder()

    // Package filter: ignore keyboard & system UI during recording
    // Track previous scroll position to detect direction
    private var lastScrollY = -1
    private var lastScrollSource: String? = null

    private val ignoredPackages = setOf(
        "com.google.android.inputmethod",
        "com.samsung.android.honeyboard",
        "com.swiftkey",
        "com.android.systemui",
        "com.xjanova.tping"
    )

    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service connected")

        // Register accessibility button callback (ปุ่มการช่วยเหลือพิเศษ)
        try {
            val controller = accessibilityButtonController
            accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    Log.d(TAG, "Accessibility button clicked - launching overlay")
                    launchOverlay()
                }
            }
            controller.registerAccessibilityButtonCallback(accessibilityButtonCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register accessibility button callback: ${e.message}")
        }
    }

    private fun launchOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission - cannot launch overlay")
            return
        }
        val intent = Intent(this, FloatingOverlayService::class.java)
        intent.putExtra("mode", "idle")
        startForegroundService(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !_isRecording.value) return

        val pkg = event.packageName?.toString() ?: return
        if (ignoredPackages.any { pkg.startsWith(it) }) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recordClickEvent(event)
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> recordLongClickEvent(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> recordTextEvent(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordScrollEvent(event)
            else -> {}
        }
    }

    private fun recordClickEvent(event: AccessibilityEvent) {
        val source = event.source ?: return
        val bounds = Rect()
        source.getBoundsInScreen(bounds)

        val action = RecordedAction(
            stepOrder = recorder.getNextStep(),
            actionType = ActionType.CLICK,
            resourceId = source.viewIdResourceName ?: "",
            text = source.text?.toString() ?: "",
            className = source.className?.toString() ?: "",
            contentDescription = source.contentDescription?.toString() ?: "",
            boundsLeft = bounds.left, boundsTop = bounds.top,
            boundsRight = bounds.right, boundsBottom = bounds.bottom,
            packageName = event.packageName?.toString() ?: "",
            delayAfterMs = recorder.getTimeSinceLastAction(),
            hintText = source.hintText?.toString() ?: ""
        )
        recorder.addAction(action)
        Log.d(TAG, "REC CLICK: ${action.resourceId.ifEmpty { action.text }} bounds=$bounds")
        safeRecycle(source)
    }

    private fun recordLongClickEvent(event: AccessibilityEvent) {
        val source = event.source ?: return
        val bounds = Rect()
        source.getBoundsInScreen(bounds)

        val action = RecordedAction(
            stepOrder = recorder.getNextStep(),
            actionType = ActionType.LONG_CLICK,
            resourceId = source.viewIdResourceName ?: "",
            text = source.text?.toString() ?: "",
            className = source.className?.toString() ?: "",
            contentDescription = source.contentDescription?.toString() ?: "",
            boundsLeft = bounds.left, boundsTop = bounds.top,
            boundsRight = bounds.right, boundsBottom = bounds.bottom,
            packageName = event.packageName?.toString() ?: "",
            delayAfterMs = recorder.getTimeSinceLastAction(),
            hintText = source.hintText?.toString() ?: ""
        )
        recorder.addAction(action)
        Log.d(TAG, "REC LONG_CLICK: ${action.resourceId}")
        safeRecycle(source)
    }

    private fun recordScrollEvent(event: AccessibilityEvent) {
        // Debounce: skip if last action was also a scroll within 200ms
        val lastAction = recorder.getActions().lastOrNull()
        if (lastAction != null &&
            (lastAction.actionType == ActionType.SCROLL_DOWN || lastAction.actionType == ActionType.SCROLL_UP) &&
            recorder.peekTimeSinceLastAction() < 200
        ) return

        // Determine scroll direction using delta from previous position
        val sourceId = event.source?.viewIdResourceName ?: event.className?.toString() ?: ""
        val currentY = event.scrollY
        val actionType = if (lastScrollSource == sourceId && lastScrollY >= 0) {
            if (currentY > lastScrollY) ActionType.SCROLL_DOWN else ActionType.SCROLL_UP
        } else {
            // First scroll or different source: use fromIndex heuristic
            if (event.fromIndex > 0) ActionType.SCROLL_DOWN else ActionType.SCROLL_DOWN
        }
        lastScrollY = currentY
        lastScrollSource = sourceId

        val action = RecordedAction(
            stepOrder = recorder.getNextStep(),
            actionType = actionType,
            packageName = event.packageName?.toString() ?: "",
            delayAfterMs = recorder.getTimeSinceLastAction()
        )
        recorder.addAction(action)
        Log.d(TAG, "REC SCROLL: $actionType (scrollY=$currentY)")
    }

    private fun recordTextEvent(event: AccessibilityEvent) {
        val source = event.source ?: return
        val currentText = event.text?.joinToString("") ?: ""
        recorder.updateOrAddTextAction(
            resourceId = source.viewIdResourceName ?: "",
            text = currentText,
            className = source.className?.toString() ?: "",
            packageName = event.packageName?.toString() ?: "",
            source = source
        )
        safeRecycle(source)
    }

    private fun safeRecycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try { node.recycle() } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        // Unregister accessibility button callback
        try {
            accessibilityButtonCallback?.let {
                accessibilityButtonController.unregisterAccessibilityButtonCallback(it)
            }
        } catch (_: Exception) {}
        accessibilityButtonCallback = null
        instance = null
        super.onDestroy()
    }

    // ====== Playback ======

    fun clickAtNode(action: RecordedAction, callback: () -> Unit) {
        // Game mode: always use coordinate-based click (no accessibility nodes)
        if (action.isGameMode) {
            clickAtCoordinates(action, callback); return
        }
        val rootNode = rootInActiveWindow ?: run {
            clickAtCoordinates(action, callback); return
        }
        if (action.resourceId.isNotEmpty()) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(action.resourceId)
            if (nodes.isNotEmpty()) {
                nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                safeRecycle(nodes[0]); callback(); return
            }
        }
        if (action.text.isNotEmpty()) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(action.text)
            for (node in nodes) {
                if (node.className?.toString() == action.className) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    safeRecycle(node); callback(); return
                }
                safeRecycle(node)
            }
        }
        clickAtCoordinates(action, callback)
    }

    fun longClickAtNode(action: RecordedAction, callback: () -> Unit) {
        // Game mode: always use coordinate-based long click (no accessibility nodes)
        if (action.isGameMode) {
            longClickAtCoordinates(action, callback); return
        }
        val rootNode = rootInActiveWindow ?: run {
            longClickAtCoordinates(action, callback); return
        }
        if (action.resourceId.isNotEmpty()) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(action.resourceId)
            if (nodes.isNotEmpty()) {
                nodes[0].performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                safeRecycle(nodes[0]); callback(); return
            }
        }
        if (action.text.isNotEmpty()) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(action.text)
            for (node in nodes) {
                if (node.className?.toString() == action.className) {
                    node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    safeRecycle(node); callback(); return
                }
                safeRecycle(node)
            }
        }
        longClickAtCoordinates(action, callback)
    }

    private fun scaleCoordinates(action: RecordedAction): Pair<Float, Float> {
        val rawX = (action.boundsLeft + action.boundsRight) / 2f
        val rawY = (action.boundsTop + action.boundsBottom) / 2f
        if (!action.isGameMode || action.screenWidth <= 0 || action.screenHeight <= 0) {
            return Pair(rawX, rawY)
        }
        // Scale from recorded resolution to current screen resolution
        val metrics = resources.displayMetrics
        val currentWidth = metrics.widthPixels.toFloat()
        val currentHeight = metrics.heightPixels.toFloat()
        val scaleX = currentWidth / action.screenWidth
        val scaleY = currentHeight / action.screenHeight
        return Pair(rawX * scaleX, rawY * scaleY)
    }

    private fun clickAtCoordinates(action: RecordedAction, callback: () -> Unit) {
        val (x, y) = scaleCoordinates(action)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback() }
            override fun onCancelled(g: GestureDescription?) { callback() }
        }, null)
    }

    private fun longClickAtCoordinates(action: RecordedAction, callback: () -> Unit) {
        val (x, y) = scaleCoordinates(action)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 600))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback() }
            override fun onCancelled(g: GestureDescription?) { callback() }
        }, null)
    }

    fun inputTextAtNode(action: RecordedAction, text: String, callback: () -> Unit) {
        // Game mode: dismiss keyboard first, then click at coordinates to focus field, then set text
        if (action.isGameMode) {
            dismissKeyboard {
                clickAtCoordinates(action) {
                    setTextWithRetry(text, 0, callback)
                }
            }
            return
        }

        val rootNode = rootInActiveWindow ?: run { callback(); return }
        var targetNode: AccessibilityNodeInfo? = null

        if (action.resourceId.isNotEmpty()) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(action.resourceId)
            if (nodes.isNotEmpty()) targetNode = nodes[0]
        }
        if (targetNode == null) {
            targetNode = findNodeByBounds(rootNode, action)
        }

        if (targetNode != null) {
            setTextOnNode(targetNode, text)
            safeRecycle(targetNode)
            callback()
        } else {
            // Fallback: click coordinates then set text on focused node
            clickAtCoordinates(action) {
                setTextWithRetry(text, 0, callback)
            }
        }
    }

    /** Set text on a specific node: focus → clear → wait → set text → verify */
    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        // Select all existing text first, then replace — more reliable than clear + set
        val selectAll = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAll)

        // Set text (replaces selection)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        // Verify text was set correctly
        node.refresh()
        val actualText = node.text?.toString() ?: ""
        if (actualText != text) {
            Log.w(TAG, "setTextOnNode: mismatch! expected='${text.take(20)}' actual='${actualText.take(20)}', retrying...")
            // Retry: clear completely then set
            val clearArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            node.refresh()
            val finalText = node.text?.toString() ?: ""
            if (finalText != text) {
                Log.e(TAG, "setTextOnNode: STILL mismatch after retry! expected='${text.take(20)}' actual='${finalText.take(20)}'")
            } else {
                Log.d(TAG, "setTextOnNode: retry succeeded")
            }
        }
    }

    /** After clicking coordinates, retry findFocus up to 3 times with increasing delay */
    private fun setTextWithRetry(text: String, attempt: Int, callback: () -> Unit) {
        val delays = longArrayOf(800, 600, 800) // longer initial delay for focus to settle
        if (attempt >= delays.size) {
            Log.w(TAG, "inputText: findFocus failed after ${delays.size} retries for text='${text.take(20)}'")
            callback()
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val freshRoot = rootInActiveWindow
                val focused = freshRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    Log.d(TAG, "inputText: found focus node class=${focused.className} " +
                            "text='${focused.text?.toString()?.take(20) ?: "null"}' on attempt ${attempt + 1}")
                    setTextOnNode(focused, text)
                    safeRecycle(focused)
                    Log.d(TAG, "inputText: set text='${text.take(20)}' on attempt ${attempt + 1}")
                    callback()
                } else {
                    Log.d(TAG, "inputText: findFocus null on attempt ${attempt + 1}, retrying...")
                    setTextWithRetry(text, attempt + 1, callback)
                }
            } catch (e: Exception) {
                Log.w(TAG, "inputText: exception on attempt ${attempt + 1}: ${e.message}")
                callback()
            }
        }, delays[attempt])
    }

    private fun findNodeByBounds(root: AccessibilityNodeInfo, action: RecordedAction): AccessibilityNodeInfo? {
        val targetRect = Rect(action.boundsLeft, action.boundsTop, action.boundsRight, action.boundsBottom)
        return findNodeRecursive(root, targetRect, action.className)
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, targetRect: Rect, targetClass: String): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds == targetRect && node.className?.toString() == targetClass) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeRecursive(child, targetRect, targetClass)
            if (result != null) return result
            safeRecycle(child)
        }
        return null
    }

    fun performScrollUp(callback: () -> Unit) {
        rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        callback()
    }

    fun performScrollDown(callback: () -> Unit) {
        rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        callback()
    }

    fun performBack(callback: () -> Unit) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        callback()
    }

    /** Dismiss any visible keyboard via BACK action, then callback after animation delay */
    fun dismissKeyboard(callback: () -> Unit) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            callback()
        }, 300)
    }

    fun getRecorder(): ActionRecorder = recorder

    // ====== Gesture APIs (for slide puzzle CAPTCHA) ======

    /** Simple one-shot swipe from start to end */
    fun swipeGesture(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 600,
        callback: () -> Unit
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback() }
            override fun onCancelled(g: GestureDescription?) { callback() }
        }, null)
    }

    /**
     * Swipe with continuation support — finger stays held down between increments.
     * Used for visual-feedback sliding (CAPTCHA puzzle solver).
     *
     * @param startX Start X of this segment
     * @param startY Start Y
     * @param endX End X of this segment
     * @param endY End Y
     * @param durationMs Duration of this segment
     * @param willContinue If true, finger stays pressed after this segment
     * @param previousStroke Previous StrokeDescription to chain from (null for first segment)
     * @param callback Returns the StrokeDescription for chaining (null on failure)
     */
    fun swipeWithContinuation(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long,
        willContinue: Boolean,
        previousStroke: GestureDescription.StrokeDescription? = null,
        callback: (GestureDescription.StrokeDescription?) -> Unit
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = if (previousStroke != null) {
            previousStroke.continueStroke(path, 0, durationMs, willContinue)
        } else {
            GestureDescription.StrokeDescription(path, 0, durationMs, willContinue)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback(stroke) }
            override fun onCancelled(g: GestureDescription?) {
                Log.w(TAG, "swipeWithContinuation cancelled")
                callback(null)
            }
        }, null)
    }

    /** Tap at specific screen coordinates (for pressing refresh button etc.) */
    fun tapAtCoordinates(x: Float, y: Float, callback: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback() }
            override fun onCancelled(g: GestureDescription?) { callback() }
        }, null)
    }
}
