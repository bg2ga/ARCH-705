// JTTY receive: a C port of WSJT-X Improved 3.2.1's lib/jtty/rjtty_sub.f90 (the quarter-frame stepping),
// jtty_mdecode.f90 (multi-signal decoder: sync search surface, candidates in three channels, sticky-sync retry,
// subtraction and retro re-sweeps, message assembly), jtty_peakup.f90, jtty_payload_correlators.f90, subtract_jtty.f90,
// ana64a.f90 and lib/twkfreq.f90. GPL v3, as WSJT-X. FFTs: ft8_lib's KISS FFT (unnormalised, forward e^-i, as four2a).
// Times (tsync) are seconds from the start of reception (jtty_rx_reset); the audio buffer slides, keeping 30 s.
#include "jtty.h"                                     // this module's API
#include "jtty_internal.h"                            // sync tones, GFSK
#include "fft/kiss_fft.h"                             // ft8_lib's KISS FFT
#include <math.h>                                     // sqrtf, atan2f, log10f
#include <stdlib.h>                                   // malloc
#include <string.h>                                   // memmove

typedef kiss_fft_cpx cpx;                             // complex float {r, i}

#define FS6 6000.0f                                   // the decoder's sample rate (analytic, after ana64a)
#define NSS (JTTY_NSPS / 2)                           // samples a symbol at 6000 (192)
#define NFRAME (JTTY_NSYM * JTTY_NSPS)                // a frame at 12000 (22656)
#define NCHUNK (NFRAME + NFRAME / 4)                  // the samples each step reads (1.25 frames)
#define STEP (NFRAME / 4)                             // quarter-frame step (5664 = 0.472 s)
#define NANA 32768                                    // FFT for the analytic signal (next power of 2 above NCHUNK)
#define NCHUNK6 (NCHUNK / 2)                          // the chunk at 6000
#define NFFT 8192                                     // sync-search FFT (df2 = 0.732 Hz)
#define NH2 (NFFT / 2)                                // its bins searched
#define NFRAME6 (JTTY_NSYM * NSS)                     // a frame at 6000 (11328)
#define NTSTEP (NFRAME6 / 4)                          // time searched each step (2832 = 0.472 s)
#define NTGRID (NTSTEP / 12)                          // its 2 ms grid (236)
#define NSYNC6 (JTTY_NSYNC * NSS)                     // the sync's samples (2496)
#define MAXCAND 100                                   // candidates a step
#define MAX_ACTIVE 30                                 // messages being assembled
#define MAX_RECENT (MAX_ACTIVE * JTTY_MAX_FRAMES)     // frames remembered (duplicates)
#define MAX_GAP 3                                     // frames a message may skip
#define MAX_RETRO 3                                   // quarter-frame steps re-swept after a subtraction
#define MAX_SUB 16                                    // subtractions a call
#define BUF_SECONDS 60                                // audio kept (then the oldest 30 s are dropped)
#define BUF_CAP (BUF_SECONDS * 12000)                 // samples
#define SMIN 4.6f                                     // channel 0's sync SNR gate (rjtty_sub's smin)
static const float FRAME_TIME_TOL = 0.05f, FRAME_FREQ_TOL = 3.0f, NEAR_FREQ_TOL = 12.0f, CONT_TIME_TOL = 0.1f, SNR_FLOOR = -17.0f; // (jtty_mdecode's tolerances)

typedef struct { float f1, xdt; double tsync; float snrdb; char decoded[JTTY_MSG_LEN + 1]; bool trailing_sep, is_last; } decode_t; // a candidate / frame decoded
typedef struct { long long id; float f1; double tsync, start_tsync; float start_snr; int k; char decoded[JTTY_MSG_LEN + 1]; bool trailing_sep; } assembly_t; // a message being assembled
typedef struct { float f1; double tsync; } fingerprint_t; // a frame already used
typedef struct { long long id; float f1; double start_tsync; char decoded[JTTY_MSG_LEN + 1]; bool complete; float snr; } upd_t; // an update for the page

// ---- state (module variables of jtty_mdec / rjtty_sub) ----
static int16_t *buf;                                  // the audio (12 kHz)
static int kz;                                        // samples in it
static long long shifted;                             // samples dropped off its front since the reset
static int istart = -1;                               // the next step's first sample (-1: nothing yet)
static assembly_t active[MAX_ACTIVE]; static int nactive; // messages being assembled
static fingerprint_t recent[MAX_RECENT]; static int nrecent; // recent frames
static upd_t pending[256]; static int npending;       // updates not yet collected
static long long next_id = 1;                         // message ids
static bool interferer_pending; static float interferer_f1; static double interferer_tsync; static uint8_t interferer_payload[JTTY_PAYLOAD_BITS]; // retro re-sweep request
static int nsubtracted; static float sub_f1[MAX_SUB]; static double sub_tsync[MAX_SUB]; static uint8_t sub_payload[MAX_SUB][JTTY_PAYLOAD_BITS]; // this call's subtractions
static int ndecodes;                                  // decodes (debug)
static float g_f0 = 1500, g_ftol = 100; static int g_nfa = 200, g_nfb = 2800; // the receive frequency, its width, the band searched

// work arrays (jtty_mdecode's saved arrays)
static cpx c0[NANA], c1[NCHUNK6], csync[NSYNC6], ctones[4][NSS], fftbuf[NFFT];
static float s0[NTGRID + 1][NH2 + 1];                 // sync search surface [time][bin]
static bool mask0[NTGRID + 1][NH2 + 1];               // channel 0's private mask
static float ref_re[4][NSS], ref_im[4][NSS];          // payload correlator references
static bool prepared;                                 // the above made

static double tbase(int is) { return (double)(is + shifted) / 12000.0; } // a buffer index as seconds from the reset

static float dbf(float x) { return x > 1.259e-10f ? 10.0f * log10f(x) : -99.0f; } // WSJT-X's db()

// ---- FFT plans (KISS), cached by size and direction ----
static kiss_fft_cfg plan(int n, int inverse)
{
    static struct { int n, inv; kiss_fft_cfg cfg; } cache[12]; static int ncache; // a few sizes
    for (int i = 0; i < ncache; i++) if (cache[i].n == n && cache[i].inv == inverse) return cache[i].cfg;
    kiss_fft_cfg c = kiss_fft_alloc(n, inverse, NULL, NULL);
    if (ncache < 12) { cache[ncache].n = n; cache[ncache].inv = inverse; cache[ncache].cfg = c; ncache++; }
    return c;
}

static void fft(cpx *x, int n, int inverse)           // in place (four2a: isign -1 forward, +1 inverse, unnormalised)
{
    static cpx tmp[NANA];                             // KISS works out of place
    kiss_fft(plan(n, inverse), x, tmp);
    memcpy(x, tmp, sizeof(cpx) * n);
}

static cpx cmul(cpx a, cpx b) { cpx r = { a.r * b.r - a.i * b.i, a.r * b.i + a.i * b.r }; return r; } // a b
static cpx cmulc(cpx a, cpx b) { cpx r = { a.r * b.r + a.i * b.i, a.r * b.i - a.i * b.r }; return r; } // conj(a) b
static float cabs2(cpx a) { return a.r * a.r + a.i * a.i; } // |a|^2

// ---- the analytic signal (ana64a.f90) and frequency shift (twkfreq.f90) ----

static void ana64a(const int16_t *iwave, int npts)    // 12 kHz samples -> complex analytic signal at 6 kHz in c0
{
    const int nfft2 = NANA / 2; const float fac = 2.0f / (32767.0f * NANA);
    for (int i = 0; i < NANA; i++) { c0[i].r = i < npts ? fac * iwave[i] : 0; c0[i].i = 0; }
    fft(c0, NANA, 0);                                 // forward
    for (int i = nfft2 / 2 + 1; i < nfft2; i++) c0[i].r = c0[i].i = 0; // above 3 kHz: none
    c0[0].r *= 0.5f; c0[0].i *= 0.5f;
    fft(c0, nfft2, 1);                                // inverse of the lower half: the 6 kHz analytic signal
    for (int i = npts; i < NANA; i++) c0[i].r = c0[i].i = 0;
}

static void twkfreq(const cpx *in, cpx *out, int npts, float fsample, float a1) // shift by a1 Hz (a2 = a3 = 0)
{
    float dphi = a1 * (6.283185307f / fsample);        // phase step a sample
    cpx wstep = { cosf(dphi), sinf(dphi) }, w = { 1, 0 }; // as twkfreq.f90: w stepped by multiplying, in single precision
    for (int i = 0; i < npts; i++) { w = cmul(w, wstep); out[i] = cmul(w, in[i]); } // (w is stepped before use)
}

// ---- sync peak-up (jtty_peakup.f90) ----

static void peakup(int nchunk, float xdt0, float f0, float *xdt, float *f1, float *snr)
{
    const int hop = 4, npsync = NSYNC6; const float dt = 1.0f / FS6, TWOPI = 6.283185307f, PI = 3.14159265f;
    cpx qstep[13], zcur[13], zbest[13];               // per-symbol sync correlations
    for (int i = 0; i < 13; i++) qstep[i] = cmulc(csync[i * NSS], csync[i * NSS + hop]); // the phase advance over one hop
    int ia = (int)lroundf((xdt0 - 0.004f) / dt); if (ia < 0) ia = 0;
    int ib = (int)lroundf((xdt0 + 0.004f) / dt); if (ib > nchunk - npsync) ib = nchunk - npsync;
    float pmax = 0, fpk = 0, xdtpk = 0;
    memset(c1, 0, sizeof c1);
    for (int idf = -5; idf <= 5; idf++) {             // half-hertz steps
        float a1 = -f0 + 0.5f * idf;
        if (ib + npsync > 0) twkfreq(c0, c1, ib + npsync, FS6, a1);
        if (ia <= ib) for (int i = 0; i < 13; i++) { cpx z = { 0, 0 }; for (int k = 0; k < NSS; k++) { cpx p = cmulc(csync[i * NSS + k], c1[ia + i * NSS + k]); z.r += p.r; z.i += p.i; } zcur[i] = z; }
        for (int i0 = ia; i0 <= ib; i0 += hop) {      // over DT
            float p = 0; for (int i = 0; i < 13; i++) p += cabs2(zcur[i]);
            if (p > pmax) { pmax = p; fpk = -a1; xdtpk = i0 * dt; memcpy(zbest, zcur, sizeof zcur); }
            if (i0 + hop <= ib)                       // slide each symbol's window by one hop
                for (int i = 0; i < 13; i++) {
                    int is = i * NSS; cpx z = { 0, 0 };
                    for (int r = 0; r < hop; r++) { cpx q = cmulc(csync[is + r], c1[i0 + is + r]); z.r += q.r; z.i += q.i; }
                    cpx d = { zcur[i].r - z.r, zcur[i].i - z.i }; zcur[i] = cmul(qstep[i], d);
                    z.r = z.i = 0;
                    for (int r = 0; r < hop; r++) { cpx q = cmulc(csync[is + NSS - hop + r], c1[i0 + is + NSS + r]); z.r += q.r; z.i += q.i; }
                    zcur[i].r += z.r; zcur[i].i += z.i;
                }
        }
    }
    if (pmax > 0) {                                   // stage 2: fit the phase ramp across the 13 symbols
        float tsym = NSS / FS6, phase[13], uw[13];
        for (int i = 0; i < 13; i++) phase[i] = atan2f(zbest[i].i, zbest[i].r);
        uw[0] = phase[0];
        for (int i = 1; i < 13; i++) { float d = phase[i] - phase[i - 1]; while (d > PI) d -= TWOPI; while (d < -PI) d += TWOPI; uw[i] = uw[i - 1] + d; }
        float xm = 6.0f, ym = 0; for (int i = 0; i < 13; i++) ym += uw[i]; ym /= 13.0f;
        float sxy = 0, sxx = 0; for (int i = 0; i < 13; i++) { sxy += (i - xm) * (uw[i] - ym); sxx += (i - xm) * (i - xm); }
        float slope = sxy / sxx, icpt = ym - slope * xm, rr = 0;
        for (int i = 0; i < 13; i++) { float r = uw[i] - (slope * i + icpt); rr += r * r; }
        rr = sqrtf(rr / 13.0f);
        float dfhz = slope / (TWOPI * tsym);
        if (rr < 1.0f && fabsf(dfhz) <= 0.5f) {       // only a clean, small correction
            cpx t = { 0, 0 };
            for (int i = 0; i < 13; i++) { cpx w = { cosf(-slope * i), sinf(-slope * i) }; cpx p = cmul(zbest[i], w); t.r += p.r; t.i += p.i; }
            fpk += dfhz; pmax = cabs2(t);
        }
    }
    *f1 = fpk; *xdt = xdtpk; *snr = pmax;
}

// ---- subtraction (subtract_jtty.f90) ----

static void subtract(int nvalid, const int *tones, float f1, float xdt)
{
    const int nframe = JTTY_NSYM * NSS, nfft = NANA, nfilt = 2 * NSS;
    static cpx cw[NANA], cfilt[NANA]; static bool cw_ready; static float cref_re[NFRAME6], cref_im[NFRAME6];
    if (!cw_ready) {                                  // the low-pass filter (cos^2 window, ~2 symbols), once
        float sumw = 0, win[2 * NSS + 1];
        for (int j = -nfilt / 2; j <= nfilt / 2; j++) { float c = cosf(3.14159265f * j / nfilt); win[j + nfilt / 2] = c * c; sumw += c * c; }
        static cpx tmp[NANA]; memset(tmp, 0, sizeof tmp);
        for (int m = 0; m <= nfilt; m++) tmp[m].r = win[m] / sumw;
        for (int i = 0; i < nfft; i++) cw[i] = tmp[(i + nfilt / 2 + 1) % nfft]; // cshift(cw, nfilt/2+1)
        fft(cw, nfft, 0);
        for (int i = 0; i < nfft; i++) { cw[i].r /= nfft; cw[i].i /= nfft; }
        cw_ready = true;
    }
    jtty_gfsk(tones, JTTY_NSYM, NSS, 2.0f, FS6, f1, cref_re, cref_im, nframe); // the signal as sent, unit amplitude
    int nstart = (int)lroundf(xdt * FS6);
    memset(cfilt, 0, sizeof cfilt);
    for (int i = 0; i < nframe; i++) { int j = nstart + i; if (j >= 0 && j < nvalid) { cpx r = { cref_re[i], cref_im[i] }; cfilt[i] = cmulc(r, c0[j]); } } // c0 conj(cref)
    fft(cfilt, nfft, 0);                              // low-pass: the slowly varying gain
    for (int i = 0; i < nfft; i++) cfilt[i] = cmul(cfilt[i], cw[i]);
    fft(cfilt, nfft, 1);
    for (int i = 0; i < nframe; i++) { int j = nstart + i; if (j >= 0 && j < nvalid) { cpx r = { cref_re[i], cref_im[i] }; cpx z = cmul(cfilt[i], r); c0[j].r -= z.r; c0[j].i -= z.i; } }
}

// ---- payload correlation (jtty_payload_correlators.f90) ----

static void correlate(int payload_start, float corr[JTTY_INFO_BITS][4][2], float half[JTTY_INFO_BITS][4][2])
{
    memset(corr, 0, sizeof(float) * JTTY_INFO_BITS * 8); memset(half, 0, sizeof(float) * JTTY_INFO_BITS * 8);
    if (payload_start < 0 || payload_start >= NCHUNK6) return;
    int avail = (NCHUNK6 - payload_start) / NSS; if (avail > JTTY_INFO_BITS) avail = JTTY_INFO_BITS;
    for (int s = 0; s < avail; s++) {
        int first = payload_start + s * NSS;
        float fr[4] = { 0 }, fi[4] = { 0 }; double en[4] = { 0 };
        for (int seg = 0; seg < 2; seg++) {           // two halves: full = their sum; half = sqrt(sum of their energies)
            float hr[4] = { 0 }, hi[4] = { 0 };
            for (int off = seg * NSS / 2; off < (seg + 1) * NSS / 2; off++) {
                float sr = c1[first + off].r, si = c1[first + off].i;
                for (int t = 0; t < 4; t++) {         // conj(ref) x sample
                    float rr = ref_re[t][off] * sr, ii = ref_im[t][off] * si, ri = ref_re[t][off] * si, ir = ref_im[t][off] * sr;
                    fr[t] += rr + ii; fi[t] += ri - ir; hr[t] += rr + ii; hi[t] += ri - ir;
                }
            }
            for (int t = 0; t < 4; t++) en[t] += (double)(hr[t] * hr[t] + hi[t] * hi[t]);
        }
        for (int t = 0; t < 4; t++) { corr[s][t][0] = fr[t]; corr[s][t][1] = fi[t]; half[s][t][0] = (float)sqrt(en[t] > 0 ? en[t] : 0); half[s][t][1] = 0; }
    }
}

// ---- message assembly (jtty_mdec) ----

static bool same_frame(float fa, double ta, float fb, double tb) { return fabsf(fa - fb) < FRAME_FREQ_TOL && fabs(ta - tb) < FRAME_TIME_TOL; }
static bool same_recent(float fa, double ta, float fb, double tb) { return fabsf(fa - fb) < NEAR_FREQ_TOL && fabs(ta - tb) < FRAME_TIME_TOL; }

static bool is_recent(const decode_t *c) { for (int i = 0; i < nrecent; i++) if (same_recent(c->f1, c->tsync, recent[i].f1, recent[i].tsync)) return true; return false; }

static void remember_recent(const decode_t *c)
{
    if (nrecent >= MAX_RECENT) memmove(recent, recent + 1, sizeof(fingerprint_t) * (MAX_RECENT - 1)); else nrecent++; // oldest out when full
    recent[nrecent - 1].f1 = c->f1; recent[nrecent - 1].tsync = c->tsync;
}

static void queue_update(const assembly_t *m, bool complete)
{
    for (int i = 0; i < npending; i++)                // already pending: update it
        if (pending[i].id == m->id) { pending[i].f1 = m->f1; strcpy(pending[i].decoded, m->decoded); pending[i].complete = complete; return; }
    if (npending >= 256) { memmove(pending, pending + 1, sizeof(upd_t) * 255); npending--; } // (never more than the page collects)
    upd_t *u = &pending[npending++];
    u->id = m->id; u->f1 = m->f1; u->start_tsync = m->start_tsync; u->snr = m->start_snr; strcpy(u->decoded, m->decoded); u->complete = complete;
}

static void remove_active(int i) { if (i < 0 || i >= nactive) return; if (i < nactive - 1) active[i] = active[nactive - 1]; nactive--; }

static int tlen(const char *s) { int n = (int)strlen(s); while (n > 0 && s[n - 1] == ' ') n--; return n; } // len_trim

static bool start_message(const decode_t *c)
{
    if (!c->is_last && nactive >= MAX_ACTIVE) return false;
    remember_recent(c);
    assembly_t m; memset(&m, 0, sizeof m);
    m.id = next_id++; m.f1 = c->f1; m.tsync = c->tsync; m.start_tsync = c->tsync; m.start_snr = c->snrdb;
    if (strncmp(c->decoded, "599 ", 4) == 0) snprintf(m.decoded, sizeof m.decoded, "~%.*s", tlen(c->decoded), c->decoded); // (keeps a leading 599 visible after display trimming)
    else snprintf(m.decoded, sizeof m.decoded, "%s", c->decoded);
    m.k = tlen(m.decoded); m.trailing_sep = c->trailing_sep;
    queue_update(&m, c->is_last);
    if (!c->is_last) active[nactive++] = m;
    return true;
}

static void append_active(int index, const decode_t *c, int gap)
{
    if (index < 0 || index >= nactive) return;
    remember_recent(c);
    assembly_t *a = &active[index];
    int k = a->k, n = tlen(c->decoded);
    char cur[JTTY_MSG_LEN + 1]; snprintf(cur, sizeof cur, "%.*s", tlen(a->decoded), a->decoded); // trim(decoded)
    char out[3 * JTTY_MSG_LEN + 8];
    if (gap > 1) {                                    // frames missed: "~~~~~" (shown as " ... ") then the new text
        int nstart = (n >= 1 && c->decoded[0] == '~') ? 1 : 0;
        int kz = k + 5 + (n - nstart); if (kz > JTTY_MSG_LEN) kz = JTTY_MSG_LEN;
        int nchar = kz - k - 5; if (nchar < 0) nchar = 0;
        snprintf(out, sizeof out, "%s~~~~~%.*s", cur, nchar, c->decoded + nstart);
        a->k = k + 5 + nchar;
    } else {
        int kz = k + n; if (kz > JTTY_MSG_LEN) kz = JTTY_MSG_LEN;
        int take = kz - k; if (take < 0) take = 0;
        snprintf(out, sizeof out, a->trailing_sep ? "%s %.*s" : "%s%.*s", cur, take, c->decoded);
        a->k = kz;
    }
    snprintf(a->decoded, sizeof a->decoded, "%.*s", JTTY_MSG_LEN, out); // (a Fortran character*80 keeps the first 80)
    a->trailing_sep = c->trailing_sep; a->f1 = c->f1; a->tsync = c->tsync;
    queue_update(a, c->is_last);
    if (c->is_last) remove_active(index);
}

static void prune(double forward_tsync, double frame_period)
{
    double oldest = forward_tsync - MAX_RETRO * frame_period / 4.0;
    int keep = 0;
    for (int i = 0; i < nrecent; i++) { if (recent[i].tsync < oldest - FRAME_TIME_TOL) continue; recent[keep++] = recent[i]; }
    nrecent = keep;
    for (int i = 0; i < nactive;) {                   // messages that stopped: their last update, as incomplete
        if (oldest - active[i].tsync > MAX_GAP * frame_period + CONT_TIME_TOL) { queue_update(&active[i], false); remove_active(i); }
        else i++;
    }
}

static void classify(const assembly_t *e, const decode_t *c, double fp, bool *match, bool *window_dupe, int *gap) // classify_active_candidate
{
    float df1 = c->f1 - e->f1; double dts = c->tsync - e->tsync;
    *match = false; *gap = 1; *window_dupe = false;
    int nfp = (int)lround(dts / fp); double resid = fabs(dts - fp * nfp);
    if (nfp >= 1 && nfp <= MAX_GAP && resid < CONT_TIME_TOL) { float tol = 10.0f + 3.0f * (nfp - 1); *match = fabsf(df1) < tol; if (*match) *gap = nfp; } // the next frame of it
    if (!*match) {                                    // the same frame seen from a neighbouring quarter-frame window
        double q = fp / 4.0; int ns = (int)lround(dts / q); double r = fabs(dts - q * ns);
        if (abs(ns) <= MAX_RETRO && fabsf(df1) < 10.0f && r < 0.003 && !((abs(ns) % 4) == 0 && ns != 0)) { *match = true; *window_dupe = true; }
    }
}

// ---- one decode call (jtty_mdecode) ----

static void search_window(float fc, float fwid, int nfa, int nfb, bool constrain, float df, int first_bin, int last_bin, int *ja, int *jb, bool *usable)
{
    *ja = first_bin; *jb = last_bin; *usable = false;
    if (df <= 0 || fwid < 0 || first_bin > last_bin) return;
    if (constrain) { if (nfa > nfb) return; if (fc < nfa) fc = (float)nfa; if (fc > nfb) fc = (float)nfb; }
    int a = (int)((fc - fwid) / df), b = (int)((fc + fwid) / df);
    *ja = a > first_bin ? a : first_bin; *jb = b < last_bin ? b : last_bin;
    if (constrain) { int ca = (int)ceilf(nfa / df), cb = (int)floorf(nfb / df); if (ca > *ja) *ja = ca; if (cb < *jb) *jb = cb; }
    *usable = *ja <= *jb;
}

typedef struct {                                      // the per-call context (jtty_mdecode's locals shared with its contained routines)
    int istart, nfa, nfb; float f0, ftol;
    int ichan, ipass, ja, jb, nstep, first_bin, last_bin;
    float fc, fwid;
    bool usable, any_subtracted, s0_valid;
    decode_t cand[MAXCAND]; int ncand;
    int n_ch0_ok, ja_ok[16], jb_ok[16]; float f1_ok[16]; double t_ok[16];
    float pt, pa; int nsync, irxsync[JTTY_NSYNC];
} ctx_t;

static void channel_window(ctx_t *x)
{
    if (x->ichan == 0) { x->fc = x->f0; x->fwid = x->ftol; } else { x->fc = x->ichan == 2 ? 1650.0f : 1350.0f; x->fwid = 150.0f; }
    search_window(x->fc, x->fwid, x->nfa, x->nfb, x->ichan != 0, FS6 / NFFT, 3, NH2 - 2, &x->ja, &x->jb, &x->usable);
}

static void build_s0(ctx_t *x)                        // the sync-correlation surface over this step's quarter frame
{
    const double TWOPI = 6.2831853071795864769;
    int first_out = x->first_bin - 2, nout = x->last_bin - x->first_bin + 5; // bins needed (with a 2-bin halo)
    int conv = NSYNC6 + nout - 1, nc = 1; while (nc < conv) nc *= 2; // Bluestein length
    bool chirp = nout > 0 && nc <= NFFT / 2;          // a narrow band: only its bins (Bluestein, as WSJT-X)
    static cpx w[NSYNC6], kern[NFFT], in[NFFT]; static int w_first = -1, w_nout = -1, w_nc = -1;
    if (chirp && (w_first != first_out || w_nout != nout || w_nc != nc)) {
        for (int n = 0; n < NSYNC6; n++) {            // input weights: conj(sync) x chirp
            double ph = -TWOPI * ((double)first_out * n + 0.5 * (double)n * n) / NFFT;
            cpx c = { (float)cos(ph), (float)sin(ph) }; w[n] = cmulc(csync[n], c);
        }
        memset(kern, 0, sizeof(cpx) * nc);
        int dmax = NSYNC6 > nout ? NSYNC6 : nout;
        for (int d = 0; d < dmax; d++) {
            double ph = 0.5 * TWOPI * (double)d * d / NFFT; cpx c = { (float)cos(ph), (float)sin(ph) };
            if (d < nout) kern[d] = c;
            if (d > 0 && d < NSYNC6) kern[nc - d] = c;
        }
        fft(kern, nc, 0);
        w_first = first_out; w_nout = nout; w_nc = nc;
    }
    int istep = 0;
    for (int i0 = 0; i0 <= NTSTEP; i0 += 12, istep++) { // every 2 ms
        const cpx *out;
        if (chirp) {
            memset(in, 0, sizeof(cpx) * nc);
            for (int n = 0; n < NSYNC6; n++) in[n] = cmul(w[n], c0[i0 + n]);
            fft(in, nc, 0);
            for (int i = 0; i < nc; i++) in[i] = cmul(in[i], kern[i]);
            fft(in, nc, 1);
            float sc = 1.0f / nc;
            float p[5];
            for (int m = 0; m < 4; m++) p[m] = cabs2(in[m]) * sc * sc;
            for (int j = x->first_bin; j <= x->last_bin; j++) {
                p[4] = cabs2(in[j - x->first_bin + 4]) * sc * sc;
                s0[istep][j] = p[0] + 2 * p[1] + 3 * p[2] + 2 * p[3] + p[4]; // smoothed over 5 bins
                p[0] = p[1]; p[1] = p[2]; p[2] = p[3]; p[3] = p[4];
            }
            continue;
        }
        memset(fftbuf, 0, sizeof fftbuf);             // the whole band: a full FFT
        for (int n = 0; n < NSYNC6; n++) fftbuf[n] = cmulc(csync[n], c0[i0 + n]);
        fft(fftbuf, NFFT, 0); out = fftbuf;
        float p[5] = { cabs2(out[x->first_bin - 2]), cabs2(out[x->first_bin - 1]), cabs2(out[x->first_bin]), cabs2(out[x->first_bin + 1]), 0 };
        for (int j = x->first_bin; j <= x->last_bin; j++) {
            p[4] = cabs2(out[j + 2]);
            s0[istep][j] = p[0] + 2 * p[1] + 3 * p[2] + 2 * p[3] + p[4];
            p[0] = p[1]; p[1] = p[2]; p[2] = p[3]; p[3] = p[4];
        }
    }
    x->nstep = istep - 1;
    x->s0_valid = true;
}

static void record_ch0(ctx_t *x)                      // channel 0's success: keep channels 1 / 2 off it
{
    if (x->ichan != 0 || x->n_ch0_ok >= 16) return;
    const float df2 = FS6 / NFFT; const int nfz = (int)lroundf(10.0f / df2);
    decode_t *c = &x->cand[x->ncand - 1]; int i = x->n_ch0_ok++;
    x->ja_ok[i] = (int)lroundf(c->f1 / df2) - nfz; x->jb_ok[i] = (int)lroundf(c->f1 / df2) + nfz; x->f1_ok[i] = c->f1; x->t_ok[i] = c->tsync;
}

static bool decode_and_merge(ctx_t *x)                // decode the candidate's 46 symbols and merge it into a message
{
    const float dt = 1.0f / FS6, baud = FS6 / NSS;
    decode_t *c = &x->cand[x->ncand - 1];
    int payload_start = (int)lroundf(c->xdt / dt) + NSYNC6;
    static float corr[JTTY_INFO_BITS][4][2], half[JTTY_INFO_BITS][4][2];
    correlate(payload_start, corr, half);
    uint8_t payload[JTTY_PAYLOAD_BITS];
    bool ok = jtty_tbcc_decode((const float (*)[4][2])corr, (const float (*)[4][2])half, payload);
    float pw[JTTY_INFO_BITS][4]; int irx[JTTY_INFO_BITS];
    for (int j = 0; j < JTTY_INFO_BITS; j++) { int best = 0; for (int t = 0; t < 4; t++) { pw[j][t] = corr[j][t][0] * corr[j][t][0] + corr[j][t][1] * corr[j][t][1]; if (pw[j][t] > pw[j][best]) best = t; } irx[j] = best; }
    c->decoded[0] = ' '; c->decoded[1] = 0;
    if (!ok) return false;
    bool sep, eom;
    if (!jtty_unpack_frame(payload, c->decoded, &sep, &eom)) return false; // the grammar must check out too
    c->trailing_sep = sep; c->is_last = eom;
    ndecodes++;
    int tones[JTTY_NSYM]; for (int i = 0; i < JTTY_NSYNC; i++) tones[i] = JTTY_SYNC[i];
    jtty_tbcc_encode(payload, tones + JTTY_NSYNC);     // the frame as sent (SNR, subtraction)
    for (int j = 0; j < JTTY_INFO_BITS; j++) { int is = tones[JTTY_NSYNC + j]; x->pt += pw[j][is]; x->pa += pw[j][0] + pw[j][1] + pw[j][2] + pw[j][3]; } (void)irx;
    float pn = (x->pa - x->pt) / 3.0f;
    if (pn > 0) { float s = SNR_FLOOR; if (x->pt > pn) { s = dbf((x->pt - pn) / pn) - dbf(2500.0f / baud); if (s < SNR_FLOOR) s = SNR_FLOOR; } c->snrdb = s; } // S/N in 2500 Hz
    c->tsync = tbase(x->istart) + c->xdt;
    bool dupe = false;                                // the same text at the same moment already this call
    for (int i = 0; i < x->ncand - 1; i++) if (strcmp(x->cand[i].decoded, c->decoded) == 0 && fabs(x->cand[i].tsync - c->tsync) < 0.032) dupe = true;
    if (x->ichan != 0) for (int i = 0; i < x->n_ch0_ok; i++) if (same_frame(c->f1, c->tsync, x->f1_ok[i], x->t_ok[i])) dupe = true;
    if (dupe) return true;
    subtract(NCHUNK6, tones, c->f1, c->xdt);          // take it out: a weaker signal underneath may show
    x->any_subtracted = true; x->s0_valid = false;
    if (nsubtracted < MAX_SUB) { sub_f1[nsubtracted] = c->f1; sub_tsync[nsubtracted] = c->tsync; memcpy(sub_payload[nsubtracted], payload, JTTY_PAYLOAD_BITS); nsubtracted++; }
    decode_t d = *c; bool match = false, pure_dupe = is_recent(&d); int iactive = -1, best_gap = 1;
    if (!pure_dupe && nactive > 0) {
        bool have_win = false; int best_cont = -1; float best_df = 1e30f;
        for (int i = 0; i < nactive; i++) {
            bool m, wd; int gap; classify(&active[i], &d, NFRAME6 / FS6, &m, &wd, &gap);
            if (!m) continue;
            if (wd) { if (!have_win) iactive = i; have_win = true; continue; }
            float dfa = fabsf(d.f1 - active[i].f1); if (dfa < best_df) { best_df = dfa; best_cont = i; best_gap = gap; }
        }
        if (have_win) { match = true; pure_dupe = true; } else if (best_cont >= 0) { match = true; iactive = best_cont; }
    }
    if (!pure_dupe && match) append_active(iactive, &d, best_gap); // the next frame of a message
    else if (!pure_dupe) start_message(&d);           // a new message
    return true;
}

static void process_channel(ctx_t *x)
{
    const float df2 = FS6 / NFFT, dt = 1.0f / FS6; const int nfz = (int)lroundf(10.0f / df2), ntz = (int)lroundf(0.016f * 6000.0f / 12.0f);
    channel_window(x); if (!x->usable) return;
    int nc0 = 2;                                      // candidates in this channel
    if (x->ichan == 0) { int v = (int)lroundf(x->fwid / (nfz * df2)); nc0 = v < 2 ? 2 : (v > 8 ? 8 : v); } // a wide band needs more
    bool decoded_any = false;
    if (x->ichan != 0) for (int i = 0; i < x->n_ch0_ok; i++) { // channel 0's decodes: not found again here
        int a = x->ja > x->ja_ok[i] ? x->ja : x->ja_ok[i], b = x->jb < x->jb_ok[i] ? x->jb : x->jb_ok[i];
        for (int t = 0; t <= x->nstep; t++) for (int j = a; j <= b; j++) s0[t][j] = 0;
    }
    if (x->ichan == 0) for (int t = 0; t <= x->nstep; t++) for (int j = x->ja; j <= x->jb; j++) mask0[t][j] = true;
    for (int ic = 0; ic < nc0; ic++) {
        int l1 = 0, l2 = 0; float best = 0; bool found = false; // maxloc (1-based; 0 0 if nothing), first maximum in column order
        for (int t = 0; t <= x->nstep; t++) for (int j = x->ja; j <= x->jb; j++) {
            if (x->ichan == 0 && !mask0[t][j]) continue;
            if (!found || s0[t][j] > best) { best = s0[t][j]; l1 = j - x->ja + 1; l2 = t + 1; found = true; }
        }
        int fa = l1 - nfz + x->ja, fb = l1 + nfz + x->ja, ta = l2 - ntz, tb = l2 + ntz; // the neighbourhood to blank
        if (fa < x->ja) fa = x->ja; if (fb > x->jb) fb = x->jb; if (ta < 0) ta = 0; if (tb > x->nstep) tb = x->nstep;
        for (int t = ta; t <= tb; t++) for (int j = fa; j <= fb; j++) { if (x->ichan == 0) mask0[t][j] = false; else s0[t][j] = 0; }
        float fbest = (l1 - 1 + x->ja) * df2, xdtbest = (l2 - 1) * dt * 12;
        if (x->ichan == 0) { float xd, ff, sn; peakup(NCHUNK6, xdtbest, fbest, &xd, &ff, &sn); xdtbest = xd; fbest = ff; } // refine time and frequency
        if (x->ncand >= MAXCAND) break;
        decode_t *c = &x->cand[x->ncand++]; memset(c, 0, sizeof *c); c->xdt = xdtbest; c->f1 = fbest;
        twkfreq(c0, c1, NCHUNK6, FS6, -c->f1);        // the candidate to 0 Hz
        x->pt = 0; x->pa = 0;
        float pw[JTTY_NSYNC][4]; memset(pw, 0, sizeof pw);
        for (int j = 0; j < JTTY_NSYNC; j++) x->irxsync[j] = -1;
        for (int j = 0; j < JTTY_NSYNC; j++) {        // sync tone powers
            int i0 = (int)lroundf(c->xdt / dt) + j * NSS;
            if (i0 < 0 || i0 + NSS > NCHUNK6) break;
            int bi = 0;
            for (int t = 0; t < 4; t++) { cpx z = { 0, 0 }; for (int k = 0; k < NSS; k++) { cpx p = cmulc(ctones[t][k], c1[i0 + k]); z.r += p.r; z.i += p.i; } pw[j][t] = cabs2(z); if (pw[j][t] > pw[j][bi]) bi = t; }
            x->irxsync[j] = bi; x->pt += pw[j][JTTY_SYNC[j]]; x->pa += pw[j][0] + pw[j][1] + pw[j][2] + pw[j][3];
        }
        float snrdb = -99.9f, pn = (x->pa - x->pt) / 3.0f; if (pn > 0) snrdb = dbf(x->pt / pn);
        x->nsync = 0; for (int j = 0; j < JTTY_NSYNC; j++) if (x->irxsync[j] == JTTY_SYNC[j]) x->nsync++;
        c->snrdb = snrdb;
        if (x->ichan == 0 && (x->nsync <= 6 || snrdb < SMIN)) continue; // not sync-like enough
        if (x->ichan != 0 && (x->nsync <= 8 || snrdb < 5.0f)) continue;
        if (decode_and_merge(x)) { record_ch0(x); decoded_any = true; }
    }
    if (!decoded_any)                                 // sticky sync: a message's next frame due now, at its remembered place
        for (int ir = 0; ir < nactive; ir++) {
            if (active[ir].f1 < x->fc - x->fwid || active[ir].f1 > x->fc + x->fwid) continue;
            if (fabs((tbase(x->istart) - active[ir].tsync) - NFRAME6 / FS6) > 0.1) continue;
            float xr = (float)(active[ir].tsync + NFRAME6 / FS6 - tbase(x->istart));
            if (xr < 0) continue;
            if (x->ncand >= MAXCAND) break;
            decode_t *c = &x->cand[x->ncand++]; memset(c, 0, sizeof *c); c->xdt = xr; c->f1 = active[ir].f1;
            twkfreq(c0, c1, NCHUNK6, FS6, -c->f1);
            x->nsync = -1;
            if (decode_and_merge(x)) record_ch0(x);
            break;                                    // one retry a channel a call
        }
}

static void mdecode(int is)                           // one quarter-frame window from buffer index [is] (jtty_mdecode)
{
    bool use_int = interferer_pending; float int_f1 = interferer_f1; double int_t = interferer_tsync; uint8_t int_payload[JTTY_PAYLOAD_BITS];
    memcpy(int_payload, interferer_payload, sizeof int_payload);
    interferer_pending = false; nsubtracted = 0;
    if (is + shifted == 0 && !use_int) { ndecodes = 0; nactive = 0; nrecent = 0; } // the very first window
    const int16_t *iwave = buf + is; long sum = 0;
    for (int i = 0; i < NCHUNK; i++) sum += abs(iwave[i]);
    if (sum == 0) return;                             // silence
    if (!prepared) {                                  // the saved arrays, once
        double ph = 0; int k = 0;                      // the sync waveform at 6000 (gen_syncwave.f90)
        for (int i = 0; i < JTTY_NSYNC; i++) { double dp = 6.283185307179586 * (FS6 / NSS) * JTTY_SYNC[i] / FS6; for (int j = 0; j < NSS; j++) { csync[k].r = (float)cos(ph); csync[k].i = (float)sin(ph); k++; ph += dp; } }
        for (int t = 0; t < 4; t++) for (int j = 0; j < NSS; j++) { double p = 6.283185307179586 * t * j / NSS; ctones[t][j].r = (float)cos(p); ctones[t][j].i = (float)sin(p); ref_re[t][j] = (float)cos(p); ref_im[t][j] = (float)sin(p); }
        memset(s0, 0, sizeof s0);
        prepared = true;
    }
    ana64a(iwave, NCHUNK);
    for (int i = NCHUNK6; i < NANA; i++) c0[i].r = c0[i].i = 0;
    if (use_int) {                                    // retro re-sweep: take the known signal out first
        int tones[JTTY_NSYM]; for (int i = 0; i < JTTY_NSYNC; i++) tones[i] = JTTY_SYNC[i];
        jtty_tbcc_encode(int_payload, tones + JTTY_NSYNC);
        subtract(NCHUNK6, tones, int_f1, (float)(int_t - tbase(is)));
    }
    static ctx_t x; memset(&x, 0, sizeof x);
    x.istart = is; x.nfa = g_nfa; x.nfb = g_nfb; x.f0 = g_f0; x.ftol = g_ftol;
    x.first_bin = NH2 - 2; x.last_bin = 3;            // the span every channel reads
    for (x.ichan = 0; x.ichan <= 2; x.ichan++) { channel_window(&x); if (!x.usable) continue; if (x.ja < x.first_bin) x.first_bin = x.ja; if (x.jb > x.last_bin) x.last_bin = x.jb; }
    if (x.first_bin > x.last_bin) return;
    x.any_subtracted = false; x.s0_valid = false; x.n_ch0_ok = 0;
    for (x.ipass = 1; x.ipass <= 2; x.ipass++) {      // phase A: channel 0 first, its own two passes
        if (x.ipass == 2 && !x.any_subtracted) break;
        if (!x.s0_valid) build_s0(&x);
        x.ichan = 0; process_channel(&x);
    }
    x.any_subtracted = false;
    for (x.ipass = 1; x.ipass <= 2; x.ipass++) {      // phase B: channels 1 and 2
        if (x.ipass == 2 && !x.any_subtracted) break;
        if (!x.s0_valid) build_s0(&x);
        for (x.ichan = 1; x.ichan <= 2; x.ichan++) process_channel(&x);
        x.s0_valid = false;
    }
}

// ---- stepping (jtty_mdecode_step, rjtty_core) ----

static void mdecode_step(int is)                      // a window, then retro re-sweeps of the 3 before it for each signal taken out
{
    prune(tbase(is), NFRAME / 12000.0);
    interferer_pending = false;
    mdecode(is);
    int n = nsubtracted; float f1[MAX_SUB]; double ts[MAX_SUB]; uint8_t pl[MAX_SUB][JTTY_PAYLOAD_BITS]; // (the retro calls overwrite these)
    memcpy(f1, sub_f1, sizeof f1); memcpy(ts, sub_tsync, sizeof ts); memcpy(pl, sub_payload, sizeof pl);
    for (int i = 0; i < n; i++)
        for (int k = 1; k <= MAX_RETRO; k++) {
            int prev = is - k * STEP;
            if (prev < 0) continue;
            interferer_pending = true; interferer_f1 = f1[i]; interferer_tsync = ts[i]; memcpy(interferer_payload, pl[i], JTTY_PAYLOAD_BITS);
            mdecode(prev);
        }
}

void jtty_rx_reset(void)
{
    if (!buf) buf = (int16_t *)malloc(sizeof(int16_t) * BUF_CAP); // the audio buffer, once
    kz = 0; shifted = 0; istart = 0;
    nactive = 0; nrecent = 0; npending = 0; ndecodes = 0; interferer_pending = false;
}

void jtty_rx_feed(const int16_t *samples, int n, float f0, float ftol, int nfa, int nfb)
{
    if (!buf) jtty_rx_reset();
    g_f0 = f0; g_ftol = ftol; g_nfa = nfa; g_nfb = nfb;
    while (n > 0) {
        if (kz == BUF_CAP) {                          // full: drop the oldest half (the steps keep well clear of it)
            int drop = BUF_CAP / 2;
            memmove(buf, buf + drop, sizeof(int16_t) * (BUF_CAP - drop));
            kz -= drop; istart -= drop; shifted += drop;
            if (istart < 0) istart = 0;
        }
        int take = BUF_CAP - kz < n ? BUF_CAP - kz : n;
        memcpy(buf + kz, samples, sizeof(int16_t) * take); kz += take; samples += take; n -= take;
        while (istart + NCHUNK <= kz) { mdecode_step(istart); istart += STEP; } // every whole quarter-frame step
    }
}

static void display_text(const char *in, char *out)   // display_message_text: "~~~~~" -> " ... ", '~' -> ' ', one leading space off
{
    char m[JTTY_MSG_LEN + 1]; snprintf(m, sizeof m, "%s", in);
    int n = tlen(m);
    for (int i = 0; i + 4 < n; i++) if (memcmp(m + i, "~~~~~", 5) == 0) memcpy(m + i, " ... ", 5);
    for (int i = 0; i < n; i++) if (m[i] == '~') m[i] = ' ';
    m[n] = 0;
    snprintf(out, JTTY_MSG_LEN + 1, "%s", m[0] == ' ' ? m + 1 : m);
    int k = tlen(out); out[k] = 0;
}

int jtty_rx_updates(jtty_update_t *out, int max)
{
    int n = npending < max ? npending : max;
    for (int i = 0; i < n; i++) {
        out[i].id = pending[i].id; out[i].freq = pending[i].f1; out[i].start = pending[i].start_tsync;
        display_text(pending[i].decoded, out[i].text); out[i].complete = pending[i].complete; out[i].snr = (int)lroundf(pending[i].snr);
    }
    memmove(pending, pending + n, sizeof(upd_t) * (npending - n)); npending -= n; // collected
    return n;
}
