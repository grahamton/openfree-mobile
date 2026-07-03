# Privacy Policy for OpenFree Dictation Keyboard

Last Updated: July 2, 2026

OpenFree ("we", "our", or "us") respects your privacy and is committed to protecting it. This Privacy Policy describes how we handle information when you use the OpenFree Voice-Dictation Keyboard mobile application (the "App") on Android devices.

---

## 1. Information We Collect and How We Use It

### A. Microphone Audio Data
* **Collection**: To provide voice-dictation functionality, the App requests permission to access your device's microphone (`android.permission.RECORD_AUDIO`).
* **Processing**: 
  - **Local Speech-to-Text**: All audio captured is processed entirely **locally and in-process on your device** using the offline Whisper speech-to-text engine. Audio data is streamed as raw samples directly into the local engine memory and is discarded immediately after transcription finishes.
  - **No Transmission**: We do **not** upload, stream, or transmit your voice recordings, audio samples, or text transcriptions to any external server or third party. The App contains no capability to send audio or transcriptions off the device.

### B. Corrections Dictionary Mappings
* The App allows you to configure a local corrections dictionary to map common voice mistranscriptions to your desired words. This mapping database is stored locally inside your device's private SharedPreferences storage and never leaves your device.

### C. Personal Data & Device Identifiers
* OpenFree does not collect, track, or share any personal identifying information (PII), device identifiers (such as IMEI or Android ID), or usage analytics.

---

## 2. Permissions Used
The App requires the following permissions to function:
1. **RECORD_AUDIO**: Required to record audio from the microphone for real-time transcription.
2. **INTERNET**: Required solely to download the offline Whisper model files from Hugging Face during initial setup. It is never used during dictation.

---

## 3. Data Retention
Because we do not collect or upload your data to any server, we do not store or retain any of your personal data, audio recordings, or transcriptions.

---

## 4. Children's Privacy
Our App does not collect any data from anyone, including children under the age of 13. Since no data is transmitted or collected, the App is fully compliant with children's privacy regulations.

---

## 5. Changes to This Privacy Policy
We may update this Privacy Policy from time to time. Any changes will be posted on this page with an updated "Last Updated" date.

---

## 6. Contact Us
If you have any questions or feedback about this Privacy Policy, please open an issue on our GitHub repository.
