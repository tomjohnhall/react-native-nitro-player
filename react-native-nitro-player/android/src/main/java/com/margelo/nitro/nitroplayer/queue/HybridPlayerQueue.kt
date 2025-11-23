package com.margelo.nitro.nitroplayer

import androidx.annotation.Keep
import com.facebook.jni.HybridData
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.nitroplayer.queue.QueueManager

class HybridPlayerQueue : HybridPlayerQueueSpec()  {
    private val queueManager = QueueManager.getInstance()
    private var queueChangeListener: (() -> Unit)? = null

    @DoNotStrip
    @Keep
    override fun loadQueue(tracks: Array<TrackItem>){
        queueManager.loadQueue(tracks)
    }

    @DoNotStrip
    @Keep
    override fun loadSingleTrack(track: TrackItem, index: Double?){
        queueManager.loadSingleTrack(track, index)
    }

    @DoNotStrip
    @Keep
    override fun deleteTrack(id: String){
        queueManager.deleteTrack(id)
    }

    @DoNotStrip
    @Keep
    override fun clearQueue(){
        queueManager.clearQueue()
    }

    @DoNotStrip
    @Keep
    override fun getQueue(): Array<TrackItem>{
        return queueManager.getTracksArray()
    }

    @DoNotStrip
    @Keep
    override fun onQueueChanged(callback: (queue: Array<TrackItem>, operation: QueueOperation?) -> Unit): Unit {
        // Remove previous listener if exists
        queueChangeListener?.invoke()
        
        // Add new listener
        queueChangeListener = queueManager.addQueueChangeListener { tracks, operation ->
            callback(tracks.toTypedArray(), operation)
        }
    }
}