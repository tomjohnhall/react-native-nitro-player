/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 */

import { NewAppScreen } from '@react-native/new-app-screen';
import { useEffect, useState } from 'react';
import { StatusBar, StyleSheet, useColorScheme, View, Text, ScrollView, TouchableOpacity } from 'react-native';
import { 
  PlayerQueue, 
  TrackPlayer,
  useOnChangeTrack,
  useOnPlaybackStateChange,
  useOnSeek,
  useOnPlaybackProgressChange
} from 'react-native-nitro-player';
import type { TrackItem, QueueOperation, TrackPlayerState, Reason, PlayerState, PlayerConfig } from '../react-native-nitro-player/src/types/PlayerQueue';

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  return (
    <AppContent />
  );
}

function AppContent() {
  const [queue, setQueue] = useState<TrackItem[]>([]);
  const [lastOperation, setLastOperation] = useState<QueueOperation | undefined>(undefined);
  const [playerState, setPlayerState] = useState<PlayerState | undefined>(undefined);

  // Use hooks to get player state directly
  const { track: currentTrack, reason: trackChangeReason } = useOnChangeTrack();
  const { state: playbackState, reason: stateChangeReason } = useOnPlaybackStateChange();
  const { position: lastSeekPosition, totalDuration: lastSeekDuration } = useOnSeek();
  const { position: playbackPosition, totalDuration, isManuallySeeked } = useOnPlaybackProgressChange();

  // Sample tracks for demonstration
  const sampleTracks: TrackItem[] = [
    {
      id: '1',
      title: 'Sunset Drive',
      artist: 'Lofi Beats',
      album: 'Chill Vibes',
      duration: 182.0,
      url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
      artwork: 'https://via.placeholder.com/150/0000FF/808080?Text=Sunset',
    },
    {
      id: '2',
      title: 'Midnight Rain',
      artist: 'Nightfall',
      album: 'Dreamscapes',
      duration: 204.0,
      url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3',
      artwork: 'https://via.placeholder.com/150/FF0000/FFFFFF?Text=Midnight',
    },
    {
      id: '3',
      title: 'City Lights',
      artist: 'Synthwave Lab',
      album: 'Neon Streets',
      duration: 195.5,
      url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3',
      artwork: 'https://via.placeholder.com/150/00FF00/000000?Text=City',
    },
  ];

  // Log changes for debugging
  useEffect(() => {
    if (currentTrack) {
      console.log('Track changed:', currentTrack, trackChangeReason);
    }
  }, [currentTrack, trackChangeReason]);

  useEffect(() => {
    if (playbackState !== undefined) {
      console.log('Playback state changed:', playbackState, stateChangeReason);
    }
  }, [playbackState, stateChangeReason]);

  useEffect(() => {
    if (lastSeekPosition !== undefined) {
      console.log('Seek:', lastSeekPosition, lastSeekDuration);
    }
  }, [lastSeekPosition, lastSeekDuration]);

  useEffect(() => {
    // Configure player for notifications and lock screen
    TrackPlayer.configure({
      androidAutoEnabled: false,
      carPlayEnabled: false,
      showInNotification: true,
      showInLockScreen: true,
    });

    // Get initial queue
    const initialQueue = PlayerQueue.getQueue();
    setQueue(initialQueue);

    // Listen to queue changes
    PlayerQueue.onQueueChanged((updatedQueue, operation) => {
      console.log('Queue changed:', operation, updatedQueue);
      setQueue(updatedQueue);
      setLastOperation(operation);
    });
  }, []);

  const handleLoadQueue = () => {
    console.log('Loading queue with', sampleTracks.length, 'tracks');
    PlayerQueue.loadQueue(sampleTracks);
  };

  const handleAddTrack = () => {
    const newTrack: TrackItem = {
      id: `${Date.now()}`,
      title: 'New Track',
      artist: 'Unknown Artist',
      album: 'Unknown Album',
      duration: 180.0,
      url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3',
      artwork: 'https://via.placeholder.com/150/FFFF00/000000?Text=New',
    };
    console.log('Adding track:', newTrack.id);
    PlayerQueue.loadSingleTrack(newTrack);
  };

  const handleAddTrackAtIndex = () => {
    const newTrack: TrackItem = {
      id: `${Date.now()}_indexed`,
      title: 'Track at Index 1',
      artist: 'Unknown Artist',
      album: 'Unknown Album',
      duration: 200.0,
      url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3',
      artwork: 'https://via.placeholder.com/150/00FFFF/000000?Text=Indexed',
    };
    console.log('Adding track at index 1:', newTrack.id);
    PlayerQueue.loadSingleTrack(newTrack, 1);
  };

  const handleDeleteTrack = () => {
    if (queue.length > 0) {
      const trackToDelete = queue[0];
      console.log('Deleting track:', trackToDelete.id);
      PlayerQueue.deleteTrack(trackToDelete.id);
    }
  };

  const handleClearQueue = () => {
    console.log('Clearing queue');
    PlayerQueue.clearQueue();
  };

  const handleGetQueue = () => {
    const currentQueue = PlayerQueue.getQueue();
    console.log('Current queue:', currentQueue);
    setQueue(currentQueue);
  };

  const handlePlay = () => TrackPlayer.play();
  const handlePause = () => TrackPlayer.pause();
  const handleSkipNext = () => TrackPlayer.skipToNext();
  const handleSkipPrevious = () => TrackPlayer.skipToPrevious();
  const handleSeekTo30 = () => TrackPlayer.seek(30);
  const handleSeekTo60 = () => TrackPlayer.seek(60);
  
  const handleGetState = () => {
    const state = TrackPlayer.getState();
    console.log('Player State:', state);
    setPlayerState(state);
  };

  return (
    <View style={styles.container}>
      <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
        <Text style={styles.title}>Nitro Player Example</Text>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Player Controls</Text>
          <View style={styles.controlsRow}>
            <TouchableOpacity style={styles.controlButton} onPress={handleSkipPrevious}>
              <Text style={styles.buttonText}>Prev</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.controlButton} onPress={handlePlay}>
              <Text style={styles.buttonText}>Play</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.controlButton} onPress={handlePause}>
              <Text style={styles.buttonText}>Pause</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.controlButton} onPress={handleSkipNext}>
              <Text style={styles.buttonText}>Next</Text>
            </TouchableOpacity>
          </View>
          <View style={styles.controlsRow}>
            <TouchableOpacity style={styles.controlButton} onPress={handleSeekTo30}>
              <Text style={styles.buttonText}>Seek 30s</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.controlButton} onPress={handleSeekTo60}>
              <Text style={styles.buttonText}>Seek 60s</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.controlButton} onPress={handleGetState}>
              <Text style={styles.buttonText}>Get State</Text>
            </TouchableOpacity>
          </View>
          <Text style={styles.statusText}>State: {playbackState !== undefined ? playbackState : 'None'}</Text>
          {currentTrack && (
            <View style={styles.currentTrack}>
              <Text style={styles.currentTrackTitle}>Now Playing: {currentTrack.title}</Text>
              <Text style={styles.currentTrackArtist}>{currentTrack.artist}</Text>
            </View>
          )}
          
          <View style={styles.progressSection}>
            <Text style={styles.progressLabel}>Playback Progress</Text>
            <Text style={styles.progressText}>
              {formatTime(playbackPosition)} / {formatTime(totalDuration)}
            </Text>
            {totalDuration > 0 && (
              <View style={styles.progressBarContainer}>
                <View 
                  style={[
                    styles.progressBar, 
                    { width: `${(playbackPosition / totalDuration) * 100}%` }
                  ]} 
                />
              </View>
            )}
            {isManuallySeeked !== undefined && (
              <Text style={styles.seekIndicator}>
                {isManuallySeeked ? '⏩ Manual Seek' : '▶️ Playing'}
              </Text>
            )}
          </View>

          {lastSeekPosition !== undefined && lastSeekDuration !== undefined && (
            <View style={styles.seekInfo}>
              <Text style={styles.seekLabel}>Last Seek Event:</Text>
              <Text style={styles.seekText}>
                Position: {formatTime(lastSeekPosition)} / Duration: {formatTime(lastSeekDuration)}
              </Text>
            </View>
          )}
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Queue Operations</Text>

          <TouchableOpacity style={styles.button} onPress={handleLoadQueue}>
            <Text style={styles.buttonText}>Load Queue (3 tracks)</Text>
          </TouchableOpacity>

          <TouchableOpacity style={styles.button} onPress={handleAddTrack}>
            <Text style={styles.buttonText}>Add Track to End</Text>
          </TouchableOpacity>

          <TouchableOpacity style={styles.button} onPress={handleAddTrackAtIndex}>
            <Text style={styles.buttonText}>Add Track at Index 1</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.button, queue.length === 0 && styles.buttonDisabled]}
            onPress={handleDeleteTrack}
            disabled={queue.length === 0}
          >
            <Text style={styles.buttonText}>Delete First Track</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.button, queue.length === 0 && styles.buttonDisabled]}
            onPress={handleClearQueue}
            disabled={queue.length === 0}
          >
            <Text style={styles.buttonText}>Clear Queue</Text>
          </TouchableOpacity>

          <TouchableOpacity style={styles.button} onPress={handleGetQueue}>
            <Text style={styles.buttonText}>Refresh Queue</Text>
          </TouchableOpacity>
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>
            Queue Status ({queue.length} tracks)
          </Text>
          {lastOperation && (
            <Text style={styles.operationText}>
              Last operation: {lastOperation}
            </Text>
          )}
        </View>

        {playerState && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>Player State (from getState())</Text>
            <View style={styles.stateInfo}>
              <Text style={styles.stateLabel}>Current State:</Text>
              <Text style={styles.stateValue}>{playerState.currentState}</Text>
            </View>
            <View style={styles.stateInfo}>
              <Text style={styles.stateLabel}>Current Position:</Text>
              <Text style={styles.stateValue}>{formatTime(playerState.currentPosition)}</Text>
            </View>
            <View style={styles.stateInfo}>
              <Text style={styles.stateLabel}>Total Duration:</Text>
              <Text style={styles.stateValue}>{formatTime(playerState.totalDuration)}</Text>
            </View>
            <View style={styles.stateInfo}>
              <Text style={styles.stateLabel}>Current Index:</Text>
              <Text style={styles.stateValue}>
                {playerState.currentIndex >= 0 ? playerState.currentIndex : 'None'}
              </Text>
            </View>
            {playerState.currentTrack && (
              <View style={styles.stateInfo}>
                <Text style={styles.stateLabel}>Current Track:</Text>
                <View style={styles.stateTrackInfo}>
                  <Text style={styles.stateValue}>{playerState.currentTrack.title}</Text>
                  <Text style={styles.stateSubValue}>{playerState.currentTrack.artist}</Text>
                </View>
              </View>
            )}
            <View style={styles.stateInfo}>
              <Text style={styles.stateLabel}>Queue Length:</Text>
              <Text style={styles.stateValue}>{playerState.queue.length} tracks</Text>
            </View>
          </View>
        )}

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Current Queue</Text>
          {queue.length === 0 ? (
            <Text style={styles.emptyText}>Queue is empty</Text>
          ) : (
            queue.map((track, index) => (
              <View key={track.id} style={styles.trackItem}>
                <Text style={styles.trackIndex}>{index + 1}.</Text>
                <View style={styles.trackInfo}>
                  <Text style={styles.trackTitle}>{track.title}</Text>
                  <Text style={styles.trackArtist}>{track.artist}</Text>
                  <Text style={styles.trackAlbum}>{track.album}</Text>
                </View>
              </View>
            ))
          )}
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  scrollView: {
    flex: 1,
  },
  content: {
    padding: 20,
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 20,
    textAlign: 'center',
  },
  section: {
    marginBottom: 30,
    backgroundColor: '#fff',
    padding: 15,
    borderRadius: 8,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 15,
    color: '#333',
  },
  button: {
    backgroundColor: '#007AFF',
    padding: 12,
    borderRadius: 8,
    marginBottom: 10,
    alignItems: 'center',
  },
  buttonDisabled: {
    backgroundColor: '#ccc',
    opacity: 0.6,
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '500',
  },
  operationText: {
    fontSize: 14,
    color: '#666',
    fontStyle: 'italic',
    marginTop: 5,
  },
  emptyText: {
    fontSize: 14,
    color: '#999',
    fontStyle: 'italic',
  },
  trackItem: {
    flexDirection: 'row',
    padding: 10,
    marginBottom: 8,
    backgroundColor: '#f9f9f9',
    borderRadius: 6,
  },
  trackIndex: {
    fontSize: 16,
    fontWeight: 'bold',
    marginRight: 10,
    color: '#666',
  },
  trackInfo: {
    flex: 1,
  },
  trackTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  trackArtist: {
    fontSize: 14,
    color: '#666',
    marginTop: 2,
  },
  trackAlbum: {
    fontSize: 12,
    color: '#999',
    marginTop: 2,
  },
  controlsRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    marginBottom: 10,
  },
  controlButton: {
    backgroundColor: '#007AFF',
    padding: 10,
    borderRadius: 5,
    minWidth: 60,
    alignItems: 'center',
  },
  statusText: {
    textAlign: 'center',
    marginBottom: 5,
    fontWeight: 'bold',
  },
  currentTrack: {
    marginTop: 10,
    padding: 10,
    backgroundColor: '#eee',
    borderRadius: 5,
  },
  currentTrackTitle: {
    fontWeight: 'bold',
    fontSize: 16,
  },
  currentTrackArtist: {
    fontSize: 14,
    color: '#666',
  },
  progressSection: {
    marginTop: 15,
    padding: 10,
    backgroundColor: '#f0f0f0',
    borderRadius: 5,
  },
  progressLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
    marginBottom: 5,
  },
  progressText: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#007AFF',
    textAlign: 'center',
    marginBottom: 8,
  },
  progressBarContainer: {
    height: 6,
    backgroundColor: '#ddd',
    borderRadius: 3,
    overflow: 'hidden',
    marginBottom: 5,
  },
  progressBar: {
    height: '100%',
    backgroundColor: '#007AFF',
    borderRadius: 3,
  },
  seekIndicator: {
    fontSize: 12,
    color: '#666',
    textAlign: 'center',
    marginTop: 5,
  },
  seekInfo: {
    marginTop: 10,
    padding: 8,
    backgroundColor: '#fff3cd',
    borderRadius: 5,
    borderWidth: 1,
    borderColor: '#ffc107',
  },
  seekLabel: {
    fontSize: 12,
    fontWeight: '600',
    color: '#856404',
    marginBottom: 3,
  },
  seekText: {
    fontSize: 12,
    color: '#856404',
  },
  stateInfo: {
    marginBottom: 12,
    paddingBottom: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
  },
  stateLabel: {
    fontSize: 12,
    fontWeight: '600',
    color: '#666',
    marginBottom: 4,
  },
  stateValue: {
    fontSize: 14,
    color: '#333',
    fontWeight: '500',
  },
  stateSubValue: {
    fontSize: 12,
    color: '#666',
    marginTop: 2,
  },
  stateTrackInfo: {
    marginTop: 4,
  },
});

// Helper function to format time
function formatTime(seconds: number): string {
  if (!isFinite(seconds) || seconds < 0) return '0:00';
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins}:${secs.toString().padStart(2, '0')}`;
}

export default App;
