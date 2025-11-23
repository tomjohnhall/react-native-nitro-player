/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 */

import { NewAppScreen } from '@react-native/new-app-screen';
import { useEffect } from 'react';
import { StatusBar, StyleSheet, useColorScheme, View } from 'react-native';
import {PlayerQueue} from 'react-native-nitro-player';

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  return (
   
      <AppContent />

  );
}

function AppContent() {

  useEffect(() => {
    const queue = PlayerQueue.getQueue();
    console.log(queue);
  }, []);
  return (
    <View style={styles.container}>
      
      <NewAppScreen
        templateFileName="App.tsx"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
});

export default App;
