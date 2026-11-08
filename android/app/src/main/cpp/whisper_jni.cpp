// Jembatan JNI antara Kotlin (id.ac.uika.everest.whisper.WhisperLib) dan whisper.cpp
#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <algorithm>
#include "whisper.h"

#define TAG "EverestWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_id_ac_uika_everest_whisper_WhisperLib_initContext(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (!ctx) LOGW("gagal memuat model: %s", path);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_id_ac_uika_everest_whisper_WhisperLib_freeContext(JNIEnv *, jobject, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx) whisper_free(ctx);
}

/**
 * Transkripsi PCM float mono 16 kHz. Mengembalikan 0 jika berhasil.
 * language: kode bahasa ("id"); nThreads <= 0 berarti otomatis.
 */
JNIEXPORT jint JNICALL
Java_id_ac_uika_everest_whisper_WhisperLib_fullTranscribe(JNIEnv *env, jobject, jlong ctxPtr,
                                                          jfloatArray audio, jstring language, jint nThreads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (!ctx) return -1;
    const char *lang = env->GetStringUTFChars(language, nullptr);
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    const jsize n = env->GetArrayLength(audio);

    int threads = nThreads > 0 ? nThreads
                               : std::max(1, std::min(4, (int) std::thread::hardware_concurrency() - 1));

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang;
    p.translate = false;
    p.no_context = true;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.suppress_blank = true;
    p.single_segment = false;

    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, p, data, n);
    if (rc != 0) LOGW("whisper_full gagal: %d", rc);

    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    return rc;
}

JNIEXPORT jint JNICALL
Java_id_ac_uika_everest_whisper_WhisperLib_segmentCount(JNIEnv *, jobject, jlong ctxPtr) {
    return whisper_full_n_segments(reinterpret_cast<whisper_context *>(ctxPtr));
}

JNIEXPORT jstring JNICALL
Java_id_ac_uika_everest_whisper_WhisperLib_segmentText(JNIEnv *env, jobject, jlong ctxPtr, jint i) {
    const char *text = whisper_full_get_segment_text(reinterpret_cast<whisper_context *>(ctxPtr), i);
    return env->NewStringUTF(text ? text : "");
}

// t0/t1 dalam satuan 10 ms (konvensi whisper.cpp)
JNIEXPORT jlong JNICALL
Java_id_ac_uika_everest_whisper_WhisperLib_segmentT0(JNIEnv *, jobject, jlong ctxPtr, jint i) {
    return whisper_full_get_segment_t0(reinterpret_cast<whisper_context *>(ctxPtr), i);
}

JNIEXPORT jlong JNICALL
Java_id_ac_uika_everest_whisper_WhisperLib_segmentT1(JNIEnv *, jobject, jlong ctxPtr, jint i) {
    return whisper_full_get_segment_t1(reinterpret_cast<whisper_context *>(ctxPtr), i);
}

JNIEXPORT jstring JNICALL
Java_id_ac_uika_everest_whisper_WhisperLib_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
