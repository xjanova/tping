package com.xjanova.tping.recorder

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.xjanova.tping.data.entity.ActionType
import com.xjanova.tping.data.entity.RecordedAction

class ActionRecorder {

    private val actions = mutableListOf<RecordedAction>()
    private var lastActionTime = System.currentTimeMillis()
    private var stepCounter = 0

    fun startRecording() {
        actions.clear()
        stepCounter = 0
        lastActionTime = System.currentTimeMillis()
    }

    fun stopRecording(): List<RecordedAction> {
        return actions.toList()
    }

    fun getNextStep(): Int {
        return ++stepCounter
    }

    fun getTimeSinceLastAction(): Long {
        val now = System.currentTimeMillis()
        val delay = now - lastActionTime
        lastActionTime = now
        return delay.coerceIn(100, 5000) // clamp between 100ms and 5s
    }

    fun addAction(action: RecordedAction) {
        actions.add(action)
    }

    fun updateOrAddTextAction(
        resourceId: String,
        text: String,
        className: String,
        packageName: String,
        source: AccessibilityNodeInfo
    ) {
        val bounds = Rect()
        source.getBoundsInScreen(bounds)

        // Check if last action was INPUT_TEXT on same field
        val last = actions.lastOrNull()
        if (last != null && last.actionType == ActionType.INPUT_TEXT
            && last.resourceId == resourceId && resourceId.isNotEmpty()
        ) {
            // Update the text in the existing action
            actions[actions.lastIndex] = last.copy(inputText = text)
        } else {
            val action = RecordedAction(
                stepOrder = getNextStep(),
                actionType = ActionType.INPUT_TEXT,
                resourceId = resourceId,
                text = "",
                className = className,
                contentDescription = source.contentDescription?.toString() ?: "",
                boundsLeft = bounds.left,
                boundsTop = bounds.top,
                boundsRight = bounds.right,
                boundsBottom = bounds.bottom,
                inputText = text,
                packageName = packageName,
                delayAfterMs = getTimeSinceLastAction()
            )
            actions.add(action)
        }
    }

    fun getActions(): List<RecordedAction> = actions.toList()

    fun getActionCount(): Int = actions.size

    fun tagLastActionAsDataField(fieldKey: String) {
        if (actions.isNotEmpty()) {
            val last = actions.last()
            actions[actions.lastIndex] = last.copy(dataFieldKey = fieldKey)
        }
    }

    fun clear() {
        actions.clear()
        stepCounter = 0
    }
}
