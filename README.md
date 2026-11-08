# No Hands 🖐️

**No Hands** is a touch-free gesture navigation and interaction application for Android. It uses the device's front-facing camera with [Google MediaPipe Tasks Vision](https://developers.google.com/mediapipe/solutions/vision/hand_landmarker) and [Android Jetpack CameraX](https://developer.android.com/training/camerax) to recognize rapid, natural hand flicks in real time without physical contact.

---

## Features

### 📷 Camera & Vision Pipeline
- **Lifecycle-aware CameraX Preview**: Full-screen front camera feed rendered via `PreviewView` in Jetpack Compose, auto-adapting to screen rotations and background transitions.
- **MediaPipe Hand Landmark Tracking**: Tracks 21 hand landmarks in real time using the bundled `hand_landmarker.task` model (float16) in `LIVE_STREAM` mode.
- **Hot-swappable Inference Delegates (GPU / CPU)**: Dedicated lifecycle background thread handles asynchronous model creation and shader compilation without freezing the UI or dropping camera frames. Gracefully falls back to CPU if GPU initialization fails.
- **Landmark Skeleton Overlay**: Direct coordinate mapping with pre-rotated, mirrored image buffers ensuring accurate alignment with the real hand on front cameras.

### ⚡ Phase 2: Motion & Swipe Recognition
- **Palm Center Tracking**: Calculates the center of the palm from the mean of landmarks `0` (wrist), `5` (index MCP), `9` (middle MCP), `13` (ring MCP), and `17` (pinky MCP).
- **Scale-Invariant Hand Units**: Normalizes all displacements and speeds relative to the user's hand size (distance between wrist landmark `0` and middle finger MCP `9`). Works consistently regardless of distance from the camera.
- **Exponential Moving Average (EMA)**: Palm coordinates are smoothed with an EMA filter ($\alpha = 0.5$) to eliminate sensor noise and jitter.
- **Three-State Machine**:
  - `ARMED`: Analyzes a 300 ms sliding window. Triggers a swipe when $|\Delta y| \ge 1.0\text{ hand sizes}$, vertical dominance $|\Delta y| \ge 2.0 \cdot |\Delta x|$, duration is within $80\text{–}400\text{ ms}$, and peak instantaneous speed $\ge 3.5\text{ hand sizes/s}$.
  - `COOLDOWN`: Suppresses gesture detection for 700 ms following an event.
  - `SETTLING`: Suppresses return-stroke flicks. Re-arms only when hand speed stays below $0.8\text{ hand sizes/s}$ for $200\text{ ms}$ continuously.
- **Hand Loss Recovery**: Tolerates brief landmark occlusions up to 250 ms. Longer absences trigger buffer clearance and a mandatory re-settle period to prevent accidental triggers upon re-acquisition.

### 🖥️ HUD & Visual Feedback
- **Status & Performance HUD**:
  - Tracking status chip (`Tracking`, `Searching...`, `Stopped`).
  - Active delegate toggle chip (`GPU`, `CPU`, or `CPU (GPU failed)`).
  - Processed result FPS and inference latency (ms).
  - Running swipe counter.
  - Second-line debug diagnostics: detector state, windowed vertical displacement ($\Delta y$), and instantaneous speed.
- **On-Screen Gesture Feedback**: Large, semi-transparent animated pill (`NEXT ▲` or `PREV ▼`) displayed in the screen center for 600 ms with smooth fade transitions.

---

## Architecture & Code Layout

```
app/src/main/java/com/delata/nohands/
├── MainActivity.kt               # Entry point and runtime camera permission check
├── camera/
│   ├── CameraAnalyzer.kt        # CameraX analyzer transforming RGBA buffers to upright bitmaps
│   └── CameraManager.kt         # CameraProvider binding for Preview and ImageAnalysis
├── gesture/
│   ├── GestureState.kt          # StateFlow & SharedFlow holders for events and debug telemetry
│   ├── MotionConfig.kt          # Tunable motion detection thresholds
│   └── MotionDetector.kt        # Pure Kotlin swipe detector (zero Android dependencies)
├── ui/
│   └── HomeScreen.kt            # Jetpack Compose UI, HUD overlays, and delegate switcher
└── vision/
    ├── HandLandmarkerHelper.kt  # MediaPipe HandLandmarker wrapper with GPU/CPU support
    ├── HandOverlay.kt           # Custom Compose Canvas drawing the 21-point hand skeleton
    └── TrackingState.kt         # Thread-safe StateFlow holder for tracking metrics
```

---

## Technical Stack & Compatibility

- **Language**: Kotlin 2.2.20
- **UI Framework**: Jetpack Compose (Material 3) with Compose BOM `2025.02.00`
- **Build System**: Gradle 8.14.3 with Android Gradle Plugin (AGP) 8.13.0
- **Dependency Management**: Gradle Version Catalog (`gradle/libs.versions.toml`)
- **JDK Toolchain**: Java 21
- **Android Target**: `compileSdk = 36`, `targetSdk = 36`, `minSdk = 24` (Android 7.0+)
- **Computer Vision**: Google MediaPipe Tasks Vision `0.10.20`
- **Camera**: AndroidX CameraX `1.4.2` (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)

---

## Getting Started

### Prerequisites
- JDK 21 installed and configured.
- Android SDK with platform tools and build-tools for API 36.
- A physical Android device with a front camera (recommended for MediaPipe GPU inference).

### Build & Test

1. **Clone the repository**:
   ```bash
   git clone https://github.com/vtajaros/no-hands.git
   cd no-hands
   ```

2. **Configure SDK path** (if not already set in environment):
   Create a `local.properties` file in the project root:
   ```properties
   sdk.dir=/path/to/your/Android/Sdk
   ```

3. **Run unit tests**:
   Unit tests verify synthetic 25 fps motion profiles (fast flicks, return-stroke suppression, slow drifts, sideways flicks, cooldowns, and hand-loss re-acquisition):
   ```bash
   ./gradlew testDebugUnitTest
   ```

4. **Assemble debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```
   The APK will be generated at:
   `app/build/outputs/apk/debug/app-debug.apk`

5. **Install on device**:
   ```bash
   ./gradlew installDebug
   ```

---

## License

This project is licensed under the Apache License, Version 2.0. See the LICENSE file for details.
