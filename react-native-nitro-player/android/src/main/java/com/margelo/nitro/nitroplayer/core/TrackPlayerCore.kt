package com.margelo.nitro.nitroplayer.core

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.margelo.nitro.nitroplayer.NitroPlayerPackage
import com.margelo.nitro.nitroplayer.Reason
import com.margelo.nitro.nitroplayer.TrackItem
import com.margelo.nitro.nitroplayer.TrackPlayerState
import com.margelo.nitro.nitroplayer.queue.QueueManager

class TrackPlayerCore private constructor(context: Context) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private val queueManager = QueueManager.getInstance()

    var onChangeTrack: ((TrackItem, Reason?) -> Unit)? = null
    var onPlaybackStateChange: ((TrackPlayerState, Reason?) -> Unit)? = null
    var onSeek: ((Double, Double) -> Unit)? = null

    companion object {
        @Volatile
        private var INSTANCE: TrackPlayerCore? = null

        fun getInstance(context: Context): TrackPlayerCore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TrackPlayerCore(context).also { INSTANCE = it }
            }
        }
    }

    init {
        handler.post {
            player = ExoPlayer.Builder(context).build()
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val track = findTrack(mediaItem)
                    if (track != null) {
                        val r = when (reason) {
                            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> Reason.END
                            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> Reason.USER_ACTION
                            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> Reason.USER_ACTION
                            else -> null
                        }
                        onChangeTrack?.invoke(track, r)
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    val r = when (reason) {
                        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> Reason.USER_ACTION
                        else -> null
                    }
                    emitStateChange(r)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    emitStateChange()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    emitStateChange()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        onSeek?.invoke(newPosition.positionMs / 1000.0, player.duration / 1000.0)
                    }
                }
            })

            updatePlayerQueue(queueManager.getTracks())
        }

        queueManager.addQueueChangeListener { tracks, _ ->
            handler.post {
                if (::player.isInitialized) {
                    updatePlayerQueue(tracks)
                }
            }
        }
    }

    private fun emitStateChange(reason: Reason? = null) {
        val state = when (player.playbackState) {
            Player.STATE_IDLE -> TrackPlayerState.STOPPED
            Player.STATE_BUFFERING -> if (player.playWhenReady) TrackPlayerState.PLAYING else TrackPlayerState.PAUSED
            Player.STATE_READY -> if (player.isPlaying) TrackPlayerState.PLAYING else TrackPlayerState.PAUSED
            Player.STATE_ENDED -> TrackPlayerState.STOPPED
            else -> TrackPlayerState.STOPPED
        }
        
        val actualReason = reason ?: if (player.playbackState == Player.STATE_ENDED) Reason.END else null
        onPlaybackStateChange?.invoke(state, actualReason)
    }

    private fun updatePlayerQueue(tracks: List<TrackItem>) {
        val mediaItems = tracks.map { it.toMediaItem() }
        player.setMediaItems(mediaItems, false)
        if (player.playbackState == Player.STATE_IDLE && mediaItems.isNotEmpty()) {
            player.prepare()
        }
    }

    private fun TrackItem.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(Uri.parse(artwork))
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun findTrack(mediaItem: MediaItem?): TrackItem? {
        if (mediaItem == null) return null
        return queueManager.getTrackById(mediaItem.mediaId)
    }

    fun play() {
        handler.post { player.play() }
    }

    fun pause() {
        handler.post { player.pause() }
    }

    fun skipToNext() {
        handler.post {
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
            }
        }
    }

    fun skipToPrevious() {
        handler.post {
            if (player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem()
            }
        }
    }

    fun seek(position: Double) {
        handler.post {
            player.seekTo((position * 1000).toLong())
        }
    }
}
