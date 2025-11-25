package com.margelo.nitro.nitroplayer

import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.nitroplayer.core.TrackPlayerCore
import com.margelo.nitro.NitroModules


class HybridTrackPlayer : HybridTrackPlayerSpec() {
    private val core: TrackPlayerCore

    init {
        val context = NitroModules.applicationContext  ?: throw IllegalStateException("React Context is not initialized")
        core = TrackPlayerCore.getInstance(context)
    }

    @DoNotStrip
    @Keep
    override fun play() {
        core.play()
    }

    @DoNotStrip
    @Keep
    override fun pause() {
        core.pause()
    }

    @DoNotStrip
    @Keep
    override fun skipToNext() {
        core.skipToNext()
    }

    @DoNotStrip
    @Keep
    override fun skipToPrevious() {
        core.skipToPrevious()
    }

    @DoNotStrip
    @Keep
    override fun seek(position: Double) {
        core.seek(position)
    }

    override fun onChangeTrack(callback: (track: TrackItem, reason: Reason?) -> Unit) {
        core.onChangeTrack = callback
    }

    override fun onPlaybackStateChange(callback: (state: TrackPlayerState, reason: Reason?) -> Unit) {
        core.onPlaybackStateChange = callback
    }

    override fun onSeek(callback: (position: Double, totalDuration: Double) -> Unit) {
        core.onSeek = callback
    }
}
