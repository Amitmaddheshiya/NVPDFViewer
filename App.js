/**
 * NV PDF Viewer - Android TV
 */

import React, { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  BackHandler,
  DeviceEventEmitter,
  Image,
  NativeModules,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

const { FilePickerModule, PDFRendererModule } = NativeModules;
const TIMER_VALUES = [5, 10, 15, 30, 60];
const fileNameFromUri = (uri) => decodeURIComponent(uri.split('/').pop() || 'PDF document');

function App() {
  const [playlist, setPlaylist] = useState([]);
  const [documentIndex, setDocumentIndex] = useState(0);
  const [pdfUri, setPdfUri] = useState(null);
  const [pageImage, setPageImage] = useState(null);
  const [page, setPage] = useState(0);
  const [pageCount, setPageCount] = useState(0);
  const [loading, setLoading] = useState(false);
  const [rotation, setRotation] = useState(0);
  const [cleanMode, setCleanMode] = useState(false);
  const [focusedControl, setFocusedControl] = useState(null);
  const [timerEnabled, setTimerEnabled] = useState(false);
  const [timerSeconds, setTimerSeconds] = useState(5);
  const [homeFocused, setHomeFocused] = useState(false);
  const nextSlideRef = useRef(null);

  const closeViewer = () => {
    setTimerEnabled(false);
    PDFRendererModule?.closeDocument?.().catch(() => {});
    setPlaylist([]);
    setDocumentIndex(0);
    setPdfUri(null);
    setPageImage(null);
    setPage(0);
    setPageCount(0);
    setRotation(0);
    setCleanMode(false);
    setFocusedControl(null);
  };

  useEffect(() => {
    const backHandler = BackHandler.addEventListener('hardwareBackPress', () => {
      if (cleanMode) {
        setCleanMode(false);
        return true;
      }
      if (pdfUri) {
        closeViewer();
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

  const loadDocument = async (index, startPage = 0, sourcePlaylist = playlist) => {
    const item = sourcePlaylist[index];
    if (!item || !PDFRendererModule) {
      return;
    }
    setLoading(true);
    try {
      await PDFRendererModule.closeDocument().catch(() => {});
      const document = await PDFRendererModule.openDocument(item.uri);
      const targetPage = startPage === 'last' ? Math.max(0, document.pageCount - 1) : startPage;
      const result = await PDFRendererModule.renderPage(targetPage, 1600, 900, 0);
      setDocumentIndex(index);
      setPdfUri(item.uri);
      setPageCount(document.pageCount);
      setPage(targetPage);
      setRotation(0);
      setPageImage(result.base64);
      setFocusedControl(targetPage > 0 ? 'previous' : 'next');
    } catch (error) {
      Alert.alert('Unable to open PDF', error.message || 'This file could not be opened.');
    } finally {
      setLoading(false);
    }
  };

  const openPdfs = async () => {
    if (!FilePickerModule?.pickPDFs || !PDFRendererModule) {
      Alert.alert('Setup error', 'The multi-PDF picker is not available in this build.');
      return;
    }
    setLoading(true);
    try {
      const selectedUris = await FilePickerModule.pickPDFs();
      const documents = Array.from(selectedUris || []).map((uri) => ({ uri, name: fileNameFromUri(uri) }));
      if (documents.length === 0) {
        return;
      }
      setPlaylist(documents);
      setTimerEnabled(false);
      setCleanMode(false);
      await loadDocument(0, 0, documents);
    } catch (error) {
      if (error.code !== 'CANCELLED') {
        Alert.alert('Unable to select PDFs', error.message || 'Please select one or more PDF files.');
      }
    } finally {
      setLoading(false);
    }
  };

  const nextSlide = async () => {
    if (loading) return;
    if (page < pageCount - 1) {
      await renderPage(page + 1);
    } else if (documentIndex < playlist.length - 1) {
      await loadDocument(documentIndex + 1);
    } else {
      setTimerEnabled(false);
    }
  };

  nextSlideRef.current = nextSlide;

  const previousSlide = async () => {
    if (loading) return;
    if (page > 0) {
      await renderPage(page - 1);
    } else if (documentIndex > 0) {
      await loadDocument(documentIndex - 1, 'last');
    }
  };

  // One timeout per displayed page gives every page its configured duration.
  useEffect(() => {
    if (!timerEnabled || !pdfUri || cleanMode || loading) return undefined;
    const timer = setTimeout(() => {
      nextSlideRef.current?.();
    }, timerSeconds * 1000);
    return () => clearTimeout(timer);
  }, [timerEnabled, timerSeconds, pdfUri, cleanMode, loading, page, documentIndex, pageCount, playlist]);

  // Fallback for TV launchers that do not send every native onFocus event to JS.
  useEffect(() => {
    if (!pdfUri || cleanMode) return undefined;
    const controls = [
      ...(page > 0 || documentIndex > 0 ? ['previous'] : []),
      ...(page < pageCount - 1 || documentIndex < playlist.length - 1 ? ['next'] : []),
      'rotate', 'timer', 'timerSet', 'fullscreen', 'exit',
    ];
    if (!controls.includes(focusedControl)) setFocusedControl(controls[0] || null);
    const subscription = DeviceEventEmitter.addListener('onHWKeyEvent', (event) => {
      if (Number(event?.eventKeyAction) !== 0 || !['left', 'right'].includes(event?.eventType)) return;
      setFocusedControl((current) => {
        const currentIndex = Math.max(0, controls.indexOf(current));
        const nextIndex = event.eventType === 'left'
          ? Math.max(0, currentIndex - 1)
          : Math.min(controls.length - 1, currentIndex + 1);
        return controls[nextIndex] || current;
      });
    });
    return () => subscription.remove();
  }, [cleanMode, documentIndex, focusedControl, page, pageCount, pdfUri, playlist.length]);

  const stopTimerForManualAction = (action) => {
    setTimerEnabled(false);
    action();
  };

  if (pdfUri) {
    const canGoPrevious = page > 0 || documentIndex > 0;
    const canGoNext = page < pageCount - 1 || documentIndex < playlist.length - 1;
    const viewerButton = (id, label, onPress, disabled = false) => (
      <TouchableOpacity
        key={id}
        hasTVPreferredFocus={id === focusedControl}
        accessible={true}
        accessibilityLabel={label}
        focusable={!disabled}
        disabled={disabled}
        style={[styles.control, focusedControl === id && styles.controlFocused, disabled && styles.controlDisabled]}
        onFocus={() => setFocusedControl(id)}
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
          onPress={() => {
            setTimerEnabled(false);
            setCleanMode(false);
          }}
          accessibilityLabel="Show PDF controls">
          {pageImage ? <Image source={{ uri: pageImage }} style={styles.pdfImage} resizeMode="stretch" /> : null}
          {loading ? <ActivityIndicator size="large" color="#fff" style={styles.loader} /> : null}
        </TouchableOpacity>
      );
    }

    return (
      <View style={styles.container}>
        <View style={styles.viewerHeader}>
          <View style={styles.headerText}>
            <Text style={styles.filename} numberOfLines={1}>{playlist[documentIndex]?.name || fileNameFromUri(pdfUri)}</Text>
            <Text style={styles.playlistInfo}>PDF {documentIndex + 1} of {playlist.length}</Text>
          </View>
          <Text style={styles.pageInfo}>Page {page + 1} / {pageCount}</Text>
        </View>
        <View style={styles.viewer}>
          {pageImage && !loading ? <Image source={{ uri: pageImage }} style={styles.pdfImage} resizeMode="stretch" /> : null}
          {loading ? <ActivityIndicator size="large" color="#fff" style={styles.loader} /> : null}
        </View>
        <View style={styles.controlPanel}>
          <Text style={styles.focusHint}>Remote focus: {focusedControl ? focusedControl.toUpperCase() : 'CONTROLS'}</Text>
          <View style={styles.controls}>
            {viewerButton('previous', 'Previous', () => stopTimerForManualAction(previousSlide), !canGoPrevious)}
            {viewerButton('next', 'Next', () => stopTimerForManualAction(nextSlide), !canGoNext)}
            {viewerButton('rotate', `Rotate ${rotation} deg`, () => stopTimerForManualAction(() => {
              const nextRotation = (rotation + 90) % 360;
              setRotation(nextRotation);
              renderPage(page, nextRotation);
            }))}
            {viewerButton('timer', timerEnabled ? 'Timer ON' : 'Timer OFF', () => setTimerEnabled((enabled) => !enabled))}
            {viewerButton('timerSet', `Timer ${timerSeconds}s`, () => {
              setTimerEnabled(false);
              setTimerSeconds((seconds) => TIMER_VALUES[(TIMER_VALUES.indexOf(seconds) + 1) % TIMER_VALUES.length]);
            })}
            {viewerButton('fullscreen', 'Full screen', () => stopTimerForManualAction(() => setCleanMode(true)))}
            {viewerButton('exit', 'Close', closeViewer)}
          </View>
        </View>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <View style={styles.content}>
        <Text style={styles.title}>NV PDF Viewer</Text>
        <Text style={styles.subtitle}>Select one or more PDF files</Text>
        <TouchableOpacity
          accessible={true}
          accessibilityLabel="Open PDF playlist"
          hasTVPreferredFocus={true}
          style={[styles.button, homeFocused && styles.buttonFocused]}
          onFocus={() => setHomeFocused(true)}
          onBlur={() => setHomeFocused(false)}
          onPress={openPdfs}>
          <Text style={styles.buttonText}>Open PDFs</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#000' },
  content: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  title: { fontSize: 48, color: '#fff', fontWeight: 'bold', marginBottom: 20 },
  subtitle: { fontSize: 24, color: '#ccc', marginBottom: 60 },
  button: { backgroundColor: '#2196F3', paddingVertical: 20, paddingHorizontal: 40, borderRadius: 12, borderWidth: 3, borderColor: 'transparent' },
  buttonFocused: { borderColor: '#FFD600', backgroundColor: '#0D6EFD', elevation: 12, transform: [{ scale: 1.08 }] },
  buttonText: { color: '#fff', fontSize: 24, fontWeight: 'bold' },
  viewerHeader: { flexDirection: 'row', padding: 20, backgroundColor: '#202020', alignItems: 'center' },
  headerText: { flex: 1 },
  filename: { color: '#fff', fontSize: 24, fontWeight: 'bold' },
  playlistInfo: { color: '#FFD600', fontSize: 16, marginTop: 4, fontWeight: '700' },
  pageInfo: { color: '#ddd', fontSize: 20, marginLeft: 24 },
  viewer: { flex: 1, alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  pdfImage: { width: '100%', height: '100%' },
  controlPanel: { paddingTop: 8, paddingBottom: 16, backgroundColor: '#202020' },
  focusHint: { color: '#FFD600', fontSize: 16, fontWeight: '800', textAlign: 'center', marginBottom: 8, letterSpacing: 1 },
  controls: { flexDirection: 'row', justifyContent: 'center', gap: 10, flexWrap: 'wrap' },
  control: { minWidth: 126, alignItems: 'center', backgroundColor: '#303030', borderRadius: 8, paddingVertical: 14, paddingHorizontal: 14, borderWidth: 4, borderColor: '#575757' },
  controlFocused: { backgroundColor: '#0D6EFD', borderColor: '#FFD600', shadowColor: '#FFD600', shadowOpacity: 1, shadowRadius: 10, elevation: 12, transform: [{ scale: 1.06 }] },
  controlDisabled: { opacity: 0.35 },
  controlText: { color: '#FFFFFF', fontSize: 18, fontWeight: '700' },
  cleanViewer: { flex: 1, backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  loader: { position: 'absolute' },
});

export default App;
