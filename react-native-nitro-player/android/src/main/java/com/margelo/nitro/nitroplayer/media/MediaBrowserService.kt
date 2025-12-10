package com.margelo.nitro.nitroplayer.media

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.MediaBrowserServiceCompat
import com.margelo.nitro.nitroplayer.core.TrackPlayerCore
import com.margelo.nitro.nitroplayer.TrackItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class NitroPlayerMediaBrowserService : MediaBrowserServiceCompat() {
    
    companion object {
        private const val ROOT_ID = "root"
        private const val EMPTY_ROOT_ID = "empty_root"
        const val MEDIA_ID_QUEUE = "queue"
        private const val PREFS_NAME = "NitroPlayerMediaService"
        private const val KEY_QUEUE_JSON = "queue_json"
        
        var trackPlayerCore: TrackPlayerCore? = null
        var mediaSessionManager: MediaSessionManager? = null
        var isAndroidAutoEnabled: Boolean = false
        var isAndroidAutoConnected: Boolean = false
        
        @Volatile
        private var instance: NitroPlayerMediaBrowserService? = null
        
        fun getInstance(): NitroPlayerMediaBrowserService? = instance
    }

    private lateinit var sharedPreferences: SharedPreferences
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var cachedQueue: List<TrackItem>? = null

    override fun onCreate() {
        super.onCreate()
        
        instance = this
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Use the existing MediaSession from MediaSessionManager
        // This ensures the session is already connected to the ExoPlayer
        try {
            val session = mediaSessionManager?.mediaSession
            if (session != null) {
                // Convert Media3 MediaSession to MediaSessionCompat for MediaBrowserService
                sessionToken = session.sessionCompatToken
                println("🎵 NitroPlayerMediaBrowserService: MediaSession token set successfully")
            } else {
                println("⚠️ NitroPlayerMediaBrowserService: MediaSession not available yet")
            }
        } catch (e: Exception) {
            println("❌ NitroPlayerMediaBrowserService: Error setting session token - ${e.message}")
            e.printStackTrace()
        }
        
        // Load cached queue from SharedPreferences
        loadQueueFromPreferences()
        
        println("🚀 NitroPlayerMediaBrowserService: Service created")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        println("🛑 NitroPlayerMediaBrowserService: Service destroyed")
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        println("📂 NitroPlayerMediaBrowserService: onGetRoot called from $clientPackageName")
        
        // Check if Android Auto is enabled
        if (!isAndroidAutoEnabled) {
            println("⚠️ NitroPlayerMediaBrowserService: Android Auto not enabled")
            return BrowserRoot(EMPTY_ROOT_ID, null)
        }
        
        // Allow Android Auto and other media browsers to connect
        println("✅ NitroPlayerMediaBrowserService: Allowing connection from $clientPackageName")
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        println("📂 NitroPlayerMediaBrowserService: onLoadChildren called for parentId: $parentId")
        
        if (!isAndroidAutoEnabled) {
            println("⚠️ NitroPlayerMediaBrowserService: Android Auto not enabled, returning empty")
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
                
                println("✅ NitroPlayerMediaBrowserService: Returning root with ${mediaItems.size} items")
                result.sendResult(mediaItems)
            }
            
            MEDIA_ID_QUEUE -> {
                // Detach the result to load asynchronously
                result.detach()
                
                serviceScope.launch {
                    try {
                        val queue = getQueue()
                        
                        if (queue.isEmpty()) {
                            println("⚠️ NitroPlayerMediaBrowserService: Queue is empty, returning empty list")
                            result.sendResult(mutableListOf())
                            return@launch
                        }
                        
                        val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()
                        
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
                        
                        println("✅ NitroPlayerMediaBrowserService: Returning queue with ${mediaItems.size} items")
                        result.sendResult(mediaItems)
                    } catch (e: Exception) {
                        println("❌ NitroPlayerMediaBrowserService: Error loading queue - ${e.message}")
                        e.printStackTrace()
                        result.sendResult(mutableListOf())
                    }
                }
            }
            
            EMPTY_ROOT_ID -> {
                result.sendResult(mutableListOf())
            }
            
            else -> {
                println("⚠️ NitroPlayerMediaBrowserService: Unknown parentId: $parentId")
                result.sendResult(mutableListOf())
            }
        }
    }
    
    private suspend fun getQueue(): List<TrackItem> = withContext(Dispatchers.IO) {
        println("📋 NitroPlayerMediaBrowserService: getQueue() called")
        
        // First try cached queue (fastest)
        if (cachedQueue != null && cachedQueue!!.isNotEmpty()) {
            println("📋 NitroPlayerMediaBrowserService: Using cached queue (${cachedQueue!!.size} items)")
            return@withContext cachedQueue!!
        }
        
        // Try to get from SharedPreferences
        val savedQueue = loadQueueFromPreferences()
        if (savedQueue.isNotEmpty()) {
            cachedQueue = savedQueue
            println("📋 NitroPlayerMediaBrowserService: Loaded queue from preferences (${savedQueue.size} items)")
            return@withContext savedQueue
        }
        
        // Last resort: try to get from the player
        val core = trackPlayerCore
        if (core != null) {
            try {
                val queue = core.getQueue()
                if (queue.isNotEmpty()) {
                    cachedQueue = queue
                    saveQueueToPreferences(queue)
                    println("📋 NitroPlayerMediaBrowserService: Got queue from player (${queue.size} items)")
                    return@withContext queue
                }
            } catch (e: Exception) {
                println("⚠️ NitroPlayerMediaBrowserService: Error getting queue from player - ${e.message}")
            }
        }
        
        println("⚠️ NitroPlayerMediaBrowserService: No queue found, returning empty list")
        return@withContext emptyList()
    }
    
    private fun saveQueueToPreferences(queue: List<TrackItem>) {
        try {
            val jsonArray = JSONArray()
            queue.forEach { track ->
                val jsonObject = JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("duration", track.duration)
                    put("url", track.url)
                    track.artwork?.let { put("artwork", it) }
                }
                jsonArray.put(jsonObject)
            }
            
            sharedPreferences.edit()
                .putString(KEY_QUEUE_JSON, jsonArray.toString())
                .apply()
            
            println("💾 NitroPlayerMediaBrowserService: Saved ${queue.size} tracks to preferences")
        } catch (e: Exception) {
            println("❌ NitroPlayerMediaBrowserService: Error saving queue - ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun loadQueueFromPreferences(): List<TrackItem> {
        try {
            val jsonString = sharedPreferences.getString(KEY_QUEUE_JSON, null)
            if (jsonString != null) {
                val jsonArray = JSONArray(jsonString)
                val queue = mutableListOf<TrackItem>()
                
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    queue.add(
                        TrackItem(
                            id = jsonObject.getString("id"),
                            title = jsonObject.getString("title"),
                            artist = jsonObject.getString("artist"),
                            album = jsonObject.getString("album"),
                            duration = jsonObject.getDouble("duration"),
                            url = jsonObject.getString("url"),
                            artwork = jsonObject.optString("artwork", null)
                        )
                    )
                }
                
                cachedQueue = queue
                println("💾 NitroPlayerMediaBrowserService: Loaded ${queue.size} tracks from preferences")
                return queue
            }
        } catch (e: Exception) {
            println("❌ NitroPlayerMediaBrowserService: Error loading queue - ${e.message}")
            e.printStackTrace()
        }
        
        return emptyList()
    }
    
    fun updateQueue(queue: List<TrackItem>) {
        cachedQueue = queue
        saveQueueToPreferences(queue)
        println("🔄 NitroPlayerMediaBrowserService: Queue updated with ${queue.size} items")
        
        // Notify Android Auto that content has changed
        try {
            notifyChildrenChanged(MEDIA_ID_QUEUE)
            println("📢 NitroPlayerMediaBrowserService: Notified Android Auto of queue update")
        } catch (e: Exception) {
            println("⚠️ NitroPlayerMediaBrowserService: Error notifying children changed: ${e.message}")
        }
    }

}

