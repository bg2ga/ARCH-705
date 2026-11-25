// PC test of the app's JTTY port (app/src/main/cpp/jtty): (1) the source codec against the 34-bit vectors in WSJT-X
// Improved's lib/jtty/jtty_source_encoding.txt, packing from text and unpacking to text; (2) round trips: messages
// encoded, made into 12 kHz audio, mixed (two stations overlapping in time, at different frequencies) with noise at
// several SNRs, and fed to the receiver in 50 ms blocks; what it decodes is printed.
#include <stdio.h>                                    // printf
#include <stdlib.h>                                   // rand
#include <string.h>                                   // strcmp
#include <math.h>                                     // sqrtf
#include "jtty/jtty.h"                                // under test

static float gauss(void) { float u = (rand() + 1.0f) / (RAND_MAX + 2.0f), v = (rand() + 1.0f) / (RAND_MAX + 2.0f); return sqrtf(-2 * logf(u)) * cosf(6.2831853f * v); }

static void hex_bits(unsigned long long h, uint8_t *b) { for (int i = 0; i < 34; i++) b[i] = (uint8_t)((h >> (33 - i)) & 1); } // vector -> bits 1..34
static unsigned long long bits_hex(const uint8_t *b) { unsigned long long h = 0; for (int i = 0; i < 34; i++) h = (h << 1) | b[i]; return h; }

int main(void)
{
    int fails = 0;
    // (1) the spec's vectors: text (and profile) -> one frame, and that frame -> text
    struct { const char *text; int profile; unsigned long long hex; const char *rendered; bool from_text; } v[] = {
        { "CQ K1ABC CQ", 0, 0x026F78D41ull, "CQ K1ABC CQ", true },
        { "599 123", 2, 0x20007B009ull, "599 123", true },          // RTTY Roundup: a serial
        { "599 CA", 2, 0x2001BA019ull, "599 CA", true },            // RTTY Roundup: a state
        { "", 0, 0x00B790D29ull, "599 05 NWT", false },             // zone / location: not inferred from text
        { "1D EMA", 0, 0x082C58029ull, "1D EMA", true },            // Field Day class / section
        { "", 0, 0x204E42D39ull, "599 156 1749", false },           // serial / time: not inferred
        { "AGN?", 0, 0x000000049ull, "AGN?", true },                // control phrase
        { "FN42", 0, 0x04A198049ull, "FN42", true },                // field-only locator
        { "HELLO", 0, 0x11395558Dull, "HELLO", true },              // five characters
    };
    for (unsigned i = 0; i < sizeof v / sizeof *v; i++) {
        uint8_t b[34]; hex_bits(v[i].hex, b); char t[81]; bool sep, eom;
        bool ok = jtty_unpack_frame(b, t, &sep, &eom);
        bool good = ok && strcmp(t, v[i].rendered) == 0 && eom;
        printf("unpack 0x%09llX -> %-14s %s\n", v[i].hex, ok ? t : "(invalid)", good ? "ok" : "FAIL"); fails += !good;
        if (!v[i].from_text) continue;
        uint8_t f[16][34]; char norm[81]; int n = jtty_pack_message(v[i].text, v[i].profile, f, norm);
        unsigned long long h = n == 1 ? bits_hex(f[0]) : 0;
        good = n == 1 && h == v[i].hex;
        printf("pack   %-14s -> %d frame(s) 0x%09llX %s\n", v[i].text, n, h, good ? "ok" : "FAIL"); fails += !good;
    }
    // frame counts from the design document's table (profile unknown)
    struct { const char *text; int frames; } fc[] = { { "CQ KA1ABC CQ", 1 }, { "WB9XYZ", 1 }, { "WB9XYZ TU CQ KA1ABC CQ", 2 }, { "WB9XYZ 599 123", 2 },
        { "599 123", 1 }, { "599 MA", 1 }, { "599 FN42", 1 }, { "1D EMA", 1 }, { "599 001", 2 }, { "599 05", 2 }, { "599 BRUCE", 2 } };
    for (unsigned i = 0; i < sizeof fc / sizeof *fc; i++) {
        uint8_t f[16][34]; char norm[81]; int n = jtty_pack_message(fc[i].text, 0, f, norm);
        printf("frames %-24s %d (expected %d) %s\n", fc[i].text, n, fc[i].frames, n == fc[i].frames ? "ok" : "FAIL"); fails += n != fc[i].frames;
    }
    // (2) round trips through the receiver
    const char *msgs[] = { "CQ M7JVY CQ", "G4ABC 599 123", "TU NOW G4ABC", "hello from the phone, 73!" };
    float snrs[] = { 0, -8, -12, -14, -16, -18, -20 };
    for (unsigned s = 0; s < sizeof snrs / sizeof *snrs; s++)
        for (unsigned m = 0; m < sizeof msgs / sizeof *msgs; m++) {
            static int tones[16 * 59]; char norm[81];
            int nsym = jtty_message_tones(msgs[m], 0, tones, 16 * 59, norm);
            int nsig = nsym * 384; static int16_t sig[16 * 59 * 384];
            int nt2; static int tones2[16 * 59]; char norm2[81]; nt2 = jtty_message_tones("CQ G4ABC CQ", 0, tones2, 16 * 59, norm2); // a second station, overlapping
            static int16_t sig2[16 * 59 * 384]; int n2 = nt2 * 384;
            float amp = 0.01f * powf(10.0f, snrs[s] / 20.0f) * sqrtf(2500.0f / 6000.0f) * 1.41421356f; // SNR in 2500 Hz: signal rms = noise rms (0.1 of full scale over 6 kHz) x sqrt(2500/6000) x 10^(snr/20)
            jtty_wave(tones, nsym, 1500, 1.0f, sig, nsig); jtty_wave(tones2, nt2, 1700, 1.0f, sig2, n2);
            int total = 12000 * 3 + nsig + 12000 * 3;  // 3 s of noise each side
            static int16_t audio[12000 * 40];
            for (int i = 0; i < total; i++) {
                float x = gauss() * 0.1f * 3276.7f;    // noise
                int a = i - 12000 * 3, b = i - 12000 * 4; // the signals' start: 3 s and 4 s in
                if (a >= 0 && a < nsig) x += sig[a] * amp;
                if (b >= 0 && b < n2) x += sig2[b] * amp;
                audio[i] = (int16_t)(x > 32767 ? 32767 : x < -32768 ? -32768 : x);
            }
            jtty_rx_reset();
            for (int i = 0; i < total; i += 600) jtty_rx_feed(audio + i, total - i < 600 ? total - i : 600, 1500, 100, 200, 2800);
            jtty_update_t u[64]; int n = jtty_rx_updates(u, 64);
            char got[81] = "", got2[81] = ""; bool c1 = false, c2 = false; int snr1 = 0;
            if (getenv("JTTY_ALL")) for (int i = 0; i < n; i++) printf("     upd id %lld f %.1f t %.2f snr %d %s [%s]\n", u[i].id, u[i].freq, u[i].start, u[i].snr, u[i].complete ? "EOM" : "...", u[i].text);
            for (int i = 0; i < n; i++) {             // the last update of each message
                if (fabsf(u[i].freq - 1500) < 20) { strcpy(got, u[i].text); c1 = u[i].complete; snr1 = u[i].snr; }
                if (fabsf(u[i].freq - 1700) < 20) { strcpy(got2, u[i].text); c2 = u[i].complete; }
            }
            bool ok = strcmp(got, norm) == 0 && c1;
            printf("SNR %+4.0f  %-28s -> %-28s %s (snr %d) | 2nd: %s%s\n", snrs[s], norm, got, ok ? "ok" : "--", snr1, got2, c2 ? "" : " (incomplete)");
        }
    printf("%d codec failures\n", fails);
    return fails != 0;
}
