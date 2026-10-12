// wfuhid.h — the input layer's copy as a UHID (virtual HID) device instead of uinput (dev build 13).
//
// Why: Android's descriptor for an input device without a serial is sha1(":vid:pid:" + a counter of the devices with
// the same ids at that moment). The copy must keep AYN's ids (2020:0111 / 0112 — AYN's mapping service takes any other
// gamepad), and AYN's pad and AYN's copies of external controllers share them: the copy's descriptor shifted whenever it
// was re-made with an external controller connected, and emulators that key their mappings on it (Eden…) lost the Thor.
// uinput can't give a device a serial; UHID can — with one, the descriptor is sha1(":vid:pid:uniqueId:<serial>"), the
// same forever (and on every Thor). Dev build 13.
//
// The kernel's generic HID driver turns a report descriptor into evdev codes (verified on the Thor, Linux 5.15):
//   Button page 1..16 inside a Physical "Gamepad" collection → BTN_GAMEPAD.. (0x130..0x13f)
//   Keyboard page 0x4A → KEY_HOME, 0x45 → KEY_F12 · Consumer 0x224 → KEY_BACK, 0x1A2 → KEY_APPSELECT,
//   0xE9 / 0xEA → KEY_VOLUMEUP / DOWN, 0x40 → KEY_MENU
//   Generic Desktop 0x30..0x35 → ABS_X, Y, Z, RX, RY, RZ · Simulation 0xC4 / 0xC5 → ABS_GAS / BRAKE,
//   0xBB → THROTTLE, 0xBA → RUDDER · Hat switch (0..7 + null) → ABS_HAT0X + ABS_HAT0Y
// The application collection is "Multi-axis Controller", not Gamepad: under Gamepad / Joystick the driver gives every
// axis fuzz (max-min)>>8 and flat (max-min)>>4 — a 12 % dead zone apps would read; under Multi-axis both are 0.
// AYN's pad also DECLARES BTN_DPAD_* (it never sends them — its D-pad is the hat): no HID usage gives those codes, so
// they're left out (Android classes the device the same: keyboard · gamepad · joystick). Any other code this file can't
// express → wfu_plan() refuses and wfpad keeps the uinput copy.
#ifndef WFUHID_H
#define WFUHID_H

#include <string.h>
#include <linux/input.h>

#define WFU_MAXK 64
#define WFU_MAXA 16
#define WFU_MAXDESC 600
#define WFU_MAXREP 128

typedef struct {
    int nk;                                       // keys, in report order
    unsigned short key[WFU_MAXK], kpage[WFU_MAXK], kusage[WFU_MAXK];
    int nbtn;                                     // the first nbtn keys are Button-page (inside the Gamepad collection)
    int na;                                       // axes (not the hat), in report order
    unsigned short axis[WFU_MAXA], apage[WFU_MAXA], ausage[WFU_MAXA];
    int amin[WFU_MAXA], amax[WFU_MAXA], abits[WFU_MAXA];
    int hat;                                      // ABS_HAT0X + ABS_HAT0Y as one hat switch
    int keybytes;                                 // key bits, padded to whole bytes
    int replen;                                   // bytes of one input report (no report id)
} wfu_layout;

#ifndef WFU_TEST_BIT
#define WFU_TEST_BIT(b, a) (((a)[(b) / (8 * sizeof(long))] >> ((b) % (8 * sizeof(long)))) & 1UL)
#endif

/** A key's HID usage: 0 if none. Buttons get page 0x09. */
static int wfu_key_usage(int code, unsigned short *page, unsigned short *usage) {
    if (code >= 0x130 && code <= 0x13f) { *page = 0x09; *usage = (unsigned short)(code - 0x130 + 1); return 1; }
    switch (code) {
    case KEY_HOME:       *page = 0x07; *usage = 0x4A; return 1;
    case KEY_F12:        *page = 0x07; *usage = 0x45; return 1;
    case KEY_BACK:       *page = 0x0C; *usage = 0x224; return 1;
    case KEY_APPSELECT:  *page = 0x0C; *usage = 0x1A2; return 1;
    case KEY_VOLUMEUP:   *page = 0x0C; *usage = 0xE9; return 1;
    case KEY_VOLUMEDOWN: *page = 0x0C; *usage = 0xEA; return 1;
    case KEY_MENU:       *page = 0x0C; *usage = 0x40; return 1;
    }
    return 0;
}
/** Declared by AYN's pad but never sent (its D-pad is the hat): fine to leave out. */
static int wfu_key_skippable(int code) { return code >= BTN_DPAD_UP && code <= BTN_DPAD_RIGHT; }

static int wfu_axis_usage(int code, unsigned short *page, unsigned short *usage) {
    switch (code) {
    case ABS_X: case ABS_Y: case ABS_Z: case ABS_RX: case ABS_RY: case ABS_RZ:
        *page = 0x01; *usage = (unsigned short)(0x30 + code); return 1;
    case ABS_GAS:      *page = 0x02; *usage = 0xC4; return 1;
    case ABS_BRAKE:    *page = 0x02; *usage = 0xC5; return 1;
    case ABS_THROTTLE: *page = 0x02; *usage = 0xBB; return 1;
    case ABS_RUDDER:   *page = 0x02; *usage = 0xBA; return 1;
    }
    return 0;
}

/** The copy's layout from the source's capabilities. 0 = fine; -1 = something it can't express ([why] says what). */
static int wfu_plan(wfu_layout *L, const unsigned long *evbits, const unsigned long *keybits, const unsigned long *absbits,
                    const struct input_absinfo *info, char *why, int whyn) {
    memset(L, 0, sizeof *L);
    why[0] = 0;
    for (int t = 0; t <= EV_MAX; t++) {
        if (!WFU_TEST_BIT(t, evbits)) continue;
        if (t == EV_SYN || t == EV_KEY || t == EV_ABS || t == EV_MSC || t == EV_REP) continue;
        snprintf(why, whyn, "event type %d", t); return -1;   // switches, LEDs, force feedback…: not expressed
    }
    // buttons first (inside the Gamepad collection), then the other keys by page
    for (int pass = 0; pass < 3; pass++) {
        for (int k = 0; k <= KEY_MAX; k++) {
            if (!WFU_TEST_BIT(k, keybits)) continue;
            unsigned short pg, us;
            if (!wfu_key_usage(k, &pg, &us)) {
                if (pass == 0 && !wfu_key_skippable(k)) { snprintf(why, whyn, "key 0x%x", k); return -1; }
                continue;
            }
            int want = pass == 0 ? 0x09 : pass == 1 ? 0x07 : 0x0C;
            if (pg != want) continue;
            if (L->nk >= WFU_MAXK) { snprintf(why, whyn, "too many keys"); return -1; }
            L->key[L->nk] = (unsigned short)k; L->kpage[L->nk] = pg; L->kusage[L->nk] = us; L->nk++;
            if (pass == 0) L->nbtn++;
        }
    }
    int hx = WFU_TEST_BIT(ABS_HAT0X, absbits), hy = WFU_TEST_BIT(ABS_HAT0Y, absbits);
    if (hx != hy) { snprintf(why, whyn, "half a hat"); return -1; }
    if (hx) {
        if (info[ABS_HAT0X].minimum != -1 || info[ABS_HAT0X].maximum != 1 || info[ABS_HAT0Y].minimum != -1 || info[ABS_HAT0Y].maximum != 1) {
            snprintf(why, whyn, "hat range"); return -1;
        }
        L->hat = 1;
    }
    for (int a = 0; a <= ABS_MAX; a++) {
        if (!WFU_TEST_BIT(a, absbits) || a == ABS_HAT0X || a == ABS_HAT0Y) continue;
        unsigned short pg, us;
        if (!wfu_axis_usage(a, &pg, &us)) { snprintf(why, whyn, "axis 0x%x", a); return -1; }
        if (L->na >= WFU_MAXA) { snprintf(why, whyn, "too many axes"); return -1; }
        int mn = info[a].minimum, mx = info[a].maximum;
        if (mn >= mx) { snprintf(why, whyn, "axis 0x%x range", a); return -1; }
        L->axis[L->na] = (unsigned short)a; L->apage[L->na] = pg; L->ausage[L->na] = us;
        L->amin[L->na] = mn; L->amax[L->na] = mx;
        L->abits[L->na] = (mn >= -32768 && mx <= 32767) ? 16 : 32;
        L->na++;
    }
    L->keybytes = (L->nk + 7) / 8;
    int bytes = L->keybytes;
    for (int i = 0; i < L->na; i++) bytes += L->abits[i] / 8;
    if (L->hat) bytes += 1;
    if (bytes > WFU_MAXREP) { snprintf(why, whyn, "report too long"); return -1; }
    L->replen = bytes;
    return 0;
}

// ---- report descriptor ----
static int wfu_put(unsigned char *d, int n, int cap, int b) { if (n < cap) d[n] = (unsigned char)b; return n + 1; }
/** A short item with a signed value in the fewest bytes. */
static int wfu_item_s(unsigned char *d, int n, int cap, int tag, int v) {
    if (v >= -128 && v <= 127) { n = wfu_put(d, n, cap, tag | 1); return wfu_put(d, n, cap, v & 0xFF); }
    if (v >= -32768 && v <= 32767) { n = wfu_put(d, n, cap, tag | 2); n = wfu_put(d, n, cap, v & 0xFF); return wfu_put(d, n, cap, (v >> 8) & 0xFF); }
    n = wfu_put(d, n, cap, tag | 3);
    for (int i = 0; i < 4; i++) n = wfu_put(d, n, cap, ((unsigned)v >> (8 * i)) & 0xFF);
    return n;
}
/** An unsigned item (usage, usage page) in the fewest bytes. */
static int wfu_item_u(unsigned char *d, int n, int cap, int tag, unsigned v) {
    if (v <= 0xFF) { n = wfu_put(d, n, cap, tag | 1); return wfu_put(d, n, cap, (int)v); }
    if (v <= 0xFFFF) { n = wfu_put(d, n, cap, tag | 2); n = wfu_put(d, n, cap, (int)(v & 0xFF)); return wfu_put(d, n, cap, (int)(v >> 8)); }
    n = wfu_put(d, n, cap, tag | 3);
    for (int i = 0; i < 4; i++) n = wfu_put(d, n, cap, (int)((v >> (8 * i)) & 0xFF));
    return n;
}
#define WFU_PAGE 0x04
#define WFU_USAGE 0x08
#define WFU_LMIN 0x14
#define WFU_LMAX 0x24
#define WFU_SIZE 0x74
#define WFU_COUNT 0x94
#define WFU_INPUT 0x80

/** The report descriptor for [L]. Returns its length (> cap = didn't fit). */
static int wfu_descriptor(const wfu_layout *L, unsigned char *d, int cap) {
    int n = 0;
    n = wfu_item_u(d, n, cap, WFU_PAGE, 0x01); n = wfu_item_u(d, n, cap, WFU_USAGE, 0x08);   // Multi-axis Controller
    n = wfu_put(d, n, cap, 0xA1); n = wfu_put(d, n, cap, 0x01);                             // Collection (Application)
    // keys: one 1-bit field per key, in order; buttons inside a Physical "Gamepad" collection
    for (int i = 0; i < L->nk; i++) {
        if (i == 0 && L->nbtn > 0) {
            n = wfu_item_u(d, n, cap, WFU_USAGE, 0x05);                                     // Gamepad
            n = wfu_put(d, n, cap, 0xA1); n = wfu_put(d, n, cap, 0x00);                     // Collection (Physical)
        }
        n = wfu_item_u(d, n, cap, WFU_PAGE, L->kpage[i]);
        n = wfu_item_u(d, n, cap, WFU_USAGE, L->kusage[i]);
        n = wfu_item_s(d, n, cap, WFU_LMIN, 0); n = wfu_item_s(d, n, cap, WFU_LMAX, 1);
        n = wfu_item_u(d, n, cap, WFU_SIZE, 1); n = wfu_item_u(d, n, cap, WFU_COUNT, 1);
        n = wfu_item_u(d, n, cap, WFU_INPUT, 0x02);                                         // Data, Var, Abs
        if (i == L->nbtn - 1) n = wfu_put(d, n, cap, 0xC0);                                 // End Collection (Physical)
    }
    if (L->nk % 8) {                                                                        // padding to a byte
        n = wfu_item_u(d, n, cap, WFU_SIZE, 1); n = wfu_item_u(d, n, cap, WFU_COUNT, (unsigned)(8 - L->nk % 8));
        n = wfu_item_u(d, n, cap, WFU_INPUT, 0x03);                                         // Constant
    }
    for (int i = 0; i < L->na; i++) {
        n = wfu_item_u(d, n, cap, WFU_PAGE, L->apage[i]);
        n = wfu_item_u(d, n, cap, WFU_USAGE, L->ausage[i]);
        n = wfu_item_s(d, n, cap, WFU_LMIN, L->amin[i]); n = wfu_item_s(d, n, cap, WFU_LMAX, L->amax[i]);
        n = wfu_item_u(d, n, cap, WFU_SIZE, (unsigned)L->abits[i]); n = wfu_item_u(d, n, cap, WFU_COUNT, 1);
        n = wfu_item_u(d, n, cap, WFU_INPUT, 0x02);
    }
    if (L->hat) {
        n = wfu_item_u(d, n, cap, WFU_PAGE, 0x01); n = wfu_item_u(d, n, cap, WFU_USAGE, 0x39);   // Hat switch
        n = wfu_item_s(d, n, cap, WFU_LMIN, 0); n = wfu_item_s(d, n, cap, WFU_LMAX, 7);
        n = wfu_put(d, n, cap, 0x35); n = wfu_put(d, n, cap, 0x00);                         // Physical Min 0
        n = wfu_put(d, n, cap, 0x46); n = wfu_put(d, n, cap, 0x3B); n = wfu_put(d, n, cap, 0x01);   // Physical Max 315
        n = wfu_put(d, n, cap, 0x65); n = wfu_put(d, n, cap, 0x14);                         // Unit: degrees
        n = wfu_item_u(d, n, cap, WFU_SIZE, 4); n = wfu_item_u(d, n, cap, WFU_COUNT, 1);
        n = wfu_item_u(d, n, cap, WFU_INPUT, 0x42);                                         // Data, Var, Abs, Null state
        n = wfu_put(d, n, cap, 0x65); n = wfu_put(d, n, cap, 0x00);                         // Unit: none
        n = wfu_item_u(d, n, cap, WFU_SIZE, 4); n = wfu_item_u(d, n, cap, WFU_COUNT, 1);
        n = wfu_item_u(d, n, cap, WFU_INPUT, 0x03);                                         // padding
    }
    n = wfu_put(d, n, cap, 0xC0);                                                           // End Collection
    return n;
}

/** The hat's value for a D-pad position (x, y each −1 / 0 / +1): 0 up, clockwise to 7 up-left; 8 = centre (null). */
static int wfu_hat(int x, int y) {
    x = x < 0 ? -1 : x > 0 ? 1 : 0; y = y < 0 ? -1 : y > 0 ? 1 : 0;
    static const int H[3][3] = { { 7, 0, 1 }, { 6, 8, 2 }, { 5, 4, 3 } };   // [y+1][x+1]
    return H[y + 1][x + 1];
}

/** One input report from the copy's state: key values by code ([nkv] of them), axis values by code. */
static int wfu_report(const wfu_layout *L, const unsigned char *keyv, int nkv, const int *absv, int nav, unsigned char *r) {
    memset(r, 0, (size_t)L->replen);
    for (int i = 0; i < L->nk; i++) {
        int c = L->key[i];
        if (c < nkv && keyv[c]) r[i / 8] |= (unsigned char)(1 << (i % 8));
    }
    int o = L->keybytes;
    for (int i = 0; i < L->na; i++) {
        int a = L->axis[i];
        int v = a < nav ? absv[a] : 0;
        if (v < L->amin[i]) v = L->amin[i];
        if (v > L->amax[i]) v = L->amax[i];
        for (int b = 0; b < L->abits[i] / 8; b++) r[o++] = (unsigned char)(((unsigned)v >> (8 * b)) & 0xFF);
    }
    if (L->hat) r[o++] = (unsigned char)wfu_hat(ABS_HAT0X < nav ? absv[ABS_HAT0X] : 0, ABS_HAT0Y < nav ? absv[ABS_HAT0Y] : 0);
    return o;
}

#endif
