
export interface TrackItem {
    id: string;
    title: string;
    artist: string;
    album: string;
    duration: number;
    url: string;
    artwork: string;
}

export type QueueOperation = 'add' | 'remove' | 'clear';

export type TrackPlayerState = 'playing' | 'paused' | 'stopped';

export type Reason = 'user_action' | 'skip' | 'end' | 'error';

export interface PlayerState {
    currentTrack: TrackItem | null;
    currentPosition: number;
    totalDuration: number;
    currentState: TrackPlayerState;
    queue: TrackItem[];
    currentIndex: number;
}

export interface PlayerConfig {
    androidAutoEnabled?: boolean;
    carPlayEnabled?: boolean;
    showInNotification?: boolean;
    showInLockScreen?: boolean;
}