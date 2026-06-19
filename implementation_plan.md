# Handoff Design & Implementation Plan: OpenFree Mobile (Android)

This document is a standalone design specification and bootstrap guide for **OpenFree Mobile**—an offline-first, local voice-dictation keyboard (IME) for Android (targeting Pixel 10 Pro). 

As requested, this plan is formatted for a **clean handoff** to jumpstart a fresh, standalone project folder.

---

## 1. Architectural Landscape & Technology Stack

To build a global system-wide dictation tool on Android, we must register the app as a system-level **Input Method Editor (IME)**. 

### Why Native Kotlin + C++ NDK (Over Hybrid Frameworks)
While Tauri 2.0 or React Native are great for standard screen-based apps, packaging a custom keyboard service (IME) under them is problematic:
1. **Memory & CPU Constraints**: Android enforces strict memory limits on keyboards. Running a heavy WebView (Tauri) or JavaScript engine (React Native) inside an active keyboard view leads to high memory overhead, causing the Android OS to frequently kill the keyboard process.
2. **Lifecycle Management**: An `InputMethodService` has a unique lifecycle tightly controlled by the system. Native Kotlin offers direct control over keyboard input connections and UI inflation.
3. **NDK Integration**: Compiling `whisper.cpp` via the Android NDK and loading it in-process via JNI is straightforward and highly optimized in a native Kotlin setup (reusing the official `whisper.cpp` Android example architecture).

### Core Components
* **Keyboard UI & Lifecycle**: Native Kotlin subclassing `InputMethodService`. It displays a simple voice-typing panel (mic button, progress wave, preview text) when active.
* **Audio Capture**: Android `AudioRecord` API capturing 16-bit PCM at 16kHz mono.
* **On-Device STT**: `whisper.cpp` running in-process via a JNI bridge, loading models (e.g. `base.en` or `large-v3-turbo`) on the Pixel 10's CPU/GPU.
* **Remote Fallback STT**: A simple HTTP multipart request to a configured home lab server IP (Tailscale).

---

## 2. Directory Structure of the New Project

Initialize a new Android Studio project with a C++ native template. The directory structure will look like this:

```text
openfree-android/
├── app/
│   ├── build.gradle.kts           # Configures Kotlin, Android SDK, and NDK paths
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml # Registers IME service & audio permissions
│   │   │   ├── cpp/               # C++ Source Code & NDK bindings
│   │   │   │   ├── CMakeLists.txt # Compiles whisper.cpp and JNI wrapper
│   │   │   │   ├── whisper-jni.cpp # JNI wrapper communicating with Rust/C++
│   │   │   │   └── whisper.cpp/   # Git submodule of whisper.cpp library
│   │   │   ├── java/com/openfree/client/
│   │   │   │   ├── OpenFreeIME.kt # The core InputMethodService
│   │   │   │   ├── WhisperEngine.kt # Kotlin wrapper for the JNI library
│   │   │   │   ├── SettingsActivity.kt # App configuration UI (model paths, remote URL)
│   │   │   │   └── AudioRecorder.kt # Handles mic input buffer
│   │   │   └── res/
│   │   │       ├── xml/
│   │   │       │   └── method.xml  # Configures keyboard settings metadata
│   │   │       └── layout/
│   │   │           └── keyboard_view.xml # Dictation panel layout
└── build.gradle.kts
```

---

## 3. Core Native Implementation Details

### AndroidManifest.xml (Permissions & Services)
To register as a system keyboard and record audio, the manifest must declare:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.openfree.client">

    <!-- Permissions -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application>
        <!-- App Settings UI -->
        <activity android:name=".SettingsActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- The Custom Keyboard Service -->
        <service
            android:name=".OpenFreeIME"
            android:label="OpenFree Voice Input"
            android:permission="android.permission.BIND_INPUT_METHOD"
            android:exported="true">
            <meta-data
                android:name="android.view.im"
                android:resource="@xml/method" />
            <intent-filter>
                <action android:name="view.InputMethod" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

### xml/method.xml (IME registration)
```xml
<?xml version="1.0" encoding="utf-8"?>
<input-method xmlns:android="http://schemas.android.com/apk/res/android"
    android:settingsActivity="com.openfree.client.SettingsActivity" />
```

### Kotlin IME Class (`OpenFreeIME.kt`)
The service manages the keyboard lifecycle, captures audio on button tap, runs the Whisper JNI engine, and injects text:
```kotlin
package com.openfree.client

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView

class OpenFreeIME : InputMethodService() {
    private var isRecording = false
    private lateinit var recorder: AudioRecorder
    private lateinit var whisper: WhisperEngine

    override fun onCreate() {
        super.onCreate()
        whisper = WhisperEngine(applicationContext)
        recorder = AudioRecorder()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        val micButton = view.findViewById<ImageButton>(R.id.btn_mic)
        val statusText = view.findViewById<TextView>(R.id.txt_status)

        micButton.setOnClickListener {
            if (!isRecording) {
                isRecording = true
                statusText.text = "Listening..."
                recorder.startRecording { samples ->
                    // Transcribe in background thread
                    Thread {
                        val text = whisper.transcribe(samples)
                        // Inject into active input connection on UI thread
                        mainExecutor.execute {
                            val ic = currentInputConnection
                            ic?.commitText(text, 1)
                            statusText.text = "Idle"
                            isRecording = false
                        }
                    }.start()
                }
            } else {
                recorder.stopRecording()
            }
        }
        return view
    }
}
```

---

## 4. whisper.cpp NDK / JNI Configuration

To compile and load `whisper.cpp` inside Android:

### CMakeLists.txt (`app/src/main/cpp/CMakeLists.txt`)
```cmake
cmake_minimum_required(VERSION 3.22.1)
project("openfree_native")

# Set paths to whisper.cpp submodule
set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")

# Enable OpenMP / CPU optimizations
set(GGML_OPENMP ON CACHE BOOL "Use OpenMP" FORCE)

add_subdirectory("${WHISPER_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp")

add_library(${PROJECT_NAME} SHARED
    whisper-jni.cpp
)

target_link_libraries(${PROJECT_NAME}
    whisper
    android
    log
)
```

### JNI Bridge (`whisper-jni.cpp`)
Exposes C++ Whisper calls to Kotlin:
```cpp
#include <jni.h>
#include <android/log.h>
#include "whisper.h"

extern "C"
JNIEXPORT jstring JNICALL
Java_com_openfree_client_WhisperEngine_transcribeBytes(JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audio_samples) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    
    // Get audio data
    jsize len = env->GetArrayLength(audio_samples);
    jfloat *samples = env->GetFloatArrayElements(audio_samples, nullptr);
    
    // Configure Whisper params
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 4; // Thread count optimization for mobile
    params.print_timestamps = false;
    
    // Run inference
    whisper_full(ctx, params, samples, len);
    
    // Extract text
    std::string result = "";
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        result += whisper_full_get_segment_text(ctx, i);
    }
    
    env->ReleaseFloatArrayElements(audio_samples, samples, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}
```

---

## 5. Development Steps for Handoff

1. **Bootstrap the Project**: Open Android Studio and create a new project named `OpenFree` using the "Native C++" template.
2. **Git Submodule**: Add `whisper.cpp` as a git submodule inside `app/src/main/cpp/`.
3. **NDK Setup**: Ensure Android SDK, NDK, and CMake are installed inside Android Studio's SDK Manager.
4. **Implement UI & IME**: Copy the Kotlin input method service code (`OpenFreeIME.kt`) and register it in `AndroidManifest.xml`.
5. **Model Loader**: Add a model downloader in the Settings activity to pull `.bin` models directly from Hugging Face into the app's cache directory:
   `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin`
6. **Deploy & Test**: Connect the Pixel 10 Pro via USB, build the app, enable "OpenFree Voice Keyboard" in Android Settings -> Languages & Input, and test dictating into messaging apps.
