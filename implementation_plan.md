# Handoff Design & Implementation Plan: OpenFree Mobile (Android 17)

This document is a standalone design specification and bootstrap guide for **OpenFree Mobile**—an offline-first, local voice-dictation keyboard (IME) for Android, targeting Android 17 (API 37) on the Pixel 10 Pro.

As requested, this plan is formatted for a **clean handoff** to jumpstart a fresh, standalone project folder.

---

## 1. Architectural Landscape & Technology Stack

To build a global system-wide dictation tool on Android, we must register the app as a system-level **Input Method Editor (IME)**. 

### Why Native Kotlin + C++ NDK (Over Hybrid Frameworks)
1. **Memory & CPU Constraints**: Android enforces strict memory limits on keyboards. Under Android 17, background memory limits are even stricter. Running heavy WebView/JavaScript frameworks inside the keyboard leads to high process memory and OS-level terminations.
2. **Lifecycle Management**: An `InputMethodService` has a unique lifecycle tightly controlled by the system. Native Kotlin offers direct control over keyboard input connections, UI inflation, and dynamic model lifecycle hooks.
3. **NDK Integration**: Compiling `whisper.cpp` via the Android NDK and loading it in-process via JNI is straightforward and highly optimized.

### Core Components
* **Keyboard UI & Lifecycle**: Native Kotlin subclassing `InputMethodService` targeting SDK 37. To preserve native RAM, the GGML model is loaded dynamically only when the input view starts and is aggressively unloaded when the input view finishes.
* **Audio Capture**: Android `AudioRecord` API capturing 16-bit PCM at 16kHz mono.
* **On-Device STT**: `whisper.cpp` running in-process via a JNI bridge.
* **AppFunctions Integration**: Uses `androidx.appfunctions` (alpha08+) and Kotlin Symbol Processing (KSP) to expose spelling dictionary corrections directly to on-device AI assistants (e.g., Google Gemini).
* **Remote Fallback STT**: A simple HTTP fallback request to a local Tailscale home lab server. Requires dynamic permission checks for `android.permission.ACCESS_LOCAL_NETWORK` on API 37+ devices.

---

## 2. Directory Structure of the New Project

Initialize a new Android Studio project with a C++ native template. The directory structure will look like this:

```text
openfree-android/
├── app/
│   ├── build.gradle.kts           # Configures Kotlin (2.0.0), target SDK (37), KSP, and NDK
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml # Registers IME service, metadata property, & permissions
│   │   │   ├── cpp/               # C++ Source Code & NDK bindings
│   │   │   │   ├── CMakeLists.txt # Compiles whisper.cpp and JNI wrapper
│   │   │   │   ├── whisper-jni.cpp # JNI wrapper
│   │   │   │   └── whisper.cpp/   # Git submodule of whisper.cpp library
│   │   │   ├── java/com/openfree/client/
│   │   │   │   ├── OpenFreeIME.kt # Core InputMethodService (handles dynamic model caching)
│   │   │   │   ├── WhisperEngine.kt # Kotlin JNI wrapper
│   │   │   │   ├── DictionaryAppFunctions.kt # Exposes dictionary methods to system AI
│   │   │   │   ├── SettingsActivity.kt # Configuration UI (handles ACCESS_LOCAL_NETWORK permission)
│   │   │   │   └── AudioRecorder.kt # Handles mic input buffer
│   │   │   └── res/
│   │   │       ├── xml/
│   │   │       │   ├── method.xml        # Configures keyboard settings metadata
│   │   │       │   └── app_metadata.xml  # Configures AppFunctions indexing description
│   │   │       └── layout/
│   │   │           └── keyboard_view.xml # Dictation panel layout
└── build.gradle.kts               # Top-level Gradle configuration (Kotlin 2.0.0 / AGP 8.9.1 / KSP 2.0.0-1.0.22)
```

---

## 3. Core Native Implementation Details

### AndroidManifest.xml (Permissions & Services)
To register as a system keyboard, support local fallback networking, and declare AppFunctions:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.openfree.client">

    <!-- Permissions -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />

    <application>
        <!-- App Metadata for AppFunctions -->
        <property
            android:name="android.app.appfunctions.app_metadata"
            android:resource="@xml/app_metadata" />

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
                <action android:name="android.view.InputMethod" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

### xml/method.xml (IME Registration)
```xml
<?xml version="1.0" encoding="utf-8"?>
<input-method xmlns:android="http://schemas.android.com/apk/res/android"
    android:settingsActivity="com.openfree.client.SettingsActivity" />
```

### xml/app_metadata.xml (AppFunctions Registration)
```xml
<AppFunctionAppMetadata 
    xmlns:appfn="http://schemas.android.com/apk/androidx.appfunctions" 
    appfn:description="Exposes personal dictionary and keyboard actions to system AI." />
```

### Kotlin IME Class (`OpenFreeIME.kt`)
The service coordinates recording, runs transcription, and dynamically loads/unloads models to minimize process memory overhead:
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
    private var loadedModelPath: String? = null

    override fun onCreate() {
        super.onCreate()
        whisper = WhisperEngine(applicationContext)
        recorder = AudioRecorder()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Dynamically load the model when keyboard displays
        reloadModel() 
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (isRecording) {
            recorder.stopRecording()
        }
        // Aggressively unload model from native memory when keyboard is hidden
        whisper.unloadModel()
        loadedModelPath = null
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
                    Thread {
                        val text = whisper.transcribe(samples)
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

    private fun reloadModel() {
        val modelPath = getSharedPreferences("openfree_prefs", MODE_PRIVATE)
            .getString("pref_key_model_path", "") ?: ""
        if (modelPath.isNotBlank() && modelPath != loadedModelPath) {
            Thread {
                if (whisper.loadModel(modelPath)) {
                    loadedModelPath = modelPath
                }
            }.start()
        }
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

set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")
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
Java_com_openfree_client_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audio_samples) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    
    jsize len = env->GetArrayLength(audio_samples);
    jfloat *samples = env->GetFloatArrayElements(audio_samples, nullptr);
    
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 4;
    params.print_timestamps = false;
    
    whisper_full(ctx, params, samples, len);
    
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
2. **Setup Build Plugin Environment**:
    * Configure root `build.gradle.kts` using: Android Gradle Plugin `8.9.1`, Kotlin `2.0.0`, and KSP plugin `2.0.0-1.0.22`.
    * Configure target SDK and compile SDK to `37` in `app/build.gradle.kts`.
3. **Configure Dependencies**: Add `androidx.appfunctions:appfunctions:1.0.0-alpha08` and the corresponding compiler compiler plugin to process `@AppFunction`.
4. **Git Submodule**: Add `whisper.cpp` as a git submodule inside `app/src/main/cpp/`.
5. **NDK Setup**: Ensure Android SDK, NDK (`26.1.10909125`), and CMake are installed inside Android Studio's SDK Manager. Ensure Platform SDK 37 is installed (`android sdk install platforms/android-37.0`).
6. **Implement UI, IME & AppFunctions**: Copy the Kotlin IME code (`OpenFreeIME.kt`), register `@AppFunction` entry points in `DictionaryAppFunctions.kt`, and declare the metadata properties.
7. **Deploy & Test**: Connect the device via USB, build the app using `./gradlew assembleDebug`, enable "OpenFree Voice Keyboard" in Android settings, and verify speech-to-text dictation and AppFunctions indexability.
