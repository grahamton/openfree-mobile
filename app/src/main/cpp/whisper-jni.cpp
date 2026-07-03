/**
 * whisper-jni.cpp
 *
 * JNI bridge between Android Kotlin code and whisper.cpp.
 * Exposes model loading, transcription, and teardown to WhisperEngine.kt.
 *
 * JNI function naming convention:
 *   Java_<package_underscored>_<ClassName>_<methodName>
 */

#include <jni.h>
#include <string>
#include <algorithm>
#include <thread>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG  "OpenFreeJNI"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// ── Model lifecycle ─────────────────────────────────────────────────────────

/**
 * Load a ggml model from a file path.
 * Returns a non-zero context pointer on success, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_openfree_client_WhisperEngine_nativeLoadModel(
        JNIEnv *env,
        jobject /* thiz */,
        jstring modelPath) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGE("nativeLoadModel: modelPath GetStringUTFChars returned null");
        return 0L;
    }
    LOGI("nativeLoadModel: loading from %s", path);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // Keep on CPU for consistent cross-device behaviour
    cparams.flash_attn = false; // Disable flash_attn to prevent SIGABRT assertions inside ggml-cpu flash attention

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("nativeLoadModel: whisper_init_from_file_with_params returned null");
        return 0L;
    }

    LOGI("nativeLoadModel: success, ctx=%p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Free a previously loaded whisper context.
 */
JNIEXPORT void JNICALL
Java_com_openfree_client_WhisperEngine_nativeUnloadModel(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong contextPtr) {

    auto *ctx = reinterpret_cast<whisper_context *>(contextPtr);
    if (ctx != nullptr) {
        whisper_free(ctx);
        LOGI("nativeUnloadModel: context freed");
    }
}

// ── Transcription ────────────────────────────────────────────────────────────

/**
 * Run Whisper inference on the supplied float audio samples.
 *
 * @param contextPtr  Pointer to whisper_context (returned by nativeLoadModel).
 * @param audioSamples Float array of PCM samples normalised to [-1.0, 1.0] at 16kHz mono.
 * @return            Transcribed text, or an empty string on failure.
 */
JNIEXPORT jstring JNICALL
Java_com_openfree_client_WhisperEngine_nativeTranscribe(
        JNIEnv *env,
        jobject /* thiz */,
        jlong   contextPtr,
        jfloatArray audioSamples) {

    auto *ctx = reinterpret_cast<whisper_context *>(contextPtr);
    if (ctx == nullptr) {
        LOGE("nativeTranscribe: null context");
        return env->NewStringUTF("");
    }

    jsize n_samples = env->GetArrayLength(audioSamples);
    if (n_samples <= 0) {
        LOGW("nativeTranscribe: empty audio array");
        return env->NewStringUTF("");
    }

    jfloat *samples = env->GetFloatArrayElements(audioSamples, nullptr);
    if (samples == nullptr) {
        LOGE("nativeTranscribe: GetFloatArrayElements returned null");
        return env->NewStringUTF("");
    }

    // Configure greedy decoding params optimised for mobile dictation.
    // Use roughly half the cores (clamped to [2, 6]) — big.LITTLE phones throttle
    // and can even slow down when whisper saturates every core.
    const int hw_threads = static_cast<int>(std::thread::hardware_concurrency());
    const int n_threads  = std::min(6, std::max(2, hw_threads / 2));

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads         = n_threads;
    params.language          = "en";
    params.translate         = false;
    params.no_context        = true;   // No cross-segment context for low latency
    params.single_segment    = false;
    params.print_timestamps  = false;
    params.print_realtime    = false;
    params.print_progress    = false;

    LOGI("nativeTranscribe: running inference on %d samples with %d threads", n_samples, n_threads);
    int rc = whisper_full(ctx, params, samples, static_cast<int>(n_samples));

    env->ReleaseFloatArrayElements(audioSamples, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("nativeTranscribe: whisper_full returned %d", rc);
        return env->NewStringUTF("");
    }

    // Concatenate all output segments
    std::string result;
    const int n_seg = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_seg; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text) {
            result += text;
        }
    }

    LOGI("nativeTranscribe: result=\"%s\"", result.c_str());
    return env->NewStringUTF(result.c_str());
}

// ── Utility ──────────────────────────────────────────────────────────────────

/**
 * Return a human-readable string of compiled Whisper capabilities.
 * Useful for diagnostics / settings screen.
 */
JNIEXPORT jstring JNICALL
Java_com_openfree_client_WhisperEngine_nativeGetSystemInfo(
        JNIEnv *env,
        jobject /* thiz */) {
    const char *info = whisper_print_system_info();
    return env->NewStringUTF(info ? info : "");
}

// ── Legacy test stub (kept for backward compatibility with stub skeletons) ──

JNIEXPORT jstring JNICALL
Java_com_openfree_client_OpenFreeIME_stringFromJNI(
        JNIEnv *env,
        jobject /* thiz */) {
    const char *info = whisper_print_system_info();
    return env->NewStringUTF(info ? info : "");
}

} // extern "C"
