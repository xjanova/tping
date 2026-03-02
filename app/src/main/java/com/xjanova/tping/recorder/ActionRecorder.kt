package com.xjanova.tping.recorder

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.xjanova.tping.data.entity.ActionType
import com.xjanova.tping.data.entity.RecordedAction

class ActionRecorder {

    private val actions = mutableListOf<RecordedAction>()
    private val lock = Any()
    @Volatile private var lastActionTime = System.currentTimeMillis()
    @Volatile private var stepCounter = 0

    private val _actionCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    val actionCount: kotlinx.coroutines.flow.StateFlow<Int> = _actionCount

    fun startRecording() {
        synchronized(lock) {
            actions.clear()
            stepCounter = 0
            lastActionTime = System.currentTimeMillis()
            _actionCount.value = 0
        }
    }

    fun stopRecording(): List<RecordedAction> {
        synchronized(lock) { return actions.toList() }
    }

    fun getNextStep(): Int {
        return ++stepCounter
    }

    fun getTimeSinceLastAction(): Long {
        val now = System.currentTimeMillis()
        val delay = now - lastActionTime
        lastActionTime = now
        return delay.coerceIn(100, 5000)
    }

    fun peekTimeSinceLastAction(): Long {
        return System.currentTimeMillis() - lastActionTime
    }

    fun addAction(action: RecordedAction) {
        synchronized(lock) {
            actions.add(action)
            _actionCount.value = actions.size
        }
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

        synchronized(lock) {
            val last = actions.lastOrNull()
            if (last != null && last.actionType == ActionType.INPUT_TEXT
                && ((last.resourceId == resourceId && resourceId.isNotEmpty())
                    || (resourceId.isEmpty() && last.boundsLeft == bounds.left && last.boundsTop == bounds.top))
            ) {
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
                    delayAfterMs = getTimeSinceLastAction(),
                    hintText = source.hintText?.toString() ?: ""
                )
                actions.add(action)
                _actionCount.value = actions.size
            }
        }
    }

    fun getActions(): List<RecordedAction> {
        synchronized(lock) { return actions.toList() }
    }

    fun getActionCount(): Int {
        synchronized(lock) { return actions.size }
    }

    fun tagLastActionAsDataField(fieldKey: String) {
        synchronized(lock) {
            if (actions.isNotEmpty()) {
                val last = actions.last()
                actions[actions.lastIndex] = last.copy(dataFieldKey = fieldKey)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            actions.clear()
            stepCounter = 0
            _actionCount.value = 0
        }
    }
}
