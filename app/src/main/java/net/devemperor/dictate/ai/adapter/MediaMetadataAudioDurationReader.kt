package net.devemperor.dictate.ai.adapter

import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.ai.port.AudioDurationReader
import java.io.File

/**
 * Android [AudioDurationReader] — delegates to `DictateUtils.getAudioDuration`
 * (android.media.MediaMetadataRetriever), the only real Android media API in the
 * AI core path. Returns -1 on any error, unchanged (spec §4.4).
 */
class MediaMetadataAudioDurationReader : AudioDurationReader {
    override fun durationSeconds(file: File): Long = DictateUtils.getAudioDuration(file)
}
