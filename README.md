# NV PDF Viewer

Android TV / Google TV app for displaying PDFs and USB advertising media, with a local-network browser CMS.

## 1. What it does

- Opens one or more PDFs and keeps the playlist on the TV.
- Supports remote-friendly previous/next, rotation, fullscreen, clear, and timed page looping controls.
- Plays images, videos, and PDF pages from a pendrive `Ads` folder in a continuous loop.
- Provides a QR code and local CMS page for controlling the TV from a phone or computer on the same Wi-Fi network.

## 2. Main features

- Android TV / Google TV launcher support with a dedicated 16:9 TV banner.
- PDF rendering at TV resolution, with playlist persistence after restart.
- USB Ads auto-scan: put supported files in `Pendrive/Ads/`; images and PDF pages use the selected duration, while videos advance after completion.
- Local CMS: PDF upload, viewer controls, timer controls, USB Ads controls, current playback status, and dark/light theme.
- Password-protected CMS sessions and app login using the TV's IP-based login code.
- TV stays awake while the viewer is open.
- Remote control support for Ads mode: DOWN button rotates content, LEFT/RIGHT buttons seek videos by 10 seconds.
- Video rotation support using ExoPlayer with TextureView for proper content rotation.

## 3. How it works

1. `App.js` is the React Native TV interface and owns viewer/playback state.
2. Native Android modules import files, render PDF pages, scan USB media, and serve the CMS.
3. The CMS server starts inside the TV app on port `8081`.
4. Scan the QR code shown on TV, or open `http://TV-IP:8081` from a device on the same network.
5. CMS actions are sent to React Native, which changes the active PDF or ads playback.

## 4. Tech used

- React Native 0.86 and React 19
- Java Android native modules
- Android `PdfRenderer` for PDF pages
- Android `VideoView` / `MediaPlayer` for video ads
- NanoHTTPD for the local CMS server
- ZXing for QR-code generation
- Hermes JavaScript engine

## 5. Requirements

- Node.js 22.11 or newer
- JDK 17
- Android SDK with API 36 / Build Tools 36
- Android TV, Google TV, or compatible Android smart TV
- Phone/computer and TV on the same Wi-Fi network for CMS use

## 6. Setup and run

```powershell
npm install
npm run start
```

In a second terminal, connect a TV/device through ADB and run:

```powershell
npm run android
```

To create a release APK:

```powershell
cd android
.\gradlew.bat assembleRelease
```

The APK is created under `android/app/build/outputs/apk/release/`.

## 7. Using USB Ads

1. Create an `Ads` folder at the root of a pendrive.
2. Add supported files: PDF (`.pdf`), images (`.jpg`, `.jpeg`, `.png`, `.webp`, `.gif`, `.bmp`, `.heic`), or videos (`.mp4`, `.m4v`, `.webm`, `.3gp`, `.mkv`, `.avi`, `.mov`, `.mpeg`, `.ts`).
3. On first use, allow storage access on the TV when requested.
4. Insert the pendrive; the app scans the folder and starts the loop automatically.

## 8. Important project files

- `App.js` - TV UI, PDF viewer, ads player, and CMS command handling.
- `android/app/src/main/assets/cms.html` - browser-based CMS interface.
- `CmsServerModule.java` / `CmsHttpServer.java` - local CMS server and authentication.
- `PDFRendererModule.java` - native PDF rendering.
- `FilePickerModule.java` - PDF import and USB Ads scanning.
- `tv_banner.xml` - scalable Android TV launcher banner.

## 9. Notes

- CMS is intended for the same trusted local network as the TV.
- A normal Android APK cannot silently install other APKs; that requires managed-device or OEM support.
- Test the release APK on each target TV model because Android TV launchers and USB-storage behavior vary by manufacturer.
