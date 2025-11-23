//
//  HybridPlayerQueue.swift
//  NitroPlayer
//
//  Created by Ritesh Shukla on 24/11/25.
//

import NitroModules

final class HybridPlayerQueue: HybridPlayerQueueSpec {
    private let queueManager = QueueManager.shared
    private var queueChangeListener: (() -> Void)?
    
    func loadQueue(tracks: [TrackItem]) throws {
        queueManager.loadQueue(tracks)
    }
    
    func loadSingleTrack(track: TrackItem, index: Double?) throws {
        queueManager.loadSingleTrack(track, index: index)
    }
    
    func deleteTrack(id: String) throws {
        queueManager.deleteTrack(id: id)
    }
    
    func clearQueue() throws {
        queueManager.clearQueue()
    }
    
    func getQueue() throws -> [TrackItem] {
        return queueManager.getTracksArray()
    }
    
    func onQueueChanged(callback: @escaping (_ queue: [TrackItem], _ operation: QueueOperation?) -> Void) throws {
        // Remove previous listener if exists
        queueChangeListener?()
        
        // Add new listener
        queueChangeListener = queueManager.addQueueChangeListener { tracks, operation in
            callback(tracks, operation)
        }
    }
}
