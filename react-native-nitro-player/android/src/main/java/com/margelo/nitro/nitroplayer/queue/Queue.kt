package com.margelo.nitro.nitroplayer.queue

import com.margelo.nitro.nitroplayer.TrackItem
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Queue class that manages a list of tracks.
 * Thread-safe implementation using CopyOnWriteArrayList.
 */
class Queue {
    private val tracks: MutableList<TrackItem> = CopyOnWriteArrayList()
    
    /**
     * Get all tracks in the queue
     */
    fun getTracks(): List<TrackItem> {
        return tracks.toList()
    }
    
    /**
     * Get tracks as an array (for compatibility with existing API)
     */
    fun getTracksArray(): Array<TrackItem> {
        return tracks.toTypedArray()
    }
    
    /**
     * Add a single track to the queue
     */
    fun addTrack(track: TrackItem) {
        tracks.add(track)
    }
    
    /**
     * Add a track at a specific index
     */
    fun addTrackAtIndex(track: TrackItem, index: Int) {
        if (index < 0 || index > tracks.size) {
            tracks.add(track)
        } else {
            tracks.add(index, track)
        }
    }
    
    /**
     * Load multiple tracks into the queue (replaces existing queue)
     */
    fun loadTracks(newTracks: List<TrackItem>) {
        tracks.clear()
        tracks.addAll(newTracks)
    }
    
    /**
     * Remove a track by ID
     * @return true if track was found and removed, false otherwise
     */
    fun removeTrack(id: String): Boolean {
        return tracks.removeAll { it.id == id }
    }
    
    /**
     * Clear all tracks from the queue
     */
    fun clear() {
        tracks.clear()
    }
    
    /**
     * Get the size of the queue
     */
    fun size(): Int {
        return tracks.size
    }
    
    /**
     * Check if the queue is empty
     */
    fun isEmpty(): Boolean {
        return tracks.isEmpty()
    }
    
    /**
     * Get a track by index
     */
    fun getTrack(index: Int): TrackItem? {
        return if (index >= 0 && index < tracks.size) {
            tracks[index]
        } else {
            null
        }
    }
    
    /**
     * Get a track by ID
     */
    fun getTrackById(id: String): TrackItem? {
        return tracks.find { it.id == id }
    }
    
    /**
     * Get the index of a track by ID
     */
    fun getTrackIndex(id: String): Int {
        return tracks.indexOfFirst { it.id == id }
    }
}

