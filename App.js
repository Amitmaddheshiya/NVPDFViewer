/**
 * NV PDF Viewer
 * React Native PDF Viewer for Android TV
 */

import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  BackHandler,
  Image,
  NativeModules,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

const { FilePickerModule, PDFRendererModule } = NativeModules;

function App() {
  const [focused, setFocused] = useState(false);
  const [pdfUri, setPdfUri] = useState(null);
  const [pageImage, setPageImage] = useState(null);
  const [page, setPage] = useState(0);
  const [pageCount, setPageCount] = useState(0);
  const [loading, setLoading] = useState(false);
  const [rotation, setRotation] = useState(0);
  const [cleanMode, setCleanMode] = useState(false);
  const [focusedControl, setFocusedControl] = useState(null);

  useEffect(() => {
    const backHandler = BackHandler.addEventListener('hardwareBackPress', () => {
      if (cleanMode) {
        setCleanMode(false);
        return true;
      }
      if (pdfUri) {
        PDFRendererModule?.closeDocument?.().catch(() => {});
        setPdfUri(null);
        setPageImage(null);
        return true;
      }
      BackHandler.exitApp();
      return true;
    });
    return () => backHandler.remove();
  }, [cleanMode, pdfUri]);

  const renderPage = async (pageIndex, pageRotation = rotation) => {
    setLoading(true);
    try {
      const result = await PDFRendererModule.renderPage(pageIndex, 1600, 900, pageRotation);
      setPageImage(result.base64);
      setPage(pageIndex);
    } catch (error) {
      Alert.alert('PDF error', error.message || 'Unable to render this PDF.');
    } finally {
      setLoading(false);
    }
  };

  const openPdf = async () => {
    if (!FilePickerModule || !PDFRendererModule) {
      Alert.alert('Setup error', 'PDF modules are not available in this build.');
      return;
    }
    setLoading(true);
    try {
      const uri = await FilePickerModule.pickPDF();
      const document = await PDFRendererModule.openDocument(uri);
      setPdfUri(uri);
      setPageCount(document.pageCount);
      setRotation(0);
      setCleanMode(false);
      await renderPage(0);
    } catch (error) {
      if (error.code !== 'CANCELLED') {
        Alert.alert('Unable to open PDF', error.message || 'Please choose a valid PDF file.');
      }
    } finally {
      setLoading(false);
    }
  };

  if (pdfUri) {
    const viewerButton = (id, label, onPress, disabled = false) => (
      <TouchableOpacity
        key={id}
        hasTVPreferredFocus={id === (page === 0 ? 'next' : 'previous')}
        accessible={true}
        accessibilityLabel={label}
        focusable={!disabled}
        disabled={disabled}
        style={[
          styles.control,
          focusedControl === id && styles.controlFocused,
          disabled && styles.controlDisabled,
        ]}
        onFocus={() => setFocusedControl(id)}
        onBlur={() => setFocusedControl((current) => current === id ? null : current)}
        onPress={onPress}>
        <Text style={styles.controlText}>{label}</Text>
      </TouchableOpacity>
    );

    if (cleanMode) {
      return (
        <TouchableOpacity
          hasTVPreferredFocus={true}
          activeOpacity={1}
          style={styles.cleanViewer}
          onPress={() => setCleanMode(false)}
          accessibilityLabel="Show PDF controls">
          {pageImage ? <Image source={{ uri: pageImage }} style={styles.pdfImage} resizeMode="stretch" /> : null}
          {loading ? <ActivityIndicator size="large" color="#fff" style={styles.loader} /> : null}
        </TouchableOpacity>
      );
    }

    return (
      <View style={styles.container}>
        <View style={styles.viewerHeader}>
          <Text style={styles.filename} numberOfLines={1}>{pdfUri.split('/').pop()}</Text>
          <Text style={styles.pageInfo}>Page {page + 1} / {pageCount}</Text>
        </View>
        <View style={styles.viewer}>
          {loading ? <ActivityIndicator size="large" color="#fff" /> : null}
          {pageImage && !loading ? <Image source={{ uri: pageImage }} style={styles.pdfImage} resizeMode="stretch" /> : null}
        </View>
        <View style={styles.controls}>
          {viewerButton('previous', '‹  Previous', () => renderPage(page - 1), page === 0)}
          {viewerButton('next', 'Next  ›', () => renderPage(page + 1), page >= pageCount - 1)}
          {viewerButton('rotate', `↻  Rotate ${rotation}°`, () => {
            const nextRotation = (rotation + 90) % 360;
            setRotation(nextRotation);
            renderPage(page, nextRotation);
          })}
          {viewerButton('fullscreen', '⛶  Full screen', () => setCleanMode(true))}
          {viewerButton('exit', 'Exit', () => BackHandler.exitApp())}
        </View>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <View style={styles.content}>
        <Text style={styles.title}>NV PDF Viewer</Text>
        <Text style={styles.subtitle}>Select a PDF file to view</Text>
        <TouchableOpacity
          accessible={true}
          accessibilityLabel="Open PDF"
          accessibilityHint="Opens file picker to select PDF"
          hasTVPreferredFocus={true}
          style={[styles.button, focused && styles.buttonFocused]}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          onPress={openPdf}>
          <Text style={styles.buttonText}>Open PDF</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  content: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  title: {
    fontSize: 48,
    color: '#fff',
    fontWeight: 'bold',
    marginBottom: 20,
  },
  subtitle: {
    fontSize: 24,
    color: '#ccc',
    marginBottom: 60,
  },
  button: {
    backgroundColor: '#2196F3',
    paddingVertical: 20,
    paddingHorizontal: 40,
    borderRadius: 12,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  buttonFocused: {
    borderColor: '#fff',
    backgroundColor: '#1976D2',
  },
  buttonText: {
    color: '#fff',
    fontSize: 24,
    fontWeight: 'bold',
  },
  viewerHeader: { flexDirection: 'row', padding: 24, backgroundColor: '#202020', alignItems: 'center' },
  filename: { flex: 1, color: '#fff', fontSize: 24, fontWeight: 'bold' },
  pageInfo: { color: '#ddd', fontSize: 20, marginLeft: 24 },
  viewer: { flex: 1, alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  pdfImage: { width: '100%', height: '100%' },
  controls: { flexDirection: 'row', justifyContent: 'center', padding: 20, gap: 16, backgroundColor: '#202020' },
  control: { backgroundColor: '#303030', borderRadius: 8, paddingVertical: 16, paddingHorizontal: 22, borderWidth: 3, borderColor: '#303030' },
  controlFocused: {
    backgroundColor: '#0D6EFD',
    borderColor: '#FFD600',
    shadowColor: '#FFD600',
    shadowOpacity: 1,
    shadowRadius: 10,
    elevation: 12,
    transform: [{ scale: 1.08 }],
  },
  controlDisabled: { opacity: 0.35 },
  controlText: { color: '#FFFFFF', fontSize: 20, fontWeight: '700' },
  cleanViewer: { flex: 1, backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  loader: { position: 'absolute' },
});

export default App;
