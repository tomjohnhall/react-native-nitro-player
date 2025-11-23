/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 */

import { NewAppScreen } from '@react-native/new-app-screen';
import { useEffect, useState } from 'react';
import { StatusBar, StyleSheet, useColorScheme, View, Text, ScrollView, TouchableOpacity } from 'react-native';
import { PlayerQueue } from 'react-native-nitro-player';
import type { TrackItem, QueueOperation } from '../react-native-nitro-player/src/types/PlayerQueue';

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  return (
    <AppContent />
  );
}

function AppContent() {
  const [queue, setQueue] = useState<TrackItem[]>([]);
  const [lastOperation, setLastOperation] = useState<QueueOperation | undefined>(undefined);

  // Sample tracks for demonstration
  const sampleTracks: TrackItem[] = [
    {
      id: '1',
      title: 'Sunset Drive',
      artist: 'Lofi Beats',
      album: 'Chill Vibes',
      duration: 182.0,
      url: 'https://example.com/audio/sunset_drive.mp3',
      artwork: 'https://example.com/artwork/sunset.jpg',
    },
    {
      id: '2',
      title: 'Midnight Rain',
      artist: 'Nightfall',
      album: 'Dreamscapes',
      duration: 204.0,
      url: 'https://example.com/audio/midnight_rain.mp3',
      artwork: 'https://example.com/artwork/midnight.jpg',
    },
    {
      id: '3',
      title: 'City Lights',
      artist: 'Synthwave Lab',
      album: 'Neon Streets',
      duration: 195.5,
      url: 'https://example.com/audio/city_lights.mp3',
      artwork: 'https://example.com/artwork/city.jpg',
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

    // Cleanup listener on unmount (if needed)
    return () => {
      // Note: You may need to implement a way to remove listeners
      // depending on your implementation
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
      url: 'https://example.com/audio/new_track.mp3',
      artwork: 'https://example.com/artwork/new.jpg',
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
      url: 'https://example.com/audio/indexed_track.mp3',
      artwork: 'https://example.com/artwork/indexed.jpg',
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

  return (
    <View style={styles.container}>
      <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
        <Text style={styles.title}>PlayerQueue Example</Text>
        
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
});

export default App;
