package com.margelo.nitro.nitroplayer.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.margelo.nitro.nitroplayer.core.TrackPlayerCore

class NitroPlayerMediaBrowserService : MediaBrowserServiceCompat() {
    private var mediaSession: MediaSessionCompat? = null
    
    companion object {
        private const val ROOT_ID = "root"
        private const val EMPTY_ROOT_ID = "empty_root"
        const val MEDIA_ID_QUEUE = "queue"
        
        var trackPlayerCore: TrackPlayerCore? = null
        var isAndroidAutoEnabled: Boolean = false
        var isAndroidAutoConnected: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        
        // Create MediaSession with callbacks
        mediaSession = MediaSessionCompat(this, "NitroPlayerMediaBrowserService").apply {
            // Set session activity (opens the app when tapped)
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this@NitroPlayerMediaBrowserService,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setSessionActivity(pendingIntent)
            
            // Set callback for handling media button events from Android Auto
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    trackPlayerCore?.play()
                }
                
                override fun onPause() {
                    trackPlayerCore?.pause()
                }
                
                override fun onSkipToNext() {
                    trackPlayerCore?.skipToNext()
                }
                
                override fun onSkipToPrevious() {
                    trackPlayerCore?.skipToPrevious()
                }
                
                override fun onSeekTo(pos: Long) {
                    trackPlayerCore?.seek((pos / 1000.0))
                }
                
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    mediaId?.toIntOrNull()?.let { index ->
                        trackPlayerCore?.playFromIndex(index)
                    }
                }
                
                override fun onStop() {
                    trackPlayerCore?.pause()
                }
            })
            
            // Set supported playback actions
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                    )
                    .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
                    .build()
            )
            
            // Enable callbacks
            isActive = true
        }
        
        sessionToken = mediaSession?.sessionToken
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        // Check if Android Auto is enabled
        if (!isAndroidAutoEnabled) {
            return BrowserRoot(EMPTY_ROOT_ID, null)
        }
        
        // Detect Android Auto connection
        isAndroidAutoConnected = rootHints?.getBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", false) == true
        
        // Notify the core that Android Auto is connected
        trackPlayerCore?.onAndroidAutoConnectionChanged(isAndroidAutoConnected)
        
        // Allow Android Auto and other media browsers to connect
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (!isAndroidAutoEnabled) {
            result.sendResult(mutableListOf())
            return
        }
        
        when (parentId) {
            ROOT_ID -> {
                // Return the queue as browsable content
                val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()
                
                // Add a "Queue" item
                val queueDescription = MediaDescriptionCompat.Builder()
                    .setMediaId(MEDIA_ID_QUEUE)
                    .setTitle("Current Queue")
                    .setSubtitle("Now playing")
                    .build()
                
                mediaItems.add(
                    MediaBrowserCompat.MediaItem(
                        queueDescription,
                        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                )
                
                result.sendResult(mediaItems)
            }
            
            MEDIA_ID_QUEUE -> {
                // Return all tracks in the queue
                val core = trackPlayerCore
                if (core != null) {
                    val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()
                    val queue = core.getQueue()
                    
                    queue.forEachIndexed { index, track ->
                        val description = MediaDescriptionCompat.Builder()
                            .setMediaId(index.toString())
                            .setTitle(track.title)
                            .setSubtitle(track.artist)
                            .setDescription(track.album)
                            .build()
                        
                        mediaItems.add(
                            MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                            )
                        )
                    }
                    
                    result.sendResult(mediaItems)
                } else {
                    result.sendResult(mutableListOf())
                }
            }
            
            EMPTY_ROOT_ID -> {
                result.sendResult(mutableListOf())
            }
            
            else -> {
                result.sendResult(mutableListOf())
            }
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}

