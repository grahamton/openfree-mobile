#include "jni.h"
#include <string.h>
#include <stdio.h>

#ifdef _WIN32
#define STRNCPY(dest, src, size) strncpy_s(dest, size, src, _TRUNCATE)
#else
#include <string.h>
#define STRNCPY(dest, src, size) { strncpy(dest, src, size - 1); dest[size - 1] = '\0'; }
#endif

struct MockFloatArray {
    int length;
    float* data;
};

static bool g_model_loaded = false;
static char g_transcription[1024] = "mocked whisper transcription";
static int g_thread_count = 4;
static bool g_jni_error_state = false;

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_openfree_client_WhisperEngine_loadModel(JNIEnv *env, jobject thiz, jstring modelPath) {
    if (g_jni_error_state) {
        g_model_loaded = false;
        return JNI_FALSE;
    }
    const char* path = (const char*)modelPath;
    if (path != nullptr && strlen(path) > 0) {
        // Attempt to open the path to verify it's a valid model file
        FILE* f = fopen(path, "rb");
        if (!f) {
            g_model_loaded = false;
            return JNI_FALSE;
        }
        fclose(f);
    }
    
    g_model_loaded = true;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_com_openfree_client_WhisperEngine_transcribe(JNIEnv *env, jobject thiz, jfloatArray audioSamples) {
    if (g_jni_error_state || !g_model_loaded) {
        return (jstring)"";
    }
    if (audioSamples != nullptr) {
        MockFloatArray* arr = (MockFloatArray*)audioSamples;
        if (arr->length <= 0) {
            return (jstring)"";
        }
        if (arr->length < 100) {
            return (jstring)"audio_too_short";
        }
    }
    return (jstring)g_transcription;
}

JNIEXPORT jstring JNICALL Java_com_openfree_client_WhisperEngine_transcribeBytes(JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audioSamples) {
    if (g_jni_error_state || !g_model_loaded) {
        return (jstring)"";
    }
    if (audioSamples != nullptr) {
        MockFloatArray* arr = (MockFloatArray*)audioSamples;
        if (arr->length <= 0) {
            return (jstring)"";
        }
        if (arr->length < 100) {
            return (jstring)"audio_too_short";
        }
    }
    return (jstring)g_transcription;
}

JNIEXPORT void JNICALL Java_com_openfree_client_WhisperEngine_unloadModel(JNIEnv *env, jobject thiz) {
    g_model_loaded = false;
}

JNIEXPORT jboolean JNICALL Java_com_openfree_client_WhisperEngine_setThreadCount(JNIEnv *env, jobject thiz, jint threads) {
    if (threads <= 0 || threads > 64) {
        return JNI_FALSE;
    }
    g_thread_count = threads;
    return JNI_TRUE;
}

// Helper methods for ctypes validation
JNIEXPORT jboolean JNICALL GetMockModelLoadedState() {
    return g_model_loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL SetMockModelLoadedState(jboolean state) {
    g_model_loaded = (state == JNI_TRUE);
}

JNIEXPORT const char* JNICALL GetMockTranscription() {
    return g_transcription;
}

JNIEXPORT void JNICALL SetMockTranscription(const char* text) {
    if (text != nullptr) {
        STRNCPY(g_transcription, text, sizeof(g_transcription));
    } else {
        g_transcription[0] = '\0';
    }
}

JNIEXPORT void JNICALL SetMockJniErrorState(jboolean errorState) {
    g_jni_error_state = (errorState == JNI_TRUE);
}

JNIEXPORT jint JNICALL GetMockThreadCount() {
    return g_thread_count;
}

}
