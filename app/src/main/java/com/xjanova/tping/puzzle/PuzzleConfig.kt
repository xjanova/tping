package com.xjanova.tping.puzzle

/**
 * Configuration for a slide CAPTCHA puzzle step.
 * Serialized as JSON into RecordedAction.inputText.
 *
 * New format: puzzle image bounds + slider button center coordinates.
 * Legacy format: puzzle bounds here, slider bar bounds in RecordedAction.bounds*.
 * If sliderButtonX/Y are 0, the legacy path is used.
 */
data class PuzzleConfig(
    val puzzleLeft: Int,
    val puzzleTop: Int,
    val puzzleRight: Int,
    val puzzleBottom: Int,
    // Slider button center (screen coordinates at recording time)
    val sliderButtonX: Int = 0,
    val sliderButtonY: Int = 0,
    // Retained for legacy backward compatibility
    val sliderPaddingPx: Int = 30,
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 2000,
    val swipeDurationMs: Long = 800,
    val analyzeMethod: String = "edge"
)
