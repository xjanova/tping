package com.xjanova.tping.puzzle

import com.google.gson.Gson
import com.xjanova.tping.data.entity.ActionType
import com.xjanova.tping.data.entity.RecordedAction

enum class PuzzleRecordingStep {
    IDLE,
    SELECT_PUZZLE_TOP_LEFT,
    SELECT_PUZZLE_BOTTOM_RIGHT,
    SELECT_SLIDER_TOP_LEFT,
    SELECT_SLIDER_BOTTOM_RIGHT,
    DONE
}

data class PuzzleRecordingState(
    val step: PuzzleRecordingStep = PuzzleRecordingStep.IDLE,
    val puzzleLeft: Int = 0,
    val puzzleTop: Int = 0,
    val puzzleRight: Int = 0,
    val puzzleBottom: Int = 0,
    val sliderLeft: Int = 0,
    val sliderTop: Int = 0,
    val sliderRight: Int = 0,
    val sliderBottom: Int = 0
)

object PuzzleRecordingFlow {

    private val gson = Gson()

    fun getCrosshairLabel(step: PuzzleRecordingStep): String {
        return when (step) {
            PuzzleRecordingStep.SELECT_PUZZLE_TOP_LEFT -> "มุมซ้ายบน ภาพ Puzzle"
            PuzzleRecordingStep.SELECT_PUZZLE_BOTTOM_RIGHT -> "มุมขวาล่าง ภาพ Puzzle"
            PuzzleRecordingStep.SELECT_SLIDER_TOP_LEFT -> "มุมซ้ายบน แถบเลื่อน"
            PuzzleRecordingStep.SELECT_SLIDER_BOTTOM_RIGHT -> "มุมขวาล่าง แถบเลื่อน"
            else -> ""
        }
    }

    fun buildAction(
        state: PuzzleRecordingState,
        stepOrder: Int,
        delayAfterMs: Long,
        screenWidth: Int,
        screenHeight: Int,
        packageName: String
    ): RecordedAction {
        val config = PuzzleConfig(
            puzzleLeft = state.puzzleLeft,
            puzzleTop = state.puzzleTop,
            puzzleRight = state.puzzleRight,
            puzzleBottom = state.puzzleBottom
        )
        return RecordedAction(
            stepOrder = stepOrder,
            actionType = ActionType.SOLVE_CAPTCHA,
            inputText = gson.toJson(config),
            boundsLeft = state.sliderLeft,
            boundsTop = state.sliderTop,
            boundsRight = state.sliderRight,
            boundsBottom = state.sliderBottom,
            delayAfterMs = delayAfterMs,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            isGameMode = true,
            packageName = packageName
        )
    }
}
