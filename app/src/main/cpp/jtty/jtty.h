// JTTY for HF Digital Modes: a C port of WSJT-X Improved 3.2.1's lib/jtty (Fortran, GPL v3; JTTY by Joe Taylor K1JT
// and the WSJT-X team, announced with WSJT-X 3.2.0-rc1). JTTY is an RTTY-like keyboard mode: free-running 1.888 s frames
// (13 sync + 46 data symbols of 4-GFSK at 31.25 baud, 127 Hz wide), each carrying a 32-bit source word (a call action,
// a typed contest exchange, a control phrase, a locator or five characters of text) + a reserved 0 bit + an
// end-of-message bit, protected by a 12-bit CRC and a K=10 tail-biting convolutional code (octal 1167 / 1545).
// See ANDROID_CHANGES.txt for what was ported from where.
#pragma once
#include <stdbool.h>                                  // bool
#include <stdint.h>                                   // int16_t, uint64_t

#ifdef __cplusplus
extern "C" {
#endif

#define JTTY_MAX_FRAMES 16                            // frames in one message at most
#define JTTY_PAYLOAD_BITS 34                          // 32-bit source word + reserved bit + end-of-message bit
#define JTTY_INFO_BITS 46                             // payload + 12-bit CRC
#define JTTY_NSYNC 13                                 // sync symbols a frame
#define JTTY_NSYM 59                                  // symbols a frame (13 sync + 46 data)
#define JTTY_NSPS 384                                 // samples a symbol at 12 kHz (31.25 baud)
#define JTTY_MSG_LEN 80                               // longest message text

// Exchange profiles (how "599 nnn" text is read when packing): unknown (generic), Field Day, RTTY Roundup.
#define JTTY_EXCHANGE_UNKNOWN 0
#define JTTY_EXCHANGE_FIELD_DAY 1
#define JTTY_EXCHANGE_RTTY 2

// ---- source codec (jtty_codec.c: jtty_source_codec.f90 + jtty_mod.f90) ----

/** Text -> frames: normalises [message] (upper case, single spaces, unknown characters -> '#'; the result is written
 *  back into [normalized], 81 bytes) and packs it into the fewest frames. Each frame is 34 bits, bit 0 first, in
 *  frames[i][0..33]. Returns the number of frames (1..16), 0 for an empty message, -1 if it cannot be sent. */
int jtty_pack_message(const char *message, int profile, uint8_t frames[][JTTY_PAYLOAD_BITS], char *normalized);

/** One frame's 34 bits -> its text (into [text], 81 bytes), as WSJT-X's unpack_jtty does for one frame: TEXT5 frames
 *  give five characters with spaces as '~'; other frames give their rendering, after which a separator column is due
 *  (*trailing_sep). *eom: the frame ends its message. Returns false for an invalid frame. */
bool jtty_unpack_frame(const uint8_t bits[JTTY_PAYLOAD_BITS], char *text, bool *trailing_sep, bool *eom);

// ---- FEC (jtty_fec.c: tbcc.f90, jtty_tbcc_list_decoder.f90, jtty_tbcc_decoder.f90) ----

/** 34 payload bits -> 46 data tones (0..3): CRC-12 appended, tail-biting rate-1/2 K=10 code, Gray mapping. */
void jtty_tbcc_encode(const uint8_t payload[JTTY_PAYLOAD_BITS], int tones[JTTY_INFO_BITS]);

/** Decode 46 data symbols from their tone correlations (full-symbol complex [corr] and half-symbol energies [half],
 *  interleaved re/im, [46][4][2]) with WSJT-X's coherent-length ladder (1, 2, 4 symbols, then half-symbol energies)
 *  and list decoding (4 hypotheses, CRC + reserved-bit check). Returns true with the payload. */
bool jtty_tbcc_decode(const float corr[JTTY_INFO_BITS][4][2], const float half[JTTY_INFO_BITS][4][2], uint8_t payload[JTTY_PAYLOAD_BITS]);

// ---- transmit (jtty_tx.c: genjtty.f90, gen_jttywave.f90) ----

/** Message -> channel tones (59 a frame: sync then data). Returns the number of symbols (0: nothing to send, -1: cannot
 *  be sent). [normalized] (81 bytes) receives the text as it will be received. */
int jtty_message_tones(const char *message, int profile, int *tones, int max_tones, char *normalized);

/** Tones -> 12 kHz audio: 4-GFSK (BT 2.0) at 31.25 baud, lowest tone [f0] Hz, peak [amplitude] 0..1. Returns samples
 *  written (nsym * 384), or -1 if [max_samples] is too small. */
int jtty_wave(const int *tones, int nsym, float f0, float amplitude, int16_t *out, int max_samples);

// ---- receive (jtty_rx.c: rjtty_sub.f90, jtty_mdecode.f90, jtty_peakup.f90, subtract_jtty.f90, ana64a.f90) ----

/** A message being received or complete: its id (the same for every update of one message), its frequency (Hz), the
 *  start of its first frame (s since the receiver's reset), the text so far, complete (end-of-message seen), SNR (dB). */
typedef struct {
    long long id;                                     // message id
    float freq;                                       // audio frequency of its latest frame (Hz)
    double start;                                     // its first frame's sync time (s since jtty_rx_reset)
    char text[JTTY_MSG_LEN + 1];                      // text so far
    bool complete;                                    // its last frame has arrived
    int snr;                                          // SNR of its first frame (dB in 2500 Hz)
} jtty_update_t;

/** Start receiving afresh (forget all audio and messages). */
void jtty_rx_reset(void);

/** More 12 kHz audio; decodes whatever whole quarter-frame steps it completes. [f0] / [ftol]: the receive frequency and
 *  its search width (WSJT-X's Rx frequency and FTol); [nfa] / [nfb]: the band searched beside it (Hz). */
void jtty_rx_feed(const int16_t *samples, int n, float f0, float ftol, int nfa, int nfb);

/** Updates since the last call (at most [max]); returns how many. */
int jtty_rx_updates(jtty_update_t *out, int max);

#ifdef __cplusplus
}
#endif
