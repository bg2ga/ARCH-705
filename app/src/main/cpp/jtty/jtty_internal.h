// JTTY internals shared between the port's files (not for the JNI layer).
#pragma once
#include "jtty.h"                                     // sizes

extern const int JTTY_SYNC[JTTY_NSYNC];               // the 13 sync tones (jtty_tx.c)

/** Gaussian-smoothed 4-FSK (gen_jttywave.f90): [nsym] tones at [nsps] samples a symbol, BT [bt], sample rate [fsample],
 *  lowest tone [f0] Hz; [nwave] samples. Real output: [re] = sin, [im] NULL. Complex output: [re] = cos, [im] = sin. */
void jtty_gfsk(const int *tones, int nsym, int nsps, float bt, float fsample, float f0, float *re, float *im, int nwave);
