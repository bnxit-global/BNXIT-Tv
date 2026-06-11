# BNXIT Tv — IPTV Player for Android TV & Projectors

A premium, TV-first IPTV player designed for Android TV, Android boxes, and smart projectors. Tailored specifically to deliver smooth HLS/DASH/M3U8 streaming on resource-constrained hardware, such as Allwinner H713-powered projectors with 1GB RAM.

---

## Key Features

- **TV-Optimized Dark UI**: Translucent glassmorphism side panels, custom typography, glowing cyan selection highlights, and smooth D-pad focus transition scales.
- **Low-Overhead Playback Engine**: Powered by Media3 ExoPlayer. Configured with a customized minimal media buffer (max 3 seconds) to prevent Out of Memory (OOM) failures and UI lag on 1GB RAM smart devices.
- **Automated Link Status Checker**: Background sequential channel check thread (spaced 300ms apart to prevent CPU load). Automatically caches results for 3 days and hides broken or dead streams at startup.
- **Immediate Single-Tap Selection**: Touchscreen and remote click optimizations to support single-tap selection for categories and channels (no double-tap focus locks).
- **Control HUD**: Fully featured fullscreen overlay controls:
  - Play/Pause toggle
  - Resolution and Quality track selection (with duplicate track filtering)
  - Aspect Ratio scaling options (Fit, Stretch, Zoom)
  - Categories & Channels sidebar toggle
  - Mini Profile Card display
- **D-pad Remote Mapping**: Standard key sequence (Back key zaps focus: Channel List -> Category List -> App Close) for an intuitive television navigation flow.
- **Inactivity Timer**: Automatic 5-second panels auto-hide, touch-aware to reset instantly upon key presses, scrolls, or tap actions.

---

## How to Install from GitHub Releases

If you want to run the precompiled app directly on your Android TV or Projector:

1. **Download the APK**:
   - Navigate to the **Releases** section on the right-hand panel of this GitHub repository.
   - Download the latest `app-debug.apk` or `app-release.apk` asset.

2. **Method A: Install via USB Drive**:
   - Transfer the downloaded `.apk` file to a FAT32/NTFS formatted USB flash drive.
   - Insert the USB drive into your Android TV or Projector.
   - Open your TV's File Manager, locate the APK, click on it, and install (ensure "Install from Unknown Sources" is enabled in settings).

3. **Method B: Install via the "Downloader" App**:
   - Install the **Downloader** app (by AFTVnews) on your TV via the Google Play Store.
   - Upload the APK to a hosting service (or use the GitHub release download URL).
   - Enter the download URL in the Downloader search bar and install it directly on your device.

---

## Technical Stack

- **Core Language**: Java (Android SDK Target 36)
- **UI Architecture**: Single-Activity, XML Layout structures (no heavy Kotlin/Compose runtime overhead)
- **Player Framework**: AndroidX Media3 ExoPlayer (with HLS, DASH, and ClearKey DRM support)
- **View Caching**: Advanced RecyclerView caching configuration (`setItemViewCacheSize`) to bypass inflation lag during fast zapping on Cortex-A53 H713 processors.

---

## How to Build the Project

To build the APK locally from source:

1. Clone this repository:
   ```bash
   git clone https://github.com/your-username/BNXITTv.git
   ```
2. Open the directory in Android Studio.
3. Build the project using Gradle:
   - On Windows (PowerShell):
     ```powershell
     .\gradlew assembleDebug
     ```
   - On Mac/Linux:
     ```bash
     ./gradlew assembleDebug
     ```
4. The compiled APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Credits & Profile

Developed and maintained by **Rasedur Rahman**.

- **Title**: Backend-focused Full-Stack Developer | AI/ML Enthusiast
- **Email**: rasedul.dev@gmail.com
- **GitHub**: [khrasedul-dev](https://github.com/khrasedul-dev)
- **Fiverr**: Fiverr Level 2 Seller (100+ projects completed)
