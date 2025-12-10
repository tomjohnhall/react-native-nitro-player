//
//  Playlist.swift
//  NitroPlayer
//
//  Created on 10/12/25.
//

import Foundation

/**
 * Represents a playlist containing multiple tracks
 * Uses AVPlayer's native playlist functionality
 */
class Playlist {
    let id: String
    let name: String
    let description: String?
    let artwork: String?
    var tracks: [TrackItem]
    
    init(id: String, name: String, description: String? = nil, artwork: String? = nil, tracks: [TrackItem] = []) {
        self.id = id
        self.name = name
        self.description = description
        self.artwork = artwork
        self.tracks = tracks
    }
    
    func getTrackCount() -> Int {
        return tracks.count
    }
    
    func isEmpty() -> Bool {
        return tracks.isEmpty
    }
}

