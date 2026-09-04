package com.xqassist.core

/** A single detected board/board-state change: new FEN + the move detected since last frame. */
data class Detection(val fen: String, val move: String? = null, val boardConfidence: Float = 0f)

/** Interface between vision and engine/analysis layers. */
fun interface BoardDetector {
    /** Detect board + pieces from a full frame; returns detected FEN and optionally the inferred move. */
    suspend fun processFrame(frame: Frame): Detection?
}
