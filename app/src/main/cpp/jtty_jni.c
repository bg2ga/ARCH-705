// JNI bridge for JTTY (cpp/jtty: the C port of WSJT-X Improved 3.2.1's lib/jtty, GPL v3). The receiver is fed on the
// app's JTTY worker thread and read by the page, so every call holds one lock.
#include <jni.h>                                      // JNI
#include <pthread.h>                                  // the lock
#include <stdio.h>                                    // snprintf
#include <stdlib.h>                                   // malloc
#include "jtty/jtty.h"                                // the port

static pthread_mutex_t g_mx = PTHREAD_MUTEX_INITIALIZER; // one caller at a time

// Start receiving afresh.
JNIEXPORT void JNICALL Java_uk_hamdigital_engine_JttyNative_reset(JNIEnv *env, jclass cls)
{
    pthread_mutex_lock(&g_mx); jtty_rx_reset(); pthread_mutex_unlock(&g_mx);
}

// 12 kHz audio in; decodes every whole quarter-frame step. f0 / ftol: receive frequency and search width (Hz);
// nfa / nfb: the band searched beside it (Hz).
JNIEXPORT void JNICALL Java_uk_hamdigital_engine_JttyNative_feed(JNIEnv *env, jclass cls, jshortArray jsamples, jint n, jfloat f0, jfloat ftol, jint nfa, jint nfb)
{
    jshort *s = (*env)->GetShortArrayElements(env, jsamples, NULL); // the audio
    pthread_mutex_lock(&g_mx); jtty_rx_feed(s, n, f0, ftol, nfa, nfb); pthread_mutex_unlock(&g_mx);
    (*env)->ReleaseShortArrayElements(env, jsamples, s, JNI_ABORT); // unchanged
}

// Messages started, grown or completed since the last call: "id\tfreq\tstart\tcomplete\tsnr\ttext" each.
JNIEXPORT jobjectArray JNICALL Java_uk_hamdigital_engine_JttyNative_updates(JNIEnv *env, jclass cls)
{
    static jtty_update_t u[64]; char line[160];       // (one caller at a time)
    pthread_mutex_lock(&g_mx); int n = jtty_rx_updates(u, 64); pthread_mutex_unlock(&g_mx);
    jobjectArray out = (*env)->NewObjectArray(env, n, (*env)->FindClass(env, "java/lang/String"), NULL);
    for (int i = 0; i < n; i++) {
        snprintf(line, sizeof line, "%lld\t%.1f\t%.3f\t%d\t%d\t%s", u[i].id, u[i].freq, u[i].start, u[i].complete ? 1 : 0, u[i].snr, u[i].text);
        (*env)->SetObjectArrayElement(env, out, i, (*env)->NewStringUTF(env, line));
    }
    return out;
}

// How [text] will go out: "frames\tnormalized text" (frames 0: nothing to send, -1: it cannot be sent).
JNIEXPORT jstring JNICALL Java_uk_hamdigital_engine_JttyNative_pack(JNIEnv *env, jclass cls, jstring jtext, jint profile)
{
    const char *text = (*env)->GetStringUTFChars(env, jtext, NULL);
    static uint8_t frames[JTTY_MAX_FRAMES][JTTY_PAYLOAD_BITS]; char norm[JTTY_MSG_LEN + 1] = "", out[JTTY_MSG_LEN + 16];
    pthread_mutex_lock(&g_mx); int n = jtty_pack_message(text, profile, frames, norm); pthread_mutex_unlock(&g_mx);
    (*env)->ReleaseStringUTFChars(env, jtext, text);
    snprintf(out, sizeof out, "%d\t%s", n, norm);
    return (*env)->NewStringUTF(env, out);
}

// Transmit audio for [text] (12 kHz, lowest tone f0 Hz, peak amplitude 0..1); null if it cannot be sent.
JNIEXPORT jshortArray JNICALL Java_uk_hamdigital_engine_JttyNative_encode(JNIEnv *env, jclass cls, jstring jtext, jint profile, jfloat f0, jfloat amplitude)
{
    const char *text = (*env)->GetStringUTFChars(env, jtext, NULL);
    static int tones[JTTY_MAX_FRAMES * JTTY_NSYM]; char norm[JTTY_MSG_LEN + 1];
    pthread_mutex_lock(&g_mx); int nsym = jtty_message_tones(text, profile, tones, JTTY_MAX_FRAMES * JTTY_NSYM, norm); pthread_mutex_unlock(&g_mx);
    (*env)->ReleaseStringUTFChars(env, jtext, text);
    if (nsym <= 0) return NULL;                       // nothing / cannot be sent
    int max = nsym * JTTY_NSPS; int16_t *buf = (int16_t *)malloc(sizeof(int16_t) * max);
    int n = jtty_wave(tones, nsym, f0, amplitude, buf, max);
    jshortArray out = NULL;
    if (n > 0) { out = (*env)->NewShortArray(env, n); (*env)->SetShortArrayRegion(env, out, 0, n, buf); }
    free(buf); return out;
}
