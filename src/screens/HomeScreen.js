import React, { useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  Alert,
} from 'react-native';
import { NativeModules } from 'react-native';

const { FilePickerModule } = NativeModules;

const HomeScreen = ({ navigation, route }) => {
  const [focusedButton, setFocusedButton] = useState('open');

  const handleOpenPDF = async () => {
    try {
      const uri = await FilePickerModule.pickPDF();
      if (uri) {
        navigation.navigate('PDFViewer', { uri });
      }
    } catch (error) {
      Alert.alert('Error', error.message || 'Failed to pick PDF file');
    }
  };

  const handleRecentPDFs = () => {
    // TODO: Implement recent PDFs
    console.log('Recent PDFs');
  };

  const handleAbout = () => {
    // TODO: Implement about
    console.log('About');
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>NV PDF Viewer</Text>
      
      <ScrollView contentContainerStyle={styles.buttonContainer}>
        <TouchableOpacity
          style={[
            styles.button,
            focusedButton === 'open' && styles.focusedButton,
          ]}
          onFocus={() => setFocusedButton('open')}
          onPress={handleOpenPDF}>
          <Text
            style={[
              styles.buttonText,
              focusedButton === 'open' && styles.focusedButtonText,
            ]}>
            Open PDF
          </Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[
            styles.button,
            focusedButton === 'recent' && styles.focusedButton,
          ]}
          onFocus={() => setFocusedButton('recent')}
          onPress={handleRecentPDFs}>
          <Text
            style={[
              styles.buttonText,
              focusedButton === 'recent' && styles.focusedButtonText,
            ]}>
            Recent PDFs
          </Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[
            styles.button,
            focusedButton === 'about' && styles.focusedButton,
          ]}
          onFocus={() => setFocusedButton('about')}
          onPress={handleAbout}>
          <Text
            style={[
              styles.buttonText,
              focusedButton === 'about' && styles.focusedButtonText,
            ]}>
            About
          </Text>
        </TouchableOpacity>
      </ScrollView>

      <Text style={styles.footer}>CloudWalker TV PDF Viewer</Text>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#1a1a1a',
    alignItems: 'center',
    justifyContent: 'center',
  },
  title: {
    fontSize: 48,
    fontWeight: 'bold',
    color: '#ffffff',
    marginBottom: 60,
  },
  buttonContainer: {
    alignItems: 'center',
    width: '100%',
  },
  button: {
    width: 400,
    height: 80,
    backgroundColor: '#2a2a2a',
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 30,
    borderWidth: 2,
    borderColor: '#2a2a2a',
  },
  focusedButton: {
    backgroundColor: '#3a3a3a',
    borderColor: '#4CAF50',
  },
  buttonText: {
    fontSize: 28,
    color: '#ffffff',
    fontWeight: '600',
  },
  focusedButtonText: {
    color: '#4CAF50',
  },
  footer: {
    position: 'absolute',
    bottom: 30,
    fontSize: 20,
    color: '#888888',
  },
});

export default HomeScreen;
