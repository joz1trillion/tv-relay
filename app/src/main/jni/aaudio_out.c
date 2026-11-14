// AAudio output stream in EXCLUSIVE sharing mode: when the phone grants it, samples go into an
// MMAP buffer read directly by the audio DSP, skipping the system mixer entirely.
#include <jni.h>
#include <aaudio/AAudio.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

typedef struct {
    AAudioStream *stream;
    int32_t rate;
    int32_t channels;
    int32_t burst;
    int32_t size;
    int32_t xruns;
    int16_t *tmp;
    int32_t tmpFrames;
} Out;

JNIEXPORT jlong JNICALL
Java_com_joz_tvrelay_AAudioOut_nativeOpen(JNIEnv *env, jclass cls, jint rate, jint channels) {
    AAudioStreamBuilder *b;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return 0;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setChannelCount(b, channels);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MOVIE);

    AAudioStream *s;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) return 0;

    Out *o = calloc(1, sizeof(Out));
    o->stream = s;
    o->rate = rate;
    o->channels = channels;
    o->burst = AAudioStream_getFramesPerBurst(s);
    // Two bursts is the usual floor; grows on underrun like Oboe's latency tuner.
    int32_t size = AAudioStream_setBufferSizeInFrames(s, o->burst * 2);
    o->size = size > 0 ? size : AAudioStream_getBufferSizeInFrames(s);
    if (AAudioStream_requestStart(s) != AAUDIO_OK) {
        AAudioStream_close(s);
        free(o);
        return 0;
    }
    return (jlong) (intptr_t) o;
}

JNIEXPORT jint JNICALL
Java_com_joz_tvrelay_AAudioOut_nativeWrite(JNIEnv *env, jclass cls, jlong h, jshortArray buf, jint frames) {
    Out *o = (Out *) (intptr_t) h;
    if (frames > o->tmpFrames) {
        free(o->tmp);
        o->tmp = malloc((size_t) frames * o->channels * sizeof(int16_t));
        o->tmpFrames = frames;
    }
    (*env)->GetShortArrayRegion(env, buf, 0, frames * o->channels, o->tmp);
    aaudio_result_t r = AAudioStream_write(o->stream, o->tmp, frames, 200 * 1000000LL);

    int32_t x = AAudioStream_getXRunCount(o->stream);
    if (x > o->xruns) {
        o->xruns = x;
        if (o->size < AAudioStream_getBufferCapacityInFrames(o->stream)) {
            int32_t size = AAudioStream_setBufferSizeInFrames(o->stream, o->size + o->burst);
            if (size > 0) o->size = size;
        }
    }
    return r; // negative = error, e.g. AAUDIO_ERROR_DISCONNECTED when the route changes
}

JNIEXPORT jfloat JNICALL
Java_com_joz_tvrelay_AAudioOut_nativeLatencyMs(JNIEnv *env, jclass cls, jlong h) {
    Out *o = (Out *) (intptr_t) h;
    int64_t pos, t;
    if (AAudioStream_getTimestamp(o->stream, CLOCK_MONOTONIC, &pos, &t) != AAUDIO_OK) return 0;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    int64_t nowNs = (int64_t) now.tv_sec * 1000000000LL + now.tv_nsec;
    float queued = (float) (AAudioStream_getFramesWritten(o->stream) - pos) * 1000.0f / o->rate;
    float since = (float) (nowNs - t) / 1e6f;
    float ms = queued - since;
    return ms > 0 ? ms : 0;
}

JNIEXPORT jint JNICALL
Java_com_joz_tvrelay_AAudioOut_nativeXRuns(JNIEnv *env, jclass cls, jlong h) {
    return ((Out *) (intptr_t) h)->xruns;
}

JNIEXPORT jstring JNICALL
Java_com_joz_tvrelay_AAudioOut_nativeInfo(JNIEnv *env, jclass cls, jlong h) {
    Out *o = (Out *) (intptr_t) h;
    // Exclusive is only ever granted on the MMAP path, so it doubles as the "MMAP in use" check.
    int exclusive = AAudioStream_getSharingMode(o->stream) == AAUDIO_SHARING_MODE_EXCLUSIVE;
    int fast = AAudioStream_getPerformanceMode(o->stream) == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    char s[160];
    snprintf(s, sizeof s, "AAudio %s%s, device buffer %d ms (burst %d)",
             exclusive ? "EXCLUSIVE (MMAP)" : "shared - phone refused exclusive",
             fast ? "" : ", not low-latency",
             o->size * 1000 / o->rate, o->burst);
    return (*env)->NewStringUTF(env, s);
}

JNIEXPORT void JNICALL
Java_com_joz_tvrelay_AAudioOut_nativeStop(JNIEnv *env, jclass cls, jlong h) {
    AAudioStream_requestStop(((Out *) (intptr_t) h)->stream);
}

JNIEXPORT void JNICALL
Java_com_joz_tvrelay_AAudioOut_nativeClose(JNIEnv *env, jclass cls, jlong h) {
    Out *o = (Out *) (intptr_t) h;
    AAudioStream_close(o->stream);
    free(o->tmp);
    free(o);
}
