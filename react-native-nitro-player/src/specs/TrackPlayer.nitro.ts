import type { HybridObject } from 'react-native-nitro-modules';


export interface TrackItem {
    id: string;
    title: string;
    artist: string;
    album: string;
    duration: number;
    url: string;
    artwork: string;
}




export interface PlayerQueue extends HybridObject<{android: "kotlin" , ios: "swift"}> {
    loadQueue(tracks: TrackItem[]): void;
    loadSingleTrack(track: TrackItem, index?: number): void;
    deleteTrack(id: string): void;
    clearQueue(): void;
    getQueue(): TrackItem[];
}





// export interface TrackPlayerSpec extends HybridObject<{android: "kotlin" , ios: "swift"}> {
//     play(): void;
//     pause(): void;
//     skip(): void;
//     seek(position: number): void;
// }