import type { HybridObject } from 'react-native-nitro-modules';
import type { QueueOperation, Reason, TrackItem, TrackPlayerState, PlayerState, PlayerConfig } from '../types/PlayerQueue';




export interface PlayerQueue extends HybridObject<{android: "kotlin" , ios: "swift"}> {
    loadQueue(tracks: TrackItem[]): void;
    loadSingleTrack(track: TrackItem, index?: number): void;
    deleteTrack(id: string): void;
    clearQueue(): void;
    getQueue(): TrackItem[];
    onQueueChanged(callback: (queue: TrackItem[], operation?: QueueOperation) => void): void;
}





export interface TrackPlayer extends HybridObject<{android: "kotlin" , ios: "swift"}> {
    play(): void;
    pause(): void;
    skipToNext(): void;
    skipToPrevious(): void;
    seek(position: number): void;
    getState(): PlayerState;
    configure(config: PlayerConfig): void;
    onChangeTrack(callback: (track: TrackItem,reason?: Reason) => void): void;
    onPlaybackStateChange(callback: (state: TrackPlayerState,reason?: Reason) => void): void;
    onSeek(callback: (position: number,totalDuration: number) => void): void;
    onPlaybackProgressChange(callback: (position: number,totalDuration: number, isManuallySeeked?:boolean) => void): void;
    onAndroidAutoConnectionChange(callback: (connected: boolean) => void): void;
    isAndroidAutoConnected(): boolean;
}

