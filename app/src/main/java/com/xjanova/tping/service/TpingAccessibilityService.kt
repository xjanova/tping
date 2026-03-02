package com.xjanova.tping.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xjanova.tping.data.entity.ActionType
import com.xjanova.tping.data.entity.RecordedAction
import com.xjanova.tping.recorder.ActionRecorder

class TpingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TpingA11y"
        var instance: TpingAccessibilityService? = null
            private set
        var isRecording = false
        var isPlaying = false
    }

    private val recorder = ActionRecorder()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isRecording) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                recordClickEvent(event)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                recordTextEvent(event)
            }
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
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
            packageName = event.packageName?.toString() ?: "",
            delayAfterMs = recorder.getTimeSinceLastAction()
        )
        recorder.addAction(action)
        Log.d(TAG, "Recorded CLICK: ${action.resourceId} | ${action.text} | bounds=${bounds}")
        source.recycle()
    }

    private fun recordTextEvent(event: AccessibilityEvent) {
        val source = event.source ?: return
        val currentText = event.text?.joinToString("") ?: ""

        // Debounce: update last action if it's INPUT_TEXT on same field
        recorder.updateOrAddTextAction(
            resourceId = source.viewIdResourceName ?: "",
            text = currentText,
            className = source.className?.toString() ?: "",
            packageName = event.packageName?.toString() ?: "",
            source = source
        )
        source.recycle()
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ====== Playback Functions ======

    fun clickAtNode(action: RecordedAction, callback: () -> Unit) {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "No root window, falling back to coordinate click")
            clickAtCoordinates(action, callback)
            return
        }

        // Layer 1: Find by Resource ID
        if (action.resourceId.isNotEmpty()) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(action.resourceId)
            if (nodes.isNotEmpty()) {
                val node = nodes[0]
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked by resourceId: ${action.resourceId}")
                node.recycle()
                callback()
                return
            }
        }

        // Layer 2: Find by Text
        if (action.text.isNotEmpty()) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(action.text)
            for (node in nodes) {
                if (node.className?.toString() == action.className) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Clicked by text: ${action.text}")
                    node.recycle()
                    callback()
                    return
                }
                node.recycle()
            }
        }

        // Layer 3: Click by coordinates
        clickAtCoordinates(action, callback)
    }

    private fun clickAtCoordinates(action: RecordedAction, callback: () -> Unit) {
        val x = (action.boundsLeft + action.boundsRight) / 2f
        val y = (action.boundsTop + action.boundsBottom) / 2f

        val path = Path()
        path.moveTo(x, y)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Coordinate click at ($x, $y)")
                callback()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Coordinate click cancelled")
                callback()
            }
        }, null)
    }

    fun inputTextAtNode(action: RecordedAction, text: String, callback: () -> Unit) {
        val rootNode = rootInActiveWindow ?: run {
            callback()
            return
        }

        var targetNode: AccessibilityNodeInfo? = null

        // Layer 1: Find by Resource ID
        if (action.resourceId.isNotEmpty()) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(action.resourceId)
            if (nodes.isNotEmpty()) {
                targetNode = nodes[0]
            }
        }

        // Layer 2: Find by class name in bounds area
        if (targetNode == null) {
            targetNode = findNodeByBounds(rootNode, action)
        }

        if (targetNode != null) {
            // Focus the field
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            // Clear existing text
            val clearArgs = Bundle()
            clearArgs.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ""
            )
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            // Set new text
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Input text: $text at ${action.resourceId}")
            targetNode.recycle()
        } else {
            // Fallback: click coordinates then paste
            clickAtCoordinates(action) {
                // Small delay then try to set text on focused node
                val focused = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                    )
                    focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    focused.recycle()
                }
            }
        }
        callback()
    }

    private fun findNodeByBounds(root: AccessibilityNodeInfo, action: RecordedAction): AccessibilityNodeInfo? {
        val targetRect = Rect(action.boundsLeft, action.boundsTop, action.boundsRight, action.boundsBottom)
        return findNodeRecursive(root, targetRect, action.className)
    }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        targetRect: Rect,
        targetClass: String
    ): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds == targetRect && node.className?.toString() == targetClass) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeRecursive(child, targetRect, targetClass)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    fun performScrollUp(callback: () -> Unit) {
        val root = rootInActiveWindow ?: run { callback(); return }
        root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        callback()
    }

    fun performScrollDown(callback: () -> Unit) {
        val root = rootInActiveWindow ?: run { callback(); return }
        root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        callback()
    }

    fun performBack(callback: () -> Unit) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        callback()
    }

    fun getRecorder(): ActionRecorder = recorder
}
