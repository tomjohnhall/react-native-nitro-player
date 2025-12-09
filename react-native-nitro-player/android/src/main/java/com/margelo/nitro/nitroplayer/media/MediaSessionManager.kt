package com.margelo.nitro.nitroplayer.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.margelo.nitro.nitroplayer.TrackItem
import com.margelo.nitro.nitroplayer.queue.QueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MediaSessionManager(
    private val context: Context,
    private val player: ExoPlayer,
    private val queueManager: QueueManager
) {
    private var mediaSession: MediaSession? = null
    private var notificationManager: NotificationManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var androidAutoEnabled: Boolean = false
    private var carPlayEnabled: Boolean = false
    private var showInNotification: Boolean = true
    private var showInLockScreen: Boolean = true
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "nitro_player_channel"
        private const val CHANNEL_NAME = "Music Player"
        const val ACTION_PLAY = "com.margelo.nitro.nitroplayer.PLAY"
        const val ACTION_PAUSE = "com.margelo.nitro.nitroplayer.PAUSE"
        const val ACTION_NEXT = "com.margelo.nitro.nitroplayer.NEXT"
        const val ACTION_PREVIOUS = "com.margelo.nitro.nitroplayer.PREVIOUS"
    }
    
    init {
        setupMediaSession()
        createNotificationChannel()
    }
    
    fun configure(
        androidAutoEnabled: Boolean?,
        carPlayEnabled: Boolean?,
        showInNotification: Boolean?,
        showInLockScreen: Boolean?
    ) {
        androidAutoEnabled?.let { this.androidAutoEnabled = it }
        carPlayEnabled?.let { this.carPlayEnabled = it }
        showInNotification?.let { 
            this.showInNotification = it
            if (it) {
                updateNotification()
            } else {
                hideNotification()
            }
        }
        showInLockScreen?.let { this.showInLockScreen = it }
    }
    
    private fun setupMediaSession() {
        try {
            mediaSession = MediaSession.Builder(context, player).build()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
            }
            
            notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager?.createNotificationChannel(channel)
        } else {
            notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
    }
    
    private fun getCurrentTrack(): TrackItem? {
        val currentMediaItem = player.currentMediaItem ?: return null
        return queueManager.getTrackById(currentMediaItem.mediaId)
    }
    
    private fun updateNotification() {
        if (!showInNotification) return
        
        val currentTrack = getCurrentTrack()
        val notification = buildNotification(currentTrack)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
    
    private fun buildNotification(track: TrackItem?): Notification {
        val mediaSession = this.mediaSession ?: return createEmptyNotification()
        
        // Launch intent
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(track?.title ?: "Unknown Title")
            .setContentText(track?.artist ?: "Unknown Artist")
            .setSubText(track?.album ?: "")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setVisibility(if (showInLockScreen) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(player.isPlaying)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        
        // Add action buttons
        builder.addAction(
            android.R.drawable.ic_media_previous,
            "Previous",
            createMediaAction(ACTION_PREVIOUS)
        )
        
        if (player.isPlaying) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                createMediaAction(ACTION_PAUSE)
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Play",
                createMediaAction(ACTION_PLAY)
            )
        }
        
        builder.addAction(
            android.R.drawable.ic_media_next,
            "Next",
            createMediaAction(ACTION_NEXT)
        )
        
        // Load artwork asynchronously
        track?.artwork?.let { artworkUrl ->
            scope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        val url = URL(artworkUrl)
                        BitmapFactory.decodeStream(url.openConnection().getInputStream())
                    }
                    builder.setLargeIcon(bitmap)
                    notificationManager?.notify(NOTIFICATION_ID, builder.build())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        return builder.build()
    }
    
    private fun createEmptyNotification(): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Music Player")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
    
    private fun createMediaAction(action: String): PendingIntent {
        val intent = Intent(action).apply {
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    private fun hideNotification() {
        notificationManager?.cancel(NOTIFICATION_ID)
    }
    
    fun onTrackChanged() {
        updateNotification()
    }
    
    fun onPlaybackStateChanged() {
        updateNotification()
    }
    
    fun release() {
        hideNotification()
        mediaSession?.release()
        mediaSession = null
    }
}

