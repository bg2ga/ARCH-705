// JTTY transmit: a C port of WSJT-X Improved 3.2.1's lib/jtty/genjtty.f90 (message -> frames -> channel tones: the
// 13-symbol sync, then each frame's 46 coded symbols) and gen_jttywave.f90 (Gaussian-smoothed 4-FSK, with
// lib/gfsk_pulse.f90). GPL v3, as WSJT-X. WSJT-X makes the audio at 48 kHz (1536 samples a symbol); the app makes the
// same waveform at 12 kHz (384), as it does for FT8.
#include "jtty.h"                                     // this module's API
#include "jtty_internal.h"                            // jtty_gfsk (shared with the receiver's subtraction)
#include <math.h>                                     // erff, sinf, cosf
#include <stdlib.h>                                   // malloc

const int JTTY_SYNC[JTTY_NSYNC] = { 0, 2, 2, 3, 0, 0, 3, 2, 1, 3, 1, 2, 0 }; // the sync tones (jtty_fec_mod's is13)

static float gfsk_pulse(float b, float t)             // frequency pulse of a symbol, BT b, at t symbols from its centre (gfsk_pulse.f90)
{
    const float c = 3.14159265f * sqrtf(2.0f / logf(2.0f));
    return 0.5f * (erff(c * b * (t + 0.5f)) - erff(c * b * (t - 0.5f)));
}

void jtty_gfsk(const int *tones, int nsym, int nsps, float bt, float fsample, float f0, float *re, float *im, int nwave)
{
    const double twopi = 6.283185307179586;           // (gen_jttywave.f90)
    float *pulse = (float *)malloc(sizeof(float) * 3 * nsps); // the smoothing pulse over 3 symbols
    for (int i = 0; i < 3 * nsps; i++) pulse[i] = gfsk_pulse(bt, ((float)(i + 1) - 1.5f * nsps) / (float)nsps);
    int nd = (nsym + 2) * nsps;                       // phase steps: the symbols plus a dummy at each end
    float *dphi = (float *)calloc((size_t)nd, sizeof(float));
    float peak = (float)(twopi / nsps);               // phase step of one tone spacing (h = 1)
    for (int j = 0; j < nsym; j++) for (int i = 0; i < 3 * nsps; i++) dphi[j * nsps + i] += peak * pulse[i] * tones[j]; // each symbol's smoothed step
    for (int i = 0; i < 2 * nsps; i++) {              // dummy symbols equal to the first and last
        dphi[i] += peak * tones[0] * pulse[nsps + i];
        dphi[nsym * nsps + i] += peak * tones[nsym - 1] * pulse[i];
    }
    float base = (float)(twopi * f0 / fsample);       // the carrier (lowest tone)
    double phi = 0;
    for (int k = 0; k < nwave; k++) {                 // the waveform, without the dummies
        if (re) re[k] = (float)sin(phi);              // real: sine
        if (im) { re[k] = (float)cos(phi); im[k] = (float)sin(phi); } // complex: e^(i phi)
        int j = nsps + k; if (j >= nd) j = nd - 1;
        phi = fmod(phi + dphi[j] + base, twopi);
    }
    int nramp = (int)lroundf(nsps / 8.0f);            // shape the first and last symbols' envelope
    int k1 = nsym * nsps - nramp;                     // (0-based start of the last ramp)
    for (int i = 0; i < nramp; i++) {
        float up = (1.0f - cosf((float)(twopi * i / (2.0 * nramp)))) / 2.0f, down = (1.0f + cosf((float)(twopi * i / (2.0 * nramp)))) / 2.0f;
        if (i < nwave) { re[i] *= up; if (im) im[i] *= up; }
        if (k1 + i >= 0 && k1 + i < nwave) { re[k1 + i] *= down; if (im) im[k1 + i] *= down; }
    }
    free(dphi); free(pulse);
}

int jtty_message_tones(const char *message, int profile, int *tones, int max_tones, char *normalized)
{
    uint8_t frames[JTTY_MAX_FRAMES][JTTY_PAYLOAD_BITS]; // the message's frames
    int nframes = jtty_pack_message(message, profile, frames, normalized);
    if (nframes <= 0) return nframes;                 // nothing / cannot be sent
    if (nframes * JTTY_NSYM > max_tones) return -1;
    for (int f = 0; f < nframes; f++) {               // each frame: sync, then its 46 coded symbols (genjtty_frames)
        for (int i = 0; i < JTTY_NSYNC; i++) tones[f * JTTY_NSYM + i] = JTTY_SYNC[i];
        jtty_tbcc_encode(frames[f], tones + f * JTTY_NSYM + JTTY_NSYNC);
    }
    return nframes * JTTY_NSYM;
}

int jtty_wave(const int *tones, int nsym, float f0, float amplitude, int16_t *out, int max_samples)
{
    int n = nsym * JTTY_NSPS;                         // samples
    if (nsym <= 0 || n > max_samples) return -1;
    float *w = (float *)malloc(sizeof(float) * n);
    jtty_gfsk(tones, nsym, JTTY_NSPS, 2.0f, 12000.0f, f0, w, NULL, n); // BT 2.0, as WSJT-X's execute_jtty_tones
    for (int i = 0; i < n; i++) out[i] = (int16_t)lroundf(w[i] * amplitude * 32767.0f);
    free(w);
    return n;
}
