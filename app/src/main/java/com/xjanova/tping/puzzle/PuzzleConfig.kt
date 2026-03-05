package com.xjanova.tping.puzzle

/**
 * Configuration for a slide CAPTCHA puzzle step.
 * Serialized as JSON into RecordedAction.inputText.
 *
 * Puzzle image region bounds are stored here.
 * Slider bar bounds use RecordedAction.boundsLeft/Top/Right/Bottom.
 */
data class PuzzleConfig(
    val puzzleLeft: Int,
    val puzzleTop: Int,
    val puzzleRight: Int,
    val puzzleBottom: Int,
    val sliderPaddingPx: Int = 20,
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 1500,
    val swipeDurationMs: Long = 600,
    val analyzeMethod: String = "edge"
)
