# Lazy Reader - Project Mandates & Memory

This file serves as the permanent context, architectural blueprint, and task checklist for the **Lazy Reader** Android app. Any development session started in this directory reads this file to ensure absolute continuity.

---

## 1. Project Overview

**Lazy Reader** is a 100% offline, privacy-first Android document reader (PDF & EPUB) designed for hands-free reading. It uses local, on-device audio classification to listen for voice commands to control the reader, preventing the screen from dimming or locking while in reading mode.

### v1 Scope
To ship a first version with the least amount of unproven, bespoke work, v1 is intentionally narrowed:
- **PDF only.** EPUB support (custom parser + WebView styling) is deferred to v2 — it's the most bespoke, time-consuming piece of the original design and isn't needed to prove out the core hands-free reading experience.
- **3-class voice model only** (`"go"`, `"backward"`, `"stop"`). `"backward"` (not `"back"`) was chosen specifically so the model can be trained entirely on Google's public Speech Commands v0.02 dataset (CC-BY-4.0), which has no clips for the word "back" but does have "backward" — avoiding a bespoke, single-speaker recording effort in favor of a dataset with thousands of speakers. `"bookmark"`, `"night"/"day"`, and the sensitivity slider (see Section 2C) stay deferred until the core loop is proven.

### Core Features
- **Recents History Dashboard**: Displays a visual list of recently read books with progress percentages.
- **Robust Local Reader**:
  - Native `PdfRenderer` for smooth offline PDF reading.
  - *(v2)* Custom lightweight EPUB parser for customizable text size and colors.
- **Offline Voice Gestures**:
  - `"go"`: Navigate to the next page.
  - `"backward"`: Navigate to the previous page.
  - `"stop"`: Stop voice listening completely and enter a locked overlay reading mode.
- **Ambient Screen Lock Prevention**: Keeps the screen awake during active reading.

---

## 2. Technical Architecture & Expert Upgrades

Based on architectural research, we have upgraded the implementation strategy for maximum reliability, speed, and clean code:

### A. Speech AI: MediaPipe Audio Classifier (Better than Raw TFLite)
* **The Problem with Raw TFLite**: Processing raw microphone bytes to feed a raw TFLite model requires writing complex digital signal processing (DSP) logic in Kotlin: recording floats, computing Hamming/Hanning windows, executing Fast Fourier Transforms (FFT), building Mel-frequency filterbanks, and managing sliding overlapping circular buffers.
* **The MediaPipe Solution**: We will use **Google MediaPipe Audio Classifier Tasks (`com.google.mediapipe:tasks-audio`)**.
  - MediaPipe runs on top of TFLite but **completely abstracts audio feature extraction**.
  - It takes raw audio samples directly from the microphone and handles all windowing, spectrogram conversions, and model sliding window overlaps internally.
  - This reduces our AI integration footprint from ~800 lines of complex DSP math to less than 50 lines of clean Kotlin code.
  - It supports standard TFLite audio classification models (such as the YAMNet model or fine-tuned Keyword Spotting models).

### B. Screen Awake & Touch Lock
- **Keep-Awake**: Handled via Compose UI's `keepScreenOn` property on the container view:
  ```kotlin
  val currentView = LocalView.current
  DisposableEffect(Unit) {
      currentView.keepScreenOn = true
      onDispose { currentView.keepScreenOn = false }
  }
  ```
- **App Lock State**: When `"stop"` is heard:
  - Deactivate the microphone recorder.
  - Show a dark, beautiful overlay stating *"Voice Controls Stopped. Tap Lock icon to Unlock."* with a physical lock icon. This acts as a "pocket-lock" mode, protecting the screen from accidental inputs while lying down.
  - **Decision (2026-07-15): no full device lock.** Locking the phone programmatically requires the device-admin permission, whose scary system prompt and uninstall friction would undermine the app's privacy-first trust story (a device-admin implementation was built, then deliberately reverted). The overlay + the user pressing the power button covers the same need permission-free. If ever revisited, it should be an **opt-in setting** (v2), never the default.

### C. Recommended Additional Features (To be implemented)
1. **"bookmark" voice command**: Saves the current page instantly without touching the screen.
2. **"night" / "day" voice command**: Instantly toggles reading view themes (dark mode vs. light mode).
3. **Threshold Sensitivity Slider**: A UI slider in the settings to let users adjust the detection confidence (e.g., 70% to 90%) depending on their ambient room noise.

---

## 3. Directory Layout

```
lazy_reader/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── assets/             # Place model.tflite here
│   │   │   ├── java/com/lazyreader/
│   │   │   │   ├── data/           # Room Database, Recents entity
│   │   │   │   ├── voice/          # MediaPipe Audio Classifier & Mic loop
│   │   │   │   ├── ui/             # Jetpack Compose Screens, Theme, Components
│   │   │   │   └── MainActivity.kt
│   │   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── CLAUDE.md                       # This file
```

---

## 3a. Build Toolchain Notes

As of this environment (JDK 25, Android SDK platforms up to 37.x available), the working combination is:
- Gradle **9.6.1**, AGP **9.2.1**, Kotlin **2.3.0**, KSP **2.3.0**, compileSdk/targetSdk **37**, minSdk **26**.
- AGP 9's new built-in-Kotlin compilation and new DSL are **not** compatible with KSP (used by Room) yet, so `gradle.properties` explicitly sets `android.builtInKotlin=false` and `android.newDsl=false` to keep the classic `org.jetbrains.kotlin.android` + KSP plugin pipeline. Revisit this once KSP supports AGP's built-in Kotlin.
- Newer androidx libraries (e.g. `core-ktx` 1.19+, `lifecycle` 2.11+) require AGP 9.1+ and compileSdk 37 — this forced the jump from AGP 8.x.
- SDK platform 37.0 and build-tools 37.0.0 were installed via `cmdline-tools/latest/bin/sdkmanager` (the SDK's old bundled `tools/bin/sdkmanager` is broken under JDK 25 — `NoClassDefFoundError` on `javax.xml.bind`).

## 4. Development & Implementation Roadmap

- [x] **Phase 1: Project Initialization & Configurations**
  - [x] Initialize standard modern Android/Gradle project structure.
  - [x] Configure `build.gradle.kts` with Room, Jetpack Compose, and MediaPipe Tasks Audio.
  - [x] Design app icons and themes.
- [x] **Phase 2: Database & Dashboard UI**
  - [x] Implement Room Database for `RecentDocument` tracking.
  - [x] Design the Dashboard Screen featuring elegant Material 3 cards for recent books.
  - [x] Implement native file import launcher.
- [x] **Phase 3: Document Rendering Core**
  - [x] Implement PDF native `PdfRenderer` view (horizontal pager or scroll).
  - [x] Implement page-turn animations and screen keep-awake controls.
- [x] **Phase 4: Offline Voice Commands with MediaPipe**
  - [x] Bundle pre-trained 3-class Keyword Spotting model ("go", "backward", "stop") inside `assets/`. *(Trained in-house: end-to-end CNN on log-mel spectrograms, 95.5% test accuracy, 628KB — see `training/`. Note: mediapipe-model-maker's documented audio_classifier API doesn't exist in the published package; MediaPipe is used for on-device inference only, per Section 2A.)*
  - [x] Implement `VoiceCommandClassifier` using MediaPipe Audio Classifier.
  - [x] Integrate listening loops with Reader UI events ("go", "backward", "stop"). *(Device-tested on Nokia C21 Plus.)*
  - [x] Lock screen overlay on "stop" (pulled forward from Phase 5; device-admin full-lock rejected — see Section 2B).
- [ ] **Phase 5: Polishing, Custom Locks & Testing**
  - [ ] Build lock screen overlay and wake/lock gestures.
  - [x] Thorough unit and integration testing. *(2026-07-17: 37 JVM unit tests — Robolectric/JUnit, headless — covering voice-command decisioning, EPUB parsing incl. zip-slip guard, Room DAO + v1→v2 migration, and both reader ViewModels' state machines; 7 instrumented tests on a real device covering PdfRenderer, the real MediaPipe/AudioRecord lifecycle, and real WebView EPUB pagination. Writing the WebView instrumented test surfaced and fixed three real, previously-undetected EPUB pagination bugs — see Phase 6 note below and project memory.)*
  - [x] Reader navigation & chrome UX (2026-07-17): tap the page indicator to jump — PDF gets a page slider; EPUB gets the book's real chapter list parsed from its EPUB3 nav TOC (anchor-accurate, falls back to a spine-section slider for TOC-less books). Top bar + page indicator auto-hide after ~3s and reappear on tap/swipe (EPUB tap detection lives in the WebView touch listener — Compose tap detectors never see taps the WebView absorbs).
  - [ ] UI responsiveness and smooth transitions polish (remaining general pass).
- [ ] **Phase 6 (v2): EPUB Support & Extra Voice Commands**
  - [x] Implement lightweight EPUB parser (spine parsing + WebView styled display). *(Pulled forward from v2, device-tested 2026-07-15: `epub/EpubParser.kt` + `EpubReaderScreen` with CSS-column pagination; swipe + voice page turns; progress tracked per chapter. Key WebView gotchas are recorded in project memory. **Update 2026-07-17**: the 2026-07-15 device test used short/simple chapters and missed three real bugs that only showed up with a real full-length book — chapters loading as strict XML instead of HTML (silently broke the pagination CSS), `document.documentElement.clientHeight` reporting >2x the real WebView height (every page height computed from it was wrong, clipping the last line), and WebView's native touch scrolling fighting the swipe-to-turn gesture. All three fixed; see project memory for the full writeup. **Update 2026-07-17 (later)**: the image-splitting remainder is fixed too — the real culprit was figure *wrapper* divs with hardcoded inline widths (e.g. Project Gutenberg's `<div class="figcenter" style="width:550px">`) spanning the column boundary even after the img itself was capped; fixed with a blanket `body *{max-width:colw !important}`. User-verified on a real illustrated book.)*
  - [ ] Add "bookmark" and "night"/"day" voice commands.
  - [ ] Add threshold sensitivity slider in settings.
  - [ ] (Optional, opt-in only) "Lock phone completely on stop" setting using device admin — see Section 2B decision note before implementing.
