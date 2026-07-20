package net.devemperor.dictate.ai.port

import java.io.File

/**
 * Returns the audio duration in whole seconds, or -1 if unknown — reproduces
 * DictateUtils.getAudioDuration (android.media.MediaMetadataRetriever) exactly,
 * including the -1 fallback on any error. The Companion backs it with a
 * javax.sound / WAV-header reader (Block D).
 *
 * @see docs/decisions/0028-shared-ai-module.md
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.4
 */
interface AudioDurationReader {
    fun durationSeconds(file: File): Long
}
