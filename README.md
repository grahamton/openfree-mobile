# OpenFree Voice Keyboard

OpenFree is an **offline-first, local voice-dictation keyboard (IME)** for Android. It records audio using the Android `AudioRecord` API and transcribes it completely on-device using a compiled C++ `whisper.cpp` engine running in-process via JNI.

---

## Features

- **Local & Private:** Voice dictation runs entirely on-device; no audio samples or text are sent to the cloud.
- **High-Speed Inference:** Whisper core compiled with full `-O3` vectorizations, bypassing OpenMP overhead for sub-1.5s latency on modern CPUs.
- **Adaptive Material 3 Design:** Styled using a clean Material 3 Day/Night theme that automatically adapts to system light and dark modes.
- **Embedded QWERTY Typing Mode:** Includes a compact, built-in QWERTY typing layout to make inline text corrections and edits directly inside the IME.
- **Quick Dictionary Corrections:** A local text-replacement dictionary (e.g. `anti gravity` -> `Antigravity`) to automatically fix recurring voice dictation typos.
- **Floating Assist Widget overlay**: An offline-first floating speech recognition bubble that runs over any keyboard (like Gboard) using Android Accessibility Services.
- **Hugging Face Downloader:** Integrated downloader UI inside app settings to pull the optimized `ggml-base.en-q5_1.bin` model directly from Hugging Face.
- **Optional Remote Fallback:** Supports routing transcription queries to a custom home lab transcription server (Tailscale/LAN) with Android 17 `ACCESS_LOCAL_NETWORK` permission support.

---

## Technical Architecture

```
User Interaction (Mic Tap)
   │
   ▼
AudioRecorder (Captures 16-bit PCM @ 16kHz mono)
   │
   ▼
FloatArray Normalization ([-1.0, 1.0])
   │
   ▼
WhisperEngine (Kotlin JNI Wrapper)
   │
   ▼
whisper-jni.cpp (C++ bridge) -> whisper.cpp
   │
   ▼
Apply Local Corrections Dictionary (Custom Mappings)
   │
   ▼
OpenFreeIME (InputMethodService) -> commitText -> Active Input Connection
```

---

## Prerequisites

- **JDK:** OpenJDK 17 or higher
- **Android SDK:** API Level 29+ (Targeting API 35)
- **Android NDK:** `26.1.10909125`
- **CMake:** `3.22.1`

---

## Installation & Build

1. **Clone the repository with submodules:**
   ```bash
   git clone --recursive <repository-url>
   cd android-dictation
   ```
   *If already cloned without submodules, run:*
   ```bash
   git submodule update --init --recursive
   ```

2. **Build the Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```

3. **Install on Device:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Getting Started

1. **Open Settings:** Find and open the **OpenFree Settings** app from your launcher.
2. **Grant Microphone Permission:** Grant the requested runtime `RECORD_AUDIO` permission.
3. **Download Model:** Tap **Download Base EN Model** to download the ~60MB optimized quantized Whisper model from Hugging Face. Once done, tap **Save Settings**.
4. **Choose Your Mode:**
   - **Hybrid Keyboard (IME)**: Go to Android Settings -> System -> Languages & Input -> On-screen keyboard -> Manage keyboards, and enable **OpenFree Voice Input**. Tap a text field, switch keyboard to OpenFree, and use the Voice/QWERTY spacebar triggers.
   - **Floating Assist Widget (Overlay)**: Tap **Enable Floating Widget** inside OpenFree Settings. In Android Accessibility settings, turn on **OpenFree Floating Assist**. A movable mic bubble will float on top of other apps (including Gboard). Tap the bubble to talk, and tap again to type!

---

## Testing

Verify the codebase structure, audio capture engine, JNI wrapper, and settings configurations using the mock verification tests:
```bash
python tests/run_tests.py --mode mock
```

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
