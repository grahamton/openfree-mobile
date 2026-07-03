# OpenFree Store Listing Metadata

This document contains pre-formatted metadata templates for the Google Play Console store listing submission.

---

## 1. App Title (Max 30 characters)
**Current Length:** 27 characters
```text
OpenFree Dictation Keyboard
```

---

## 2. Short Description (Max 80 characters)
**Current Length:** 80 characters
```text
An offline-first, private voice dictation and typing keyboard powered by Whisper.
```

---

## 3. Full Description (Max 4,000 characters)
```text
Experience private, fast, and completely offline voice dictation on your Android device.

OpenFree is a hybrid voice-dictation and typing keyboard (Input Method Editor) designed to give you back control of your voice data. Powered by an in-process Whisper.cpp speech-to-text engine running locally, OpenFree transcribes your voice directly on your device without sending your audio to third-party cloud servers.

KEY FEATURES:
• 100% Local & Private: Your microphone input is processed entirely in-process on your device's hardware. Excellent for privacy-conscious users and confidential work.
• Whisper STT Engine: Utilizes high-performance C++ Whisper models optimized for mobile CPUs.
• Hybrid Layout: Easily switch between an animated pulsing Voice Dictation panel, a clean QWERTY typing layout for inline edits, and a quick dictionary corrections screen.
• Customizable Corrections Dictionary: Automatically maps custom shorthand or common voice mistranscriptions to your desired words (e.g., auto-correcting "open free" to "OpenFree").
• Dynamic Model Downloader: Download the highly optimized Whisper models (like ggml-base.en-q5_1) directly from Hugging Face on your first startup. Keeps your initial app installation size under 15 MB.
• No Cloud Dependency: The app has no transcription servers of any kind — once the model is downloaded, dictation works fully offline, forever.

HOW TO ENABLE:
1. Open the app and download your preferred Whisper model.
2. Go to Android Settings -> System -> Languages & input -> On-screen keyboard -> Manage keyboards.
3. Toggle "OpenFree Voice Input" on.
4. Switch to OpenFree by tapping the keyboard icon in the bottom right corner when typing in any app.

PERMISSIONS EXPLAINED:
• Record Audio (android.permission.RECORD_AUDIO): Required to capture voice input for dictation. Audio samples are only used locally by the on-device model and are never stored or sent over the internet.
• Internet: Required only to download the Whisper speech model from Hugging Face on first launch.
```
