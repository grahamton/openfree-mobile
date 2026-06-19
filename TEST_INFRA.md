# Test Infrastructure Configuration (TEST_INFRA.md)

This document specifies the E2E Test Infrastructure for the OpenFree Android Voice-Dictation Keyboard application.

## 1. Feature Inventory
The E2E Test Infrastructure verifies the following core application features at their public boundaries:

### F1: Keyboard UI & Lifecycle
- **Scope**: Registers as a system-level IME service and implements the dictation interface.
- **Boundaries**: 
  - `AndroidManifest.xml` IME service declaration, binding permissions, and launcher activities.
  - `app/src/main/res/xml/method.xml` IME registration configuration.
  - `app/src/main/res/layout/keyboard_view.xml` keyboard interface layout.
  - Fieldwork color scheme compliance:
    - Background/Surface tint: `#19120e`
    - Primary tint: `#ffb691`
    - Secondary tint: `#9dd3a8`
    - Text color: `#ece7de`

### F2: Audio Capture Engine
- **Scope**: Recording audio using the `AudioRecord` API at 16-bit PCM @ 16kHz mono.
- **Boundaries**:
  - `AudioRecorder.startRecording(onAudioSamplesReady: (FloatArray) -> Unit)`
  - `AudioRecorder.stopRecording()`
  - Audio normalization converter from 16-bit signed PCM samples to FloatArray normalized in the range `[-1.0, 1.0]`.
  - Proper callback dispatching and boundary checks (empty audio, extreme values, silent periods).

### F3: Whisper.cpp JNI Wrapper
- **Scope**: Executing local in-process STT inference using `whisper.cpp` NDK bindings.
- **Boundaries**:
  - `WhisperEngine.loadModel(modelPath: String): Boolean`
  - `WhisperEngine.transcribe(audioSamples: FloatArray): String`
  - `WhisperEngine.unloadModel()`
  - Dual environment verification: On development machines (e.g., Windows), compiles a mock JNI native library and verifies JNI logic via `ctypes` bindings.

### F4: Settings UI & Model Downloader
- **Scope**: Manages user configuration preferences and on-device model downloading.
- **Boundaries**:
  - Android SharedPreferences keys:
    - `pref_key_model_path` (default: path to `ggml-base.en-q5_1.bin` in app cache)
    - `pref_key_remote_fallback_url` (optional home lab fallback server URL)
  - Downloader component for downloading models from Hugging Face offline-compliant endpoints (mocked using a local Python HTTP server).

---

## 2. Test Architecture
The test suite utilizes a Python-based test runner (`tests/run_tests.py`) supporting two primary execution modes:

```
                  +---------------------+
                  |  tests/run_tests.py |
                  +----------+----------+
                             |
              +--------------+--------------+
              |                             |
      [--mode mock]                 [--mode emulator]
              |                             |
     v----------------v            v-----------------v
     | Python unittest |            | ADB / Emulator  |
     |  (mock_harness) |            | Gradle Android  |
     +-----------------+            +-----------------+
```

### Dual-Mode Execution

#### A. Mock Mode (`--mode mock` - Default)
- **Objective**: Lightweight, offline-only, fast unit/integration validation.
- **Implementation**: Runs Python `unittest` test suite inside `tests/mock_harness/`.
- **Offline Guarantee**: The runner spawns a local HTTP mock server on a random port for any test requiring network communication. No external network connections are established.
- **Platform Portability**: Native libraries are tested via ctypes loading a mock C++ DLL locally compiled using a local C++ compiler (CMake/MSBuild).

#### B. Emulator Mode (`--mode emulator`)
- **Objective**: Full E2E device verification on target emulator.
- **Behavior**:
  1. Detects if emulator `'Medium_Phone_API_36.1'` is already running.
  2. If not running, launches the emulator `'Medium_Phone_API_36.1'`.
  3. Waits for the emulator to fully boot by monitoring the `sys.boot_completed` ADB property.
  4. Runs the Android instrumented tests via Gradle: `./gradlew connectedAndroidTest`.
  5. Shuts down the emulator *only if* the script was responsible for starting it.
  6. Returns exit code `0` on success and non-zero on test failures.

---

## 3. Coverage Thresholds

To pass quality gateways, the test suite must adhere to these minimum requirements:
1. **Tier 1: Feature Coverage**: At least 5 test cases per feature (F1-F4) verifying happy-path behavior.
2. **Tier 2: Boundary & Corner Cases**: At least 5 test cases per feature verifying boundaries, invalid inputs, overflow, and error states.
3. **Tier 3: Cross-Feature Combinations**: At least 4 test cases verifying pairwise integrations (e.g., AudioRecorder feeding WhisperEngine, Settings changes impacting Downloader).
4. **Tier 4: Real-World Scenarios**: At least 5 test cases verifying full end-to-end user journeys (e.g., download model -> change settings -> record audio -> transcribe -> IME text output).
