package com.margelo.nitro.nitroplayer.core

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.margelo.nitro.nitroplayer.NitroPlayerPackage
import com.margelo.nitro.nitroplayer.PlayerState
import com.margelo.nitro.nitroplayer.Reason
import com.margelo.nitro.nitroplayer.TrackItem
import com.margelo.nitro.nitroplayer.TrackPlayerState
import com.margelo.nitro.nitroplayer.Variant_NullType_TrackItem
import com.margelo.nitro.nitroplayer.queue.QueueManager
import com.margelo.nitro.nitroplayer.media.MediaSessionManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TrackPlayerCore private constructor(context: Context) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private val queueManager = QueueManager.getInstance()
    private var mediaSessionManager: MediaSessionManager? = null
    private var isManuallySeeked = false
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.playbackState != Player.STATE_IDLE) {
                val position = player.currentPosition / 1000.0
                val duration = if (player.duration > 0) player.duration / 1000.0 else 0.0
                onPlaybackProgressChange?.invoke(position, duration, if (isManuallySeeked) true else null)
                isManuallySeeked = false
            }
            handler.postDelayed(this, 250) // Update every 250ms
        }
    }

    var onChangeTrack: ((TrackItem, Reason?) -> Unit)? = null
    var onPlaybackStateChange: ((TrackPlayerState, Reason?) -> Unit)? = null
    var onSeek: ((Double, Double) -> Unit)? = null
    var onPlaybackProgressChange: ((Double, Double, Boolean?) -> Unit)? = null

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
            mediaSessionManager = MediaSessionManager(context, player, queueManager)
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
                        mediaSessionManager?.onTrackChanged()
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
                        isManuallySeeked = true
                        onSeek?.invoke(newPosition.positionMs / 1000.0, player.duration / 1000.0)
                    }
                }
            })

            updatePlayerQueue(queueManager.getTracks())
            // Start progress updates
            handler.post(progressUpdateRunnable)
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
        mediaSessionManager?.onPlaybackStateChanged()
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
            isManuallySeeked = true
            player.seekTo((position * 1000).toLong())
        }
    }

    fun getState(): PlayerState {
        // Check if we're already on the main thread
        if (android.os.Looper.myLooper() == handler.looper) {
            return getStateInternal()
        }
        
        // Use CountDownLatch to wait for the result on the main thread
        val latch = CountDownLatch(1)
        var result: PlayerState? = null
        
        handler.post {
            try {
                result = getStateInternal()
            } finally {
                latch.countDown()
            }
        }
        
        try {
            // Wait up to 5 seconds for the result
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        
        return result ?: getStateInternal()
    }
    
    private fun getStateInternal(): PlayerState {
        return if (::player.isInitialized) {
            val currentMediaItem = player.currentMediaItem
            val track = if (currentMediaItem != null) {
                findTrack(currentMediaItem)
            } else {
                null
            }
            
            // Convert nullable TrackItem to Variant_NullType_TrackItem
            val currentTrack: Variant_NullType_TrackItem? = if (track != null) {
                Variant_NullType_TrackItem.create(track)
            } else {
                null
            }
            
            val currentPosition = player.currentPosition / 1000.0
            val totalDuration = if (player.duration > 0) player.duration / 1000.0 else 0.0
            
            val currentState = when (player.playbackState) {
                Player.STATE_IDLE -> TrackPlayerState.STOPPED
                Player.STATE_BUFFERING -> if (player.playWhenReady) TrackPlayerState.PLAYING else TrackPlayerState.PAUSED
                Player.STATE_READY -> if (player.isPlaying) TrackPlayerState.PLAYING else TrackPlayerState.PAUSED
                Player.STATE_ENDED -> TrackPlayerState.STOPPED
                else -> TrackPlayerState.STOPPED
            }
            
            // Convert List to Array
            val queueList = queueManager.getTracks()
            val queue = queueList.toTypedArray()
            
            // Use ExoPlayer's currentMediaItemIndex, or find by mediaId if index is not available
            // Convert Int to Double
            val currentIndex = if (player.currentMediaItemIndex >= 0) {
                player.currentMediaItemIndex.toDouble()
            } else if (currentMediaItem != null) {
                val index = queueManager.getTrackIndex(currentMediaItem.mediaId)
                if (index >= 0) index.toDouble() else -1.0
            } else {
                -1.0
            }
            
            PlayerState(
                currentTrack = currentTrack,
                currentPosition = currentPosition,
                totalDuration = totalDuration,
                currentState = currentState,
                queue = queue,
                currentIndex = currentIndex
            )
        } else {
            // Return default state if player is not initialized
            val queueList = queueManager.getTracks()
            val queue = queueList.toTypedArray()
            
            PlayerState(
                currentTrack = null,
                currentPosition = 0.0,
                totalDuration = 0.0,
                currentState = TrackPlayerState.STOPPED,
                queue = queue,
                currentIndex = -1.0
            )
        }
    }
    
    fun configure(
        androidAutoEnabled: Boolean?,
        carPlayEnabled: Boolean?,
        showInNotification: Boolean?,
        showInLockScreen: Boolean?
    ) {
        handler.post {
            mediaSessionManager?.configure(
                androidAutoEnabled,
                carPlayEnabled,
                showInNotification,
                showInLockScreen
            )
        }
    }
}
