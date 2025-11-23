package com.margelo.nitro.nitroplayer

import androidx.annotation.Keep
import com.facebook.jni.HybridData
import com.facebook.proguard.annotations.DoNotStrip

class HybridPlayerQueue : HybridPlayerQueueSpec()  {
    @DoNotStrip
    @Keep
    override fun loadQueue(tracks: Array<TrackItem>){
        // We will implement Later
    }

    @DoNotStrip
    @Keep
    override fun loadSingleTrack(track: TrackItem, index: Double?){
        // We will implement later
    }

    @DoNotStrip
    @Keep
    override  fun deleteTrack(id: String){
        // We will implemnt later
    }

    @DoNotStrip
    @Keep
    override fun clearQueue(){
        //We will implement later
    }
    fun getDummyTracks(): Array<TrackItem> {
        return arrayOf(
            TrackItem(
                id = "1",
                title = "Sunset Drive",
                artist = "Lofi Beats",
                album = "Chill Vibes",
                duration = 182.0,
                url = "https://example.com/audio/sunset_drive.mp3",
                artwork = "https://example.com/artwork/sunset.jpg"
            ),
            TrackItem(
                id = "2",
                title = "Midnight Rain",
                artist = "Nightfall",
                album = "Dreamscapes",
                duration = 204.0,
                url = "https://example.com/audio/midnight_rain.mp3",
                artwork = "https://example.com/artwork/midnight.jpg"
            ),
            TrackItem(
                id = "3",
                title = "City Lights",
                artist = "Synthwave Lab",
                album = "Neon Streets",
                duration = 195.5,
                url = "https://example.com/audio/city_lights.mp3",
                artwork = "https://example.com/artwork/city.jpg"
            ),
            TrackItem(
                id = "4",
                title = "Ocean Breeze",
                artist = "Calm Collective",
                album = "Relax & Flow",
                duration = 210.3,
                url = "https://example.com/audio/ocean_breeze.mp3",
                artwork = "https://example.com/artwork/ocean.jpg"
            ),
            TrackItem(
                id = "5",
                title = "Electric Heart",
                artist = "RetroWave",
                album = "Pulse",
                duration = 188.0,
                url = "https://example.com/audio/electric_heart.mp3",
                artwork = "https://example.com/artwork/electric.jpg"
            )
        )
    }

    @DoNotStrip
    @Keep
     override fun getQueue(): Array<TrackItem>{
            return  getDummyTracks();
     }



}