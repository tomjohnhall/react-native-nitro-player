/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 */

import { NewAppScreen } from '@react-native/new-app-screen';
import { useEffect, useState } from 'react';
import { StatusBar, StyleSheet, useColorScheme, View, Text, ScrollView, TouchableOpacity } from 'react-native';
import { PlayerQueue, TrackPlayer } from 'react-native-nitro-player';
import type { TrackItem, QueueOperation, TrackPlayerState, Reason } from '../react-native-nitro-player/src/types/PlayerQueue';

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  return (
    <AppContent />
  );
}

function AppContent() {
  const [queue, setQueue] = useState<TrackItem[]>([]);
  const [lastOperation, setLastOperation] = useState<QueueOperation | undefined>(undefined);
  const [playbackState, setPlaybackState] = useState<number | undefined>(undefined);
  const [currentTrack, setCurrentTrack] = useState<TrackItem | undefined>(undefined);

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

  useEffect(() => {
    // Get initial queue
    const initialQueue = PlayerQueue.getQueue();
    setQueue(initialQueue);

    // Listen to queue changes
    PlayerQueue.onQueueChanged((updatedQueue, operation) => {
      console.log('Queue changed:', operation, updatedQueue);
      setQueue(updatedQueue);
      setLastOperation(operation);
    });

    TrackPlayer.onPlaybackStateChange((state, reason) => {
      console.log('Playback state changed:', state, reason);
      setPlaybackState(state);
    });

    TrackPlayer.onChangeTrack((track, reason) => {
      console.log('Track changed:', track, reason);
      setCurrentTrack(track);
    });
    TrackPlayer.onSeek((position, totalDuration) => {
      console.log('Seek:', position, totalDuration);
    });
    return () => {
    };
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
          </View>
          <Text style={styles.statusText}>State: {playbackState !== undefined ? playbackState : 'None'}</Text>
          {currentTrack && (
            <View style={styles.currentTrack}>
              <Text style={styles.currentTrackTitle}>Now Playing: {currentTrack.title}</Text>
              <Text style={styles.currentTrackArtist}>{currentTrack.artist}</Text>
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
});

export default App;
