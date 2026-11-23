package com.soulbot.app.workflow.square

/** Timing and viewport bounds retained from the original square workflow. */
internal object SoulSquareTiming {
    const val SQUARE_POST_LOAD_TIMEOUT_MS = 4500L
    const val SQUARE_POST_STABLE_MS = 1200L
    const val SQUARE_SCROLL_PREPARE_MS = 2000L
    const val SQUARE_SCROLL_SETTLE_MS = 1600L
    const val SQUARE_MAX_VIEWPORTS = 10
}
