package com.videoeditor.core.media.composition

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi

/**
 * A [SpeedProvider] for a single constant speed spanning the whole item.
 *
 * [getNextSpeedChangeTimeUs] returns [C.TIME_UNSET], the Media3 sentinel for
 * "no further speed change", which is what the audio and video speed pipelines
 * compare against internally.
 */
@UnstableApi
class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {

    init {
        require(speed > 0f) { "Speed must be positive, was $speed" }
    }

    override fun getSpeed(timeUs: Long): Float = speed

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}
