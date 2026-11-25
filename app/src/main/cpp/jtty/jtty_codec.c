// JTTY source codec: a C port of WSJT-X Improved 3.2.1's lib/jtty/jtty_source_codec.f90 (source atoms: pack, unpack,
// render) and the packing / unpacking parts of jtty_mod.f90 (pack_jtty's fewest-frames dynamic program,
// normalize_jtty_message, unpack_jtty for one frame), with lib/chkcall.f90 and the standard-callsign part of
// packjt77's pack28 / unpack28 (through ft8_lib's pack_basecall). GPL v3, as WSJT-X.
//
// A frame's 34 bits are kept as bits[0..33] = the Fortran frame string's characters 1..34: bits 0-31 the 32-bit source
// word, most significant first; bit 32 reserved (always 0); bit 33 end of message.
//   i2 (bits 30-31)  0 / 1: call actions (28-bit call + 2-bit action + i2)    2: STRUCT30    3: TEXT5 (5 x 6-bit chars)
#include "jtty.h"                                     // this module's API
#include <stdio.h>                                    // snprintf
#include <string.h>                                   // strlen, memcpy

int32_t pack_basecall(const char *callsign, int length); // ft8_lib (ft8/message.c): a standard callsign's 28-bit base (-1: not one)

static const char ALPHABET[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ +-./?!\"#$%,&*()_'=[]{}<>|:;"; // the 64 JTTY characters
static const char B36[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"; // base-36 digits (location tokens)

enum { ATOM_CALL = 0, ATOM_EXCH_NUM = 1, ATOM_EXCH_LOC = 2, ATOM_EXCH_PAIR = 3, ATOM_EXCH_NUM_TIME = 4, ATOM_CONTROL = 5, ATOM_GRID4 = 6, ATOM_TEXT5 = 7 }; // atom kinds
enum { CALL_CQ = 0, CALL_CALL = 1, CALL_TU_CQ = 2, CALL_CALL_TU = 3, CALL_CALL_AGN = 4, CALL_TU_NOW = 5 }; // call actions
enum { ROLE_FIELD_ONLY = 0, ROLE_FULL = 1 };          // a field alone, or "599 " + field
enum { NUM_SERIAL = 0, NUM_CQ_ZONE = 1, NUM_ITU_ZONE = 2, NUM_AGE = 3, NUM_POWER = 4, NUM_CHECK = 5, NUM_LICENSE_YEAR = 6, NUM_GENERIC = 7 }; // number kinds
enum { LOC_STATE_PROVINCE = 0, LOC_SECTION = 1, LOC_COUNTRY_PREFIX = 2, LOC_QTH = 3, LOC_ADMIN_CODE = 4 }; // location kinds
enum { PAIR_ZONE_LOC3 = 0, PAIR_CLASS_SECTION = 1 };   // pair schemas
enum { MISC_CONTROL = 0, MISC_GRID4 = 1 };             // MISC subtypes

static const char *CONTROL_TEXT[18] = { "AGN?", "CALL?", "AGN CALL", "NR?", "AGN NR", "EXCH?", "STATE?", "SECTION?", // the 18 control phrases
    "ZONE?", "GRID?", "RPRT?", "QSL TU", "TU", "QRZ?", "QSO B4", "WAIT", "NIL?", "OK?" };

#define NSEC 86                                       // ARRL / RAC sections (packjt77_grammar's PACK77_ARRL_SECTIONS)
static const char *SECTIONS[NSEC] = {
    "AB", "AK", "AL", "AR", "AZ", "BC", "CO", "CT", "DE", "EB", "EMA", "ENY", "EPA", "EWA", "GA", "GH", "IA", "ID", "IL", "IN",
    "KS", "KY", "LA", "LAX", "NS", "MB", "MDC", "ME", "MI", "MN", "MO", "MS", "MT", "NC", "ND", "NE", "NFL", "NH", "NL", "NLI",
    "NM", "NNJ", "NNY", "TER", "NTX", "NV", "OH", "OK", "ONE", "ONN", "ONS", "OR", "ORG", "PAC", "PR", "QC", "RI", "SB", "SC", "SCV",
    "SD", "SDG", "SF", "SFL", "SJV", "SK", "SNJ", "STX", "SV", "TN", "UT", "VA", "VI", "VT", "WCF", "WI", "WMA", "WNY", "WPA", "WTX",
    "WV", "WWA", "WY", "DX", "PE", "NB" };

typedef struct {                                      // one source atom (jtty_source_atom)
    int kind, subtype, role, value, value2;           // kind (-1 none), its subtype, role, numbers
    char text[14];                                    // call, token, grid, class letter or 5 text characters
} atom_t;

#define NTOKENS 2063592u                              // packjt77: special tokens before the 22-bit hashes
#define MAX22 4194304u                                // 22-bit hashes before the standard calls

// ---- small helpers ----

static int source_index(char c) { const char *p = c ? strchr(ALPHABET, c) : NULL; return p ? (int)(p - ALPHABET) : -1; } // a character's JTTY index (-1: none)
static char source_char(int i) { return ALPHABET[i & 63]; } // a JTTY index's character
static bool is_letter(char c) { return c >= 'A' && c <= 'Z'; } // A-Z
static bool is_digit(char c) { return c >= '0' && c <= '9'; } // 0-9
static int trimlen(const char *s) { int n = (int)strlen(s); while (n > 0 && s[n - 1] == ' ') n--; return n; } // Fortran len_trim
static atom_t atom0(void) { atom_t a; memset(&a, 0, sizeof a); a.kind = -1; return a; } // a blank atom (kind -1)

static int section_index(const char *word)            // pack77_arrl_section_index: 1-based, -1 if none (compares 3 columns, blank padded)
{
    char w[4] = "   "; int n = (int)strlen(word); if (n > 3) n = 3; memcpy(w, word, n); // the word's first 3 columns
    for (int i = 0; i < NSEC; i++) {
        char s[4] = "   "; memcpy(s, SECTIONS[i], strlen(SECTIONS[i])); // the section, padded
        if (memcmp(s, w, 3) == 0) return i + 1;      // found
    }
    return -1;
}

// ---- callsigns (chkcall.f90; packjt77 pack28 / unpack28 for standard calls) ----

static bool chkcall(const char *w)                    // could [w] be a standard (or compound) callsign? (chkcall.f90's cok)
{
    int n1 = trimlen(w);                              // its length
    if (n1 > 11) return false;
    if (strchr(w, '.') || strchr(w, '+') || strchr(w, '-') || strchr(w, '?')) return false;
    const char *sl = strchr(w, '/'); int i0 = sl ? (int)(sl - w) + 1 : 0; // 1-based position of '/'
    if (n1 > 6 && i0 <= 0) return false;
    if ((i0 - 1 > n1 - i0 ? i0 - 1 : n1 - i0) > 6) return false; // the base call must be < 7 characters
    char bc[7] = "      ";                            // the base call
    memcpy(bc, w, n1 < 6 ? n1 : 6);
    if (i0 >= 2 && i0 <= n1 - 1) {                    // compound: the longer part is the base
        memset(bc, ' ', 6);
        if (i0 - 1 <= n1 - i0) memcpy(bc, w + i0, n1 - i0 < 6 ? n1 - i0 : 6); else memcpy(bc, w, i0 - 1 < 6 ? i0 - 1 : 6);
    }
    int nbc = trimlen(bc); if (nbc > 6) return false;
    if (!is_letter(bc[0]) && !is_letter(bc[1])) return false; // a letter in the 1st or 2nd place
    if (bc[0] == 'Q' && memcmp(bc, "QU1RK", 5) != 0) return false; // not Q (but the QU1RK placeholder)
    int i1 = 0; if (is_digit(bc[1])) i1 = 2; if (is_digit(bc[2])) i1 = 3; // a digit in the 2nd or 3rd place
    if (i1 == 0 || i1 == nbc) return false;           // and a suffix after it
    int n = 0;
    for (int i = i1; i < nbc; i++) { if (!is_letter(bc[i])) return false; n++; } // 1-3 letters
    return n >= 1 && n <= 3;
}

static long pack28(const char *call)                  // a standard call's 28-bit value (-1: not one)
{
    int32_t n = pack_basecall(call, (int)strlen(call)); // ft8_lib, the same as packjt77 for standard calls
    return n < 0 ? -1 : (long)(NTOKENS + MAX22 + (uint32_t)n);
}

static bool unpack28(long n28, char *out)             // a 28-bit value -> standard call (false for tokens / hashes)
{
    if (n28 < (long)(NTOKENS + MAX22)) return false;  // DE / QRZ / CQ / hashes are not JTTY calls
    unsigned long n = (unsigned long)n28 - NTOKENS - MAX22;
    if (n >= 37ul * 36 * 10 * 27 * 27 * 27) return false; // beyond the standard-call range
    char c[7]; c[6] = 0;                              // six columns
    c[5] = " ABCDEFGHIJKLMNOPQRSTUVWXYZ"[n % 27]; n /= 27;
    c[4] = " ABCDEFGHIJKLMNOPQRSTUVWXYZ"[n % 27]; n /= 27;
    c[3] = " ABCDEFGHIJKLMNOPQRSTUVWXYZ"[n % 27]; n /= 27;
    c[2] = "0123456789"[n % 10]; n /= 10;
    c[1] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"[n % 36]; n /= 36;
    c[0] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"[n % 37];
    char t[16]; int k = 0;                            // the call as unpack28 gives it
    if (memcmp(c, "3D0", 3) == 0 && c[3] != ' ') { memcpy(t, "3DA0", 4); k = 4; for (int i = 3; i < 6; i++) if (c[i] != ' ') t[k++] = c[i]; } // Swaziland work-around
    else if (c[0] == 'Q' && is_letter(c[1])) { t[0] = '3'; t[1] = 'X'; k = 2; for (int i = 1; i < 6; i++) if (c[i] != ' ') t[k++] = c[i]; } // Guinea work-around
    else for (int i = 0; i < 6; i++) if (c[i] != ' ') t[k++] = c[i]; // trimmed
    t[k] = 0; strcpy(out, t);
    return k > 0;
}

static bool standard_call(const char *call)           // a call a 28-bit JTTY field can carry exactly (standard_call)
{
    if (!chkcall(call) || strchr(call, '/') || call[0] == 'Q') return false;
    for (const char *p = call; *p; p++) if (!is_letter(*p) && !is_digit(*p)) return false; // A-Z 0-9 only
    long n28 = pack28(call); char back[16];
    return n28 >= 0 && unpack28(n28, back) && strcmp(back, call) == 0; // it must come back the same
}

// ---- base 36, grid ----

static bool pack_base36(const char *token, long *value) // a 2- or 3-character token (3: not with a leading 0)
{
    int n = (int)strlen(token); *value = 0;
    if (n != 2 && n != 3) return false;
    for (int i = 0; i < n; i++) { const char *p = strchr(B36, token[i]); if (!p || !token[i]) return false; *value = 36 * *value + (p - B36); }
    return !(n == 3 && *value < 36 * 36);
}

static void unpack_base36(long value, int n, char *token) // value -> n base-36 characters
{
    for (int i = n - 1; i >= 0; i--) { token[i] = B36[value % 36]; value /= 36; }
    token[n] = 0;
}

static bool grid4_to_index(const char *g, int *index) // "IO91" -> 0..32399
{
    if (trimlen(g) != 4) return false;
    int a = g[0] - 'A', b = g[1] - 'A', c = g[2] - '0', d = g[3] - '0';
    if (a < 0 || a >= 18 || b < 0 || b >= 18 || c < 0 || c >= 10 || d < 0 || d >= 10) return false;
    *index = ((a * 18 + b) * 10 + c) * 10 + d; return true;
}

static void index_to_grid4(int index, char *g)        // 0..32399 -> "IO91"
{
    int d = index % 10; index /= 10; int c = index % 10; index /= 10; int b = index % 18; int a = index / 18;
    g[0] = (char)('A' + a); g[1] = (char)('A' + b); g[2] = (char)('0' + c); g[3] = (char)('0' + d); g[4] = 0;
}

static bool valid_number(int kind, int value)         // a number kind's range
{
    if (kind < 0 || kind > 7 || value < 0 || value > 131071) return false;
    if (kind == NUM_CQ_ZONE) return value >= 1 && value <= 40;
    if (kind == NUM_ITU_ZONE) return value >= 1 && value <= 90;
    if (kind == NUM_LICENSE_YEAR) return value <= 9999;
    return true;
}

static bool valid_role(int role) { return role == ROLE_FIELD_ONLY || role == ROLE_FULL; } // field only / full

// ---- atom <-> 34 bits ----

static void put32(uint64_t n32, bool eom, uint8_t *bits) // the 32-bit word (msb first), reserved 0, EOM
{
    for (int i = 0; i < 32; i++) bits[i] = (uint8_t)((n32 >> (31 - i)) & 1u);
    bits[32] = 0; bits[33] = eom ? 1 : 0;
}

static uint64_t finish_struct(uint64_t body, int family) { return 4 * (8 * body + (uint64_t)family) + 2; } // STRUCT30: body, family, i2 = 2

static bool pack_atom(const atom_t *a, bool eom, uint8_t *bits) // pack_jtty_atom
{
    uint64_t n32 = 0, body; long token;
    switch (a->kind) {
    case ATOM_CALL: {
        if (a->subtype < 0 || a->subtype > 5 || !standard_call(a->text)) return false;
        long n28 = pack28(a->text); if (n28 < 0) return false;
        int i2 = a->subtype <= 3 ? 0 : 1, n2 = a->subtype <= 3 ? a->subtype : a->subtype - 4; // action -> i2, n2
        n32 = ((uint64_t)n28 << 4) + 4u * (uint64_t)n2 + (uint64_t)i2;
        break; }
    case ATOM_EXCH_NUM:
        if (!valid_role(a->role) || !valid_number(a->subtype, a->value)) return false;
        body = ((uint64_t)a->role << 26) + ((uint64_t)a->subtype << 22) + ((uint64_t)a->value << 5);
        n32 = finish_struct(body, 0); break;
    case ATOM_EXCH_LOC: {
        if (!valid_role(a->role) || a->subtype < 0 || a->subtype > 4) return false;
        int n = trimlen(a->text); if (n != 2 && n != 3) return false;
        char t[4]; memcpy(t, a->text, n); t[n] = 0;
        if (!pack_base36(t, &token)) return false;
        body = ((uint64_t)a->role << 26) + ((uint64_t)a->subtype << 22) + ((uint64_t)(n - 2) << 21) + ((uint64_t)token << 5);
        n32 = finish_struct(body, 1); break; }
    case ATOM_EXCH_PAIR: {
        uint64_t pair;
        if (a->subtype == PAIR_ZONE_LOC3) {
            if (a->value < 1 || a->value > 40) return false;
            int n = trimlen(a->text); if (n != 2 && n != 3) return false;
            char t[4]; memcpy(t, a->text, n); t[n] = 0;
            if (!pack_base36(t, &token)) return false;
            pair = ((uint64_t)a->value << 17) + ((uint64_t)(n - 2) << 16) + (uint64_t)token;
        } else if (a->subtype == PAIR_CLASS_SECTION) {
            if (a->value < 1 || a->value > 32 || a->value2 < 1 || a->value2 > NSEC || trimlen(a->text) != 1) return false;
            int cls = a->text[0] - 'A'; if (cls < 0 || cls > 5) return false;
            pair = ((uint64_t)a->value << 17) + ((uint64_t)cls << 14) + ((uint64_t)a->value2 << 7);
        } else return false;
        body = ((uint64_t)a->subtype << 24) + (pair << 1); n32 = finish_struct(body, 2); break; }
    case ATOM_EXCH_NUM_TIME:
        if (!valid_role(a->role) || a->value < 0 || a->value > 16383 || a->value2 < 0 || a->value2 > 1439) return false;
        body = ((uint64_t)a->role << 26) + ((uint64_t)a->value << 12) + ((uint64_t)a->value2 << 1);
        n32 = finish_struct(body, 3); break;
    case ATOM_CONTROL:
        if (a->subtype < 0 || a->subtype > 17) return false;
        body = ((uint64_t)MISC_CONTROL << 23) + ((uint64_t)a->subtype << 16); n32 = finish_struct(body, 4); break;
    case ATOM_GRID4: {
        int gi; if (!valid_role(a->role) || !grid4_to_index(a->text, &gi)) return false;
        body = ((uint64_t)MISC_GRID4 << 23) + ((uint64_t)a->role << 22) + ((uint64_t)gi << 7); n32 = finish_struct(body, 4); break; }
    case ATOM_TEXT5: {
        uint64_t top30 = 0;
        for (int i = 0; i < 5; i++) { char c = a->text[i] ? a->text[i] : ' '; int k = source_index(c); if (k < 0) return false; top30 = 64 * top30 + (uint64_t)k; }
        n32 = 4 * top30 + 3; break; }
    default: return false;
    }
    put32(n32, eom, bits); return true;
}

static uint64_t ibits(uint64_t v, int pos, int len) { return (v >> pos) & ((1ull << len) - 1); } // Fortran ibits

static bool unpack_atom(const uint8_t *bits, atom_t *a, bool *eom) // unpack_jtty_atom
{
    *a = atom0(); *eom = false;
    uint64_t n32 = 0;
    for (int i = 0; i < 32; i++) { if (bits[i] > 1) return false; n32 = (n32 << 1) | bits[i]; }
    if (bits[32] != 0 || bits[33] > 1 || n32 == 0) return false; // reserved bit must be 0; all-zero word invalid
    uint64_t top30 = n32 >> 2; int i2 = (int)(n32 & 3);
    if (i2 == 0 || i2 == 1) {                         // call actions
        long n28 = (long)(n32 >> 4); int n2 = (int)((n32 >> 2) & 3);
        if (i2 == 1 && n2 > 1) return false;          // 1.2, 1.3 reserved
        char call[16]; if (!unpack28(n28, call) || !standard_call(call)) return false;
        a->kind = ATOM_CALL; a->subtype = i2 == 0 ? n2 : n2 + 4; strcpy(a->text, call);
    } else if (i2 == 2) {                             // STRUCT30
        int family = (int)(top30 & 7); uint64_t body = top30 >> 3;
        if (family == 0) {
            a->kind = ATOM_EXCH_NUM; a->role = (int)ibits(body, 26, 1); a->subtype = (int)ibits(body, 22, 4); a->value = (int)ibits(body, 5, 17);
            if (ibits(body, 0, 5) != 0 || !valid_number(a->subtype, a->value)) return false;
        } else if (family == 1) {
            a->kind = ATOM_EXCH_LOC; a->role = (int)ibits(body, 26, 1); a->subtype = (int)ibits(body, 22, 4);
            int n = (int)ibits(body, 21, 1) + 2; long tv = (long)ibits(body, 5, 16);
            if (ibits(body, 0, 5) != 0 || a->subtype > 4 || tv >= (n == 2 ? 36 * 36 : 36 * 36 * 36)) return false;
            if (n == 3 && tv < 36 * 36) return false;
            unpack_base36(tv, n, a->text);
        } else if (family == 2) {
            a->kind = ATOM_EXCH_PAIR; a->subtype = (int)ibits(body, 24, 3);
            uint64_t pair = ibits(body, 1, 23); if (ibits(body, 0, 1) != 0) return false;
            if (a->subtype == PAIR_ZONE_LOC3) {
                a->value = (int)ibits(pair, 17, 6); int n = (int)ibits(pair, 16, 1) + 2; long tv = (long)ibits(pair, 0, 16);
                if (a->value < 1 || a->value > 40 || tv >= (n == 2 ? 36 * 36 : 36 * 36 * 36)) return false;
                if (n == 3 && tv < 36 * 36) return false;
                unpack_base36(tv, n, a->text);
            } else if (a->subtype == PAIR_CLASS_SECTION) {
                a->value = (int)ibits(pair, 17, 6); int cls = (int)ibits(pair, 14, 3); a->value2 = (int)ibits(pair, 7, 7);
                if (a->value < 1 || a->value > 32 || cls > 5 || a->value2 < 1 || a->value2 > NSEC || ibits(pair, 0, 7) != 0) return false;
                a->text[0] = (char)('A' + cls); a->text[1] = 0;
            } else return false;
        } else if (family == 3) {
            a->kind = ATOM_EXCH_NUM_TIME; a->role = (int)ibits(body, 26, 1); a->value = (int)ibits(body, 12, 14); a->value2 = (int)ibits(body, 1, 11);
            if (ibits(body, 0, 1) != 0 || a->value2 > 1439) return false;
        } else if (family == 4) {
            int sub = (int)ibits(body, 23, 4); uint64_t data = ibits(body, 0, 23);
            if (sub == MISC_CONTROL) {
                a->kind = ATOM_CONTROL; a->subtype = (int)ibits(data, 16, 7);
                if (ibits(data, 0, 16) != 0 || a->subtype > 17) return false;
            } else if (sub == MISC_GRID4) {
                a->kind = ATOM_GRID4; a->role = (int)ibits(data, 22, 1); int gi = (int)ibits(data, 7, 15);
                if (ibits(data, 0, 7) != 0 || gi >= 32400) return false;
                index_to_grid4(gi, a->text);
            } else return false;
        } else return false;                          // families 5-7: reserved / guard
    } else {                                          // TEXT5
        a->kind = ATOM_TEXT5;
        for (int i = 0; i < 5; i++) a->text[i] = source_char((int)((top30 >> (6 * (4 - i))) & 63));
        a->text[5] = 0;
    }
    *eom = bits[33] == 1; return true;
}

static void render_number(int kind, int value, char *t) // serials 3 digits, zones / checks 2, years 4, others as is
{
    if (kind == NUM_SERIAL) { if (value < 1000) snprintf(t, 16, "%03d", value); else snprintf(t, 16, "%d", value); }
    else if (kind == NUM_CQ_ZONE || kind == NUM_ITU_ZONE || kind == NUM_CHECK) { if (value < 100) snprintf(t, 16, "%02d", value); else snprintf(t, 16, "%d", value); }
    else if (kind == NUM_LICENSE_YEAR) snprintf(t, 16, "%04d", value);
    else snprintf(t, 16, "%d", value);
}

static void render_role(int role, const char *field, char *text) // "599 " + field for a full exchange
{
    if (role == ROLE_FULL) snprintf(text, JTTY_MSG_LEN + 1, "599 %s", field); else snprintf(text, JTTY_MSG_LEN + 1, "%s", field);
}

static bool render_atom(const atom_t *a, char *text)  // render_jtty_atom
{
    char f[16], s[24]; text[0] = 0;
    switch (a->kind) {
    case ATOM_CALL:
        switch (a->subtype) {
        case CALL_CQ: snprintf(text, JTTY_MSG_LEN + 1, "CQ %s CQ", a->text); break;
        case CALL_CALL: snprintf(text, JTTY_MSG_LEN + 1, "%s", a->text); break;
        case CALL_TU_CQ: snprintf(text, JTTY_MSG_LEN + 1, "TU %s CQ", a->text); break;
        case CALL_CALL_TU: snprintf(text, JTTY_MSG_LEN + 1, "%s TU", a->text); break;
        case CALL_CALL_AGN: snprintf(text, JTTY_MSG_LEN + 1, "%s AGN?", a->text); break;
        case CALL_TU_NOW: snprintf(text, JTTY_MSG_LEN + 1, "TU NOW %s", a->text); break;
        default: return false;
        }
        return true;
    case ATOM_EXCH_NUM:
        if (!valid_role(a->role) || !valid_number(a->subtype, a->value)) return false;
        render_number(a->subtype, a->value, f); render_role(a->role, f, text); return true;
    case ATOM_EXCH_LOC:
        if (a->subtype < 0 || a->subtype > 4) return false;
        render_role(a->role, a->text, text); return true;
    case ATOM_EXCH_PAIR:
        if (a->subtype == PAIR_ZONE_LOC3) { snprintf(text, JTTY_MSG_LEN + 1, "599 %02d %s", a->value, a->text); return true; }
        if (a->subtype == PAIR_CLASS_SECTION) {
            if (a->value2 < 1 || a->value2 > NSEC) return false;
            snprintf(text, JTTY_MSG_LEN + 1, "%d%c %s", a->value, a->text[0], SECTIONS[a->value2 - 1]); return true;
        }
        return false;
    case ATOM_EXCH_NUM_TIME:
        if (!valid_role(a->role) || a->value < 0 || a->value > 16383 || a->value2 < 0 || a->value2 > 1439) return false;
        render_number(NUM_SERIAL, a->value, f); snprintf(s, sizeof s, "%s %02d%02d", f, a->value2 / 60, a->value2 % 60);
        render_role(a->role, s, text); return true;
    case ATOM_CONTROL:
        if (a->subtype < 0 || a->subtype > 17) return false;
        snprintf(text, JTTY_MSG_LEN + 1, "%s", CONTROL_TEXT[a->subtype]); return true;
    case ATOM_GRID4: { char g[5]; memcpy(g, a->text, 4); g[4] = 0; render_role(a->role, g, text); return true; }
    case ATOM_TEXT5: memcpy(text, a->text, 5); text[5] = 0; return true;
    default: return false;
    }
}

// ---- atoms by kind (jtty_*_atom) ----

static atom_t call_atom(int action, const char *call) { atom_t a = atom0(); a.kind = ATOM_CALL; a.subtype = action; snprintf(a.text, sizeof a.text, "%s", call); return a; }
static atom_t num_atom(int role, int kind, int value) { atom_t a = atom0(); a.kind = ATOM_EXCH_NUM; a.role = role; a.subtype = kind; a.value = value; return a; }
static atom_t loc_atom(int role, int kind, const char *t) { atom_t a = atom0(); a.kind = ATOM_EXCH_LOC; a.role = role; a.subtype = kind; snprintf(a.text, sizeof a.text, "%s", t); return a; }
static atom_t class_section_atom(int count, char cls, int sec) { atom_t a = atom0(); a.kind = ATOM_EXCH_PAIR; a.subtype = PAIR_CLASS_SECTION; a.value = count; a.value2 = sec; a.text[0] = cls; a.text[1] = 0; return a; }
static atom_t control_atom(int id) { atom_t a = atom0(); a.kind = ATOM_CONTROL; a.subtype = id; return a; }
static atom_t grid4_atom(int role, const char *g) { atom_t a = atom0(); a.kind = ATOM_GRID4; a.role = role; snprintf(a.text, sizeof a.text, "%s", g); return a; }
static atom_t text5_atom(const char *t, int n)        // up to 5 characters, blank padded
{
    atom_t a = atom0(); a.kind = ATOM_TEXT5; memset(a.text, ' ', 5); a.text[5] = 0;
    for (int i = 0; i < n && i < 5; i++) a.text[i] = t[i];
    return a;
}

// ---- text <-> frames (jtty_mod.f90) ----

static void normalize(const char *raw, char *out)     // normalize_jtty_message: upper case, '#' for others, single spaces, trimmed
{
    int j = 0; bool last_space = true;
    for (int i = 0; raw[i] && i < 200 && j < JTTY_MSG_LEN; i++) {
        char c = raw[i];
        if (c == '~') c = ' ';                        // '~' is the decoder's space marker, not a symbol
        if (c >= 'a' && c <= 'z') c = (char)(c - 32);
        if (source_index(c) < 0) c = '#';             // not in the alphabet
        if (c == ' ') { if (last_space) continue; out[j++] = ' '; last_space = true; }
        else { out[j++] = c; last_space = false; }
    }
    while (j > 0 && out[j - 1] == ' ') j--;            // (Fortran strings drop trailing blanks)
    out[j] = 0;
}

static bool decimal_value(const char *t, int len, int *value) // 1-6 digits, at most 131071
{
    *value = 0;
    if (len < 1 || len > 6) return false;
    for (int i = 0; i < len; i++) { if (!is_digit(t[i])) return false; *value = 10 * *value + (t[i] - '0'); }
    return *value <= 131071;
}

typedef struct {                                      // pack_jtty's dynamic program over the message's columns
    const char *msg; int n, profile, ipos;            // the message, its length, the profile, the column being planned
    int dp[JTTY_MSG_LEN + 2], successor[JTTY_MSG_LEN + 1]; // fewest frames from each column; where its atom ends
    atom_t choice[JTTY_MSG_LEN + 1];                  // the atom chosen at each column
} planner_t;

static void consider(planner_t *p, const atom_t *a, int next) // a candidate atom at ipos, ending before column [next]
{
    int ip = p->ipos, cost = 1 + p->dp[next];
    if (cost > JTTY_MAX_FRAMES || cost > p->dp[ip]) return;
    int rank = a->kind == ATOM_TEXT5, best_rank = p->choice[ip].kind == ATOM_TEXT5; // structured atoms win ties
    int key = 100 * a->kind + 2 * a->subtype + a->role, best_key = 100 * p->choice[ip].kind + 2 * p->choice[ip].subtype + p->choice[ip].role;
    if (cost == p->dp[ip]) {
        if (rank > best_rank) return;
        if (rank == best_rank) {
            if (next < p->successor[ip]) return;      // then the longest span
            if (next == p->successor[ip] && key >= best_key) return; // then kind / subtype / role order
        }
    }
    p->dp[ip] = cost; p->successor[ip] = next; p->choice[ip] = *a;
}

static void offer(planner_t *p, const atom_t *a)      // a compact atom, if it renders exactly as the text at ipos
{
    uint8_t bits[JTTY_PAYLOAD_BITS]; atom_t back; bool eom; char r[JTTY_MSG_LEN + 1];
    if (!pack_atom(a, false, bits) || !unpack_atom(bits, &back, &eom) || !render_atom(&back, r)) return;
    int len = trimlen(r); if (len == 0) return;
    int last = p->ipos + len - 1;                     // its last column
    if (last > p->n - 1 || memcmp(p->msg + p->ipos, r, len) != 0) return;
    int next = p->n;
    if (last < p->n - 1) { if (p->msg[last + 1] != ' ') return; next = last + 2; } // structured frames supply the following space
    consider(p, a, next);
}

static void try_compact(planner_t *p)                 // every compact atom that could start at ipos
{
    char words[3][JTTY_MSG_LEN + 1]; int nwords = 0, first = p->ipos; // up to 3 words from here
    for (int j = 0; j < 3 && first < p->n; j++) {
        int last = first; while (last < p->n && p->msg[last] != ' ') last++; // to the next space
        int len = last - first; memcpy(words[j], p->msg + first, len); words[j][len] = 0;
        nwords = j + 1; first = last + 1;
    }
    for (int j = 0; j < nwords; j++) {                // call actions (CQ K1ABC CQ: the call is the 2nd word)
        if ((int)strlen(words[j]) > 13) continue;
        for (int action = CALL_CQ; action <= CALL_TU_NOW; action++) { atom_t a = call_atom(action, words[j]); offer(p, &a); }
    }
    for (int j = 0; j < 18; j++) { atom_t a = control_atom(j); offer(p, &a); } // control phrases
    for (int role = ROLE_FIELD_ONLY; role <= ROLE_FULL; role++) { // exchanges: a field, or 599 + field
        int field = 0;
        if (role == ROLE_FULL) { if (nwords < 2 || strcmp(words[0], "599") != 0) continue; field = 1; }
        const char *w = words[field]; int len = (int)strlen(w), value;
        bool numeric = decimal_value(w, len, &value);
        if (numeric) { atom_t a = num_atom(role, NUM_GENERIC, value); offer(p, &a); }
        if (numeric && role == ROLE_FULL && p->profile == JTTY_EXCHANGE_RTTY) { atom_t a = num_atom(role, NUM_SERIAL, value); offer(p, &a); }
        if (len == 4) { atom_t a = grid4_atom(role, w); offer(p, &a); }
        if (role != ROLE_FULL || len < 2 || len > 3) continue;
        bool letter = false; for (int i = 0; i < len; i++) if (is_letter(w[i])) letter = true;
        if (!letter) continue;
        { atom_t a = loc_atom(role, LOC_QTH, w); offer(p, &a); }
        if (p->profile == JTTY_EXCHANGE_RTTY) { atom_t a = loc_atom(role, LOC_STATE_PROVINCE, w); offer(p, &a); }
    }
    int len = nwords >= 1 ? (int)strlen(words[0]) : 0, value; // Field Day: "1D EMA"
    if (nwords < 2 || len < 2 || len > 3 || !decimal_value(words[0], len - 1, &value)) return;
    atom_t a = class_section_atom(value, words[0][len - 1], section_index(words[1])); offer(p, &a);
}

static bool normalize_serials(char *msg)              // RTTY Roundup: "599 05" -> "599 005" (rejected if it grows past 80)
{
    char result[2 * JTTY_MSG_LEN + 2] = ""; int length = (int)strlen(msg), first = 0;
    while (first < length) {
        int last = first; while (last < length && msg[last] != ' ') last++; // this token
        char rendered[JTTY_MSG_LEN + 1]; int tl = last - first; memcpy(rendered, msg + first, tl); rendered[tl] = 0;
        int next = last + 1;
        if (strcmp(rendered, "599") == 0 && next < length) {
            int lf = next; while (lf < length && msg[lf] != ' ') lf++; // the next token
            int value;
            if (decimal_value(msg + next, lf - next, &value)) { atom_t a = num_atom(ROLE_FULL, NUM_SERIAL, value); if (!render_atom(&a, rendered)) return false; next = lf + 1; }
        }
        if (result[0]) strcat(result, " ");
        strcat(result, rendered);
        if ((int)strlen(result) > JTTY_MSG_LEN) return false;
        first = next;
    }
    strcpy(msg, result); return true;
}

int jtty_pack_message(const char *message, int profile, uint8_t frames[][JTTY_PAYLOAD_BITS], char *normalized)
{
    char msg[2 * JTTY_MSG_LEN + 2];                   // normalized text
    normalize(message, msg);
    if (profile < JTTY_EXCHANGE_UNKNOWN || profile > JTTY_EXCHANGE_RTTY) return -1;
    if (profile == JTTY_EXCHANGE_RTTY && !normalize_serials(msg)) return -1;
    if (normalized) snprintf(normalized, JTTY_MSG_LEN + 1, "%s", msg);
    static planner_t p;                               // (static: large; one packing at a time - the JNI side locks)
    memset(&p, 0, sizeof p); p.msg = msg; p.n = (int)strlen(msg); p.profile = profile;
    if (p.n <= 0) return 0;                           // nothing to send
    for (int i = 0; i <= p.n; i++) { p.dp[i] = 999; p.choice[i] = atom0(); }
    p.dp[p.n] = 0;
    for (p.ipos = p.n - 1; p.ipos >= 0; p.ipos--) {   // fewest frames for each suffix, back to front
        int inext = p.ipos + 5 < p.n ? p.ipos + 5 : p.n;
        atom_t t5 = text5_atom(msg + p.ipos, inext - p.ipos); consider(&p, &t5, inext); // TEXT5 always works
        if (p.ipos > 0 && msg[p.ipos - 1] != ' ') continue; // compact atoms only at word starts
        try_compact(&p);
    }
    if (p.dp[0] > JTTY_MAX_FRAMES) return -1;         // too long
    int nframes = 0;
    for (int ipos = 0; ipos < p.n; ipos = p.successor[ipos]) { // the chosen atoms, in order
        bool last = p.successor[ipos] >= p.n;
        if (!pack_atom(&p.choice[ipos], last, frames[nframes])) return -1;
        nframes++;
        if (nframes > JTTY_MAX_FRAMES) return -1;
    }
    return nframes;
}

bool jtty_unpack_frame(const uint8_t bits[JTTY_PAYLOAD_BITS], char *text, bool *trailing_sep, bool *eom)
{
    atom_t a; char r[JTTY_MSG_LEN + 1];
    *trailing_sep = false; *eom = false; text[0] = 0;
    if (!unpack_atom(bits, &a, eom) || !render_atom(&a, r)) { *eom = false; return false; } // invalid frame
    if (a.kind == ATOM_TEXT5) {                       // five characters, spaces shown as '~'
        for (int i = 0; i < 5; i++) text[i] = a.text[i] == ' ' ? '~' : a.text[i];
        text[5] = 0;
    } else {                                          // a rendered atom, then a separator column
        snprintf(text, JTTY_MSG_LEN + 1, "%.*s", trimlen(r), r);
        *trailing_sep = true;
    }
    return true;
}
