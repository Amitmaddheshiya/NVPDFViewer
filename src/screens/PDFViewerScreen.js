import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Image,
  ActivityIndicator,
  BackHandler,
} from 'react-native';
import { NativeModules } from 'react-native';

const { PDFRendererModule } = NativeModules;

const PDFViewerScreen = ({ navigation, route }) => {
  const [pdfUri] = useState(route.params?.uri);
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [pageImage, setPageImage] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [zoom, setZoom] = useState(1);
  const [focusedControl, setFocusedControl] = useState(null);

  const renderPage = useCallback(async (pageIndex, pageZoom) => {
    setLoading(true);
    setError(null);
    try {
      const result = await PDFRendererModule.renderPage(
        pageIndex,
        1920 * pageZoom,
        1080 * pageZoom
      );
      setPageImage(result.base64);
      setCurrentPage(pageIndex);
    } catch (err) {
      setError('Failed to render page: ' + err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const openPDF = useCallback(async (uri) => {
    setLoading(true);
    setError(null);
    try {
      const result = await PDFRendererModule.openDocument(uri);
      setTotalPages(result.pageCount);
      if (result.pageCount > 0) {
        await renderPage(0, 1);
      }
    } catch (err) {
      setError('Failed to open PDF: ' + err.message);
    } finally {
      setLoading(false);
    }
  }, [renderPage]);

  const handleBackPress = useCallback(() => {
    PDFRendererModule.closeDocument(() => {});
    navigation.goBack();
    return true;
  }, [navigation]);

  useEffect(() => {
    if (pdfUri) {
      openPDF(pdfUri);
    }
    return () => {
      PDFRendererModule.closeDocument(() => {});
    };
  }, [openPDF, pdfUri]);

  useEffect(() => {
    const backHandler = BackHandler.addEventListener(
      'hardwareBackPress',
      handleBackPress
    );
    return () => backHandler.remove();
  }, [handleBackPress]);

  const handleNextPage = () => {
    if (currentPage < totalPages - 1) {
      renderPage(currentPage + 1, zoom);
    }
  };

  const handlePreviousPage = () => {
    if (currentPage > 0) {
      renderPage(currentPage - 1, zoom);
    }
  };

  const handleZoomIn = () => {
    const newZoom = Math.min(zoom + 0.25, 3);
    setZoom(newZoom);
    renderPage(currentPage, newZoom);
  };

  const handleZoomOut = () => {
    const newZoom = Math.max(zoom - 0.25, 0.5);
    setZoom(newZoom);
    renderPage(currentPage, newZoom);
  };

  const handleFitToScreen = () => {
    setZoom(1);
    renderPage(currentPage, 1);
  };

  if (error) {
    return (
      <View style={styles.container}>
        <Text style={styles.errorText}>{error}</Text>
        <TouchableOpacity
          style={[styles.controlButton, focusedControl === 'back' && styles.focusedButton]}
          onFocus={() => setFocusedControl('back')}
          onPress={handleBackPress}>
          <Text style={styles.controlButtonText}>Back</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.filename}>
          {pdfUri ? pdfUri.split('/').pop() : 'PDF Viewer'}
        </Text>
        <Text style={styles.pageInfo}>
          Page {currentPage + 1} of {totalPages}
        </Text>
      </View>

      <View style={styles.pdfContainer}>
        {loading && <ActivityIndicator size="large" color="#4CAF50" />}
        {pageImage && !loading && (
          <Image
            source={{ uri: pageImage }}
            style={styles.pdfImage}
            resizeMode="contain"
          />
        )}
      </View>

      <View style={styles.controls}>
        <TouchableOpacity
          style={[styles.controlButton, focusedControl === 'prev' && styles.focusedButton]}
          onFocus={() => setFocusedControl('prev')}
          onPress={handlePreviousPage}
          disabled={currentPage === 0}>
          <Text style={styles.controlButtonText}>Previous</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.controlButton, focusedControl === 'zoomOut' && styles.focusedButton]}
          onFocus={() => setFocusedControl('zoomOut')}
          onPress={handleZoomOut}
          disabled={zoom <= 0.5}>
          <Text style={styles.controlButtonText}>Zoom -</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.controlButton, focusedControl === 'fit' && styles.focusedButton]}
          onFocus={() => setFocusedControl('fit')}
          onPress={handleFitToScreen}>
          <Text style={styles.controlButtonText}>Fit</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.controlButton, focusedControl === 'zoomIn' && styles.focusedButton]}
          onFocus={() => setFocusedControl('zoomIn')}
          onPress={handleZoomIn}
          disabled={zoom >= 3}>
          <Text style={styles.controlButtonText}>Zoom +</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.controlButton, focusedControl === 'next' && styles.focusedButton]}
          onFocus={() => setFocusedControl('next')}
          onPress={handleNextPage}
          disabled={currentPage === totalPages - 1}>
          <Text style={styles.controlButtonText}>Next</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#1a1a1a',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 20,
    backgroundColor: '#2a2a2a',
  },
  filename: {
    fontSize: 24,
    color: '#ffffff',
    fontWeight: '600',
    flex: 1,
  },
  pageInfo: {
    fontSize: 24,
    color: '#4CAF50',
    fontWeight: '600',
  },
  pdfContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 20,
  },
  pdfImage: {
    width: '100%',
    height: '100%',
  },
  controls: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    alignItems: 'center',
    padding: 20,
    backgroundColor: '#2a2a2a',
  },
  controlButton: {
    width: 150,
    height: 60,
    backgroundColor: '#3a3a3a',
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 2,
    borderColor: '#3a3a3a',
  },
  focusedButton: {
    borderColor: '#4CAF50',
    backgroundColor: '#4a4a4a',
  },
  controlButtonText: {
    fontSize: 20,
    color: '#ffffff',
    fontWeight: '600',
  },
  errorText: {
    fontSize: 24,
    color: '#ff4444',
    textAlign: 'center',
    padding: 40,
  },
});

export default PDFViewerScreen;
