package com.videoeditor.core.media.composition

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ClippingMediaPeriod
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.Allocator

/** Opt-in clipping for transition graphs; ordinary projects retain pinned Media3 behavior. */
@UnstableApi
class ReorderAwareMediaSourceFactory(private val delegate: MediaSource.Factory) : MediaSource.Factory by delegate {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource = object : WrappingMediaSource(delegate.createMediaSource(mediaItem)) {
        override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
            val period = super.createPeriod(id, allocator, startPositionUs)
            if (period is ClippingMediaPeriod) period.enableVideoReorderDrain(mediaItem)
            return period
        }
    }
}
