import type { HybridObject } from 'react-native-nitro-modules';
import type { QueueOperation, TrackItem } from '../types/PlayerQueue';




export interface PlayerQueue extends HybridObject<{android: "kotlin" , ios: "swift"}> {
    loadQueue(tracks: TrackItem[]): void;
    loadSingleTrack(track: TrackItem, index?: number): void;
    deleteTrack(id: string): void;
    clearQueue(): void;
    getQueue(): TrackItem[];
    onQueueChanged(callback: (queue: TrackItem[], operation?: QueueOperation) => void): void;
}





// export interface TrackPlayerSpec extends HybridObject<{android: "kotlin" , ios: "swift"}> {
//     play(): void;
//     pause(): void;
//     skip(): void;
//     seek(position: number): void;
// }