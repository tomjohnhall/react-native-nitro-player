// TODO: Export all HybridObjects here for the user

import { NitroModules } from 'react-native-nitro-modules';
import type { PlayerQueue as PlayerQueueType } from './specs/TrackPlayer.nitro';


// export * from './specs/TrackPlayer.nitro';

export const PlayerQueue = NitroModules.createHybridObject<PlayerQueueType>('PlayerQueue');