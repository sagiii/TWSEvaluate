package com.sagiii.twsevaluate.data

import kotlinx.serialization.Serializable

enum class EvaluationMode {
    MUSIC,
    CONFERENCE,
}

@Serializable
data class ModeSpan(
    val mode: EvaluationMode,
    val startMs: Long,
    val endMs: Long,
)

@Serializable
data class Session(
    val id: String,
    val createdAtEpochMs: Long,
    val photoPath: String,
    val videoPath: String? = null,
    val conferenceAudioPaths: List<String> = emptyList(),
    val modeTimeline: List<ModeSpan> = emptyList(),
) {
    val durationMs: Long
        get() = modeTimeline.maxOfOrNull { it.endMs } ?: 0L

    val usedModes: Set<EvaluationMode>
        get() = modeTimeline.map { it.mode }.toSet()
}
