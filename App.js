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
  TextInput,
  TouchableOpacity,
  UIManager,
  findNodeHandle,
  useWindowDimensions,
  View,
  requireNativeComponent,
} from 'react-native';

const { FilePickerModule, PDFRendererModule, CmsServerModule } = NativeModules;
const TIMER_VALUES = [5, 10, 15, 30, 60];
const AdsVideoView = requireNativeComponent('AdsVideoView');
const fileNameFromUri = (uri) => decodeURIComponent(uri.split('/').pop() || 'PDF document');

function App() {
  const { width: screenWidth, height: screenHeight } = useWindowDimensions();
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
  const [libraryReady, setLibraryReady] = useState(false);
  const [settingsReady, setSettingsReady] = useState(false);
  const [cmsQr, setCmsQr] = useState(null);
  const [appPassword, setAppPassword] = useState('');
  const [appAuthenticated, setAppAuthenticated] = useState(false);
  const [loginError, setLoginError] = useState('');
  const [homeFocused, setHomeFocused] = useState('pdf');
  const [adsPlaylist, setAdsPlaylist] = useState([]);
  const [adsActive, setAdsActive] = useState(false);
  const [adsIndex, setAdsIndex] = useState(0);
  const [adsImage, setAdsImage] = useState(null);
  const [adsPdfPage, setAdsPdfPage] = useState(0);
  const [adsPdfPageCount, setAdsPdfPageCount] = useState(0);
  const [adsSeconds, setAdsSeconds] = useState(10);
  const [adsRotation, setAdsRotation] = useState(0);
  const [adsAutoSuppressed, setAdsAutoSuppressed] = useState(false);
  // Incremented for every item transition, including a one-item playlist
  // wrapping from its end back to itself. Native VideoView otherwise ignores
  // an identical source URI and would stop after the first playback.
  const [adsCycle, setAdsCycle] = useState(0);
  // Keep the previous still visible until Android's VideoView has decoded the
  // first frame of the next video. This prevents the usual black flash between
  // an image/PDF and a video.
  const [adsVideoReady, setAdsVideoReady] = useState(false);
  const nextSlideRef = useRef(null);
  const loadDocumentRef = useRef(null);
  const cmsCommandRef = useRef(null);
  const cmsPollBusyRef = useRef(false);
  const adsNextRef = useRef(null);
  const adsVideoViewRef = useRef(null);
  const startAdsRef = useRef(null);
  // A CMS command is sent through the native event channel immediately and is
  // also left in the native queue as a delivery fallback. Remember its id so
  // the fallback cannot execute the same action a second time.
  const processedCmsCommandIdsRef = useRef(new Set());
  const playlistRef = useRef([]);
  playlistRef.current = playlist;

  const closeViewer = () => {
    setTimerEnabled(false);
    PDFRendererModule?.closeDocument?.().catch(() => {});
    setDocumentIndex(0);
    setPdfUri(null);
    setPageImage(null);
    setPage(0);
    setPageCount(0);
    setRotation(0);
    setCleanMode(false);
    setFocusedControl(null);
  };

  const stopAds = async (manual = true) => {
    if (manual) setAdsAutoSuppressed(true);
    setAdsActive(false);
    setAdsImage(null);
    setAdsVideoReady(false);
    setAdsRotation(0);
    setAdsPdfPage(0);
    setAdsPdfPageCount(0);
    await PDFRendererModule?.closeDocument?.().catch(() => {});
  };

  useEffect(() => {
    const backHandler = BackHandler.addEventListener('hardwareBackPress', () => {
      if (cleanMode) {
        setCleanMode(false);
        return true;
      }
      if (adsActive) {
        stopAds();
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
  }, [adsActive, cleanMode, pdfUri]);

  // Every library item points to app-private storage, so it survives source
  // picker permissions, app restarts, and TV reboots.
  useEffect(() => {
    let active = true;
    const loadSavedPlaylist = CmsServerModule?.getSavedPlaylist
      ? CmsServerModule.getSavedPlaylist()
      : Promise.resolve([]);
    const loadSettings = CmsServerModule?.getViewerSettings
      ? CmsServerModule.getViewerSettings()
      : Promise.resolve({ timerEnabled: false, timerSeconds: 5 });
    Promise.all([loadSavedPlaylist, loadSettings])
      .then(([items, settings]) => {
        if (!active) return;
        const saved = Array.from(items || []);
        playlistRef.current = saved;
        setPlaylist(saved);
        setTimerEnabled(Boolean(settings?.timerEnabled));
        setTimerSeconds(Math.max(1, Number(settings?.timerSeconds) || 5));
        if (saved.length > 0) loadDocumentRef.current?.(0, 0, saved);
      })
      .catch(() => {})
      .finally(() => {
        if (active) {
          setLibraryReady(true);
          setSettingsReady(true);
        }
      });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (libraryReady) CmsServerModule?.savePlaylist?.(playlist);
  }, [libraryReady, playlist]);

  useEffect(() => {
    if (settingsReady) CmsServerModule?.saveViewerSettings?.(timerEnabled, timerSeconds);
  }, [settingsReady, timerEnabled, timerSeconds]);

  useEffect(() => {
    CmsServerModule?.getCmsQrCode?.().then((result) => setCmsQr(result)).catch(() => {});
  }, []);

  // First login uses the TV-IP code and saves it in app-private storage. On
  // every later launch this saved credential unlocks the viewer immediately;
  // Android Clear Data or uninstall removes it and restores first-login flow.
  useEffect(() => {
    CmsServerModule?.getSavedLoginPassword?.()
      .then((savedPassword) => {
        if (typeof savedPassword === 'string' && savedPassword.length) {
          setAppPassword(savedPassword);
          setAppAuthenticated(true);
        }
      })
      .catch(() => {});
  }, []);

  // TV file-picker apps are often absent. Ask once for Android's official
  // storage permission page; after owner approval the native scanner can read
  // /storage/<USB-VOLUME>/Ads directly.
  useEffect(() => {
    FilePickerModule?.requestUsbAdsAccess?.().catch(() => {});
  }, []);

  // USB insertion is not delivered reliably by every Android TV launcher.
  // A short foreground rescan is the portable fallback: a newly inserted
  // pendrive begins playback without the owner opening a picker.
  useEffect(() => {
    if (!appAuthenticated || adsActive || adsAutoSuppressed || !FilePickerModule?.scanAdsFolder) return undefined;
    let cancelled = false;
    const scan = async () => {
      try {
        const files = await FilePickerModule.scanAdsFolder();
        if (!cancelled && files?.length) await startAdsRef.current?.(files);
      } catch (_) { /* Pendrive/Ads folder not present yet. */ }
    };
    scan();
    const interval = setInterval(scan, 3000);
    return () => { cancelled = true; clearInterval(interval); };
  }, [adsActive, adsAutoSuppressed, appAuthenticated]);

  // Immediate USB mount/unmount signal from Android. The 2-second settle time
  // lets slower TV firmware finish mounting before we ask for the Ads files;
  // the interval above remains the recovery path if a launcher suppresses the
  // broadcast.
  useEffect(() => {
    const subscription = DeviceEventEmitter.addListener('usbAdsStorageChanged', () => {
      if (!appAuthenticated || adsAutoSuppressed) return;
      setTimeout(async () => {
        try {
          const files = await FilePickerModule?.scanAdsFolder?.();
          if (files?.length) await startAdsRef.current?.(files);
          else if (adsActive) await stopAds(false);
        } catch (_) {
          if (adsActive) stopAds(false);
        }
      }, 2000);
    });
    return () => subscription.remove();
  }, [adsActive, adsAutoSuppressed, appAuthenticated]);

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

  loadDocumentRef.current = loadDocument;

  const openPdfs = async () => {
    if (!FilePickerModule?.pickPDFs || !PDFRendererModule || !CmsServerModule?.importPdf) {
      Alert.alert('Setup error', 'The multi-PDF picker is not available in this build.');
      return;
    }
    setLoading(true);
    try {
      const selectedUris = await FilePickerModule.pickPDFs();
      const documents = [];
      for (const uri of Array.from(selectedUris || [])) {
        const imported = await CmsServerModule.importPdf(uri, fileNameFromUri(uri));
        documents.push({ uri: imported.uri, name: imported.name });
      }
      if (documents.length === 0) {
        return;
      }
      playlistRef.current = documents;
      setPlaylist(documents);
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
    } else if (timerEnabled && playlist.length > 0) {
      // Timer mode is a continuous presentation: after the last page of the
      // last PDF, return to the first page of the first PDF and keep running.
      await loadDocument(0, 0);
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

  // These are the single source of truth for both TV buttons and CMS actions.
  // Keeping both entry points on the same functions prevents CMS behavior from
  // drifting from the on-screen remote controls.
  const previousAction = () => stopTimerForManualAction(previousSlide);
  const nextAction = () => stopTimerForManualAction(nextSlide);
  const rotateAction = () => stopTimerForManualAction(() => {
    const nextRotation = (rotation + 90) % 360;
    setRotation(nextRotation);
    return renderPage(page, nextRotation);
  });
  const fullscreenAction = () => setCleanMode((value) => !value);
  const timerAction = () => setTimerEnabled((enabled) => !enabled);
  const timerSecondsAction = (seconds) => {
    setTimerEnabled(false);
    setTimerSeconds(Math.max(1, Math.min(3600, Math.floor(Number(seconds)) || 5)));
  };

  const appendCmsPdf = async (item) => {
    const updatedPlaylist = [...playlistRef.current, item];
    playlistRef.current = updatedPlaylist;
    setPlaylist(updatedPlaylist);
    CmsServerModule?.savePlaylist?.(updatedPlaylist);
    if (!pdfUri) {
      await loadDocument(0, 0, updatedPlaylist);
    }
  };

  const clearLibrary = async () => {
    setTimerEnabled(false);
    await PDFRendererModule?.closeDocument?.().catch(() => {});
    await CmsServerModule?.clearLibrary?.();
    playlistRef.current = [];
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

  const showAd = async (index, source = adsPlaylist) => {
    if (!source.length) throw new Error('No supported files were found in the USB Ads folder.');
    const safeIndex = ((index % source.length) + source.length) % source.length;
    const item = source[safeIndex];
    setAdsCycle((value) => value + 1);
    setAdsIndex(safeIndex);
    setAdsVideoReady(false);
    // Do not clear adsImage here. While a PDF is being rendered (or the next
    // video is buffering), it acts as a stable previous-frame backdrop instead
    // of exposing a loader/black screen.
    setAdsPdfPage(0);
    setAdsPdfPageCount(0);
    if (item.type === 'pdf') {
      setLoading(true);
      try {
        await PDFRendererModule?.closeDocument?.().catch(() => {});
        const document = await PDFRendererModule.openDocument(item.uri);
        const pageResult = await PDFRendererModule.renderPage(0, 1600, 900, 0);
        setAdsImage(pageResult.base64);
        setAdsPdfPageCount(document.pageCount);
      } finally { setLoading(false); }
    } else if (item.type === 'image') {
      setAdsImage(item.uri);
    }
  };

  const startAds = async (items) => {
    const source = Array.from(items || []);
    if (!source.length) throw new Error('No image, video, or PDF files found in Ads folder.');
    setTimerEnabled(false);
    setAdsAutoSuppressed(false);
    setCleanMode(false);
    setAdsRotation(0);
    // Ads owns the renderer while playing. Clear the regular viewer state so
    // a stopped USB loop never exposes a stale PDF render underneath it.
    setPdfUri(null);
    setPageImage(null);
    setPage(0);
    setPageCount(0);
    setRotation(0);
    setAdsPlaylist(source);
    setAdsActive(true);
    await showAd(0, source);
  };
  startAdsRef.current = startAds;

  const scanUsbAds = async () => {
    if (!FilePickerModule?.scanAdsFolder) throw new Error('USB Ads scanner is unavailable in this build.');
    const files = await FilePickerModule.scanAdsFolder();
    await startAds(files);
  };

  const openSavedPdfViewer = async () => {
    const saved = playlistRef.current;
    if (!saved.length) throw new Error('No saved PDF is available. Upload or open a PDF first.');
    // Choosing the PDF viewer explicitly must not be immediately overridden
    // by the USB auto-play policy.
    setAdsAutoSuppressed(true);
    await stopAds(false);
    setCleanMode(false);
    await loadDocument(0, 0, saved);
  };

  const chooseUsbAdsFolder = async () => {
    if (!FilePickerModule?.requestUsbAdsAccess) throw new Error('USB Ads storage access is unavailable in this build.');
    const allowed = await FilePickerModule.requestUsbAdsAccess();
    if (!allowed) throw new Error('Allow storage access on the Android settings page, then return to this app. USB Ads will start automatically when pendrive Ads folder is detected.');
    try {
      await scanUsbAds();
    } catch (error) {
      // If no ads found, still activate ads mode to show instruction screen
      setAdsAutoSuppressed(true);
      setCleanMode(false);
      setAdsRotation(0);
      setPdfUri(null);
      setPageImage(null);
      setPage(0);
      setPageCount(0);
      setRotation(0);
      setAdsPlaylist([]);
      setAdsActive(true);
    }
  };

  const nextAd = async () => {
    const item = adsPlaylist[adsIndex];
    if (!item || loading) return;
    if (item.type === 'pdf' && adsPdfPage + 1 < adsPdfPageCount) {
      setLoading(true);
      try {
        const pageResult = await PDFRendererModule.renderPage(adsPdfPage + 1, 1600, 900, 0);
        setAdsImage(pageResult.base64);
        setAdsPdfPage((value) => value + 1);
      } finally { setLoading(false); }
      return;
    }
    await showAd(adsIndex + 1);
  };
  adsNextRef.current = nextAd;

  const previousAd = async () => {
    if (!adsPlaylist.length || loading) return;
    await showAd(adsIndex - 1);
  };

  const rotateAds = () => setAdsRotation((value) => (value + 90) % 360);

  cmsCommandRef.current = async ({ id, type, payload }) => {
    if (!id) return;
    if (processedCmsCommandIdsRef.current.has(id)) return;
    processedCmsCommandIdsRef.current.add(id);
    // Keep the dedupe set bounded during long-running TV sessions.
    if (processedCmsCommandIdsRef.current.size > 200) {
      const oldestId = processedCmsCommandIdsRef.current.values().next().value;
      processedCmsCommandIdsRef.current.delete(oldestId);
    }
    try {
      const data = JSON.parse(payload || '{}');
      // A browser that is authenticated in the CMS is allowed to operate the
      // TV viewer. Unlock the TV UI before applying its command so a PDF loaded
      // from CMS is immediately visible instead of remaining behind login.
      setAppAuthenticated(true);
      if (type === 'uploadPdf' && data.uri) {
        await appendCmsPdf({ uri: data.uri, name: data.name || fileNameFromUri(data.uri) });
      } else if (type === 'uploadAds' && data.uri) {
        // Rescan after every CMS upload. The scanner maintains the intended
        // source priority: pendrive Ads, public TV Ads, then CMS-saved Ads.
        await scanUsbAds();
      } else if (type === 'previous') {
        if (!pdfUri) throw new Error('Open a PDF before using Previous.');
        await previousAction();
      } else if (type === 'next') {
        if (!pdfUri) throw new Error('Open a PDF before using Next.');
        await nextAction();
      } else if (type === 'rotate' && pdfUri) {
        await rotateAction();
      } else if (type === 'fullscreen' && pdfUri) {
        fullscreenAction();
      } else if (type === 'timer') {
        if (!pdfUri) throw new Error('Open a PDF before turning on the timer.');
        timerAction();
      } else if (type === 'timerSeconds') {
        timerSecondsAction(data.seconds);
      } else if (type === 'close') {
        closeViewer();
      } else if (type === 'clearLibrary') {
        await clearLibrary();
      } else if (type === 'openPdf') {
        await openSavedPdfViewer();
      } else if (type === 'adsScan') {
        await scanUsbAds();
      } else if (type === 'adsStart') {
        await scanUsbAds();
      } else if (type === 'adsStop') {
        await stopAds();
      } else if (type === 'adsNext') {
        if (!adsActive) throw new Error('USB Ads mode is not playing.');
        await nextAd();
      } else if (type === 'adsPrevious') {
        if (!adsActive) throw new Error('USB Ads mode is not playing.');
        await previousAd();
      } else if (type === 'adsRotate') {
        if (!adsActive) throw new Error('USB Ads mode is not playing.');
        rotateAds();
      } else if (type === 'adsSeconds') {
        setAdsSeconds(Math.max(1, Math.min(3600, Math.floor(Number(data.seconds)) || 10)));
      } else if (type === 'clearAds') {
        if (!FilePickerModule?.clearCmsAds) throw new Error('TV Ads storage is unavailable in this build.');
        await FilePickerModule.clearCmsAds();
        await stopAds(false);
      } else if (type === 'reloadApp') {
        // Reload the app by restarting the activity
        if (CmsServerModule?.reloadApp) {
          CmsServerModule.reloadApp();
        } else {
          // Fallback: reload the bundle
          if (typeof __DEV__ !== 'undefined' && __DEV__) {
            location.reload();
          }
        }
      } else if (type === 'adsForward') {
        if (!adsActive) throw new Error('USB Ads mode is not playing.');
        if (adsPlaylist[adsIndex]?.type !== 'video') throw new Error('Seek is only available for videos.');
        if (!adsVideoReady || !adsVideoViewRef.current) throw new Error('Video player not ready. Wait for video to start playing.');
        const seekMs = Math.max(1000, Math.min(60000, Math.floor(Number(data.seconds) || 10) * 1000));
        const reactTag = findNodeHandle(adsVideoViewRef.current);
        if (!reactTag) throw new Error('Video player not ready. Try again.');
        UIManager.dispatchViewManagerCommand(
          reactTag,
          UIManager.getViewManagerConfig('AdsVideoView').Commands.seekForward,
          [seekMs]
        );
      } else if (type === 'adsBackward') {
        if (!adsActive) throw new Error('USB Ads mode is not playing.');
        if (adsPlaylist[adsIndex]?.type !== 'video') throw new Error('Seek is only available for videos.');
        if (!adsVideoReady || !adsVideoViewRef.current) throw new Error('Video player not ready. Wait for video to start playing.');
        const seekMs = Math.max(1000, Math.min(60000, Math.floor(Number(data.seconds) || 10) * 1000));
        const reactTag = findNodeHandle(adsVideoViewRef.current);
        if (!reactTag) throw new Error('Video player not ready. Try again.');
        UIManager.dispatchViewManagerCommand(
          reactTag,
          UIManager.getViewManagerConfig('AdsVideoView').Commands.seekBackward,
          [seekMs]
        );
      } else {
        throw new Error('This command is unavailable because no PDF is open.');
      }
      CmsServerModule?.completeCommand?.(id, true, '');
    } catch (error) {
      CmsServerModule?.completeCommand?.(id, false, error.message || 'TV command failed');
    }
  };

  useEffect(() => {
    CmsServerModule?.getStatus?.().catch(() => {});
    // Native pushes the command as soon as the HTTP request arrives. Polling
    // below remains as a recovery path if Android temporarily cannot emit an
    // event (for example while the React bridge is resuming).
    const commandSubscription = DeviceEventEmitter.addListener('cmsCommand', (command) => {
      cmsCommandRef.current?.(command);
    });
    const pollCommands = async () => {
      if (cmsPollBusyRef.current || !CmsServerModule?.getNextCommand) return;
      cmsPollBusyRef.current = true;
      try {
        const command = await CmsServerModule.getNextCommand();
        if (command) await cmsCommandRef.current?.(command);
      } catch (_) {
        // The next poll retries automatically if the bridge is briefly busy.
      } finally {
        cmsPollBusyRef.current = false;
      }
    };
    pollCommands();
    const interval = setInterval(pollCommands, 150);
    return () => {
      clearInterval(interval);
      commandSubscription.remove();
    };
  }, []);

  useEffect(() => {
    CmsServerModule?.updateState?.({
      viewerOpen: Boolean(pdfUri) || adsActive,
      filename: adsActive ? (adsPlaylist[adsIndex]?.name || 'USB Ad') : (pdfUri ? (playlist[documentIndex]?.name || fileNameFromUri(pdfUri)) : ''),
      documentIndex: adsActive ? adsIndex + 1 : (pdfUri ? documentIndex + 1 : 0),
      documentCount: adsActive ? adsPlaylist.length : playlist.length,
      page: adsActive && adsPlaylist[adsIndex]?.type === 'pdf' ? adsPdfPage + 1 : (pdfUri ? page + 1 : 0),
      pageCount: adsActive && adsPlaylist[adsIndex]?.type === 'pdf' ? adsPdfPageCount : pageCount,
      rotation,
      fullscreen: cleanMode,
      timerEnabled,
      timerSeconds,
      timerLoop: true,
      loading,
      adsActive,
      adsCount: adsPlaylist.length,
      adsIndex: adsIndex + 1,
      adsSeconds,
      adsRotation,
    });
  }, [adsActive, adsIndex, adsPdfPage, adsPdfPageCount, adsPlaylist, adsRotation, adsSeconds, cleanMode, documentIndex, loading, page, pageCount, pdfUri, playlist, rotation, timerEnabled, timerSeconds]);

  // One timeout per displayed page gives every page its configured duration.
  useEffect(() => {
    if (!timerEnabled || !pdfUri || loading) return undefined;
    const timer = setTimeout(() => {
      nextSlideRef.current?.();
    }, timerSeconds * 1000);
    return () => clearTimeout(timer);
  }, [timerEnabled, timerSeconds, pdfUri, cleanMode, loading, page, documentIndex, pageCount, playlist]);

  // Images and each PDF page use the ad duration; native videos advance only
  // after their completion callback, so they are never cut off mid-playback.
  useEffect(() => {
    const item = adsPlaylist[adsIndex];
    if (!adsActive || !item || item.type === 'video' || loading) return undefined;
    const timeout = setTimeout(() => adsNextRef.current?.(), adsSeconds * 1000);
    return () => clearTimeout(timeout);
  }, [adsActive, adsCycle, adsImage, adsIndex, adsPdfPage, adsPlaylist, adsSeconds, loading]);

  // Fallback for TV launchers that do not send every native onFocus event to JS.
  useEffect(() => {
    if (!pdfUri || cleanMode) return undefined;
    const controls = [
      ...(page > 0 || documentIndex > 0 ? ['previous'] : []),
      ...(page < pageCount - 1 || documentIndex < playlist.length - 1 ? ['next'] : []),
      'rotate', 'timer', 'timerSet', 'fullscreen', 'clear', 'exit',
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

  // Some TV launchers do not forward visual focus updates to TouchableOpacity
  // on the home screen. Keep a matching JS focus state from the remote's
  // left/right events, so the yellow/blue active choice is always visible.
  useEffect(() => {
    if (!appAuthenticated || pdfUri || adsActive) return undefined;
    const subscription = DeviceEventEmitter.addListener('onHWKeyEvent', (event) => {
      if (Number(event?.eventKeyAction) !== 0 || !['left', 'right', 'up', 'down'].includes(event?.eventType)) return;
      setHomeFocused((current) => ['left', 'up'].includes(event.eventType) ? 'pdf' : 'ads');
    });
    return () => subscription.remove();
  }, [adsActive, appAuthenticated, pdfUri]);

  const stopTimerForManualAction = (action) => {
    setTimerEnabled(false);
    return action();
  };

  const loginToApp = async () => {
    try {
      const valid = await CmsServerModule?.validateLogin?.(appPassword.trim());
      if (!valid) {
        setLoginError('Incorrect password. Check the TV IP based password.');
        return;
      }
      setLoginError('');
      setAppPassword('');
      setAppAuthenticated(true);
    } catch (_) {
      setLoginError('Unable to verify password. Confirm the TV is connected to Wi-Fi.');
    }
  };

  if (!appAuthenticated) {
    return (
      <View style={styles.loginScreen}>
        <View style={styles.loginCard}>
          <Text style={styles.loginTitle}>NV PDF Viewer</Text>
          {cmsQr ? (
            <View style={styles.loginQrBlock}>
              <Image source={{ uri: cmsQr.base64 }} style={styles.loginQr} />
              <Text style={styles.loginTvIp}>{cmsQr.url}</Text>
            </View>
          ) : null}
          <TextInput
            value={appPassword}
            onChangeText={(value) => { setAppPassword(value.replace(/[^0-9]/g, '')); setLoginError(''); }}
            keyboardType="numeric"
            secureTextEntry={true}
            placeholder="Enter Password"
            placeholderTextColor="#98A6B9"
            style={styles.loginInput}
            autoFocus={true}
            onSubmitEditing={loginToApp}
          />
          <TouchableOpacity
            hasTVPreferredFocus={true}
            style={styles.loginButton}
            onPress={loginToApp}>
            <Text style={styles.buttonText}>Login</Text>
          </TouchableOpacity>
          {loginError ? <Text style={styles.loginError}>{loginError}</Text> : null}
          <Text style={styles.licenseContact}>For license contact:{'\n'}+918574700615, 9227896944</Text>
        </View>
      </View>
    );
  }

  if (adsActive) {
    if (!adsPlaylist.length) {
      return (
        <View style={styles.adsScreen}>
          <View style={styles.emptyAdsContainer}>
            <Text style={styles.emptyAdsTitle}>No Ads Found</Text>
            <Text style={styles.emptyAdsText}>To display ads, please follow these steps:</Text>
            <Text style={styles.emptyAdsBullet}>• Connect a USB drive to your TV</Text>
            <Text style={styles.emptyAdsBullet}>• Create a folder named "Ads" on the USB drive</Text>
            <Text style={styles.emptyAdsBullet}>• Add images, videos, or PDF files to the Ads folder</Text>
            <Text style={styles.emptyAdsBullet}>• The app will automatically detect and play the ads</Text>
            <TouchableOpacity
              style={styles.emptyAdsButton}
              onPress={() => stopAds()}>
              <Text style={styles.emptyAdsButtonText}>Return to Home</Text>
            </TouchableOpacity>
          </View>
        </View>
      );
    }
    const currentAd = adsPlaylist[adsIndex];
    // Match the NVA Player's rotation strategy: for 90/270 degrees the
    // entire media stage swaps its dimensions before rotation. Rotating only
    // a VideoView leaves a landscape surface clipped inside its old bounds.
    const isQuarterTurn = adsRotation === 90 || adsRotation === 270;
    const stageWidth = isQuarterTurn ? screenHeight : screenWidth;
    const stageHeight = isQuarterTurn ? screenWidth : screenHeight;
    const adsStageRotation = {
      width: stageWidth,
      height: stageHeight,
      position: 'absolute',
      top: (screenHeight - stageHeight) / 2,
      left: (screenWidth - stageWidth) / 2,
      transform: adsRotation ? [{ rotate: `${adsRotation}deg` }] : [],
    };
    return (
      <View style={styles.adsScreen}>
        <View style={[styles.adsStage, adsStageRotation]}>
          {currentAd?.type === 'video' ? (
            <>
              {adsImage ? <Image source={{ uri: adsImage }} style={[styles.adsMedia, styles.adsBackdrop]} resizeMode="stretch" /> : null}
              <AdsVideoView
                ref={adsVideoViewRef}
                key={`${currentAd.uri}-${adsCycle}`}
                style={[styles.adsMedia, styles.adsVideoLayer, !adsVideoReady && styles.adsVideoHidden]}
                source={currentAd.uri}
                muted={false}
                rotation={adsRotation}
                onAdReady={() => setAdsVideoReady(true)}
                onAdComplete={() => adsNextRef.current?.()}
                onAdError={() => adsNextRef.current?.()}
              />
            </>
          ) : adsImage ? <Image source={{ uri: adsImage }} style={styles.adsMedia} resizeMode="stretch" /> : null}
          {cmsQr ? <Text style={styles.ipOverlay}>{cmsQr.url}</Text> : null}
        </View>
      </View>
    );
  }

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
            setCleanMode(false);
          }}
          accessibilityLabel="Show PDF controls">
          {pageImage ? <Image source={{ uri: pageImage }} style={styles.pdfImage} resizeMode="stretch" /> : null}
          {loading ? <ActivityIndicator size="large" color="#fff" style={styles.loader} /> : null}
          {cmsQr ? <Text style={styles.ipOverlay}>{cmsQr.url}</Text> : null}
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
          {cmsQr ? <Image source={{ uri: cmsQr.base64 }} style={styles.cmsQr} accessibilityLabel={`CMS QR code: ${cmsQr.url}`} /> : null}
        </View>
        <View style={styles.viewer}>
          {pageImage && !loading ? <Image source={{ uri: pageImage }} style={styles.pdfImage} resizeMode="stretch" /> : null}
          {loading ? <ActivityIndicator size="large" color="#fff" style={styles.loader} /> : null}
          {cmsQr ? <Text style={styles.ipOverlay}>{cmsQr.url}</Text> : null}
        </View>
        <View style={styles.controlPanel}>
          <Text style={styles.focusHint}>Remote focus: {focusedControl ? focusedControl.toUpperCase() : 'CONTROLS'}</Text>
          <View style={styles.controls}>
            {viewerButton('previous', 'Previous', previousAction, !canGoPrevious)}
            {viewerButton('next', 'Next', nextAction, !canGoNext)}
            {viewerButton('rotate', `Rotate ${rotation} deg`, rotateAction)}
            {viewerButton('timer', timerEnabled ? 'Timer ON (Loop)' : 'Timer OFF', timerAction)}
            {viewerButton('timerSet', `Timer ${timerSeconds}s`, () => timerSecondsAction(TIMER_VALUES[(TIMER_VALUES.indexOf(timerSeconds) + 1) % TIMER_VALUES.length]))}
            {viewerButton('fullscreen', 'Full screen', fullscreenAction)}
            {viewerButton('clear', 'Clear PDFs', clearLibrary)}
            {viewerButton('exit', 'Close', closeViewer)}
          </View>
        </View>
      </View>
    );
  }

  const isLandscape = screenWidth > screenHeight;

  return (
    <View style={styles.container}>
      <View style={[styles.content, isLandscape && styles.contentLandscape]}>
        <View style={[styles.homeMainSection, isLandscape && styles.homeMainSectionLandscape]}>
          {/* <Text style={[styles.title, isLandscape && styles.titleLandscape]}>NV PDF Viewer</Text> */}
          <Text style={[styles.subtitle, isLandscape && styles.subtitleLandscape]}>Select one or more PDF files</Text>
          {cmsQr ? (
            <View style={[styles.homeQrBlock, isLandscape && styles.homeQrBlockLandscape]}>
              <Image source={{ uri: cmsQr.base64 }} style={[styles.homeQr, isLandscape && styles.homeQrLandscape]} />
              <Text style={styles.homeTvIp}>{cmsQr.url}</Text>
            </View>
          ) : null}
        </View>
        <View style={[styles.homeButtonSection, isLandscape && styles.homeButtonSectionLandscape]}>
          <TouchableOpacity
            accessible={true}
            accessibilityLabel="Open PDF playlist"
            hasTVPreferredFocus={homeFocused === 'pdf'}
            focusable={true}
            style={[styles.button, homeFocused === 'pdf' && styles.buttonFocused, isLandscape && styles.buttonLandscape]}
            onFocus={() => setHomeFocused('pdf')}
            onPress={openPdfs}>
            <Text style={styles.buttonText}>Open PDFs</Text>
          </TouchableOpacity>
          <TouchableOpacity
            accessible={true}
            accessibilityLabel="Start Ads scan"
            hasTVPreferredFocus={homeFocused === 'ads'}
            focusable={true}
            style={[styles.button, styles.adsStartButton, homeFocused === 'ads' && styles.buttonFocused, isLandscape && styles.buttonLandscape]}
            onFocus={() => setHomeFocused('ads')}
            onPress={() => chooseUsbAdsFolder().catch((error) => Alert.alert('USB Ads', error.message || 'Could not read USB Ads folder.'))}>
            <Text style={styles.buttonText}>Ads</Text>
          </TouchableOpacity>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#000' },
  loginScreen: { flex: 1, backgroundColor: '#0E1522', alignItems: 'center', justifyContent: 'center', padding: 30 },
  loginCard: { width: '74%', maxWidth: 650, backgroundColor: '#1C2738', borderRadius: 16, padding: 30, borderWidth: 2, borderColor: '#3D506D' },
  loginTitle: { color: '#fff', textAlign: 'center', fontSize: 36, fontWeight: '800', marginBottom: 14 },
  loginSubtitle: { color: '#C4D0E2', fontSize: 19, textAlign: 'center', marginBottom: 24 },
  loginInput: { backgroundColor: '#0C111A', color: '#fff', fontSize: 24, borderRadius: 10, borderWidth: 2, borderColor: '#566B89', paddingHorizontal: 18, paddingVertical: 14, textAlign: 'center' },
  loginButton: { backgroundColor: '#0D6EFD', borderRadius: 10, borderWidth: 3, borderColor: '#FFD600', paddingVertical: 15, alignItems: 'center', marginTop: 18 },
  loginQrBlock: { alignItems: 'center', marginBottom: 18 },
  loginQr: { width: 132, height: 132, backgroundColor: '#fff', borderRadius: 5, marginBottom: 7 },
  loginTvIp: { color: '#FFD600', fontSize: 16, fontWeight: '700' },
  loginError: { color: '#FF9696', textAlign: 'center', fontSize: 16, marginTop: 14 },
  licenseContact: { color: '#FFD600', textAlign: 'center', fontSize: 17, fontWeight: '700', marginTop: 26, lineHeight: 25 },
  homeQrBlock: { alignItems: 'center', marginBottom: 30 },
  homeQr: { width: 150, height: 150, backgroundColor: '#fff', borderRadius: 8, marginBottom: 5 },
  homeTvIp: { color: '#FFD600', fontSize: 18, fontWeight: '700', letterSpacing: 1, marginTop: 2 },
  content: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  contentLandscape: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-around', paddingHorizontal: 60 },
  homeMainSection: { alignItems: 'center' },
  homeMainSectionLandscape: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  homeButtonSection: { alignItems: 'center' },
  homeButtonSectionLandscape: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  title: { fontSize: 48, color: '#fff', fontWeight: 'bold', marginBottom: 20 },
  titleLandscape: { fontSize: 56, marginBottom: 25 },
  subtitle: { fontSize: 24, color: '#ccc', marginBottom: 60 },
  subtitleLandscape: { fontSize: 28, marginBottom: 40 },
  homeQrBlockLandscape: { marginBottom: 40 },
  homeQrLandscape: { width: 180, height: 180 },
  button: { backgroundColor: '#2196F3', paddingVertical: 20, paddingHorizontal: 40, borderRadius: 12, borderWidth: 3, borderColor: 'transparent' },
  buttonLandscape: { paddingVertical: 25, paddingHorizontal: 50, minWidth: 200 },
  buttonFocused: { borderColor: '#FFD600', backgroundColor: '#0D6EFD', elevation: 12, transform: [{ scale: 1.08 }] },
  buttonText: { color: '#fff', fontSize: 24, fontWeight: 'bold' },
  adsStartButton: { marginTop: 18, backgroundColor: '#00796B' },
  adsStartButtonLandscape: { marginTop: 0 },
  adsScreen: { flex: 1, backgroundColor: '#000', alignItems: 'center', justifyContent: 'center' },
  ipOverlay: { position: 'absolute', bottom: 10, right: 10, color: 'rgba(255,255,255,0.3)', fontSize: 12, fontWeight: '400' },
  emptyAdsContainer: { alignItems: 'center', justifyContent: 'center', padding: 40, maxWidth: 700 },
  emptyAdsTitle: { color: '#fff', fontSize: 42, fontWeight: 'bold', marginBottom: 20, textAlign: 'center' },
  emptyAdsText: { color: '#ccc', fontSize: 22, marginBottom: 25, textAlign: 'center' },
  emptyAdsBullet: { color: '#ddd', fontSize: 20, marginBottom: 12, lineHeight: 28 },
  emptyAdsButton: { backgroundColor: '#0D6EFD', paddingVertical: 18, paddingHorizontal: 40, borderRadius: 12, borderWidth: 3, borderColor: '#FFD600', marginTop: 30 },
  emptyAdsButtonText: { color: '#fff', fontSize: 22, fontWeight: 'bold' },
  adsStage: { position: 'absolute', top: 0, left: 0, width: '100%', height: '100%' },
  adsMedia: { width: '100%', height: '100%' },
  adsBackdrop: { position: 'absolute', top: 0, left: 0 },
  adsVideoLayer: { position: 'absolute', top: 0, left: 0 },
  adsVideoHidden: { opacity: 0 },
  viewerHeader: { flexDirection: 'row', padding: 20, backgroundColor: '#202020', alignItems: 'center' },
  headerText: { flex: 1 },
  filename: { color: '#fff', fontSize: 24, fontWeight: 'bold' },
  playlistInfo: { color: '#FFD600', fontSize: 16, marginTop: 4, fontWeight: '700' },
  pageInfo: { color: '#ddd', fontSize: 20, marginLeft: 24 },
  cmsQr: { width: 94, height: 94, marginLeft: 18, backgroundColor: '#fff', borderRadius: 4 },
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
