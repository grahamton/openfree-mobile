# Project: OpenFree Android Voice-Dictation Keyboard

## Architecture
This is an offline-first, local voice-dictation and typing hybrid keyboard (IME) application for Android.
The application consists of a system-level Input Method Editor (IME) service written in Kotlin, which interacts with:
1. An Audio Capture Engine (`AudioRecorder.kt`) for high-fidelity microphone input.
2. An In-Process Whisper.cpp engine compiled via Android NDK/CMake and exposed via a custom JNI Bridge.
3. A dynamic tab switcher providing Voice Dictation (with pulsing animation), a QWERTY Typing Layout for inline editing, and a Quick Dictionary Corrections panel.
4. A Settings & Configuration UI (`SettingsActivity.kt`) that handles preferences, local dictionary mappings, and model downloads.

Data flow:
`User Interaction (Mic Tap) -> AudioRecorder (AudioRecord 16-bit PCM @ 16kHz mono) -> FloatArray -> WhisperEngine (JNI to C++ whisper.cpp) -> Raw Transcription -> Apply Corrections Dictionary -> OpenFreeIME (commitText) -> Active Input Connection`
`User Interaction (QWERTY Tap) -> commitText -> Active Input Connection`

## Code Layout
- `app/build.gradle.kts` - Module build configuration
- `app/src/main/AndroidManifest.xml` - IME service declarations and permissions
- `app/src/main/cpp/CMakeLists.txt` - NDK CMake build script
- `app/src/main/cpp/whisper-jni.cpp` - C++ JNI bridge code
- `app/src/main/java/com/openfree/client/OpenFreeIME.kt` - InputMethodService subclass
- `app/src/main/java/com/openfree/client/WhisperEngine.kt` - Kotlin JNI wrapper
- `app/src/main/java/com/openfree/client/AudioRecorder.kt` - PCM recording wrapper
- `app/src/main/java/com/openfree/client/SettingsActivity.kt` - Settings activity and HF downloader
- `app/src/main/res/xml/method.xml` - Input Method description metadata
- `app/src/main/res/layout/keyboard_view.xml` - compact keyboard panel layout

## Milestones

### Implementation Track
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Project Bootstrapping | Root gradle, settings, app build.gradle.kts, AndroidManifest.xml, whisper.cpp submodule integration | none | DONE |
| M2 | Audio Capture Engine | AudioRecorder implementation (16-bit PCM @ 16kHz mono to FloatArray converter, start/stop callbacks) | M1 | DONE |
| M3 | Whisper.cpp JNI Wrapper | whisper-jni.cpp, WhisperEngine Kotlin wrapper, NDK build system, local model loading & inference | M1 | DONE |
| M4 | Android IME Keyboard | OpenFreeIME Kotlin service implementation, Fieldwork styling keyboard UI view, commitText integrations | M2, M3 | DONE |
| M5 | Settings & Downloader | SettingsActivity UI, preferences storage, HuggingFace model download helper | M1 | DONE |
| M6 | E2E Verification & Hardening | Final integrations, passing all test suite tiers (1-4) and adversarial testing (Tier 5) | M2, M3, M4, M5, TEST_READY.md | DONE |

### E2E Testing Track
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| T1 | Test Infrastructure | Setup test framework, runner script, and test case structures | none | DONE |
| T2 | Test Suite (Tiers 1-4)| Implement feature coverage, edge cases, cross-features, and workloads; write TEST_READY.md | T1 | DONE |

## Interface Contracts

### AudioRecorder ↔ OpenFreeIME
- `AudioRecorder.startRecording(onAudioSamplesReady: (FloatArray) -> Unit)`
- `AudioRecorder.stopRecording()`
- Callback delivers float arrays containing audio samples normalized to `[-1.0, 1.0]`.

### WhisperEngine ↔ OpenFreeIME / SettingsActivity
- `WhisperEngine.loadModel(modelPath: String): Boolean`
- `WhisperEngine.transcribe(audioSamples: FloatArray): String`
- `WhisperEngine.unloadModel()`

### SettingsActivity ↔ OpenFreeIME
- Uses Android SharedPreferences:
  - `pref_key_model_path`: String (default: path to ggml-base.en-q5_1.bin in app cache)
  - `pref_key_remote_fallback_url`: String (optional fallback URL)
