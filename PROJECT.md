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
- `app/src/main/java/com/openfree/client/FloatingOpenFreeService.kt` - Floating overlay AccessibilityService subclass
- `app/src/main/java/com/openfree/client/WhisperEngine.kt` - Kotlin JNI wrapper
- `app/src/main/java/com/openfree/client/AudioRecorder.kt` - PCM recording wrapper
- `app/src/main/java/com/openfree/client/DictionaryStore.kt` - Shared JSON-backed corrections dictionary (single source of truth)
- `app/src/main/java/com/openfree/client/VoiceCommandProcessor.kt` - Shared spoken-command parser (punctuation words, "new line", "delete that") producing TextOps
- `app/src/main/java/com/openfree/client/VoiceActivityDetector.kt` - Shared energy VAD: silence-timeout auto-stop + leading/trailing silence trim
- `app/src/main/java/com/openfree/client/StreamingTranscriber.kt` - Shared dictation-session orchestrator: live partial passes + trimmed final full-context pass
- `app/src/main/java/com/openfree/client/SettingsActivity.kt` - Settings activity and HF downloader
- `app/src/main/res/xml/method.xml` - Input Method description metadata
- `app/src/main/res/xml/floating_service_config.xml` - Accessibility service metadata
- `app/src/main/res/layout/keyboard_view.xml` - compact keyboard panel layout
- `app/src/main/res/layout/floating_layout.xml` - floating speech bubble layout

## Milestones

### Implementation Track
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Project Bootstrapping | Root gradle, settings, app build.gradle.kts, AndroidManifest.xml, whisper.cpp submodule integration | none | DONE |
| M2 | Audio Capture Engine | AudioRecorder implementation (16-bit PCM @ 16kHz mono to FloatArray converter, start/stop callbacks) | M1 | DONE |
| M3 | Whisper.cpp JNI Wrapper | whisper-jni.cpp, WhisperEngine Kotlin wrapper, NDK build system, local model loading & inference | M1 | DONE |
| M4 | Android IME Keyboard | OpenFreeIME Kotlin service implementation, Material 3 keyboard UI view, commitText integrations | M2, M3 | DONE |
| M5 | Settings & Downloader | SettingsActivity UI, preferences storage, HuggingFace model download helper | M1 | DONE |
| M6 | E2E Verification & Hardening | Final integrations, passing all test suite tiers (1-4) and adversarial testing (Tier 5) | M2, M3, M4, M5, TEST_READY.md | DONE |
| M7 | Floating Assist Overlay | FloatingOpenFreeService implementation (drag gestures, TYPE_ACCESSIBILITY_OVERLAY, node injection) | M2, M3, M5 | DONE |
| M8 | Offline-Only Hardening | Remove remote fallback + ACCESS_LOCAL_NETWORK, shared JSON DictionaryStore, model storage moved to filesDir, adaptive native thread count | M4, M5, M7 | DONE |
| M9 | Dictation Quality & Live Feedback | Streaming partial transcription, VAD auto-stop, voice editing commands | M8 | DONE |
| M10 | Verification & Play Store Launch | Instrumented test suite, CI, release signing/AAB, store listing submission | M8, M9 | PLANNED |

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
  - `pref_key_model_path`: String (default: path to ggml-base.en-q5_1.bin in app files dir)
  - `pref_key_dictionary_mappings`: String (JSON object of corrections, managed via DictionaryStore)
  - `pref_key_live_preview`: Boolean (default true) — streaming partial transcription
  - `pref_key_vad_auto_stop_seconds`: Int (default 2, 0 = off) — VAD silence auto-stop
  - `pref_key_voice_commands`: Boolean (default true) — spoken editing commands

### StreamingTranscriber ↔ OpenFreeIME / FloatingOpenFreeService (M9)
- `startSession(config: Config, listener: Listener)` — begins a dictation session; `Config.fromPrefs(prefs)` reads the keys above
- `feedChunk(chunk: FloatArray)` — live audio from `AudioRecorder.onChunk`; drives VAD (`Listener.onSilenceTimeout`) and partial passes (`Listener.onPartial`)
- `finishSession(fullSamples: FloatArray): String` — trims silence, runs the final full-context pass (call on a background thread)
- `cancelSession()` — abandon without a final pass

### VoiceCommandProcessor ↔ dictation surfaces (M9)
- `process(text: String): List<TextOp>` — applied after the DictionaryStore pass; surfaces execute `InsertText`/`DeleteLastCommit` ops
- Command set is a registry inside the object — new phrases are one-line additions mirrored in `tests/mock_harness/test_f5_dictation.py`
