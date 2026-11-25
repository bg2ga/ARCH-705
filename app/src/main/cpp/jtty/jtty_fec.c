// JTTY forward error correction: a C port of WSJT-X Improved 3.2.1's lib/jtty/tbcc.f90 (CRC-12, tail-biting encoder),
// jtty_tbcc_list_decoder.f90 (list WAVA decoder - its reference form, jtty_tbcc_list_wava_reference, which the
// optimised form reproduces) and jtty_tbcc_decoder.f90 (the coherent-length ladder). GPL v3, as WSJT-X.
// Code: rate 1/2, memory 9 (K = 10), generators octal 1167 / 1545, outer CRC polynomial 0x80F (12 bits); two coded bits a
// symbol, Gray mapped (00 01 11 10 -> tones 0 1 2 3).
#include "jtty.h"                                     // this module's API
#include <stdlib.h>                                   // qsort
#include <string.h>                                   // memset

#define NU 9                                          // memory: 512 states
#define NSTATES (1 << NU)                             // states
#define STATE_MASK (NSTATES - 1)                      // state bits
#define REG_MASK ((1 << (NU + 1)) - 1)                // constraint-length register
#define G0 01167                                      // generator 0 (octal)
#define G1 01545                                      // generator 1
#define CRC_POLY 0x80F                                // outer CRC (top bit implicit)
#define CRC_BITS 12                                   // CRC length
#define WIDTH 4                                       // survivors kept a state (P4)
#define WRAPS 2                                       // passes round the circular trellis
#define HYPOTHESES 4                                  // codewords offered to the CRC (H4)
#define RESERVED_BIT 33                               // 1-based payload bit always 0
#define NEG (-1.0e300)                                // "no path" metric

static int parity(int v) { v ^= v >> 16; v ^= v >> 8; v ^= v >> 4; v ^= v >> 2; v ^= v >> 1; return v & 1; } // odd number of 1s?

static void transition(int state, int bit, int *next, int *tone) // one input bit: next state and tone (jtty_tbcc_transition)
{
    int reg = ((state << 1) | bit) & REG_MASK;        // the register with the new bit
    int b0 = parity(reg & G0), b1 = parity(reg & G1); // the two coded bits
    *tone = 2 * b0 + (b0 ^ b1);                       // Gray: 00 -> 0, 01 -> 1, 11 -> 2, 10 -> 3
    *next = reg & STATE_MASK;
}

static void crc12_bits(const uint8_t *payload, uint8_t *info) // payload (34) -> info (46): payload then its CRC, msb first (encode_crc12)
{
    int reg = 0;
    for (int i = 0; i < JTTY_PAYLOAD_BITS; i++) {
        info[i] = payload[i];
        reg ^= payload[i] << (CRC_BITS - 1);
        reg = (reg & (1 << (CRC_BITS - 1))) ? ((reg << 1) ^ CRC_POLY) : (reg << 1);
        reg &= (1 << CRC_BITS) - 1;
    }
    for (int i = 0; i < CRC_BITS; i++) info[JTTY_PAYLOAD_BITS + i] = (uint8_t)((reg >> (CRC_BITS - 1 - i)) & 1);
}

static bool crc_valid(uint64_t bits)                  // all 46 bits through the CRC: remainder 0? (jtty_tbcc_crc_valid; bit i-1 = info bit i)
{
    int reg = 0;
    for (int i = 0; i < JTTY_INFO_BITS; i++) {
        reg ^= (int)((bits >> i) & 1) << (CRC_BITS - 1);
        reg = (reg & (1 << (CRC_BITS - 1))) ? ((reg << 1) ^ CRC_POLY) : (reg << 1);
        reg &= (1 << CRC_BITS) - 1;
    }
    return reg == 0;
}

void jtty_tbcc_encode(const uint8_t payload[JTTY_PAYLOAD_BITS], int tones[JTTY_INFO_BITS])
{
    uint8_t info[JTTY_INFO_BITS]; crc12_bits(payload, info); // payload + CRC
    int state = 0;                                    // tail-biting: start in the state the last NU bits leave
    for (int t = 0; t < NU; t++) state = ((state << 1) | info[JTTY_INFO_BITS - NU + t]) & STATE_MASK;
    for (int t = 0; t < JTTY_INFO_BITS; t++) transition(state, info[t], &state, &tones[t]); // one tone per bit
}

// ---- list WAVA decoder (jtty_tbcc_list_wava_reference) ----

typedef struct {                                      // a path into a state
    bool valid;                                       // in use
    double metric;                                    // its metric
    int origin;                                       // the state it started this wrap from
    uint64_t bits;                                    // its input bits so far: bit i-1 = information bit i
} surv_t;

typedef struct {                                      // a closed codeword offered to the CRC
    uint64_t bits;                                    // its 46 bits (= its identity)
    int start;                                        // its start (= end) state
    double clean, wava;                               // its single-frame score; its WAVA path metric
    bool crc;                                         // CRC remainder 0
} pool_t;

static surv_t prev_s[NSTATES][WIDTH], cur_s[NSTATES][WIDTH]; // survivors (static: one decode at a time - the receiver is single-threaded)
static pool_t pool[NSTATES * WIDTH];                  // closed codewords
static int next_state_tab[NSTATES][2], tone_tab[NSTATES][2]; // the trellis
static bool tables_ready;                             // built?

static uint64_t prefix_mask(int len) { return len >= 64 ? ~0ull : ((1ull << len) - 1); } // bits 1..len

static bool survivor_precedes(const surv_t *l, const surv_t *r, int prefix) // better metric, then lower origin, then lexically smaller bits
{
    if (l->metric > r->metric) return true;
    if (l->metric < r->metric) return false;
    if (l->origin != r->origin) return l->origin < r->origin;
    uint64_t diff = (l->bits ^ r->bits) & prefix_mask(prefix);
    if (!diff) return false;                          // identical
    int i = __builtin_ctzll(diff);                    // the first differing bit
    return ((l->bits >> i) & 1) == 0;                 // a 0 there sorts first
}

static void insert_survivor(surv_t *s, const surv_t *c, int prefix) // keep the WIDTH best distinct paths into a state
{
    for (int slot = 0; slot < WIDTH; slot++) {        // the same path (origin and prefix) already here?
        if (!s[slot].valid || s[slot].origin != c->origin || ((s[slot].bits ^ c->bits) & prefix_mask(prefix))) continue;
        if (c->metric <= s[slot].metric) return;      // no better
        for (int k = slot; k < WIDTH - 1; k++) s[k] = s[k + 1]; // take it out
        s[WIDTH - 1].valid = false; s[WIDTH - 1].metric = NEG; s[WIDTH - 1].origin = -1; s[WIDTH - 1].bits = 0;
        break;
    }
    int at = WIDTH;                                   // where it goes
    for (int slot = 0; slot < WIDTH; slot++) if (!s[slot].valid || survivor_precedes(c, &s[slot], prefix)) { at = slot; break; }
    if (at >= WIDTH) return;                          // not among the best
    for (int k = WIDTH - 1; k > at; k--) s[k] = s[k - 1];
    s[at] = *c;
}

static double coherent_metric(const float corr[JTTY_INFO_BITS][4][2], int first, const int *tones, int len) // |sum of the tones' correlations|^2 / len
{
    double re = 0, im = 0;
    for (int k = 0; k < len; k++) { re += corr[first + k][tones[k]][0]; im += corr[first + k][tones[k]][1]; }
    return (re * re + im * im) / len;
}

static int tailbiting_state(uint64_t bits)            // the state the last NU bits leave
{
    int s = 0;
    for (int i = JTTY_INFO_BITS - NU; i < JTTY_INFO_BITS; i++) s = ((s << 1) | (int)((bits >> i) & 1)) & STATE_MASK;
    return s;
}

static double score_candidate(const float corr[JTTY_INFO_BITS][4][2], int L, uint64_t bits, bool *closed) // single-frame score (jtty_tbcc_score_candidate)
{
    int start = tailbiting_state(bits), state = start; double metric = 0;
    for (int b = 0; b < JTTY_INFO_BITS; b += L) {     // block by block
        int len = JTTY_INFO_BITS - b < L ? JTTY_INFO_BITS - b : L, tones[4];
        for (int k = 0; k < len; k++) { int nx; transition(state, (int)((bits >> (b + k)) & 1), &nx, &tones[k]); state = nx; }
        metric += coherent_metric(corr, b, tones, len);
    }
    *closed = state == start; return metric;
}

static int cmp_pool(const void *a, const void *b)     // candidate_precedes: clean metric, identity, start state, WAVA metric
{
    const pool_t *l = (const pool_t *)a, *r = (const pool_t *)b;
    if (l->clean > r->clean) return -1; if (l->clean < r->clean) return 1;
    if (l->bits != r->bits) return l->bits < r->bits ? -1 : 1;
    if (l->start != r->start) return l->start < r->start ? -1 : 1;
    if (l->wava > r->wava) return -1; if (l->wava < r->wava) return 1;
    return 0;
}

// One rung: coherent length L. Fills cand (up to HYPOTHESES, best first) and returns how many.
static int list_wava(const float corr[JTTY_INFO_BITS][4][2], int L, pool_t *cand)
{
    if (!tables_ready) {                              // the trellis, once
        for (int s = 0; s < NSTATES; s++) for (int b = 0; b < 2; b++) transition(s, b, &next_state_tab[s][b], &tone_tab[s][b]);
        tables_ready = true;
    }
    int nblocks = (JTTY_INFO_BITS + L - 1) / L;       // blocks of L symbols
    static double branch_metric[JTTY_INFO_BITS][16][NSTATES]; // [block][branch word][state] -> coherent metric (46 blocks at L = 1, 16 words at L = 4)
    static int end_tab[JTTY_INFO_BITS][16][NSTATES];  // [block][branch word][state] -> end state
    for (int blk = 0; blk < nblocks; blk++) {         // every branch from every state: end state and coherent metric
        int start = blk * L, len = JTTY_INFO_BITS - start < L ? JTTY_INFO_BITS - start : L;
        for (int w = 0; w < (1 << len); w++)
            for (int s = 0; s < NSTATES; s++) {
                int st = s, tones[4];
                for (int k = 0; k < len; k++) { int bit = (w >> (len - 1 - k)) & 1; tones[k] = tone_tab[st][bit]; st = next_state_tab[st][bit]; } // msb of the word first
                end_tab[blk][w][s] = st;
                branch_metric[blk][w][s] = coherent_metric(corr, start, tones, len);
            }
    }
    for (int s = 0; s < NSTATES; s++) for (int r = 0; r < WIDTH; r++) { prev_s[s][r].valid = false; prev_s[s][r].metric = NEG; prev_s[s][r].origin = -1; prev_s[s][r].bits = 0; }
    for (int s = 0; s < NSTATES; s++) { prev_s[s][0].valid = true; prev_s[s][0].metric = 0; prev_s[s][0].origin = s; } // every start state
    for (int wrap = 0; wrap < WRAPS; wrap++) {
        for (int s = 0; s < NSTATES; s++) for (int r = 0; r < WIDTH; r++) if (prev_s[s][r].valid) { prev_s[s][r].origin = s; prev_s[s][r].bits = 0; } // a new frame from here
        for (int blk = 0; blk < nblocks; blk++) {
            int start = blk * L, len = JTTY_INFO_BITS - start < L ? JTTY_INFO_BITS - start : L;
            bool has_reserved = start + 1 <= RESERVED_BIT && start + 1 + len > RESERVED_BIT; // this block holds the reserved bit
            int roff = RESERVED_BIT - 1 - start;      // its offset in the block
            for (int s = 0; s < NSTATES; s++) for (int r = 0; r < WIDTH; r++) { cur_s[s][r].valid = false; cur_s[s][r].metric = NEG; cur_s[s][r].origin = -1; cur_s[s][r].bits = 0; }
            for (int s = 0; s < NSTATES; s++)
                for (int r = 0; r < WIDTH; r++) {
                    if (!prev_s[s][r].valid) continue;
                    for (int w = 0; w < (1 << len); w++) {
                        if (has_reserved && ((w >> (len - 1 - roff)) & 1)) continue; // the reserved bit is always 0
                        surv_t e = prev_s[s][r];      // extend this path by the branch
                        e.metric += branch_metric[blk][w][s];
                        for (int k = 0; k < len; k++) if ((w >> (len - 1 - k)) & 1) e.bits |= 1ull << (start + k);
                        insert_survivor(cur_s[end_tab[blk][w][s]], &e, start + len);
                    }
                }
            memcpy(prev_s, cur_s, sizeof prev_s);
        }
    }
    int npool = 0;                                    // closed paths -> distinct codewords
    for (int s = 0; s < NSTATES; s++)
        for (int r = 0; r < WIDTH; r++) {
            const surv_t *p = &prev_s[s][r];
            if (!p->valid || p->origin != s) continue; // tail-biting: it must end where it started
            int found = -1;
            for (int i = 0; i < npool; i++) if (pool[i].bits == p->bits) { found = i; break; }
            if (found >= 0) {                         // the same word by another path: keep the better metric
                if (p->metric > pool[found].wava || (!(p->metric < pool[found].wava) && s < pool[found].start)) { pool[found].wava = p->metric; pool[found].start = s; }
                continue;
            }
            pool_t *c = &pool[npool++];
            c->bits = p->bits; c->start = s; c->wava = p->metric;
            bool closed; c->clean = score_candidate(corr, L, p->bits, &closed); // a clean single-frame score orders them
            c->crc = crc_valid(p->bits);
        }
    qsort(pool, npool, sizeof(pool_t), cmp_pool);     // best first
    int n = npool < HYPOTHESES ? npool : HYPOTHESES;
    for (int i = 0; i < n; i++) cand[i] = pool[i];
    return n;
}

static bool decode_rung(const float corr[JTTY_INFO_BITS][4][2], int L, uint8_t payload[JTTY_PAYLOAD_BITS]) // the first CRC-valid word (not all-zero)
{
    pool_t cand[HYPOTHESES]; int n = list_wava(corr, L, cand);
    for (int i = 0; i < n; i++) {
        if (!cand[i].crc) continue;
        if ((cand[i].bits & prefix_mask(JTTY_PAYLOAD_BITS)) == 0) return false; // the all-zero word ends the search
        for (int k = 0; k < JTTY_PAYLOAD_BITS; k++) payload[k] = (uint8_t)((cand[i].bits >> k) & 1);
        return true;
    }
    return false;
}

bool jtty_tbcc_decode(const float corr[JTTY_INFO_BITS][4][2], const float half[JTTY_INFO_BITS][4][2], uint8_t payload[JTTY_PAYLOAD_BITS])
{
    static const int L[3] = { 1, 2, 4 };              // coherent lengths, in turn
    memset(payload, 0, JTTY_PAYLOAD_BITS);
    for (int i = 0; i < 3; i++) if (decode_rung(corr, L[i], payload)) return true;
    return decode_rung(half, 1, payload);             // half-symbol energies (no phase): length 1 only
}
