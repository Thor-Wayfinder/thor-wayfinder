// wfhid_core — the logic of wfhid (fx/wfhid.c), external controllers.
// Header-only, no I/O of its own (output through two callbacks), so fx/wfhid_test.c tests it without a controller.
//
//   reports of 1 or 2 controllers (a Joy-Con pair) ──► FIELDS (a bit, a value, a hat — from the HID report
//   descriptor, or a built-in list for Nintendo's report 0x30) ──► the base mapping (what each field IS: a default
//   from what the fields are, or the user's own from the setup wizard; a single Joy-Con upright or sideways) ──►
//   a standard pad's state ──► the input layer's own engine (fx/wfmap.h: the game's profile — remaps, turbo,
//   toggles, stick shapes — and the face-button layout) ──► the pad games read (the Thor's layout, AYN's 2020:0111)
//
// Our own code from public protocol documentation (HID 1.11; the Switch controllers' reports and subcommands).
// Every length is checked: the reports come over the air from any Bluetooth device.
#ifndef WFHID_CORE_H
#define WFHID_CORE_H

#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "wfmap.h"

#define HX_MAXF 256
#define HX_MAXR 128           /* the longest report looked at */
#define HX_MAXSRC 2

enum { K_BTN = 'b', K_AXIS = 'a', K_HAT = 'h' };

typedef struct {
    int kind, src, rid, bit, bits;   /* src: which controller (0 / 1); rid: report id (0 = none); bit: offset AS READ */
    int32_t lmin, lmax;
    int page, usage, sgn;
} hx_field;

/* ---- targets: the controls of a standard pad ------------------------------------------------------- */
enum { T_S, T_E, T_W, T_N, T_L1, T_R1, T_L2, T_R2, T_L3, T_R3, T_ST, T_SE, T_HO, T_SL, T_SR,
       T_DU, T_DD, T_DL, T_DR, T_LX, T_LY, T_RX, T_RY, T_LT, T_RT, T_BK, T_AY, NT };
static const char *const HX_TNAME[NT] = { "S", "E", "W", "N", "L1", "R1", "L2", "R2", "L3", "R3", "ST", "SE", "HO", "SL", "SR",
                                          "DU", "DD", "DL", "DR", "LX", "LY", "RX", "RY", "LT", "RT", "BK", "AY" };
/* the PRINTED button (fx/wfmap.h: Nintendo positions — A right, B bottom, X top, Y left) each position stands for;
   the engine applies the face layout (Thor's, or the game's own) on the way out. SL / SR: C / Z, which AYN's key
   layout declares. Home: MODE (not the Thor's Home key: an external Home never gates).
   dev build 17 (theme G): BK its Back (the Thor's: gates, reaches Android), AY its AYN button (a line to the app, "A") */
static const int HX_PRINTED[NT] = { WF_B, WF_A, WF_Y, WF_X, 0x136, 0x137, WF_L2, WF_R2, 0x13d, 0x13e, 0x13b, 0x13a, 0x13c,
                                    0x132, 0x135, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, WF_BACK, 0 };

/** The printed button a target stands for now (Home: the Thor's Home, or the game's MODE). */
static int hx_printed(int t);

/* Nintendo report 0x30 usages (page 0xFF00, ours): 0x01 Y 0x02 X 0x03 B 0x04 A 0x05 SR(R) 0x06 SL(R) 0x07 R 0x08 ZR ·
   0x11 − 0x12 + 0x13 R-stick 0x14 L-stick 0x15 Home 0x16 Capture · 0x21 down 0x22 up 0x23 right 0x24 left
   0x25 SR(L) 0x26 SL(L) 0x27 L 0x28 ZL · 0x30..0x33 sticks (left X, Y, right X, Y — Y up = larger) */
#define NP 0xFF00

/* dev build 18f: a PlayStation pad's touchpad moving Android's pointer ([hx_touch]) — one touch's bookkeeping */
typedef struct {
    int n, max_n;                 /* fingers in the last sample · the most during this touch */
    int lx, ly, lid;              /* one finger: its last point (the motion's start) and its contact id */
    int id2[2];                   /* two fingers: their contact ids */
    int sx, sy, travel, moving;   /* where the touch began · the farthest from there · past the slop */
    int cx, cy, c0x, c0y, scrolling;   /* two fingers: the centroid last · where they began · past their slop */
    int clicked;                  /* the pad was pressed during this touch: no motion, no tap */
    long down_ms, last_ms, emit_ms, wemit_ms, btn_up_at;
    float ax, ay, wv, wh;         /* remainders: pixels, wheel notches */
    int pend_x, pend_y, pend_v, pend_h;   /* whole pixels / notches held back by the rate limit */
    int btn;                      /* a tap's click held ("H b 1") */
} hx_tp_t;

typedef struct {
    hx_field F[HX_MAXF]; int nf;
    int nsrc;
    /* sony: 1 DualShock 4, 2 DualSense (built-in report layouts); rumble: how it rumbles (HX_RUMBLE_*); bt: Bluetooth;
       bus: as hidraw says it (5 Bluetooth, 3 USB) */
    struct { int nintendo, sony, rumble, bt, pid, ids_used; uint16_t vid; int fstart, fend, bus; } S[HX_MAXSRC];
    int32_t val[HX_MAXF]; unsigned char have[HX_MAXF];
    int map_f[NT], map_inv[NT];
    int user_f[2][NT], user_inv[2][NT], has_user[2];   /* the user's own mappings (the wizard): [0] upright, [1] sideways */
    int home_wf;            /* its Home is the Thor's Home (Wayfinder's combos, Home + X…) — else the game's (MODE) */
    int home_next;          /* "h" while Home was held: the owner it takes at Home's release (1 / 0), -1 none */
    int sh_prev;           /* a single Joy-Con's main shoulder (L / R) — "G sh" for the L + R pairing gesture */
    int app_last[2][2]; long app_at[2];   /* a stick given to Wayfinder: last position sent ("T"), when */
    /* stick calibration: centre (the first reading, or after "c") + the farthest seen each way */
    int32_t cen[HX_MAXF], lo[HX_MAXF], hi[HX_MAXF]; unsigned char have_cen[HX_MAXF]; int recentre;
    /* learn mode (the setup wizard): every change, the pad drives nothing; [learn_at] the last change heard (it ends by
       itself a minute after) */
    int learn; int32_t learn_val[HX_MAXF], learn_prev[HX_MAXF]; unsigned char learn_away[HX_MAXF], learn_have[HX_MAXF];
    long learn_at;
    /* dev build 16 (theme E): holds while learning — a button down since [learn_down] (0 = up), its hold said already,
       heard before in this session ([learn_heard]; [learn_again]: this press is such a one) */
    long learn_down[HX_MAXF]; unsigned char learn_said[HX_MAXF], learn_again[HX_MAXF], learn_heard[HX_MAXF];
    int face_x;             /* the Thor's face buttons are Xbox's (1) or Nintendo's (0) */
    int sideways;           /* a single Joy-Con held sideways */
    /* gestures: SL + SR held (a Joy-Con → sideways) */
    long now_ms, slsr_since; int slsr_sent;
    int quiet;              /* after a change of meaning (sideways, learn mode over): nothing reaches the game until all
                               is let go */
    long sub_since; int sub_on;   /* a controller with no Home button: Select + Start held = its Home (combos) */
    /* the last report (which controller, its bytes): played again by [hx_tick] for a pad that reports only on change */
    int last_src, last_len; uint8_t last_rep[HX_MAXR];
    int prof_bad;           /* the last profile was refused ("P bad"): the one in use, sent again, is answered */
    /* motion (Nintendo report 0x30, once the IMU is on): the last sample */
    int imu_seen, imu_on; int16_t acc[3], gyr[3];
    /* aim with motion (the engine's gyro_stick): sensitivity 1..50, flags 1 invert x · 2 invert y · 4 swap;
       the gyro's resting offset, learnt while the controller is still */
    int gyro_sens, gyro_flags; int32_t gbias[3]; int gbias_n;
    /* battery 0..4 per controller (Nintendo report byte 2: high nibble 0 2 4 6 8, +1 charging), -1 unknown */
    int battery[HX_MAXSRC];
    /* dev build 15 (C4): the controller went away and its pad is kept, at rest, until it comes back ([hx_detach]) */
    int idle;
    /* dev build 15 (C5): our pad taken down for a moment ("n 0"): the engine runs on, nothing is written */
    int pad_down;
    int last_lights;        /* the player lights asked last ("L"), -1 none: given again when the controller is back */
    /* the engine */
    wf_state ws; wf_map wm; wf_out out; int have_out;
    int src_key[NT]; int src_abs[WF_NA];
    /* dev build 17 (theme G): its AYN button held (said "A 1"); a Back Wayfinder hands to the game ("p 158 1") — out at
       once, its Home held or not; the targets the user's mapping names, "-1" too ([hx_apply_map] fills the others it
       added since); the controls said as "V" lines ("w") and what each was last said at (a control said pressed is
       always said let go); Home + Back held since (-1: done for this hold) / its controls off (5 s) / the motion aim to
       give back */
    int ayn, vback;
    unsigned char user_named[2][NT];
    unsigned char watch_key[WF_NK], watch_abs[WF_NA], vsaid_key[WF_NK]; int vsaid_abs[WF_NA];
    long hb_since; int muted, gyro_want;
    /* dev build 18f: the touchpad moves the pointer ("t 1"; default off: a reader moves nothing until the helper says so)
       and its touch now */
    int tp_on; hx_tp_t tp;
    /* b18o: the buttons its pad pulses for a hand that holds a turbo button, said to Wayfinder ("TU", fx/wfmap.h
       [wf_turbo_held]) — never cleared but by the diff (what no longer counts is said let go) */
    unsigned char tsaid[WF_NK];
} hx_t;

static hx_t HX;

/* output: one evdev event for our pad; one line for the helper */
static void (*hx_emit)(int type, int code, int value);
static void (*hx_say)(const char *line);

static void hx_sayf(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
#include <stdarg.h>
static void hx_sayf(const char *fmt, ...) {
    char b[512]; va_list a; va_start(a, fmt); int n = vsnprintf(b, sizeof b, fmt, a); va_end(a);
    if (n < 0) return;
    for (char *c = b; *c; c++) if (*c == '\n' || *c == '\r') *c = ' ';
    if (hx_say) hx_say(b);
}

/* ---- fields -------------------------------------------------------------------------------------------- */
static void hx_add(int kind, int src, int rid, int bit, int bits, int32_t lmin, int32_t lmax, int page, int usage) {
    if (HX.nf >= HX_MAXF || bits <= 0 || bits > 32 || bit < 0) return;
    if ((bit + bits + 7) / 8 > HX_MAXR) return;
    hx_field *f = &HX.F[HX.nf++];
    f->kind = kind; f->src = src; f->rid = rid; f->bit = bit; f->bits = bits; f->lmin = lmin; f->lmax = lmax;
    f->page = page; f->usage = usage; f->sgn = lmin < 0;
}

static int32_t hx_sext(uint32_t v, int sz) {
    if (sz == 1) return (int8_t)v;
    if (sz == 2) return (int16_t)v;
    return (int32_t)v;
}

/** A HID report descriptor (HID 1.11 §6.2.2) → the Input fields of controller [src]. Arrays and padding are skipped
 *  (game controllers declare their controls as variables). */
static void hx_parse_desc(int src, const uint8_t *d, int len) {
    int page = 0, rid = 0, size = 0, count = 0; int32_t lmin = 0, lmax = 0;
    int usages[64], nu = 0, umin = -1, umax = -1;
    int offs[256]; memset(offs, 0, sizeof offs);
    struct { int page, size, count, rid; int32_t lmin, lmax; } stack[4]; int sp = 0;
    int ids = 0;
    for (int i = 0; i < len;) {
        uint8_t b = d[i];
        if (b == 0xFE) { if (i + 2 >= len) break; i += 3 + d[i + 1]; continue; }   /* long item */
        int sz = (b & 3) == 3 ? 4 : (b & 3);
        if (i + 1 + sz > len) break;
        uint32_t v = 0; for (int k = 0; k < sz; k++) v |= (uint32_t)d[i + 1 + k] << (8 * k);
        int tag = b & 0xFC; i += 1 + sz;
        switch (tag) {
        case 0x04: page = (int)v; break;
        case 0x14: lmin = hx_sext(v, sz); break;
        /* a logical maximum read as negative while the minimum isn't: the device meant it unsigned (0..255) */
        case 0x24: lmax = (sz < 4 && hx_sext(v, sz) < 0 && lmin >= 0) ? (int32_t)v : hx_sext(v, sz); break;
        /* 4-byte items: a "negative" size from a hostile descriptor drove the offsets below 0 (review 2026-10-03) */
        case 0x74: size = v > 255 ? 0 : (int)v; break;
        case 0x84: rid = (int)(v & 0xFF); ids = 1; break;
        case 0x94: count = (int)v; break;
        case 0xA4: if (sp < 4) { stack[sp].page = page; stack[sp].size = size; stack[sp].count = count; stack[sp].rid = rid;
                                 stack[sp].lmin = lmin; stack[sp].lmax = lmax; sp++; } break;
        case 0xB4: if (sp > 0) { sp--; page = stack[sp].page; size = stack[sp].size; count = stack[sp].count; rid = stack[sp].rid;
                                 lmin = stack[sp].lmin; lmax = stack[sp].lmax; } break;
        case 0x08: if (nu < 64) usages[nu++] = sz == 4 ? (int)(v & 0xFFFF) : (int)v; break;
        case 0x18: umin = (int)v; break;
        case 0x28: umax = (int)v; break;
        case 0x80: {
            int constant = v & 1, variable = (v >> 1) & 1;
            for (int k = 0; k < count && count <= 4096; k++) {
                int bit = (ids ? 8 : 0) + offs[rid & 0xFF];
                if (offs[rid & 0xFF] < (1 << 20)) offs[rid & 0xFF] += size;   /* no overflow; such fields fail hx_add */
                if (constant || !variable) continue;
                int u = k < nu ? usages[k] : (umin >= 0 ? umin + k : (nu ? usages[nu - 1] : 0));
                if (umin >= 0 && umax >= 0 && umin + k > umax) u = umax;
                int kind = size == 1 ? K_BTN : (page == 1 && u == 0x39) ? K_HAT : K_AXIS;
                hx_add(kind, src, ids ? rid : 0, bit, size, lmin, lmax, page, u);
            }
            nu = 0; umin = umax = -1; break;
        }
        case 0x90: case 0xB0: case 0xA0: case 0xC0: nu = 0; umin = umax = -1; break;
        default: break;
        }
    }
    HX.S[src].ids_used = ids;
}

/** Nintendo's full report 0x30 (Joy-Con, Pro Controller, 8BitDo in Switch mode): the descriptor doesn't describe it. */
static void hx_nintendo_fields(int src) {
    static const int ub[24] = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                                0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0, 0,
                                0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28 };
    for (int i = 0; i < 24; i++) if (ub[i]) hx_add(K_BTN, src, 0x30, (3 + i / 8) * 8 + i % 8, 1, 0, 1, NP, ub[i]);
    hx_add(K_AXIS, src, 0x30, 6 * 8, 12, 0, 4095, NP, 0x30);
    hx_add(K_AXIS, src, 0x30, 7 * 8 + 4, 12, 0, 4095, NP, 0x31);
    hx_add(K_AXIS, src, 0x30, 9 * 8, 12, 0, 4095, NP, 0x32);
    hx_add(K_AXIS, src, 0x30, 10 * 8 + 4, 12, 0, 4095, NP, 0x33);
}

static int hx_is_nintendo(uint16_t vid, uint16_t pid) {
    return vid == 0x057e && (pid == 0x2006 || pid == 0x2007 || pid == 0x2009);
}

/* PlayStation pads: their full reports (what their kernel drivers switch them to over Bluetooth) aren't described by
   the descriptor — built-in layouts, our page 0xFF01: 1 □ 2 ✕ 3 ○ 4 △ 5 L1 6 R1 7 L2 8 R2 9 Share/Create 10 Options
   11 L3 12 R3 13 PS 14 touchpad click 15 mute · 0x30..0x33 sticks (8-bit, down = larger) · 0x34 / 0x35 L2 / R2 pull
   · 0x39 hat (8 = none). DualShock 4: report 0x01 (USB), 0x11 (Bluetooth, 2 bytes further). DualSense: 0x01 (USB),
   0x31 (Bluetooth, 1 byte further). */
#define SP 0xFF01
static int hx_sony_kind(uint16_t vid, uint16_t pid) {
    if (vid != 0x054c) return 0;
    if (pid == 0x05c4 || pid == 0x09cc || pid == 0x0ba0) return 1;
    if (pid == 0x0ce6 || pid == 0x0df2) return 2;
    return 0;
}
static void hx_sony_fields(int src, int kind, int bt) {
    int rid = kind == 1 ? (bt ? 0x11 : 0x01) : (bt ? 0x31 : 0x01);
    int o = kind == 1 ? (bt ? 2 : 0) : (bt ? 1 : 0);
    for (int a = 0; a < 4; a++) hx_add(K_AXIS, src, rid, (1 + o + a) * 8, 8, 0, 255, SP, 0x30 + a);
    int hat = kind == 1 ? 5 : 8, b2 = hat + 1, b3 = hat + 2;
    hx_add(K_HAT, src, rid, (hat + o) * 8, 4, 0, 7, SP, 0x39);
    for (int k = 0; k < 4; k++) hx_add(K_BTN, src, rid, (hat + o) * 8 + 4 + k, 1, 0, 1, SP, 1 + k);
    for (int k = 0; k < 8; k++) hx_add(K_BTN, src, rid, (b2 + o) * 8 + k, 1, 0, 1, SP, 5 + k);
    for (int k = 0; k < (kind == 2 ? 3 : 2); k++) hx_add(K_BTN, src, rid, (b3 + o) * 8 + k, 1, 0, 1, SP, 13 + k);
    int pull = kind == 1 ? 8 : 5;
    hx_add(K_AXIS, src, rid, (pull + o) * 8, 8, 0, 255, SP, 0x34);
    hx_add(K_AXIS, src, rid, (pull + 1 + o) * 8, 8, 0, 255, SP, 0x35);
}

/* how a controller rumbles (from its documented output reports) */
enum { HX_RUMBLE_NONE, HX_RUMBLE_NINTENDO, HX_RUMBLE_DS4, HX_RUMBLE_DUALSENSE, HX_RUMBLE_XBOX };
static int hx_is_xbox_bt(uint16_t vid, uint16_t pid) {
    return vid == 0x045e && (pid == 0x02e0 || pid == 0x02fd || pid == 0x0b05 || pid == 0x0b13 || pid == 0x0b20 || pid == 0x0b22);
}

/** Add controller [src] (0, then 1 for a Joy-Con pair); [bus] 5 Bluetooth, 3 USB. */
static void hx_add_source_bus(int src, int bus, uint16_t vid, uint16_t pid, const uint8_t *desc, int dlen) {
    if (src < 0 || src >= HX_MAXSRC) return;
    HX.S[src].vid = vid; HX.S[src].pid = pid; HX.S[src].fstart = HX.nf; HX.S[src].bt = bus == 5; HX.S[src].bus = bus;
    int sony = hx_sony_kind(vid, pid);
    if (hx_is_nintendo(vid, pid)) { HX.S[src].nintendo = 1; hx_nintendo_fields(src); HX.S[src].rumble = HX_RUMBLE_NINTENDO; }
    else if (sony) {
        HX.S[src].sony = sony; HX.S[src].ids_used = 1; hx_sony_fields(src, sony, bus == 5);
        HX.S[src].rumble = sony == 1 ? HX_RUMBLE_DS4 : HX_RUMBLE_DUALSENSE;
    } else {
        hx_parse_desc(src, desc, dlen);
        if (bus == 5 && hx_is_xbox_bt(vid, pid)) HX.S[src].rumble = HX_RUMBLE_XBOX;
    }
    HX.S[src].fend = HX.nf;
    if (src + 1 > HX.nsrc) HX.nsrc = src + 1;
}
__attribute__((unused)) static void hx_add_source(int src, uint16_t vid, uint16_t pid, const uint8_t *desc, int dlen) {
    hx_add_source_bus(src, 5, vid, pid, desc, dlen);
}

/** The lines that announce controller [src] and its fields ("I", "F"): at start, and when it is back (dev build 15). */
static void hx_print_source(int src) {
    if (src < 0 || src >= HX.nsrc) return;
    hx_sayf("I %d %04x %04x %d %s %d", HX.S[src].bus, HX.S[src].vid, (uint16_t)HX.S[src].pid, HX.S[src].fend - HX.S[src].fstart,
            HX.S[src].nintendo ? "nintendo" : HX.S[src].sony ? "sony" : "hid", src);
    for (int i = HX.S[src].fstart; i < HX.S[src].fend; i++)
        hx_sayf("F %d %c %d %d %d %x %x %d", i, HX.F[i].kind, HX.F[i].bits, HX.F[i].lmin, HX.F[i].lmax, HX.F[i].page, HX.F[i].usage, src);
}

/** Field [usage] of [page] on controller [src] (-1 = any controller). */
static int hx_find(int src, int page, int usage) {
    for (int i = 0; i < HX.nf; i++)
        if ((src < 0 || HX.F[i].src == src) && HX.F[i].page == page && HX.F[i].usage == usage) return i;
    return -1;
}

/* ---- the base mapping ----------------------------------------------------------------------------------- */
static void hx_set(int t, int f, int inv) { if (t >= 0 && t < NT && f >= -1 && f < HX.nf) { HX.map_f[t] = f; HX.map_inv[t] = inv; } }

/** One Nintendo controller [src] (pid 2006 left Joy-Con, 2007 right, 2009 Pro), upright: its own half / everything. */
static void hx_map_nintendo_upright(int src, int pid) {
    if (pid != 0x2006) {                         /* right side: by POSITION — Nintendo's B is at the bottom */
        hx_set(T_S, hx_find(src, NP, 0x03), 0); hx_set(T_E, hx_find(src, NP, 0x04), 0);
        hx_set(T_W, hx_find(src, NP, 0x01), 0); hx_set(T_N, hx_find(src, NP, 0x02), 0);
        hx_set(T_R1, hx_find(src, NP, 0x07), 0); hx_set(T_R2, hx_find(src, NP, 0x08), 0);
        hx_set(T_ST, hx_find(src, NP, 0x12), 0); hx_set(T_HO, hx_find(src, NP, 0x15), 0); hx_set(T_R3, hx_find(src, NP, 0x13), 0);
        hx_set(T_RX, hx_find(src, NP, 0x32), 0); hx_set(T_RY, hx_find(src, NP, 0x33), 1);
    }
    if (pid != 0x2007) {                         /* left side */
        hx_set(T_DU, hx_find(src, NP, 0x22), 0); hx_set(T_DD, hx_find(src, NP, 0x21), 0);
        hx_set(T_DL, hx_find(src, NP, 0x24), 0); hx_set(T_DR, hx_find(src, NP, 0x23), 0);
        hx_set(T_L1, hx_find(src, NP, 0x27), 0); hx_set(T_L2, hx_find(src, NP, 0x28), 0);
        hx_set(T_SE, hx_find(src, NP, 0x11), 0); hx_set(T_L3, hx_find(src, NP, 0x14), 0);
        hx_set(T_LX, hx_find(src, NP, 0x30), 0); hx_set(T_LY, hx_find(src, NP, 0x31), 1);
    }
    /* a Joy-Con's rail */
    if (pid == 0x2007) { hx_set(T_SL, hx_find(src, NP, 0x06), 0); hx_set(T_SR, hx_find(src, NP, 0x05), 0); }
    if (pid == 0x2006) { hx_set(T_SL, hx_find(src, NP, 0x26), 0); hx_set(T_SR, hx_find(src, NP, 0x25), 0); }
}

/** One Joy-Con held SIDEWAYS (the rail up, as on a Switch): a small pad of its own — its stick is the left stick,
 *  its four buttons the face buttons (turned a quarter), SL / SR the shoulders.
 *  Right Joy-Con: turned a quarter clockwise — X → right, A → bottom, B → left, Y → top; stick right = its up.
 *  Left Joy-Con: a quarter anticlockwise — left (◀) → bottom, down (▼) → right, right (▶) → top, up (▲) → left. */
static void hx_map_sideways(int src, int pid) {
    if (pid == 0x2007) {
        hx_set(T_S, hx_find(src, NP, 0x04), 0); hx_set(T_E, hx_find(src, NP, 0x02), 0);
        hx_set(T_W, hx_find(src, NP, 0x03), 0); hx_set(T_N, hx_find(src, NP, 0x01), 0);
        hx_set(T_L1, hx_find(src, NP, 0x06), 0); hx_set(T_R1, hx_find(src, NP, 0x05), 0);
        hx_set(T_ST, hx_find(src, NP, 0x12), 0); hx_set(T_HO, hx_find(src, NP, 0x15), 0); hx_set(T_L3, hx_find(src, NP, 0x13), 0);
        hx_set(T_LX, hx_find(src, NP, 0x33), 0);  /* its up (larger) = right */
        hx_set(T_LY, hx_find(src, NP, 0x32), 0);  /* its right (larger) = down */
    } else if (pid == 0x2006) {
        hx_set(T_S, hx_find(src, NP, 0x24), 0); hx_set(T_E, hx_find(src, NP, 0x21), 0);
        hx_set(T_W, hx_find(src, NP, 0x22), 0); hx_set(T_N, hx_find(src, NP, 0x23), 0);
        hx_set(T_L1, hx_find(src, NP, 0x26), 0); hx_set(T_R1, hx_find(src, NP, 0x25), 0);
        hx_set(T_ST, hx_find(src, NP, 0x11), 0); hx_set(T_HO, hx_find(src, NP, 0x16), 0); hx_set(T_L3, hx_find(src, NP, 0x14), 0);
        hx_set(T_LX, hx_find(src, NP, 0x31), 1);  /* its up (larger) = left */
        hx_set(T_LY, hx_find(src, NP, 0x30), 1);  /* its right (larger) = up */
    }
}

/** The default mapping, from what the fields ARE. */
static void hx_default_map(void) {
    for (int t = 0; t < NT; t++) { HX.map_f[t] = -1; HX.map_inv[t] = 0; }
    if (HX.nsrc == 0) return;
    if (HX.S[0].nintendo) {
        if (HX.nsrc == 2) {                       /* a Joy-Con pair = one controller, as on a Switch */
            for (int s = 0; s < 2; s++) hx_map_nintendo_upright(s, HX.S[s].pid);
            HX.map_f[T_SL] = HX.map_f[T_SR] = -1;  /* the rails face each other: nothing */
            /* the pair's Home is the right one's; dev build 17 (D4): the pair's Capture (the left one's) = its Back */
            for (int s = 0; s < 2; s++) if (HX.S[s].pid == 0x2006) hx_set(T_BK, hx_find(s, NP, 0x16), 0);
            return;
        }
        if (HX.sideways && (HX.S[0].pid == 0x2006 || HX.S[0].pid == 0x2007)) hx_map_sideways(0, HX.S[0].pid);
        else {
            hx_map_nintendo_upright(0, HX.S[0].pid);
            /* dev build 17 (D4): a lone left Joy-Con upright has no Home — its Capture is; the Pro's (8BitDo in Switch
               mode) = its Back */
            if (HX.S[0].pid == 0x2006) hx_set(T_HO, hx_find(0, NP, 0x16), 0);
            else if (HX.S[0].pid == 0x2009) hx_set(T_BK, hx_find(0, NP, 0x16), 0);
        }
        return;
    }
    if (HX.S[0].sony) {                          /* by position: ✕ bottom, ○ right, □ left, △ top */
        /* dev build 17 (D4): the touchpad click = its Back, Mute = its AYN button (a DualShock 4 has no Mute: none) */
        static const int t[15] = { T_W, T_S, T_E, T_N, T_L1, T_R1, T_L2, T_R2, T_SE, T_ST, T_L3, T_R3, T_HO, T_BK, T_AY };
        for (int k = 0; k < 15; k++) if (t[k] >= 0) hx_set(t[k], hx_find(0, SP, 1 + k), 0);
        hx_set(T_LX, hx_find(0, SP, 0x30), 0); hx_set(T_LY, hx_find(0, SP, 0x31), 0);
        hx_set(T_RX, hx_find(0, SP, 0x32), 0); hx_set(T_RY, hx_find(0, SP, 0x33), 0);
        hx_set(T_LT, hx_find(0, SP, 0x34), 0); hx_set(T_RT, hx_find(0, SP, 0x35), 0);
        int hat = hx_find(0, SP, 0x39);
        hx_set(T_DU, hat, 0); hx_set(T_DD, hat, 0); hx_set(T_DL, hat, 0); hx_set(T_DR, hat, 0);
        return;
    }
    /* Generic Desktop: X / Y left stick; Z / Rz right stick (Rx / Ry then the triggers), else Rx / Ry right stick */
    hx_set(T_LX, hx_find(0, 1, 0x30), 0); hx_set(T_LY, hx_find(0, 1, 0x31), 0);
    int z = hx_find(0, 1, 0x32), rz = hx_find(0, 1, 0x35), rx = hx_find(0, 1, 0x33), ry = hx_find(0, 1, 0x34);
    /* an Xbox pad whose triggers aren't on the Simulation page (older One firmware over Bluetooth, the "DirectInput"
       layout — SDL's controller database): right stick Rx / Ry, triggers Z / Rz, buttons A B X Y LB RB View Menu LS RS */
    int xbox_old = HX.S[0].vid == 0x045e && hx_find(0, 2, 0xC4) < 0 && hx_find(0, 2, 0xC5) < 0 && rx >= 0 && ry >= 0;
    if (xbox_old) { hx_set(T_RX, rx, 0); hx_set(T_RY, ry, 0); hx_set(T_LT, z, 0); hx_set(T_RT, rz, 0); }
    else if (z >= 0 && rz >= 0) { hx_set(T_RX, z, 0); hx_set(T_RY, rz, 0); hx_set(T_LT, rx, 0); hx_set(T_RT, ry, 0); }
    else { hx_set(T_RX, rx, 0); hx_set(T_RY, ry, 0); }
    int hat = hx_find(0, 1, 0x39);
    if (hat >= 0) { hx_set(T_DU, hat, 0); hx_set(T_DD, hat, 0); hx_set(T_DL, hat, 0); hx_set(T_DR, hat, 0); }
    else { hx_set(T_DU, hx_find(0, 1, 0x90), 0); hx_set(T_DD, hx_find(0, 1, 0x91), 0);
           hx_set(T_DR, hx_find(0, 1, 0x92), 0); hx_set(T_DL, hx_find(0, 1, 0x93), 0); }
    if (hx_find(0, 2, 0xC5) >= 0) hx_set(T_LT, hx_find(0, 2, 0xC5), 0);   /* Simulation: Brake / Accelerator */
    if (hx_find(0, 2, 0xC4) >= 0) hx_set(T_RT, hx_find(0, 2, 0xC4), 0);
    /* Buttons 1.. in the order the maker uses (by vendor), else the most common one; the wizard fixes the rest */
    static const int common[] = { T_S, T_E, T_W, T_N, T_L1, T_R1, T_L2, T_R2, T_SE, T_ST, T_L3, T_R3, T_HO };
    /* □ ✕ ○ △ … (dev build 17, D4: button 14 — the touchpad click on Sony's own descriptors — its Back) */
    static const int sony[] = { T_W, T_S, T_E, T_N, T_L1, T_R1, T_L2, T_R2, T_SE, T_ST, T_L3, T_R3, T_HO, T_BK };
    /* Xbox over Bluetooth (checked against SDL's controller database, Linux entries for 045e:0b13 / 0b20 / 0b22 / 02e0):
       newer firmware A B _ X Y _ LB RB _ _ View Menu Xbox LS RS; the older layout A B X Y LB RB View Menu LS RS Xbox */
    static const int xbox[] = { T_S, T_E, -1, T_W, T_N, -1, T_L1, T_R1, -1, -1, T_SE, T_ST, T_HO, T_L3, T_R3 };
    static const int xbox1[] = { T_S, T_E, T_W, T_N, T_L1, T_R1, T_SE, T_ST, T_L3, T_R3, T_HO };
    const int *order = common; int no = 13;
    if (HX.S[0].vid == 0x054c) { order = sony; no = 14; }
    else if (xbox_old) { order = xbox1; no = 11; }
    else if (HX.S[0].vid == 0x045e) { order = xbox; no = 15; }
    for (int k = 0; k < no; k++) {
        int f = hx_find(0, 9, k + 1);
        if (f < 0 || order[k] < 0) continue;
        if (HX.map_f[order[k]] < 0) hx_set(order[k], f, 0);
        /* an analog trigger declared as a button (a pressure value): the trigger axis too */
        if (HX.F[f].kind == K_AXIS && order[k] == T_L2 && HX.map_f[T_LT] < 0) hx_set(T_LT, f, 0);
        if (HX.F[f].kind == K_AXIS && order[k] == T_R2 && HX.map_f[T_RT] < 0) hx_set(T_RT, f, 0);
    }
    /* Consumer page: Home, Menu / Properties = Start, Back / Bookmarks = Select */
    int c, b9 = hx_find(0, 9, 9), b10 = hx_find(0, 9, 10);
    int cons = 0;
    if ((c = hx_find(0, 0x0C, 0x223)) >= 0) hx_set(T_HO, c, 0);
    if ((c = hx_find(0, 0x0C, 0x40)) >= 0 || (c = hx_find(0, 0x0C, 0x209)) >= 0) { hx_set(T_ST, c, 0); cons = 1; }
    if ((c = hx_find(0, 0x0C, 0x224)) >= 0 || (c = hx_find(0, 0x0C, 0x204)) >= 0) { hx_set(T_SE, c, 0); cons = 1; }
    /* dev build 17 (D4): a Record button (an Xbox Series pad's Share) = its Back */
    if ((c = hx_find(0, 0x0C, 0xB2)) >= 0 && HX.map_f[T_BK] < 0) hx_set(T_BK, c, 0);
    /* Start / Select came from the consumer page: buttons 9 / 10 are then the stick clicks (Apple-style pads) */
    if (cons && HX.S[0].vid != 0x054c && HX.S[0].vid != 0x045e) {
        if (b9 >= 0 && HX.map_f[T_L3] < 0) hx_set(T_L3, b9, 0);
        if (b10 >= 0 && HX.map_f[T_R3] < 0) hx_set(T_R3, b10, 0);
    }
}

/** The mapping in use: the user's own for how it's held (upright / a Joy-Con sideways), else the default. dev build 17
 *  (theme G): a user's own that doesn't name a target new in build 17 — its Back, its AYN, a lone left Joy-Con's
 *  Capture-as-Home — takes the default's, when the user's mapping doesn't use that field already (every button the user
 *  set keeps its job; a "-1" stays none). */
static void hx_apply_map(void) {
    int o = HX.sideways != 0;
    hx_default_map();
    if (!HX.has_user[o]) return;
    int def_f[NT], def_i[NT]; memcpy(def_f, HX.map_f, sizeof def_f); memcpy(def_i, HX.map_inv, sizeof def_i);
    memcpy(HX.map_f, HX.user_f[o], sizeof HX.map_f); memcpy(HX.map_inv, HX.user_inv[o], sizeof HX.map_inv);
    for (int t = 0; t < NT; t++) if (HX.map_f[t] >= HX.nf) HX.map_f[t] = -1;
    static const int NEW17[3] = { T_BK, T_AY, T_HO };
    for (int k = 0; k < 3; k++) {
        int t = NEW17[k];
        if (HX.user_named[o][t] || def_f[t] < 0 || def_f[t] >= HX.nf) continue;
        /* Home only for a lone left Joy-Con upright: before build 16 the wizard dropped "Not on it", so an unnamed Home
           elsewhere may be a deliberate "none" */
        if (t == T_HO && !(HX.nsrc == 1 && HX.S[0].nintendo && HX.S[0].pid == 0x2006 && !o)) continue;
        int used = 0; for (int u = 0; u < NT; u++) if (HX.map_f[u] == def_f[t]) used = 1;
        if (!used) { HX.map_f[t] = def_f[t]; HX.map_inv[t] = def_i[t]; }
    }
}

static void hx_print_map(void) {
    char b[480]; int n = snprintf(b, sizeof b, "D");
    for (int t = 0; t < NT && n < (int)sizeof b - 16; t++)
        if (HX.map_f[t] >= 0) n += snprintf(b + n, sizeof b - n, " %s=%d%s", HX_TNAME[t], HX.map_f[t], HX.map_inv[t] ? "i" : "");
    if (hx_say) hx_say(b);
}

/** "S=3 E=4 LY=1i …" (the user's mapping, from the wizard; [o] 0 upright, 1 a Joy-Con sideways). Every token
 *  checked; a bad one = the whole line refused. An empty line = back to the default. Returns 0 if applied. */
static int hx_parse_map_o(const char *line, int o) {
    if (o < 0 || o > 1) return -1;
    int nf2[NT], ni2[NT]; unsigned char named[NT];
    for (int t = 0; t < NT; t++) { nf2[t] = -1; ni2[t] = 0; named[t] = 0; }
    char buf[600]; snprintf(buf, sizeof buf, "%s", line);
    int any = 0;
    for (char *save = 0, *tok = strtok_r(buf, " ", &save); tok; tok = strtok_r(0, " ", &save)) {
        char *eq = strchr(tok, '='); if (!eq) return -1;
        *eq = 0; int t = -1;
        for (int k = 0; k < NT; k++) if (!strcmp(tok, HX_TNAME[k])) t = k;
        char *end; long f = strtol(eq + 1, &end, 10);
        int inv = 0; if (*end == 'i') { inv = 1; end++; }
        if (t < 0 || end == eq + 1 || *end || f < -1 || f >= HX.nf) return -1;
        nf2[t] = (int)f; ni2[t] = inv; named[t] = 1; any = 1;   /* dev build 17: named, a "-1" too ([hx_apply_map]) */
    }
    HX.has_user[o] = any;
    memcpy(HX.user_f[o], nf2, sizeof nf2); memcpy(HX.user_inv[o], ni2, sizeof ni2); memcpy(HX.user_named[o], named, sizeof named);
    hx_apply_map();
    return 0;
}
__attribute__((unused)) static int hx_parse_map(const char *line) { return hx_parse_map_o(line, 0); }

/* ---- reading fields ----------------------------------------------------------------------------------- */
static int32_t hx_get(const uint8_t *r, int len, const hx_field *f) {
    if ((f->bit + f->bits + 7) / 8 > len) return 0;
    uint64_t v = 0;
    for (int k = 0; k < f->bits; k++) { int b = f->bit + k; if (r[b / 8] >> (b % 8) & 1) v |= (uint64_t)1 << k; }
    if (f->sgn && f->bits < 32 && (v >> (f->bits - 1) & 1)) v |= ~(((uint64_t)1 << f->bits) - 1);
    return (int32_t)v;
}

static int32_t hx_span(const hx_field *f) {
    int32_t s = f->lmax - f->lmin;
    return s > 0 ? s : (1 << (f->bits < 31 ? f->bits : 30)) - 1;
}

/** A stick axis → -32767..32767: centred on its first reading (or after "c"), scaled by the farthest seen each way. */
static int hx_stick(int fi, int32_t v, int inv) {
    if (!HX.have_cen[fi] || HX.recentre) {
        /* the centre: a Joy-Con / Pro reports from the moment it connects (its first reading is its rest, which differs
           per unit); a HID pad may only report changes — its first report can be a pushed stick — so its centre is the
           middle of its declared range, refined by the first reading only when that is close to the middle. "c" (re-centre)
           always takes the reading. */
        const hx_field *f = &HX.F[fi];
        int32_t c = v;
        if (!HX.S[f->src].nintendo && !HX.recentre && f->lmax > f->lmin) {
            int32_t mid = f->lmin + (f->lmax - f->lmin) / 2, d = v - mid;
            if (d < 0) d = -d;
            if (d * 8 > hx_span(f)) c = mid;
        }
        HX.cen[fi] = c; HX.have_cen[fi] = 1;
        int32_t init = HX.S[f->src].nintendo ? 1100 : hx_span(f) / 2;   /* Nintendo: ±1300 of 4096 */
        HX.lo[fi] = c - init; HX.hi[fi] = c + init;
    }
    if (v < HX.lo[fi]) HX.lo[fi] = v;
    if (v > HX.hi[fi]) HX.hi[fi] = v;
    double up = HX.hi[fi] - HX.cen[fi], dn = HX.cen[fi] - HX.lo[fi];
    double x = v >= HX.cen[fi] ? (v - HX.cen[fi]) / (up > 0 ? up : 1) : -(HX.cen[fi] - v) / (dn > 0 ? dn : 1);
    if (x > -0.06 && x < 0.06) x = 0;
    if (x > 1) x = 1;
    if (x < -1) x = -1;
    int o = (int)(x * 32767);
    return inv ? -o : o;
}

static int hx_trig(int fi, int32_t v) {
    const hx_field *f = &HX.F[fi];
    if (f->kind == K_BTN) return v ? 32767 : 0;
    double x = (double)(v - f->lmin) / hx_span(f);
    if (x < 0.02) x = 0;
    if (x > 1) x = 1;
    return (int)(x * 32767);
}

/** A digital control from any field: a bit, past half of a value's range, a hat direction, or one SIDE of an axis
 *  (a D-pad direction from a stick — the 8BitDo Micro's D-pad is a stick in Switch mode). */
static int hx_pressed(int fi, int32_t v, int t) {
    const hx_field *f = &HX.F[fi];
    if (f->kind == K_BTN) return v != 0;
    if (f->kind == K_HAT) {
        int32_t d = v - f->lmin; if (d < 0 || d > 7) return 0;
        static const int up[8] = {1,1,0,0,0,0,0,1}, rt[8] = {0,1,1,1,0,0,0,0}, dn[8] = {0,0,0,1,1,1,0,0}, lf[8] = {0,0,0,0,0,1,1,1};
        return t == T_DU ? up[d] : t == T_DD ? dn[d] : t == T_DL ? lf[d] : t == T_DR ? rt[d] : 1;
    }
    int32_t span = hx_span(f);
    if (t == T_DU || t == T_DD || t == T_DL || t == T_DR) {
        int dir = (t == T_DU || t == T_DL) ? -1 : 1;
        if (HX.map_inv[t]) dir = -dir;
        int64_t mid = ((int64_t)f->lmin + f->lmax) / 2, q = span / 4;
        return dir < 0 ? v < mid - q : v > mid + q;
    }
    return (v - f->lmin) > span / 2;
}

/** dev build 17 (theme G): L2 / R2 from a trigger's pull — a pad whose triggers are only axes (an Xbox pad's LT / RT),
 *  or an L2 read from a value (a pressure): pressed at 30 % of the pull, let go below 20 % (no flicker at the edge).
 *  Home + L2, a change of L2 and "Triggers all-or-nothing" then work on it. A real button: as it is. */
#define HX_TRIG_ON  (32767 * 30 / 100)
#define HX_TRIG_OFF (32767 * 20 / 100)
/** The axis [tb] (T_L2 / T_R2) is read from: its own field when that is a value, else its trigger's ([ta]) when it has
 *  no button at all; -1 = a real button (or nothing). */
static int hx_trig_field(int tb, int ta) {
    int fi = HX.map_f[tb];
    if (fi >= 0 && fi < HX.nf) return HX.F[fi].kind == K_AXIS ? fi : -1;
    fi = HX.map_f[ta];
    return fi >= 0 && fi < HX.nf && HX.F[fi].kind == K_AXIS ? fi : -1;
}
static int hx_trig_button(int tb, int ta, int now) {
    int fi = hx_trig_field(tb, ta);
    if (fi < 0 || !HX.have[fi]) return now;
    int v = hx_trig(fi, HX.val[fi]);
    return HX.src_key[tb] ? v >= HX_TRIG_OFF : v >= HX_TRIG_ON;
}

/* ---- the engine: our pad = the input layer's engine on a standard pad ------------------------------------ */
static const int HX_AXES[] = { WF_LX, WF_LY, WF_RX, WF_RY, WF_GAS, WF_BRAKE, WF_HX, WF_HY };

static void hx_engine_init(void) {
    wf_state *s = &HX.ws; memset(s, 0, sizeof *s);
    for (int t = 0; t < NT; t++) if (HX_PRINTED[t]) {
        int dup = 0; for (int i = 0; i < s->nk; i++) if (s->keys[i] == HX_PRINTED[t]) dup = 1;
        if (!dup) s->keys[s->nk++] = (unsigned short)HX_PRINTED[t];
    }
    s->keys[s->nk++] = WF_HOME;                    /* Home as the Thor's (hx_printed) */
    for (int k = 0; k < 8; k++) {
        int a = HX_AXES[k]; s->axes[s->na++] = (unsigned char)a;
        struct input_absinfo *i = &s->info[a]; memset(i, 0, sizeof *i);
        if (a == WF_HX || a == WF_HY) { i->minimum = -1; i->maximum = 1; }
        else if (a == WF_GAS || a == WF_BRAKE) { i->minimum = 0; i->maximum = 32767; }
        else { i->minimum = -32767; i->maximum = 32767; i->flat = 15; }
    }
    wf_state_rest(s);
    wf_map_reset(&HX.wm);
    memset(HX.src_key, 0, sizeof HX.src_key);
    for (int a = 0; a < WF_NA; a++) HX.src_abs[a] = 0;
    HX.have_out = 0;
}

/** Push the engine's output: only the differences. b18l (GitHub #81, b18w B1): when Home or Back changes, in up to three
 *  reports — Home's change / Back's release first, then the rest (the gate letting go of what is held, or giving it back),
 *  Back's press last (fx/wfmap.h [wf_frames]). In one report Home came last (its code after the buttons): X let go before
 *  Home's press was no partner of it in the app, R2 given back before Home's release made "Home + R2".
 *  b18o: first, what the hand holds as turbo buttons ("TU <code> <1|0> <ms>", fx/wfmap.h [wf_turbo_held]) — said with or
 *  without our pad (it is the hand's): Wayfinder takes their pulses for no presses. */
static void hx_flush(void) {
    {
        int c[WF_APP_HI - WF_APP_LO + 1], v[WF_APP_HI - WF_APP_LO + 1];
        int n = wf_turbo_held(&HX.ws, &HX.wm, HX.tsaid, c, v, WF_APP_HI - WF_APP_LO + 1);
        for (int i = 0; i < n; i++) hx_sayf("TU %d %d %ld", c[i], v[i], HX.ws.now_ms);
    }
    if (!hx_emit || HX.pad_down) return;          /* (our pad down for a moment: [hx_pad_up] sends the whole state) */
    wf_out o; wf_compute(&HX.ws, &HX.wm, &o);
    if (HX.vback) o.key[WF_BACK] = 1;             /* dev build 17 (theme G): a Back handed to the game ([hx_vpress]) */
    /* a face button can come out under its swapped code: every code of the set is compared (the keys: hx_engine_init's,
       the axes: HX_AXES, in that order) */
    struct input_event ev[3 * (NT + 2 + 8 + 1)];
    int n = wf_frame_events(&HX.out, &o, HX.ws.keys, HX.ws.nk, HX.ws.axes, HX.ws.na, !HX.have_out, ev, (int)(sizeof ev / sizeof *ev));
    HX.out = o; HX.have_out = 1;
    for (int i = 0; i < n; i++) hx_emit(ev[i].type, ev[i].code, ev[i].value);
}

/** A source control changed (a printed button / an axis of the standard pad): through the engine's bookkeeping
 *  (gates, toggles, turbo). The face layout: AYN's Xbox mode reports face buttons by position, the engine swaps
 *  them back — an external pad on an Xbox-mode Thor is fed the same way. */
static void hx_src_key(int printed, int v) {
    int code = HX.ws.xbox ? wf_swap_face(printed) : printed;
    int p;
    /* withheld from the game (its Home — the Thor's — held): to Wayfinder for its combos, as the Thor's copy does */
    if (wf_event(&HX.ws, EV_KEY, code, v, &p)) hx_sayf("J %d %d %d", EV_KEY, p, v);
    wf_fire_edge(&HX.ws, &HX.wm, p, v);
    /* a button the game's controls give to Wayfinder (a key, an action, a macro): Wayfinder plays it ("W") */
    if (wf_ext_event(&HX.ws, &HX.wm, p, v)) hx_sayf("W %d %d", p, v);
    /* dev build 17 (theme G): a watched button ("w"), raw — and its release once it was said pressed */
    if (p >= 0 && p < WF_NK && (HX.watch_key[p] || HX.vsaid_key[p])) { HX.vsaid_key[p] = v != 0; hx_sayf("V %d %d %d", EV_KEY, p, v); }
}
static void hx_src_abs(int a, int old, int v) {
    int p; if (wf_event(&HX.ws, EV_ABS, a, v, &p)) hx_sayf("J %d %d %d", EV_ABS, a, v);
    int dc[4], dv[4], n = wf_dir_ext(&HX.ws, &HX.wm, a, old, v, dc, dv);   /* a D-pad direction that is Wayfinder's */
    for (int j = 0; j < n; j++) hx_sayf("W %d %d", dc[j], dv[j]);
    /* dev build 17 (theme G): a watched control ("w"), raw; its D-pad also while its own AYN is held (AYN + D-pad combos:
       AYN doesn't gate, and asking the app first would race the press) — and back to rest once it was said away */
    if (a >= 0 && a < WF_NA && (HX.watch_abs[a] || HX.vsaid_abs[a] || ((a == WF_HX || a == WF_HY) && HX.ayn))) {
        HX.vsaid_abs[a] = v; hx_sayf("V %d %d %d", EV_ABS, a, v);
    }
}

/** dev build 17 (theme G): "w [k<code>|a<axis>]…" (at most 4; "w" = none) — these controls said as "V <type> <code>
 *  <value>", their raw state (printed codes), as wfpad's "w": the app reads a connected controller's D-pad through it
 *  while it needs it (Recents, a press being captured). A control no longer watched while it was said away from rest is
 *  said at rest now (the app never keeps a direction held it won't hear let go). 0, or -1 (refused: the set in use
 *  stays). */
static int hx_watch(const char *args) {
    unsigned char wk[WF_NK], wa[WF_NA]; memset(wk, 0, sizeof wk); memset(wa, 0, sizeof wa);
    char buf[128]; if (strlen(args) >= sizeof buf) return -1;
    snprintf(buf, sizeof buf, "%s", args);
    int n = 0;
    for (char *save = 0, *t = strtok_r(buf, " ", &save); t; t = strtok_r(0, " ", &save)) {
        if (++n > 4 || (t[0] != 'k' && t[0] != 'a') || t[1] < '0' || t[1] > '9') return -1;
        char *end; long c = strtol(t + 1, &end, 10);
        if (*end) return -1;
        if (t[0] == 'k') { if (c <= 0 || c >= WF_NK) return -1; wk[c] = 1; }
        else { if (c < 0 || c >= WF_NA) return -1; wa[c] = 1; }
    }
    memcpy(HX.watch_key, wk, sizeof wk); memcpy(HX.watch_abs, wa, sizeof wa);
    for (int k = 0; k < WF_NK; k++) if (HX.vsaid_key[k] && !wk[k]) { HX.vsaid_key[k] = 0; hx_sayf("V %d %d 0", EV_KEY, k); }
    for (int a = 0; a < WF_NA; a++)
        if (HX.vsaid_abs[a] && !wa[a] && !((a == WF_HX || a == WF_HY) && HX.ayn)) { HX.vsaid_abs[a] = 0; hx_sayf("V %d %d 0", EV_ABS, a); }
    return 0;
}
/** dev build 17: every control said away from rest ("V") said at rest — the state is thrown away ([hx_release_all]). */
static void hx_watch_rest(void) {
    for (int k = 0; k < WF_NK; k++) if (HX.vsaid_key[k]) { HX.vsaid_key[k] = 0; hx_sayf("V %d %d 0", EV_KEY, k); }
    for (int a = 0; a < WF_NA; a++) if (HX.vsaid_abs[a]) { HX.vsaid_abs[a] = 0; hx_sayf("V %d %d 0", EV_ABS, a); }
}

/** A stick given to Wayfinder (mouse, wheel, 4 keys): its position for the app ("T side x y", −1000..1000), at most
 *  every 8 ms while it moves, its return to 0 at once. [force] = send both again. */
static void hx_app_sticks(int force) {
    for (int side = 0; side < 2; side++) {
        int x, y; wf_app_stick(&HX.ws, &HX.wm, side, &x, &y);
        if (!force && x == HX.app_last[side][0] && y == HX.app_last[side][1]) continue;
        if (!force && (x || y) && HX.now_ms - HX.app_at[side] < 8) continue;
        if (force && !HX.wm.app_stick[side] && !HX.app_last[side][0] && !HX.app_last[side][1]) continue;
        HX.app_last[side][0] = x; HX.app_last[side][1] = y; HX.app_at[side] = HX.now_ms;
        hx_sayf("T %d %d %d", side, x, y);
    }
}

/** A release for every key / action Wayfinder plays that is held now: before the source state is thrown away ([next]
 *  NULL), or before map [next] is swapped in — then only what [next] no longer gives to Wayfinder (the rest keeps its
 *  meaning, and stays held). */
static void hx_ext_release(const wf_map *next) {
    for (int i = 0; i < HX.ws.nk; i++) {
        int k = HX.ws.keys[i];
        if (HX.ws.key[k] && HX.wm.keymap[k] == WF_EXT && (!next || next->keymap[k] != WF_EXT)) hx_sayf("W %d 0", k);
    }
    static const int DIRS[4] = { WF_HAT_UP, WF_HAT_DOWN, WF_HAT_LEFT, WF_HAT_RIGHT };
    for (int i = 0; i < 4; i++)
        if (HX.wm.keymap[DIRS[i]] == WF_EXT && (!next || next->keymap[DIRS[i]] != WF_EXT) &&
            wf_dir_on(HX.ws.abs[WF_HX], HX.ws.abs[WF_HY], DIRS[i])) hx_sayf("W %d 0", DIRS[i]);
}

/** A new map in place, as wfpad's "m" (dev build 14): the source state stays — what is held, the gate (Home), what it
 *  consumed — so nothing the hand holds gets an edge. A release + replay here sent Home up and down: its real release
 *  was then a Home tap (it went home), and the combo's button, no longer consumed, reached the page it opened. What
 *  changes meaning is let go (Wayfinder's own outputs, toggles, Wayfinder's virtual presses); then only the differences
 *  go out. */
static void hx_swap_map(const wf_map *m) {
    wf_state *s = &HX.ws;
    hx_ext_release(m);
    for (int i = 0; i < s->nk; i++) {          /* a toggle stays latched only if it still toggles the same output */
        int p = s->keys[i];
        if (m->fire[p] != 2 || HX.wm.fire[p] != 2 || m->keymap[p] != HX.wm.keymap[p]) s->latch[p] = 0;
    }
    memset(s->vkey, 0, sizeof s->vkey);
    /* the shift button changed while held: the old one stays silent until let go (it was Wayfinder's), a new one
       already held is no lone tap at its release, and is said held as wf_event says a press of it (dev build 14: the
       app starts a new profile with no shift held — without this, every press under it was withheld for nothing) */
    if (s->shift && s->shift != m->shift && s->key[s->shift]) s->ckey[s->shift] = 1;
    if (m->shift && m->shift != s->shift && s->key[m->shift]) { s->shift_used = 1; hx_sayf("J %d %d 1", EV_KEY, m->shift); }
    HX.wm = *m; wf_state_map(s, m); s->shift_tap_until = 0;   /* b18h: with what the copy shows of each key */
    /* the gate follows what is held now (Home keeps it open), with wf_event's bookkeeping when it opens or closes */
    int g = wf_gate(s);
    if (g && !s->gated) {                      /* opens: what is held now is not consumed */
        for (int i = 0; i < s->nk; i++) s->akey_prev[s->keys[i]] = s->key[s->keys[i]];
        for (int i = 0; i < s->na; i++) s->aabs_prev[s->axes[i]] = (unsigned char)wf_abs_active(s, s->axes[i]);
    }
    if (!g && s->gated) {                      /* closes: what is back at rest is free again */
        for (int i = 0; i < s->nk; i++) if (!s->key[s->keys[i]]) s->ckey[s->keys[i]] = 0;
        for (int i = 0; i < s->na; i++) if (!wf_abs_active(s, s->axes[i])) s->cabs[s->axes[i]] = 0;
    }
    s->gated = g;
    hx_flush();
    hx_app_sticks(1);
}

/** The game's profile for this controller (fx/wfmap.h `wf_parse` tokens; "" = none), swapped in ([hx_swap_map]).
 *  0 if applied, 1 if it is the one in use (nothing done, nothing to say: the app sends it again), -1 if refused (the
 *  one in use stays). */
static int hx_profile(const char *spec) {
    wf_map m;
    /* dev build 17 (theme G): its controls off (Home + Back held 5 s, [hx_mute]): nothing applied, nothing said — the
       helper sends it again after "u". Only the game's "Back goes to the game" (bg=1: RetroArch's Back hotkeys) follows,
       as on a plain pad (critique F2) */
    if (HX.muted) {
        if (wf_parse(&m, &HX.ws, spec) == 0 && m.back_free != HX.wm.back_free) {
            wf_map p; wf_map_reset(&p); p.back_free = m.back_free; hx_swap_map(&p);
        }
        return 1;
    }
    if (wf_parse(&m, &HX.ws, spec) != 0) { HX.prof_bad = 1; return -1; }
    if (!HX.prof_bad && !memcmp(&m, &HX.wm, sizeof m)) return 1;
    HX.prof_bad = 0;
    hx_swap_map(&m);
    return 0;
}

/** A virtual press from Wayfinder ("p code 0|1": its macros, combos, turbo for a button that is Wayfinder's) — held
 *  on top of the controller's own; "p 0 0" lets them all go. 0 if taken. */
static int hx_vpress(int code, int v) {
    if (code == WF_HOME) return -1;
    /* dev build 17 (theme G): a Back Wayfinder hands to the game (ButtonEngine.pressBackOnPad) — out at once even while
       its Home is held: on a pad with no Back of its own that Back is Home + Select, and its Home is often still down */
    if (code == WF_BACK) { if (v != 0 && v != 1) return -1; HX.vback = v; hx_flush(); return 0; }
    if (code == 0 && v == 0) HX.vback = 0;
    if (wf_vpress(&HX.ws, code, v) != 0) return -1;
    hx_flush();
    return 0;
}

/* ---- touchpad pointer (dev build 18f) ------------------------------------------------------------------------------
   A PlayStation pad's touchpad (DualSense, DualShock 4) moves Android's pointer: one finger moves it (relative, a mild
   acceleration — Android adds its own on top), a light tap clicks, two fingers dragging scroll (natural: the page follows
   the fingers, like the touchscreen). Pressing the pad stays what its mapping says (its Back by default): the press
   freezes the pointer for the rest of that touch, which is then no tap. Two-finger taps do nothing. Off until the helper
   says "t 1"; nothing while learning, muted, away, quiet, or for the replay of the last report ([hx_tick]).
   Lines: "H m <dx> <dy>" pixels (≤ every 8 ms) · "H b <1|0>" a tap's click · "H w <v> <h>" wheel notches (≤ every 16 ms).
   Layout facts (public documentation of Sony's reports, the kernel driver's struct layout — our own code): a touch point
   is 4 bytes, byte 0 bit 7 = NO finger, bits 0-6 the contact's id; x = b1 | (b2 & 0x0F) << 8, y = b2 >> 4 | b3 << 4.
   DualSense: points 0 / 1 at bytes 33 / 37 (USB report 0x01), 34 / 38 (Bluetooth 0x31); its pad 1920 × 1080.
   DualShock 4: a count of touch reports at byte 33 (USB 0x01, ≤ 3) / 35 (Bluetooth 0x11, ≤ 4), then touch reports of 9
   bytes from 34 / 36 (a timestamp, points 0 and 1), oldest first; its pad 1920 × 942. Every length is checked. */
#define HX_TP_SLOP        50       /* units (≈ 1.4 mm) a finger moves before the pointer does */
#define HX_TP_SLOP2       40       /* two fingers: their centroid moves this far before they scroll */
#define HX_TP_GAIN_LO     0.45f    /* pixels per unit at ≤ HX_TP_SPEED_LO units / ms … */
#define HX_TP_GAIN_HI     1.6f     /* … rising linearly to this at ≥ HX_TP_SPEED_HI */
#define HX_TP_SPEED_LO    0.5f
#define HX_TP_SPEED_HI    4.0f
#define HX_TP_DT_MIN      2        /* ms between two samples, clamped (speed) */
#define HX_TP_DT_MAX      50
#define HX_TP_EMIT_MS     8        /* "H m" at most this often */
#define HX_TP_WEMIT_MS    16       /* "H w" at most this often */
#define HX_TP_NOTCH       80       /* units of a two-finger drag per wheel notch */
#define HX_TP_TAP_MS      180      /* a tap: this short, … */
#define HX_TP_TAP_TRAVEL  60       /* … moving no farther than this (units), one finger, the pad not pressed */
#define HX_TP_CLICK_MS    40       /* a tap's click held this long */
#define HX_TP_MAX_PX      2000     /* one "H m", each way */
#define HX_TP_MAX_NOTCH   10       /* one "H w", each way */

typedef struct { int x, y, id; } hx_tpt;

/** The touch state cleared (pending amounts too); a held tap's click let go ("H b 0"). */
static void hx_tp_reset(void) {
    int held = HX.tp.btn;
    memset(&HX.tp, 0, sizeof HX.tp);
    if (held) hx_sayf("H b 0");
}
/** "t 1" / "t 0": the touchpad moves the pointer, or nothing (what it holds let go). */
static void hx_touch_on(int on) {
    on = on != 0;
    if (!on) hx_tp_reset();
    HX.tp_on = on;
}

static float hx_tp_len(int dx, int dy) { return __builtin_sqrtf((float)dx * (float)dx + (float)dy * (float)dy); }
static int hx_tp_clamp(int v, int m) { return v > m ? m : v < -m ? -m : v; }

/** The whole pixels / notches waiting, said when their rate allows (the rest: [hx_tp_wait] / [hx_tp_tick]). */
static void hx_tp_out(void) {
    hx_tp_t *t = &HX.tp; long now = HX.now_ms;
    if ((t->pend_x || t->pend_y) && (!t->emit_ms || now - t->emit_ms >= HX_TP_EMIT_MS)) {
        hx_sayf("H m %d %d", hx_tp_clamp(t->pend_x, HX_TP_MAX_PX), hx_tp_clamp(t->pend_y, HX_TP_MAX_PX));
        t->pend_x = t->pend_y = 0; t->emit_ms = now;
    }
    if ((t->pend_v || t->pend_h) && (!t->wemit_ms || now - t->wemit_ms >= HX_TP_WEMIT_MS)) {
        hx_sayf("H w %d %d", hx_tp_clamp(t->pend_v, HX_TP_MAX_NOTCH), hx_tp_clamp(t->pend_h, HX_TP_MAX_NOTCH));
        t->pend_v = t->pend_h = 0; t->wemit_ms = now;
    }
}

/** A sample's points from two 4-byte touch points at [q] (the caller checked the 8 bytes are in the report): the active
 *  ones (bit 7 clear) into [o]. Returns how many (0..2). */
static int hx_tp_points(const uint8_t *q, hx_tpt *o) {
    int n = 0;
    for (int k = 0; k < 2; k++) {
        const uint8_t *b = q + 4 * k;
        if (b[0] & 0x80) continue;
        o[n].id = b[0] & 0x7F; o[n].x = b[1] | ((b[2] & 0x0F) << 8); o[n].y = (b[2] >> 4) | (b[3] << 4);
        n++;
    }
    return n;
}

/** A new contact (a touch begins, a finger changes, one ↔ two fingers): the baselines, no delta. */
static void hx_tp_base(const hx_tpt *p, int n) {
    hx_tp_t *t = &HX.tp;
    if (n == 1) { t->lx = p[0].x; t->ly = p[0].y; t->lid = p[0].id; }
    else {
        t->cx = t->c0x = (p[0].x + p[1].x) / 2; t->cy = t->c0y = (p[0].y + p[1].y) / 2;
        t->id2[0] = p[0].id; t->id2[1] = p[1].id; t->scrolling = 0;
    }
    t->n = n;
}

/** One sample of the touchpad: [n] fingers [p], the pad pressed ([click]); [newest] = the input report's newest sample —
 *  only it moves (a DualShock 4's older samples in the same report keep the bookkeeping: contacts, travel, the click). */
static void hx_tp_step(const hx_tpt *p, int n, int click, int newest) {
    hx_tp_t *t = &HX.tp; long now = HX.now_ms;
    if (n <= 0) {
        if (t->n > 0) {                                   /* the touch ends */
            if (click) t->clicked = 1;
            if (now - t->down_ms <= HX_TP_TAP_MS && t->travel <= HX_TP_TAP_TRAVEL && !t->clicked && t->max_n == 1) {
                if (t->btn) hx_sayf("H b 0");
                hx_sayf("H b 1"); t->btn = 1; t->btn_up_at = now + HX_TP_CLICK_MS;
            }
            t->ax = t->ay = t->wv = t->wh = 0;            /* the fractions go; whole amounts pending still go out */
        }
        t->n = 0; t->max_n = 0;
        if (newest) t->last_ms = now;
        return;
    }
    if (n > 2) n = 2;
    if (t->n == 0) {                                      /* a touch begins */
        t->down_ms = now; t->sx = p[0].x; t->sy = p[0].y; t->travel = 0; t->moving = 0;
        t->clicked = click != 0; t->max_n = n;
        hx_tp_base(p, n);
        if (newest) t->last_ms = now;
        return;
    }
    if (n > t->max_n) t->max_n = n;
    if (click) t->clicked = 1;
    if (n == 1) {
        if (t->n != 1 || p[0].id != t->lid) { hx_tp_base(p, 1); if (newest) t->last_ms = now; return; }   /* a new contact */
        int d0 = (int)hx_tp_len(p[0].x - t->sx, p[0].y - t->sy);
        if (d0 > t->travel) t->travel = d0;
        if (t->travel > HX_TP_SLOP) t->moving = 1;
        if (!newest) return;
        int dx = p[0].x - t->lx, dy = p[0].y - t->ly;
        if (t->moving && !t->clicked && t->max_n == 1 && (dx || dy)) {
            long dt = now - t->last_ms;
            if (dt < HX_TP_DT_MIN) dt = HX_TP_DT_MIN;
            if (dt > HX_TP_DT_MAX) dt = HX_TP_DT_MAX;
            float sp = hx_tp_len(dx, dy) / (float)dt, g;
            if (sp <= HX_TP_SPEED_LO) g = HX_TP_GAIN_LO;
            else if (sp >= HX_TP_SPEED_HI) g = HX_TP_GAIN_HI;
            else g = HX_TP_GAIN_LO + (HX_TP_GAIN_HI - HX_TP_GAIN_LO) * (sp - HX_TP_SPEED_LO) / (HX_TP_SPEED_HI - HX_TP_SPEED_LO);
            t->ax += (float)dx * g; t->ay += (float)dy * g;
            int wx = (int)t->ax, wy = (int)t->ay;          /* whole pixels (toward 0: the remainder keeps its sign) */
            t->ax -= (float)wx; t->ay -= (float)wy; t->pend_x += wx; t->pend_y += wy;
            hx_tp_out();
        }
        t->lx = p[0].x; t->ly = p[0].y; t->last_ms = now;
        return;
    }
    /* two fingers: their centroid scrolls (never the pointer) */
    int same = t->n == 2 && ((p[0].id == t->id2[0] && p[1].id == t->id2[1]) || (p[0].id == t->id2[1] && p[1].id == t->id2[0]));
    if (!same) { hx_tp_base(p, 2); if (newest) t->last_ms = now; return; }
    int cx = (p[0].x + p[1].x) / 2, cy = (p[0].y + p[1].y) / 2;
    if (!t->scrolling && hx_tp_len(cx - t->c0x, cy - t->c0y) > HX_TP_SLOP2) t->scrolling = 1;
    if (!newest) return;
    if (t->scrolling && !t->clicked) {
        t->wv += (float)(cy - t->cy) / HX_TP_NOTCH;        /* natural: fingers down = the page down = up the wheel */
        t->wh -= (float)(cx - t->cx) / HX_TP_NOTCH;
        int v = (int)t->wv, h = (int)t->wh;
        t->wv -= (float)v; t->wh -= (float)h; t->pend_v += v; t->pend_h += h;
        hx_tp_out();
    }
    t->cx = cx; t->cy = cy; t->last_ms = now;
}

/** The touch data of a report from controller [src] ([r] as read, its report id first) — called by [hx_report]. */
static void hx_touch(int src, const uint8_t *r, int len) {
    if (src < 0 || src >= HX.nsrc || !HX.S[src].sony || !HX.tp_on || HX.learn || HX.muted || HX.idle || HX.quiet) return;
    if (r == HX.last_rep || len < 1) return;              /* [hx_tick]'s replay of the last report moves nothing */
    int ds = HX.S[src].sony == 2, bt = HX.S[src].bt;
    if (r[0] != (ds ? (bt ? 0x31 : 0x01) : (bt ? 0x11 : 0x01))) return;   /* by bus, as [hx_sony_fields] */
    int cf = hx_find(src, SP, 14);                        /* the touchpad's click, raw (whatever it is mapped to) */
    int click = cf >= 0 && hx_get(r, len, &HX.F[cf]) != 0;
    hx_tpt pts[2];
    if (ds) {
        int o = bt ? 34 : 33;
        if (len < o + 8) return;
        hx_tp_step(pts, hx_tp_points(r + o, pts), click, 1);
        return;
    }
    int co = bt ? 35 : 33, base = bt ? 36 : 34, cap = bt ? 4 : 3;
    if (len <= co) return;
    int cnt = r[co] < cap ? r[co] : cap, use = 0;
    while (use < cnt && base + 9 * (use + 1) <= len) use++;   /* only the touch reports fully inside the report */
    for (int k = 0; k < use; k++) hx_tp_step(pts, hx_tp_points(r + base + 9 * k + 1, pts), click, k == use - 1);
}

/** ms until the touchpad has something to say (a tap's click to let go, pixels / notches held back), -1 nothing. */
static int hx_tp_wait(void) {
    hx_tp_t *t = &HX.tp; long due = -1;
    if (t->btn) due = t->btn_up_at;
    if (t->pend_x || t->pend_y) { long d = t->emit_ms + HX_TP_EMIT_MS; if (due < 0 || d < due) due = d; }
    if (t->pend_v || t->pend_h) { long d = t->wemit_ms + HX_TP_WEMIT_MS; if (due < 0 || d < due) due = d; }
    if (due < 0) return -1;
    long l = due - HX.now_ms;
    return l < 0 ? 0 : l > 1000 ? 1000 : (int)l;
}
static void hx_tp_tick(void) {
    if (HX.tp.btn && HX.now_ms >= HX.tp.btn_up_at) { HX.tp.btn = 0; hx_sayf("H b 0"); }
    hx_tp_out();
}

/** Release everything (learn mode, quit, a Joy-Con turned): the pad at rest for the game. dev build 17 (theme G): its
 *  AYN button let go ("A 0"), a watched control at rest ("V … 0"), a Back handed to the game and Home + Back's 5 s too.
 *  dev build 18f: the touchpad's touch too (a held tap's click: "H b 0"). */
static void hx_release_all(void) {
    hx_tp_reset();
    hx_ext_release(NULL);
    HX.sub_since = 0; HX.sub_on = 0;   /* Select + Start: counted afresh */
    wf_state_rest(&HX.ws);
    memset(HX.src_key, 0, sizeof HX.src_key);
    for (int a = 0; a < WF_NA; a++) HX.src_abs[a] = 0;
    HX.vback = 0; HX.hb_since = 0;
    if (HX.ayn) { HX.ayn = 0; hx_sayf("A 0"); }
    hx_watch_rest();
    hx_flush();
}

/* ---- one report in ------------------------------------------------------------------------------------- */
/** dev build 16 (theme E): a button pressed / let go while learning — its hold is timed ([hx_learn_due]). */
static void hx_learn_hold(int i, int down) {
    if (!down) { HX.learn_down[i] = 0; return; }
    HX.learn_down[i] = HX.now_ms ? HX.now_ms : 1; HX.learn_said[i] = 0;
    HX.learn_again[i] = HX.learn_heard[i]; HX.learn_heard[i] = 1;
}

static void hx_learn(void) {
    for (int i = 0; i < HX.nf; i++) {
        if (!HX.have[i]) continue;
        if (!HX.learn_have[i]) {
            /* first heard since learn mode began: nothing from this pad since its reader started (one that reports
               only changes, dev build 14 — the wizard missed its first press). A button or a D-pad rests let go, so
               a first report that IS a press is heard as one; an axis' rest can't be known: this reading is it */
            int32_t v = HX.val[i];
            if (HX.F[i].kind == K_BTN) v = 0;
            else if (HX.F[i].kind == K_HAT && v - HX.F[i].lmin >= 0 && v - HX.F[i].lmin <= 7) v = HX.F[i].lmin + 8;
            HX.learn_val[i] = HX.learn_prev[i] = v; HX.learn_away[i] = 0; HX.learn_have[i] = 1;
            if (HX.F[i].kind == K_AXIS) continue;
        }
        if (HX.F[i].kind != K_AXIS) {
            if (HX.val[i] != HX.learn_prev[i]) {
                HX.learn_prev[i] = HX.val[i]; HX.learn_at = HX.now_ms;
                hx_sayf("C %d %d %d", i, HX.val[i], HX.learn_val[i]);
                if (HX.F[i].kind == K_BTN) hx_learn_hold(i, HX.val[i] != 0);
            }
            continue;
        }
        int32_t q = hx_span(&HX.F[i]) / 6; if (q < 1) q = 1;
        int32_t d = HX.val[i] - HX.learn_val[i]; if (d < 0) d = -d;
        int away = d > q;
        if (away != HX.learn_away[i]) {
            HX.learn_away[i] = (unsigned char)away; HX.learn_at = HX.now_ms;
            if (away) hx_sayf("C %d %d %d", i, HX.val[i], HX.learn_val[i]);
        }
    }
}

static void hx_orient(int sideways);
static void hx_release_all(void);
/** Learn mode on (the setup wizard): the controller drives nothing; its state NOW is the rest — a controller that
 *  only reports changes (most HID pads) would otherwise have its first press taken for the rest. */
static void hx_learn_start(void) {
    if (HX.idle) { hx_sayf("L 0"); return; }       /* dev build 15: the controller is away — the wizard pauses */
    hx_release_all();
    HX.learn = 1; HX.learn_at = HX.now_ms;
    for (int i = 0; i < HX.nf; i++) {
        HX.learn_have[i] = HX.have[i];
        HX.learn_val[i] = HX.learn_prev[i] = HX.val[i]; HX.learn_away[i] = 0;
    }
    memset(HX.learn_down, 0, sizeof HX.learn_down); memset(HX.learn_said, 0, sizeof HX.learn_said);
    memset(HX.learn_again, 0, sizeof HX.learn_again); memset(HX.learn_heard, 0, sizeof HX.learn_heard);
}

/** A minute without a change ends learn mode by itself (dev build 14): the wizard's page gone without saying so (the
 *  app left, stopped) left the controller silent in games. */
#define HX_LEARN_MS 60000
/** dev build 16 (theme E): while learning, its Home held 1.5 s says "L h <field>" (the wizard: Cancel) and a button heard
 *  before in this session held 1 s says "L k <field>" (the wizard: "Not on it" — never for its Home). Once per press; the
 *  app decides what it means (a press it has just learnt is no skip). */
#define HX_LEARN_HOME_MS 1500
#define HX_LEARN_SKIP_MS 1000
/** When field [i]'s hold is due (ms), -1 = none. */
static long hx_learn_due(int i) {
    if (!HX.learn_down[i] || HX.learn_said[i]) return -1;
    if (i == HX.map_f[T_HO]) return HX.learn_down[i] + HX_LEARN_HOME_MS;
    return HX.learn_again[i] ? HX.learn_down[i] + HX_LEARN_SKIP_MS : -1;
}

/** A button of the standard pad held now (from the last values read: they are kept current in learn mode too). */
static int hx_held(void) {
    for (int t = 0; t < NT; t++) {
        int fi = HX.map_f[t];
        /* dev build 17 (theme G): L2 / R2 from a trigger's pull — held from its press point, as [hx_report] presses
           them; its AYN button too (never on the pad): a Mute or an LT held as the wizard ends must not come out as a
           press nobody made */
        int tf = t == T_L2 ? hx_trig_field(T_L2, T_LT) : t == T_R2 ? hx_trig_field(T_R2, T_RT) : -1;
        if (tf >= 0) { if (HX.have[tf] && hx_trig(tf, HX.val[tf]) >= HX_TRIG_ON) return 1; continue; }
        if ((hx_printed(t) || t == T_AY) && fi >= 0 && HX.have[fi] && hx_pressed(fi, HX.val[fi], t)) return 1;
    }
    return 0;
}

/** Learn mode off ("l 0", or by itself): what is still held stays silent until all is let go — a button just learnt
 *  (its Home…) must not reach the game or Wayfinder as a press nobody made. Only if something IS held (dev build 14):
 *  a pad that reports only on change sent its "all let go" while learning, so its next report was the user's first
 *  press — taken for one still held, dropped, and its release freed it with no edge. */
static void hx_learn_stop(void) {
    if (!HX.learn) return;
    HX.learn = 0; HX.quiet = hx_held();
    memset(HX.learn_down, 0, sizeof HX.learn_down);   /* dev build 16: no hold said once it's over */
}

/** SL + SR held together 0.6 s on a single upright Joy-Con: it turns sideways, as on a Switch ("O 1"). Only that
 *  way — sideways, SL + SR are the shoulders and a game may hold both; back upright from the page. */
static void hx_gestures(int src) {
    if (!HX.S[src].nintendo || HX.nsrc != 1) return;
    int pid = HX.S[src].pid; if (pid != 0x2006 && pid != 0x2007) return;
    /* its main shoulder (L on a left one, R on a right one): the helper pairs a left and a right held together */
    int shf = hx_find(src, NP, pid == 0x2007 ? 0x07 : 0x27);
    int sh = shf >= 0 && HX.val[shf] && !HX.learn;
    if (sh != HX.sh_prev) { HX.sh_prev = sh; hx_sayf("G sh %d", sh); }
    int sl = hx_find(src, NP, pid == 0x2007 ? 0x06 : 0x26), sr = hx_find(src, NP, pid == 0x2007 ? 0x05 : 0x25);
    int both = sl >= 0 && sr >= 0 && HX.val[sl] && HX.val[sr];
    if (!both || HX.learn) { HX.slsr_since = 0; HX.slsr_sent = 0; return; }
    if (!HX.slsr_since) HX.slsr_since = HX.now_ms ? HX.now_ms : 1;
    if (!HX.slsr_sent && HX.now_ms - HX.slsr_since >= 600) {
        HX.slsr_sent = 1;
        if (!HX.sideways) { hx_orient(1); HX.quiet = 1; hx_say("O 1"); hx_print_map(); }
    }
}

/** Aim with motion: the controller's turn rate → a stick deflection, added by the engine to that stick. Raw gyro
 *  ≈ 16.4 per degree / s (±2000 °/s); sensitivity 10 → a quarter of the stick at 100 °/s. Its axes for a Joy-Con /
 *  Pro held upright: [2] turn left-right (yaw), [1] tilt forward-back (pitch). The resting offset (each unit has one)
 *  is learnt from the first samples, then follows slowly whenever the controller is almost still. */
static int32_t hx_tremor(int32_t v) { return v > 35 ? v - 35 : v < -35 ? v + 35 : 0; }   /* below ~2 °/s: nothing */
static void hx_gyro(void) {
    int32_t g[3] = { HX.gyr[0], HX.gyr[1], HX.gyr[2] };
    if (HX.gbias_n < 32) {                               /* the first ~0.5 s: the controller is put down / held */
        for (int k = 0; k < 3; k++) HX.gbias[k] = (HX.gbias[k] * HX.gbias_n + g[k] * 16) / (HX.gbias_n + 1);
        HX.gbias_n++;
        return;
    }
    int32_t d[3]; int still = 1;
    for (int k = 0; k < 3; k++) { d[k] = g[k] - HX.gbias[k] / 16; if (d[k] > 60 || d[k] < -60) still = 0; }
    if (still) for (int k = 0; k < 3; k++) HX.gbias[k] += (g[k] * 16 - HX.gbias[k]) / 64;
    int32_t yaw = -d[2], pitch = -d[1];
    if (HX.sideways) { int32_t t = yaw; yaw = pitch; pitch = -t; }     /* held sideways: a quarter turn */
    if (HX.gyro_flags & 4) { int32_t t = yaw; yaw = pitch; pitch = t; }
    if (HX.gyro_flags & 1) yaw = -yaw;
    if (HX.gyro_flags & 2) pitch = -pitch;
    yaw = hx_tremor(yaw); pitch = hx_tremor(pitch);
    long x = (long)yaw * HX.gyro_sens / 2, y = (long)pitch * HX.gyro_sens / 2;
    HX.ws.gyro_x = (int)(x > 32767 ? 32767 : x < -32767 ? -32767 : x);
    HX.ws.gyro_y = (int)(y > 32767 ? 32767 : y < -32767 ? -32767 : y);
}

/** Nintendo report 0x30: battery (byte 2) and motion (bytes 13..48: three samples of accel x y z, gyro x y z). */
static void hx_nintendo_extra(int src, const uint8_t *r, int len) {
    if (len < 3) return;
    int bat = (r[2] >> 4) & 0x0F;                      /* 0, 2, 4, 6, 8 (+1 = charging) */
    int level = bat >> 1;
    if (level != HX.battery[src]) { HX.battery[src] = level; hx_sayf("B %d %d %d", level, bat & 1, src); }
    /* motion: a single controller's, a pair's right Joy-Con (the hand that aims) */
    if (len >= 49 && (HX.nsrc == 1 || HX.S[src].pid == 0x2007)) {
        const uint8_t *p = r + 13 + 24;                /* the latest of the three samples */
        int nz = 0;
        for (int k = 0; k < 3; k++) {
            HX.acc[k] = (int16_t)(p[2 * k] | (p[2 * k + 1] << 8));
            HX.gyr[k] = (int16_t)(p[6 + 2 * k] | (p[7 + 2 * k] << 8));
            nz |= HX.acc[k] | HX.gyr[k];
        }
        if (nz) HX.imu_seen = 1;
        if (nz && HX.ws.gyro_stick) hx_gyro();
    }
}

/** dev build 17 (theme G): its own Home + Back held 5 s (whoever owns its Home) — Wayfinder's controls for it off, as
 *  the Thor's Home + Back turns the input layer off: plain buttons (a game's bg=1 kept), no motion aim, until "u" ("M 0").
 *  Nothing persisted: a new reader plays its controls again. Nothing held gets an edge ([hx_swap_map]). */
#define HX_MUTE_MS 5000
static void hx_mute(int on) {
    on = on != 0; if (on == HX.muted) return;
    HX.muted = on;
    if (on) {
        hx_tp_reset();                                           /* dev build 18f: no pointer while its controls are off */
        wf_map m; wf_map_reset(&m); m.back_free = HX.wm.back_free;
        HX.gyro_want = HX.ws.gyro_stick; hx_swap_map(&m);
        HX.ws.gyro_stick = 0; HX.ws.gyro_x = HX.ws.gyro_y = 0; hx_flush();
    } else HX.ws.gyro_stick = HX.gyro_want;                      /* its controls: the helper says them again ("P") */
    hx_sayf("M %d", on);
}
/** Once per hold ([hb_since] -1 = done for this one): "u" while both are still held (the app's own unmute when docked —
 *  the Thor's emergency covers it) must not mute it again at the next report. */
static void hx_mute_check(void) {
    if (!(HX.src_key[T_HO] && HX.src_key[T_BK])) { HX.hb_since = 0; return; }
    if (!HX.hb_since) HX.hb_since = HX.now_ms > 0 ? HX.now_ms : 1;
    if (HX.hb_since > 0 && HX.now_ms - HX.hb_since >= HX_MUTE_MS) { HX.hb_since = -1; hx_mute(1); }
}

static void hx_home(int wf);
/** A report from controller [src] ([r] as read: with its report id first when the controller uses ids). */
static void hx_report(int src, const uint8_t *r, int len) {
    if (src < 0 || src >= HX.nsrc || len <= 0 || HX.idle) return;
    int rid = (HX.S[src].ids_used || HX.S[src].nintendo || HX.S[src].sony) ? r[0] : 0;
    if (HX.S[src].nintendo && rid != 0x30) return;
    int any = 0;
    for (int i = HX.S[src].fstart; i < HX.S[src].fend; i++) {
        if (HX.F[i].rid != rid || (HX.F[i].bit + HX.F[i].bits + 7) / 8 > len) continue;
        HX.val[i] = hx_get(r, len, &HX.F[i]); HX.have[i] = 1; any = 1;
    }
    if (!any) return;
    /* the last report with fields (a Nintendo reply, an empty one don't count; a cut read is forgotten) */
    if (r != HX.last_rep) { if (len <= HX_MAXR) { memcpy(HX.last_rep, r, (size_t)len); HX.last_len = len; HX.last_src = src; } else HX.last_len = 0; }
    if (HX.S[src].nintendo) hx_nintendo_extra(src, r, len);
    hx_gestures(src);
    hx_touch(src, r, len);                         /* dev build 18f: the touchpad moves the pointer ("t 1") */
    if (HX.learn) { hx_learn(); return; }          /* learning: the controller drives nothing */
    /* SL + SR that turned it sideways must not reach the game as L1 + R1, nor a button held as learn mode ends */
    if (HX.quiet) {
        if (hx_held()) return;
        HX.quiet = 0;
    }
    /* the standard pad's state from the base mapping */
    int cur[NT];
    for (int t = 0; t < NT; t++) {
        int fi = HX.map_f[t];
        cur[t] = hx_printed(t) && fi >= 0 && fi < HX.nf && HX.have[fi] ? hx_pressed(fi, HX.val[fi], t) : 0;
    }
    /* a controller with no Home button (many generic pads): Select + Start held 0.6 s is its Home — Wayfinder's combos
       (Home + X…) reach it too. Until then both reach the game as usual; then they let go for the game and Home is held
       while both are; they stay silent until both are let go. */
    if (HX.map_f[T_HO] < 0 && HX.home_wf && HX.map_f[T_SE] >= 0 && HX.map_f[T_ST] >= 0) {
        int both = cur[T_SE] && cur[T_ST];
        if (both) {
            if (!HX.sub_since) HX.sub_since = HX.now_ms ? HX.now_ms : 1;
            if (!HX.sub_on && HX.now_ms - HX.sub_since >= 600) HX.sub_on = 1;
        } else {
            HX.sub_since = 0;
            if (!cur[T_SE] && !cur[T_ST]) HX.sub_on = 0;
        }
        if (HX.sub_on) { cur[T_HO] = both; cur[T_SE] = cur[T_ST] = 0; }
    }
    /* dev build 17 (theme G): L2 / R2 from a trigger's pull (an Xbox pad's LT / RT, a pressure) — [hx_trig_button] */
    cur[T_L2] = hx_trig_button(T_L2, T_LT, cur[T_L2]);
    cur[T_R2] = hx_trig_button(T_R2, T_RT, cur[T_R2]);
    /* Home first (dev build 14): a button in the same report is then pressed under it — a combo (J, withheld), as Home
       then the button. In enum order it came before Home: no combo, and Home's release went home. (The Thor's frames
       carry Home's code first too.) */
    if (cur[T_HO] != HX.src_key[T_HO]) {
        HX.src_key[T_HO] = cur[T_HO];
        /* Select + Start's Home, said as such: a modifier only — its release alone is no Home tap for the app (Start +
           Select held is a game's own combo too: RetroArch's menu, a soft reset) */
        if (HX.map_f[T_HO] < 0) hx_sayf("J %d %d %d", EV_KEY, WF_HOME, cur[T_HO]);
        hx_src_key(hx_printed(T_HO), cur[T_HO]);
    }
    if (HX.home_next >= 0 && !HX.src_key[T_HO] && !HX.sub_on) hx_home(HX.home_next);   /* "h" while it was held */
    /* dev build 17 (theme G): its Back next, for the same reason — held, it gates (a button in the same report is pressed
       under it). b18h (review): but a button let go in the report that presses Back is let go before it. Our pad writes it
       so (its buttons before KEY_BACK), and the app decides that Back from what it sees held then: counted as still held,
       that Back went with the game ([wf_state.back_hot], no gate) while the app took it as Wayfinder's. */
    if (cur[T_BK] && !HX.src_key[T_BK])
        for (int t = 0; t < NT; t++)
            if (t != T_HO && t != T_BK && hx_printed(t) && !cur[t] && HX.src_key[t]) { HX.src_key[t] = 0; hx_src_key(hx_printed(t), 0); }
    if (cur[T_BK] != HX.src_key[T_BK]) { HX.src_key[T_BK] = cur[T_BK]; hx_src_key(hx_printed(T_BK), cur[T_BK]); }
    /* dev build 17 (theme G): its AYN button — a line to the app ("A"), like the Thor's AYN (gpio-keys) never on the pad;
       before the D-pad, so an AYN + D-pad combo in one report is said in that order ("V") */
    int af = HX.map_f[T_AY];
    int ay = af >= 0 && af < HX.nf && HX.have[af] && hx_pressed(af, HX.val[af], T_AY);
    if (ay != HX.ayn) { HX.ayn = ay; hx_sayf("A %d", ay); }
    for (int t = 0; t < NT; t++) {
        if (t == T_HO || t == T_BK || !hx_printed(t)) continue;
        int v = cur[t];
        if (v != HX.src_key[t]) { HX.src_key[t] = v; hx_src_key(hx_printed(t), v); }
    }
    static const int at[6] = { T_LX, T_LY, T_RX, T_RY, T_LT, T_RT };
    static const int aa[6] = { WF_LX, WF_LY, WF_RX, WF_RY, WF_BRAKE, WF_GAS };
    for (int k = 0; k < 6; k++) {
        int t = at[k], fi = HX.map_f[t], v = 0;
        if (fi >= 0 && fi < HX.nf && HX.have[fi]) {
            if (t == T_LT || t == T_RT) v = hx_trig(fi, HX.val[fi]);
            else if (HX.F[fi].kind == K_AXIS) v = hx_stick(fi, HX.val[fi], HX.map_inv[t]);
        } else if (t == T_LT && HX.src_key[T_L2]) v = 32767;   /* a digital-only trigger (ZL / ZR): a full pull */
        else if (t == T_RT && HX.src_key[T_R2]) v = 32767;
        if (v != HX.src_abs[aa[k]]) { int old = HX.src_abs[aa[k]]; HX.src_abs[aa[k]] = v; hx_src_abs(aa[k], old, v); }
    }
    HX.recentre = 0;
    int du = HX.map_f[T_DU] >= 0 && HX.have[HX.map_f[T_DU]] && hx_pressed(HX.map_f[T_DU], HX.val[HX.map_f[T_DU]], T_DU);
    int dd = HX.map_f[T_DD] >= 0 && HX.have[HX.map_f[T_DD]] && hx_pressed(HX.map_f[T_DD], HX.val[HX.map_f[T_DD]], T_DD);
    int dl = HX.map_f[T_DL] >= 0 && HX.have[HX.map_f[T_DL]] && hx_pressed(HX.map_f[T_DL], HX.val[HX.map_f[T_DL]], T_DL);
    int dr = HX.map_f[T_DR] >= 0 && HX.have[HX.map_f[T_DR]] && hx_pressed(HX.map_f[T_DR], HX.val[HX.map_f[T_DR]], T_DR);
    int hx = dr - dl, hy = dd - du;
    if (hx != HX.src_abs[WF_HX]) { int old = HX.src_abs[WF_HX]; HX.src_abs[WF_HX] = hx; hx_src_abs(WF_HX, old, hx); }
    if (hy != HX.src_abs[WF_HY]) { int old = HX.src_abs[WF_HY]; HX.src_abs[WF_HY] = hy; hx_src_abs(WF_HY, old, hy); }
    hx_flush();
    hx_app_sticks(0);
    hx_mute_check();                               /* dev build 17 (theme G): Home + Back held 5 s */
}

/** Timers between reports (many HID pads report only on change, review 2026-10-03): Select + Start held long enough
 *  becomes Home without a new report, a stick given to Wayfinder sends its deferred position, learn mode ends after a
 *  minute without a change ("L 0"). [hx_wait] = how soon (ms) there's something to do, -1 nothing; [hx_tick] does it
 *  (HX.now_ms set). */
static int hx_wait(void) {
    int w = -1;
    if (HX.idle) return w;                         /* dev build 15: the controller away — nothing replayed, nothing due */
    if (HX.sub_since && !HX.sub_on && HX.last_len > 0) w = 50;
    for (int side = 0; side < 2; side++) {
        int x, y; wf_app_stick(&HX.ws, &HX.wm, side, &x, &y);
        if (x != HX.app_last[side][0] || y != HX.app_last[side][1]) w = 10;
    }
    /* dev build 17 (theme G): Home + Back held — its 5 s due (a pad reporting only on change sends nothing meanwhile) */
    if (HX.hb_since > 0 && !HX.muted) {
        long left = HX.hb_since + HX_MUTE_MS - HX.now_ms;
        int m = left < 0 ? 0 : left > HX_MUTE_MS ? HX_MUTE_MS : (int)left;
        if (w < 0 || m < w) w = m;
    }
    if (HX.learn) {
        long left = HX.learn_at + HX_LEARN_MS - HX.now_ms;
        int l = left < 0 ? 0 : left > HX_LEARN_MS ? HX_LEARN_MS : (int)left;
        if (w < 0 || l < w) w = l;
        /* dev build 16 (theme E): a hold due (a pad reporting only on change sends nothing while it is held) */
        for (int i = 0; i < HX.nf; i++) {
            long due = hx_learn_due(i); if (due < 0) continue;
            long hl = due - HX.now_ms; int h = hl < 0 ? 0 : hl > HX_LEARN_HOME_MS ? HX_LEARN_HOME_MS : (int)hl;
            if (w < 0 || h < w) w = h;
        }
    }
    /* dev build 18f: the touchpad — a tap's click to let go, pixels / notches held back by the rate limit (a pad that
       reports only on change, the test tool's among them, would otherwise keep them for ever) */
    { int t = hx_tp_wait(); if (t >= 0 && (w < 0 || t < w)) w = t; }
    return w;
}
static void hx_tick(void) {
    if (HX.idle) return;
    hx_tp_tick();                                  /* dev build 18f: the touchpad's timers */
    if (HX.sub_since && !HX.sub_on && HX.last_len > 0 && HX.now_ms - HX.sub_since >= 600)
        hx_report(HX.last_src, HX.last_rep, HX.last_len);   /* the same state again, as a pad reporting all the time */
    if (HX.learn) for (int i = 0; i < HX.nf; i++) {         /* dev build 16 (theme E): a hold, said once */
        long due = hx_learn_due(i);
        if (due < 0 || HX.now_ms < due) continue;
        HX.learn_said[i] = 1;
        if (i == HX.map_f[T_HO]) hx_sayf("L h %d", i); else hx_sayf("L k %d", i);
    }
    if (HX.learn && HX.now_ms - HX.learn_at >= HX_LEARN_MS) { hx_learn_stop(); if (hx_say) hx_say("L 0"); }
    hx_app_sticks(0);
    hx_mute_check();                               /* dev build 17 (theme G): Home + Back held 5 s, no new report */
}

/** Reset everything (a new process, or a test). */
static void hx_reset(void) {
    memset(&HX, 0, sizeof HX);
    HX.battery[0] = HX.battery[1] = -1; HX.gyro_sens = 10; HX.home_wf = 1; HX.home_next = -1; HX.last_lights = -1;
    hx_engine_init();
}

/** The Thor's face-button layout (the engine's "follow AYN"): an external button does what the Thor's does there.
 *  Only what comes out changes: the engine keeps buttons by position (dev build 14 — nothing held is let go, Home's
 *  gate and what it consumed stay). */
static void hx_face(int xbox) {
    if ((xbox != 0) == (HX.ws.xbox != 0)) return;
    HX.face_x = xbox != 0; HX.ws.xbox = HX.face_x;
    hx_flush();
}

/** A single Joy-Con upright (0) or sideways (1). Everything held is released first (a button changes meaning). */
static void hx_orient(int sideways) {
    if ((sideways != 0) == HX.sideways) return;
    hx_release_all();
    HX.sideways = sideways != 0;
    hx_apply_map();
    memset(HX.have_cen, 0, sizeof HX.have_cen);   /* the stick's axes trade places: calibrate afresh */
}

/* ---- Nintendo subcommands (output report 0x01): player lights, motion on ---------------------------------- */
/** The bytes of a subcommand [cmd] with one argument for a Nintendo controller ([pkt] its packet counter): written to
 *  its hidraw as an output report. The rumble part is neutral (160 Hz / 320 Hz at amplitude 0). Returns the length. */
static int hx_subcommand(uint8_t *out, int cap, int pkt, uint8_t cmd, uint8_t arg) {
    static const uint8_t neutral[8] = { 0x00, 0x01, 0x40, 0x40, 0x00, 0x01, 0x40, 0x40 };
    if (cap < 12) return 0;
    out[0] = 0x01; out[1] = (uint8_t)(pkt & 0x0F);
    memcpy(out + 2, neutral, 8);
    out[10] = cmd; out[11] = arg;
    return 12;
}
/** Player [n] (1..4) as Nintendo's lights: 1 ▮▯▯▯, 2 ▮▮▯▯, 3 ▮▮▮▯, 4 ▮▮▮▮ (0 = off). */
static uint8_t hx_player_lights(int n) { return n <= 0 ? 0 : n >= 4 ? 0x0F : (uint8_t)((1 << n) - 1); }

static int hx_printed(int t) { return t == T_HO && HX.home_wf ? WF_HOME : (t >= 0 && t < NT ? HX_PRINTED[t] : 0); }

/** Its Home: the Thor's (1) or the game's (0). Only where Home goes changes (dev build 14: nothing held is let go);
 *  while it is held (its own, or Select + Start's), at its release — a held Home never gets an edge nobody made. */
static void hx_home(int wf) {
    wf = wf != 0;
    HX.home_next = -1;
    if (wf == HX.home_wf) return;
    if (HX.src_key[T_HO] || HX.sub_on) { HX.home_next = wf; return; }
    HX.home_wf = wf;
    HX.sub_since = 0;                          /* Select + Start: counted afresh under the new owner */
}

/* ---- dev build 15 (C4, C5): the controller away and back; our pad down and up ----------------------------------- */
/** The controller went away (wfhid "k 1": its pad stays, at rest, so games keep it and its place). What it held is let
 *  go the way a report would (Wayfinder's own outputs "W … 0", what its Home withheld "J … 0"; Home last: turning a
 *  DualSense off is a long PS hold, its end no Home tap); learn mode pauses ("L 0": the wizard shows Paused). Nothing
 *  more until it is back ([hx_attach_begin] / [hx_attach_end]). */
static void hx_detach(void) {
    if (HX.idle) return;
    if (HX.learn) { hx_learn_stop(); hx_sayf("L 0"); }
    /* dev build 15 (review N1): the shift button held alone as it goes is no lone press — no tap for the game on the
       kept pad (its release still says "J … 0" to Wayfinder), as Home's end is no Home tap */
    HX.ws.shift_used = 1;
    for (int t = 0; t < NT; t++)
        if (t != T_HO && HX.src_key[t]) { HX.src_key[t] = 0; hx_src_key(hx_printed(t), 0); }
    static const int aa[8] = { WF_LX, WF_LY, WF_RX, WF_RY, WF_BRAKE, WF_GAS, WF_HX, WF_HY };
    for (int k = 0; k < 8; k++)
        if (HX.src_abs[aa[k]]) { int old = HX.src_abs[aa[k]]; HX.src_abs[aa[k]] = 0; hx_src_abs(aa[k], old, 0); }
    if (HX.src_key[T_HO]) {
        HX.src_key[T_HO] = 0;
        if (HX.map_f[T_HO] < 0) hx_sayf("J %d %d 0", EV_KEY, WF_HOME);   /* Select + Start's Home, said as such */
        hx_src_key(hx_printed(T_HO), 0);
    }
    HX.ws.gyro_x = HX.ws.gyro_y = 0;               /* motion aim: the stick it moved back to rest */
    hx_release_all();                              /* the rest: toggles, Wayfinder's virtual presses, the engine at rest */
    if (HX.home_next >= 0) hx_home(HX.home_next);   /* "h" while its Home was held */
    if (HX.sh_prev) { HX.sh_prev = 0; hx_sayf("G sh 0"); }
    hx_app_sticks(0);                              /* a stick given to Wayfinder: back to 0 ("T") */
    HX.slsr_since = 0; HX.slsr_sent = 0;
    HX.last_len = 0; HX.quiet = 0; HX.idle = 1;
}

/** The layout's signature: what the base mapping and the user's own refer to — the controllers (ids, kind) and every
 *  field (kind, page, usage, size, range), NOT where a field sits (report id, bit): a DualSense over USB and over
 *  Bluetooth sign the same. (64-bit FNV-1a.) */
static void hx_mix(uint64_t *h, int32_t v) {
    for (int k = 0; k < 4; k++) { *h ^= (uint8_t)((uint32_t)v >> (8 * k)); *h *= 1099511628211ULL; }
}
static uint64_t hx_sig(void) {
    uint64_t h = 1469598103934665603ULL;
    hx_mix(&h, HX.nsrc); hx_mix(&h, HX.nf);
    for (int s = 0; s < HX.nsrc; s++) { hx_mix(&h, HX.S[s].vid); hx_mix(&h, HX.S[s].pid); hx_mix(&h, HX.S[s].nintendo); hx_mix(&h, HX.S[s].sony); }
    for (int i = 0; i < HX.nf; i++) {
        const hx_field *f = &HX.F[i];
        hx_mix(&h, f->kind); hx_mix(&h, f->src); hx_mix(&h, f->page); hx_mix(&h, f->usage);
        hx_mix(&h, f->bits); hx_mix(&h, f->lmin); hx_mix(&h, f->lmax);
    }
    return h;
}

/* the layout it had, kept aside while it comes back */
static struct { int armed; hx_field F[HX_MAXF]; int nf, nsrc; __typeof__(HX.S) S; uint64_t sig; } HX_BAK;

/** The controller is back (wfhid "a"): its old layout kept aside, what belongs to the connection cleared (fields,
 *  values, stick centres, learn state, battery, the last report, gestures, motion) — then its sources are read again
 *  ([hx_add_source_bus] for each) and [hx_attach_end] decides. What the user and the app set stays: the pad's state (at
 *  rest), the game's profile, the face layout, its Home's owner, the user's mappings, held sideways, motion aim and the
 *  sensors on, the lights. -1 if it isn't away. */
static int hx_attach_begin(void) {
    if (!HX.idle) return -1;
    memcpy(HX_BAK.F, HX.F, sizeof HX.F); HX_BAK.nf = HX.nf; HX_BAK.nsrc = HX.nsrc; memcpy(HX_BAK.S, HX.S, sizeof HX.S);
    HX_BAK.sig = hx_sig(); HX_BAK.armed = 1;
    HX.nf = 0; HX.nsrc = 0; memset(HX.S, 0, sizeof HX.S);
    memset(HX.val, 0, sizeof HX.val); memset(HX.have, 0, sizeof HX.have);
    memset(HX.cen, 0, sizeof HX.cen); memset(HX.lo, 0, sizeof HX.lo); memset(HX.hi, 0, sizeof HX.hi);
    memset(HX.have_cen, 0, sizeof HX.have_cen); HX.recentre = 0;
    memset(HX.learn_val, 0, sizeof HX.learn_val); memset(HX.learn_prev, 0, sizeof HX.learn_prev);
    memset(HX.learn_away, 0, sizeof HX.learn_away); memset(HX.learn_have, 0, sizeof HX.learn_have);
    memset(HX.learn_down, 0, sizeof HX.learn_down); memset(HX.learn_said, 0, sizeof HX.learn_said);
    memset(HX.learn_again, 0, sizeof HX.learn_again); memset(HX.learn_heard, 0, sizeof HX.learn_heard);
    HX.battery[0] = HX.battery[1] = -1;
    HX.last_src = 0; HX.last_len = 0;
    HX.sh_prev = 0; HX.slsr_since = 0; HX.slsr_sent = 0; HX.sub_since = 0; HX.sub_on = 0;
    HX.imu_seen = 0; memset(HX.acc, 0, sizeof HX.acc); memset(HX.gyr, 0, sizeof HX.gyr);
    memset(HX.gbias, 0, sizeof HX.gbias); HX.gbias_n = 0;
    memset(HX.app_last, 0, sizeof HX.app_last); memset(HX.app_at, 0, sizeof HX.app_at);
    return 0;
}

/** The controller back has the ids it had: as many sources, each with its vendor and product. */
static int hx_same_ids(void) {
    if (!HX_BAK.armed || HX.nsrc != HX_BAK.nsrc) return 0;
    for (int s = 0; s < HX.nsrc; s++) if (HX.S[s].vid != HX_BAK.S[s].vid || HX.S[s].pid != HX_BAK.S[s].pid) return 0;
    return 1;
}

/** After [hx_attach_begin] and its sources: [ok] they were all opened, with the same ids. The same layout ([hx_sig]):
 *  its mapping again (the user's own names the same fields), the lines that announce it ("I", "F", "D"), and it drives
 *  the pad again — quiet: a button held as it comes back (the press that woke it) reaches nothing until all is let go.
 *  Else the layout it had comes back and it stays away: -1 not opened / other ids, -2 another layout. */
static int hx_attach_end(int ok) {
    if (!HX_BAK.armed || !HX.idle) return -1;
    HX_BAK.armed = 0;
    int r = !ok ? -1 : (HX.nf == 0 || hx_sig() != HX_BAK.sig) ? -2 : 0;
    if (r) {
        memcpy(HX.F, HX_BAK.F, sizeof HX.F); HX.nf = HX_BAK.nf; HX.nsrc = HX_BAK.nsrc; memcpy(HX.S, HX_BAK.S, sizeof HX.S);
        return r;
    }
    hx_apply_map();
    for (int s = 0; s < HX.nsrc; s++) hx_print_source(s);
    hx_print_map();
    HX.quiet = 1; HX.idle = 0;
    return 0;
}

/** Our pad taken down for a moment (wfhid "n 0": made again in place, for the player order — C5). The engine keeps
 *  running on the controller's reports, nothing is written ([hx_flush]). Up again ("n 1", a new device): it gets the
 *  whole state at once — what is held stays held. */
static void hx_pad_down(void) { HX.pad_down = 1; }
static void hx_pad_up(void) { HX.pad_down = 0; HX.have_out = 0; hx_flush(); }

/** The pad's name as uinput keeps it (79 bytes): control characters become spaces; a longer name is cut on a UTF-8
 *  character boundary, its " (Wayfinder)" ending kept (AYN's hold list and the input layer know our pads by it).
 *  Dev build 15: a long non-ASCII name was refused ("E usage", exit 2 — that controller never got a reader). */
#define HX_NAME_MAX 79
static void hx_pad_name(char out[HX_NAME_MAX + 1], const char *in) {
    static const char suf[] = " (Wayfinder)";
    size_t n = strlen(in), k = sizeof suf - 1;
    int keep_suf = n > HX_NAME_MAX && wf_wayfinder_name(in);
    size_t keep = keep_suf ? n - k : n, room = keep_suf ? HX_NAME_MAX - k : HX_NAME_MAX;
    if (keep > room) { keep = room; while (keep > 0 && ((unsigned char)in[keep] & 0xC0) == 0x80) keep--; }
    memcpy(out, in, keep);
    if (keep_suf) { memcpy(out + keep, suf, k); keep += k; }
    out[keep] = 0;
    for (char *c = out; *c; c++) if ((unsigned char)*c < 0x20 || *c == 0x7f) *c = ' ';
}

/* ---- rumble (Nintendo: HD rumble data, 4 bytes per Joy-Con) --------------------------------------------- */
/** Amplitude 0..1 → the documented encoding (high band amp 0x00..0xC8, low band 0x40..0x72), at the neutral
 *  frequencies 320 Hz (high) / 160 Hz (low). No libm: log2 from a small table (amplitude steps of 1/16). */
static void hx_rumble_bytes(uint8_t out[4], int lo_amp, int hi_amp) {
    /* encoded amplitude for a = k/16, k = 0..16: round(log2(a*8.7)*32) above 0.23, round(log2(a*17)*16) above
       0.12, a small step below; 0 = silent */
    static const uint8_t ENC[17] = { 0, 8, 17, 27, 36, 46, 55, 62, 68, 73, 78, 83, 87, 90, 94, 97, 100 };
    int kl = lo_amp * 16 / 65535, kh = hi_amp * 16 / 65535;
    if (kl < 0) kl = 0;
    if (kl > 16) kl = 16;
    if (kh < 0) kh = 0;
    if (kh > 16) kh = 16;
    int hf = 0x0100, lf = 0x40;                 /* 320 Hz, 160 Hz */
    int hamp = ENC[kh] * 2, lamp = ENC[kl] / 2 + 0x40;
    if (!kl) lamp = 0x40;
    out[0] = (uint8_t)(hf & 0xFF);
    out[1] = (uint8_t)(hamp + ((hf >> 8) & 0xFF));
    out[2] = (uint8_t)(lf + ((lamp >> 8) & 0xFF));
    out[3] = (uint8_t)(lamp & 0xFF);
}
/** Output report 0x10 (rumble only) for a Nintendo controller: [pkt] its packet counter. Both halves the same
 *  (a Joy-Con uses its own half; a Pro Controller both). Returns the length. */
static int hx_rumble_report(uint8_t *out, int cap, int pkt, int lo_amp, int hi_amp) {
    if (cap < 10) return 0;
    out[0] = 0x10; out[1] = (uint8_t)(pkt & 0x0F);
    hx_rumble_bytes(out + 2, lo_amp, hi_amp);
    memcpy(out + 6, out + 2, 4);
    return 10;
}

/* ---- rumble for PlayStation / Xbox pads (their documented output reports) ------------------------------------ */
/** CRC-32 (IEEE, reflected): PlayStation Bluetooth output reports end with one over 0xA2 + the report. Chainable:
 *  hx_crc32(hx_crc32(0, a, n), b, m) = the CRC of a then b. */
static uint32_t hx_crc32(uint32_t crc, const uint8_t *p, int n) {
    crc = ~crc;
    while (n-- > 0) { crc ^= *p++; for (int k = 0; k < 8; k++) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u))); }
    return ~crc;
}
static void hx_ps_bt_crc(uint8_t *b, int len) {          /* the last 4 bytes */
    static const uint8_t a2 = 0xA2;
    uint32_t c = hx_crc32(hx_crc32(0, &a2, 1), b, len - 4);
    b[len - 4] = (uint8_t)c; b[len - 3] = (uint8_t)(c >> 8); b[len - 2] = (uint8_t)(c >> 16); b[len - 1] = (uint8_t)(c >> 24);
}
/** The rumble output report for controller [src]: [lo] the strong (low) motor, [hi] the weak (high) one, 0..65535.
 *  [pkt] a counter (Nintendo packet number, DualSense sequence). Returns the length (0 = it can't rumble). */
static int hx_rumble_out(int src, uint8_t *b, int cap, int pkt, int lo, int hi) {
    if (src < 0 || src >= HX.nsrc || cap < 78) return 0;
    uint8_t strong = (uint8_t)(lo >> 8), weak = (uint8_t)(hi >> 8);
    switch (HX.S[src].rumble) {
    case HX_RUMBLE_NINTENDO: return hx_rumble_report(b, cap, pkt, lo, hi);
    case HX_RUMBLE_DS4:
        if (!HX.S[src].bt) { memset(b, 0, 32); b[0] = 0x05; b[1] = 0x01; b[4] = weak; b[5] = strong; return 32; }
        memset(b, 0, 78); b[0] = 0x11; b[1] = 0xC0; b[3] = 0x01; b[6] = weak; b[7] = strong;
        hx_ps_bt_crc(b, 78); return 78;
    case HX_RUMBLE_DUALSENSE:
        if (!HX.S[src].bt) { memset(b, 0, 63); b[0] = 0x02; b[1] = 0x03; b[3] = weak; b[4] = strong; return 63; }
        memset(b, 0, 78); b[0] = 0x31; b[1] = (uint8_t)((pkt & 0x0F) << 4); b[2] = 0x10; b[3] = 0x03; b[5] = weak; b[6] = strong;
        hx_ps_bt_crc(b, 78); return 78;
    case HX_RUMBLE_XBOX:                                  /* enable weak + strong, magnitudes in %, ~2.5 s, refreshed */
        memset(b, 0, 9); b[0] = 0x03; b[1] = 0x03; b[4] = (uint8_t)(lo * 100 / 65535); b[5] = (uint8_t)(hi * 100 / 65535);
        b[6] = 0xFF; return 9;
    }
    return 0;
}

/** Player lights for a PlayStation pad, player [n] (1..4; 0 = the first pattern off / no change for a DS4): DualSense —
 *  its player LEDs (the console's patterns: centre, two, three, four); DualShock 4 — its lightbar in the player colours
 *  (blue, red, green, pink). Only the light flag is set: rumble and the rest stay as they are. Returns the length. */
static int hx_lights_out(int src, uint8_t *b, int cap, int pkt, int n) {
    if (src < 0 || src >= HX.nsrc || cap < 78 || n < 0) return 0;
    static const uint8_t PLED[5] = { 0x00, 0x04, 0x0a, 0x15, 0x1b };
    static const uint8_t RGB[5][3] = { { 0, 0, 0 }, { 0x00, 0x00, 0x40 }, { 0x40, 0x00, 0x00 }, { 0x00, 0x40, 0x00 }, { 0x20, 0x00, 0x20 } };
    int k = n > 4 ? 4 : n;
    switch (HX.S[src].rumble) {
    case HX_RUMBLE_DUALSENSE:
        if (!HX.S[src].bt) { memset(b, 0, 63); b[0] = 0x02; b[2] = 0x10; b[44] = PLED[k]; return 63; }
        memset(b, 0, 78); b[0] = 0x31; b[1] = (uint8_t)((pkt & 0x0F) << 4); b[2] = 0x10; b[4] = 0x10; b[46] = PLED[k];
        hx_ps_bt_crc(b, 78); return 78;
    case HX_RUMBLE_DS4:
        if (!k) return 0;
        if (!HX.S[src].bt) { memset(b, 0, 32); b[0] = 0x05; b[1] = 0x02; memcpy(b + 6, RGB[k], 3); return 32; }
        memset(b, 0, 78); b[0] = 0x11; b[1] = 0xC0; b[3] = 0x02; memcpy(b + 8, RGB[k], 3);
        hx_ps_bt_crc(b, 78); return 78;
    }
    return 0;
}

#endif
