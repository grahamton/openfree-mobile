# Original User Request

## Initial Request — 2026-06-18T20:51:44-07:00

<USER_REQUEST>
An offline-first, local voice-dictation keyboard (IME) application for Android (targeting Pixel 10 Pro) that records audio and transcribes it using whisper.cpp running in-process via JNI/NDK, with remote fallback capabilities.

Working directory: c:\dev\android-dictation
Integrity mode: benchmark

## Requirements

### R1. Android Input Method Editor (IME) Service
- Subclass native Kotlin `InputMethodService` to create a custom voice-keyboard.
- Register the service in `AndroidManifest.xml` with appropriate IME and audio permissions.
- Design a compact UI keyboard panel following the "Fieldwork" design system color scheme specified in `C:\assets\design system\DESIGN.md` (e.g. background `#19120e`, surface `#19120e`, primary tint `#ffb691`, secondary `#9dd3a8`, text `#ece7de`).
- The keyboard UI must display a microphone activation button, dynamic recording status, and a transcription preview panel.
- Commit transcribed text back to the active input connection.

### R2. Audio Recording Engine
- Capture 16-bit PCM mono audio at 16kHz via Android `AudioRecord` API and convert it to floating-point sample arrays.
- Provide callback mechanisms for transmitting recorded chunk buffers.

### R3. In-Process Whisper.cpp Transcription
- Compile `whisper.cpp` via Android NDK and CMake.
- Use a JNI bridge (`whisper-jni.cpp`) to load a local `.bin` Whisper model and transcribe the captured audio samples.
- Support loading model paths dynamically (e.g. from app cache).

### R4. Settings & Configuration UI
- Create a Settings activity allowing configuration of local model paths, remote fallback URL endpoint, and a basic downloader to pull models from Hugging Face (`https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin`).

## Acceptance Criteria

### Build & Structure Verification
- [ ] Project directory structure is successfully bootstrapped with standard Android-NDK layout.
- [ ] Root-level Gradle configurations (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`) are valid and successfully parsed.
- [ ] App-level Android manifest contains standard IME intent-filters and records audio permissions correctly.

### UI Styling Verification
- [ ] Keyboard UI panel layout uses colors matching the "Fieldwork" design system theme (background `#19120e`, primary `#ffb691`, text `#ece7de`).
</USER_REQUEST>
