// wfhid_test — unit tests of the external controllers' logic (fx/wfhid_core.h), run ON the Thor:
//   tools/build_wfhid.sh test   → pushes and runs /data/local/tmp/wfhid_test
// Real data: the SteelSeries Nimbus+'s report descriptor and Joy-Con (R) reports captured on a Thor (2026-10-02).
#include <stdio.h>
#include "wfhid_core.h"

static int fails = 0, checks = 0;
#define CHECK(cond, ...) do { checks++; if (!(cond)) { fails++; printf("FAIL %s:%d ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

/* what our pad shows (the events wfhid would write; KEYEV: how many for each key — its edges) and the lines it would say */
static int KEY[WF_NK], KEYEV[WF_NK], ABSV[WF_NA], ABSEV[WF_NA], syncs;
/* b18h: the order of the key events written (KEYSEQ: the number of each key's last event) */
static int KEYSEQ[WF_NK], keyseq;
/* b18l: every event written, in order (type, code, value), since the last nevt = 0 — [rep_of] */
static int EVT[1024][3], nevt;
static char LINES[256][256]; static int nlines;
static void emit(int type, int code, int value) {
    if (nevt < 1024) { EVT[nevt][0] = type; EVT[nevt][1] = code; EVT[nevt][2] = value; nevt++; }
    if (type == EV_KEY && code < WF_NK) { KEY[code] = value; KEYEV[code]++; KEYSEQ[code] = ++keyseq; }
    if (type == EV_ABS && code < WF_NA) { ABSV[code] = value; ABSEV[code]++; }
    if (type == EV_SYN) syncs++;
}
/* b18l: the report (0, 1, … — SYN_REPORTs before it in the log) holding that event, -1 if none; how many events a report
   holds (its SYN not counted); how many reports the log holds */
static int rep_of(int type, int code, int value) {
    for (int i = 0, r = 0; i < nevt; i++) {
        if (EVT[i][0] == EV_SYN) { r++; continue; }
        if (EVT[i][0] == type && EVT[i][1] == code && EVT[i][2] == value) return r;
    }
    return -1;
}
static int rep_size(int rep) {
    int n = 0;
    for (int i = 0, r = 0; i < nevt; i++) { if (EVT[i][0] == EV_SYN) r++; else if (r == rep) n++; }
    return n;
}
static int reps(void) { int n = 0; for (int i = 0; i < nevt; i++) n += EVT[i][0] == EV_SYN; return n; }
/* b18l review — docked, or the merged seat: wfpad's engine (D) reads our pad as its loop does — the events written since
   nevt = 0, wf_event each (DJ: its echoes, the "J" lines wfpad says), wf_compute at each SYN_REPORT (DE / DNE: the copy's
   events, [wf_frame_events]) */
static wf_state D; static wf_map DM; static wf_out DO;
static struct input_event DE[256]; static int DNE, DJ;
static void dock_init(void) {
    memset(&D, 0, sizeof D); wf_map_reset(&DM);
    D.nk = HX.ws.nk; memcpy(D.keys, HX.ws.keys, sizeof D.keys);
    D.na = HX.ws.na; memcpy(D.axes, HX.ws.axes, sizeof D.axes); memcpy(D.info, HX.ws.info, sizeof D.info);
    wf_state_rest(&D); wf_compute(&D, &DM, &DO);
}
static void dock_feed(void) {
    DNE = DJ = 0;
    for (int i = 0; i < nevt; i++) {
        if (EVT[i][0] != EV_SYN) { int p; DJ += wf_event(&D, EVT[i][0], EVT[i][1], EVT[i][2], &p); continue; }
        wf_out c = DO; wf_compute(&D, &DM, &DO);
        DNE += wf_frame_events(&c, &DO, D.keys, D.nk, D.axes, D.na, 0, DE + DNE, 256 - DNE);
    }
}
static int de_is(int i, int type, int code, int value) { return i < DNE && DE[i].type == type && DE[i].code == code && DE[i].value == value; }
static int de_syn(int i) { return i < DNE && DE[i].type == EV_SYN && DE[i].code == SYN_REPORT; }
static void say(const char *l) { if (nlines < 256) snprintf(LINES[nlines++], 256, "%s", l); }
static int said(const char *prefix) {
    for (int i = 0; i < nlines; i++) if (!strncmp(LINES[i], prefix, strlen(prefix))) return 1;
    return 0;
}
/* dev build 17: a line starting with [prefix] that holds [part] */
static int saidhas(const char *prefix, const char *part) {
    for (int i = 0; i < nlines; i++) if (!strncmp(LINES[i], prefix, strlen(prefix)) && strstr(LINES[i], part)) return 1;
    return 0;
}
static void fresh(void) {
    hx_reset(); hx_emit = emit; hx_say = say;
    memset(KEY, 0, sizeof KEY); memset(KEYEV, 0, sizeof KEYEV); memset(ABSV, 0, sizeof ABSV); memset(ABSEV, 0, sizeof ABSEV);
    nlines = 0; syncs = 0;
}

static const uint8_t NIMBUS[] = { 0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x09, 0x05, 0xa1, 0x02, 0x15, 0x81, 0x25, 0x7f, 0x35, 0x81,
    0x45, 0x7f, 0x75, 0x08, 0x09, 0x01, 0xa1, 0x00, 0x95, 0x04, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x81, 0x02,
    0xc0, 0x15, 0x00, 0x26, 0xff, 0x00, 0x35, 0x00, 0x46, 0xff, 0x00, 0x95, 0x02, 0x05, 0x09, 0x09, 0x07, 0x09, 0x08, 0x81,
    0x02, 0x75, 0x01, 0x25, 0x01, 0x45, 0x01, 0x95, 0x04, 0x05, 0x01, 0x09, 0x90, 0x09, 0x92, 0x09, 0x91, 0x09, 0x93, 0x81,
    0x02, 0x95, 0x06, 0x05, 0x09, 0x19, 0x01, 0x29, 0x06, 0x81, 0x02, 0x95, 0x02, 0x09, 0x09, 0x09, 0x0a, 0x81, 0x02, 0x95,
    0x01, 0x05, 0x0c, 0x0a, 0x23, 0x02, 0x81, 0x02, 0x0a, 0x09, 0x02, 0x81, 0x02, 0x0a, 0x04, 0x02, 0x81, 0x02, 0x81, 0x03,
    0x95, 0x04, 0x05, 0x08, 0x1a, 0x00, 0xff, 0x2a, 0x03, 0xff, 0x91, 0x02, 0x91, 0x03, 0xc0, 0xc0 };

/* a Nimbus+ report (8 bytes): sticks −127..127, L2 / R2 0..255, then bits from 48: D-pad up right down left,
   buttons 1..6, 9, 10, Home, Start (0x209), Select (0x204) */
static void nimbus(int lx, int ly, int rx, int ry, int l2, int r2, uint32_t bits) {
    uint8_t r[8] = { (uint8_t)(int8_t)lx, (uint8_t)(int8_t)ly, (uint8_t)(int8_t)rx, (uint8_t)(int8_t)ry, (uint8_t)l2, (uint8_t)r2,
                     (uint8_t)(bits & 0xFF), (uint8_t)(bits >> 8) };
    hx_report(0, r, 8);
}
enum { NB_UP = 1, NB_RIGHT = 2, NB_DOWN = 4, NB_LEFT = 8, NB_1 = 16, NB_2 = 32, NB_3 = 64, NB_4 = 128, NB_5 = 256, NB_6 = 512,
       NB_9 = 1024, NB_10 = 2048, NB_HOME = 4096, NB_START = 8192, NB_SELECT = 16384 };

/* an Xbox pad's older "DirectInput" layout over Bluetooth (SDL's controller database, not captured): X Y Z Rx Ry Rz
   16-bit, buttons 1..10, a hat (1..8, null) */
static const uint8_t XOLD[] = { 0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x15, 0x00, 0x27, 0xff, 0xff, 0x00, 0x00, 0x75, 0x10, 0x95, 0x06,
    0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x33, 0x09, 0x34, 0x09, 0x35, 0x81, 0x02,
    0x05, 0x09, 0x19, 0x01, 0x29, 0x0a, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0a, 0x81, 0x02, 0x75, 0x06, 0x95, 0x01, 0x81, 0x03,
    0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, 0x75, 0x04, 0x95, 0x01, 0x81, 0x03, 0xc0 };

/* Joy-Con report 0x30 (49 bytes): buttons bytes 3..5, sticks 12-bit */
static uint8_t JC[49];
static void jc(int src, uint8_t b3, uint8_t b4, uint8_t b5, int lx, int ly, int rx, int ry) {
    memset(JC, 0, sizeof JC);
    JC[0] = 0x30; JC[2] = 0x8e; JC[3] = b3; JC[4] = b4; JC[5] = b5;
    JC[6] = (uint8_t)(lx & 0xFF); JC[7] = (uint8_t)((lx >> 8) | ((ly & 0xF) << 4)); JC[8] = (uint8_t)(ly >> 4);
    JC[9] = (uint8_t)(rx & 0xFF); JC[10] = (uint8_t)((rx >> 8) | ((ry & 0xF) << 4)); JC[11] = (uint8_t)(ry >> 4);
    hx_report(src, JC, 49);
}

/* dev build 17 (theme G): a DualSense over USB (report 1): [face] □ 1 ✕ 2 ○ 4 △ 8 · [b9] L1 1 R1 2 L2 4 R2 8 Create 16
   Options 32 L3 64 R3 128 · [b10] PS 1, the touchpad click 2, Mute 4 · [hat] 0 up, clockwise, 8 none */
static uint8_t DSU[64];
static void dsu(int face, int b9, int b10, int hat) {
    memset(DSU, 0, sizeof DSU); DSU[0] = 0x01; DSU[1] = DSU[2] = DSU[3] = DSU[4] = 128;
    DSU[8] = (uint8_t)((hat & 0x0F) | (face << 4)); DSU[9] = (uint8_t)b9; DSU[10] = (uint8_t)b10;
    hx_report(0, DSU, 64);
}
/* the test tool's synthetic Xbox pad ("xbox": XOLD with an 11th button, the Xbox one): LT / RT in percent of the
   pull, [btn] its buttons (bit 10 = the Xbox button) */
static uint8_t XR[15];
static void xrep(int lt, int rt, int btn) {
    memset(XR, 0, sizeof XR); XR[1] = XR[3] = XR[7] = XR[9] = 0x80;
    int l = lt * 65535 / 100, r = rt * 65535 / 100;
    XR[4] = (uint8_t)l; XR[5] = (uint8_t)(l >> 8); XR[10] = (uint8_t)r; XR[11] = (uint8_t)(r >> 8);
    XR[12] = (uint8_t)btn; XR[13] = (uint8_t)((btn >> 8) & 0x07);
    hx_report(0, XR, 15);
}
/* the test tool's "xs" (dev build 17): an Xbox Series pad over Bluetooth — SYNTHETIC, the layout of public descriptor dumps:
   report 1 = X Y Z Rz 16-bit, Simulation Brake / Accelerator 10-bit, a hat, buttons 1..15, Consumer Record (its Share);
   17 bytes */
static const uint8_t XS[] = { 0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x85, 0x01,
    0x09, 0x01, 0xa1, 0x00, 0x09, 0x30, 0x09, 0x31, 0x15, 0x00, 0x27, 0xff, 0xff, 0x00, 0x00, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02, 0xc0,
    0x09, 0x01, 0xa1, 0x00, 0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x27, 0xff, 0xff, 0x00, 0x00, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02, 0xc0,
    0x05, 0x02, 0x09, 0xc5, 0x15, 0x00, 0x26, 0xff, 0x03, 0x75, 0x0a, 0x95, 0x01, 0x81, 0x02, 0x75, 0x06, 0x95, 0x01, 0x81, 0x03,
    0x05, 0x02, 0x09, 0xc4, 0x15, 0x00, 0x26, 0xff, 0x03, 0x75, 0x0a, 0x95, 0x01, 0x81, 0x02, 0x75, 0x06, 0x95, 0x01, 0x81, 0x03,
    0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, 0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
    0x05, 0x09, 0x19, 0x01, 0x29, 0x0f, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0f, 0x81, 0x02, 0x75, 0x01, 0x95, 0x01, 0x81, 0x03,
    0x05, 0x0c, 0x0a, 0xb2, 0x00, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02, 0x75, 0x07, 0x95, 0x01, 0x81, 0x03,
    0xc0 };
/* a line wfhid's logic says, well formed (the helper's patterns: A / M exactly, V three numbers) */
static int well_formed(const char *l) {
    int a, b, c; char e;
    if (l[0] == 'A') return !strcmp(l, "A 0") || !strcmp(l, "A 1");
    if (l[0] == 'M') return !strcmp(l, "M 0") || !strcmp(l, "M 1");
    if (l[0] == 'V') return sscanf(l, "V %d %d %d%c", &a, &b, &c, &e) == 3 && (a == EV_KEY || a == EV_ABS) && b >= 0;
    /* b18o: "TU <code> <1|0> <ms>" — a button the app names, a time (ExtPads' TU_LINE) */
    if (l[0] == 'T' && l[1] == 'U') { long t; return sscanf(l, "TU %d %d %ld%c", &a, &b, &t, &e) == 3 && a >= WF_APP_LO && a <= WF_APP_HI && (b == 0 || b == 1) && t >= 0; }
    return l[0] && strchr("IFDCLOBJWTG", l[0]) && (l[1] == ' ' || (l[0] == 'D' && !l[1])) && !strchr(l, '\n');
}

static unsigned rnd_state = 12345;
static unsigned rnd(void) { rnd_state = rnd_state * 1103515245u + 12345u; return rnd_state >> 8; }

/* dev build 18f: PlayStation reports with touch data, our own builders of the four layouts — DualSense USB (0x01, points
   at 33 / 37) and Bluetooth (0x31, 34 / 38); DualShock 4 USB (0x01, count at 33, touch reports of 9 bytes from 34) and
   Bluetooth (0x11, count at 35, from 36). The rest at rest; [click] = the touchpad pressed (usage 14) */
enum { TK_DSU, TK_DSB, TK_D4U, TK_D4B };
typedef struct { int on0, id0, x0, y0, on1, id1, x1, y1; } tsample;
static void tpt(uint8_t *b, int on, int id, int x, int y) {
    b[0] = (uint8_t)((on ? 0 : 0x80) | (id & 0x7F)); b[1] = (uint8_t)(x & 0xFF);
    b[2] = (uint8_t)(((x >> 8) & 0x0F) | ((y & 0x0F) << 4)); b[3] = (uint8_t)((y >> 4) & 0xFF);
}
static uint8_t TPR[80];
/** A report of kind [k] with touch samples [s] ([ns]: a DualSense takes s[0]; a DualShock 4 1..4, oldest first, its count
 *  byte = [ns]). Its full length. */
static int tp_build(int k, int click, const tsample *s, int ns) {
    uint8_t *r = TPR; int len, b0, ck, pt, cnt = -1;
    memset(r, 0, sizeof TPR);
    switch (k) {
    case TK_DSU: r[0] = 0x01; len = 64; b0 = 1; r[8] = 0x08; ck = 10; pt = 33; break;
    case TK_DSB: r[0] = 0x31; len = 78; b0 = 2; r[9] = 0x08; ck = 11; pt = 34; break;
    case TK_D4U: r[0] = 0x01; len = 64; b0 = 1; r[5] = 0x08; ck = 7; pt = 34; cnt = 33; break;
    default:     r[0] = 0x11; len = 78; b0 = 3; r[7] = 0x08; ck = 9; pt = 36; cnt = 35; break;
    }
    for (int a = 0; a < 4; a++) r[b0 + a] = 128;
    if (click) r[ck] |= 0x02;
    if (cnt < 0) { tpt(r + pt, s[0].on0, s[0].id0, s[0].x0, s[0].y0); tpt(r + pt + 4, s[0].on1, s[0].id1, s[0].x1, s[0].y1); }
    else {
        r[cnt] = (uint8_t)ns;
        for (int i = 0; i < ns && pt + 9 * i + 9 <= len; i++) {
            r[pt + 9 * i] = (uint8_t)(i * 3);                                        /* its timestamp (unused) */
            tpt(r + pt + 9 * i + 1, s[i].on0, s[i].id0, s[i].x0, s[i].y0); tpt(r + pt + 9 * i + 5, s[i].on1, s[i].id1, s[i].x1, s[i].y1);
        }
    }
    return len;
}
static int TK = TK_DSU, TID = 1;
static void at(long t) { HX.now_ms = HX.ws.now_ms = t; }
static void tsend(int click, int on0, int x0, int y0, int on1, int x1, int y1) {
    tsample s = { on0, TID, x0, y0, on1, TID + 1, x1, y1 };
    hx_report(0, TPR, tp_build(TK, click, &s, 1));
}
static void f1(long t, int x, int y) { at(t); tsend(0, 1, x, y, 0, 0, 0); }                /* one finger */
static void fc(long t, int x, int y) { at(t); tsend(1, 1, x, y, 0, 0, 0); }                /* one finger, pressed */
static void f2(long t, int x0, int y0, int x1, int y1) { at(t); tsend(0, 1, x0, y0, 1, x1, y1); }   /* two fingers */
static void up(long t) { at(t); tsend(0, 0, 0, 0, 0, 0, 0); }                               /* lifted */
static void tp_source(int k) {
    fresh(); TK = k; TID = 1;
    hx_add_source_bus(0, (k == TK_DSU || k == TK_D4U) ? 3 : 5, 0x054c, k <= TK_DSB ? 0x0ce6 : 0x09cc, 0, 0); hx_apply_map();
    hx_touch_on(1); at(10000);
}
/** The "H m" lines said: how many, their sums. */
static int hm(int *dx, int *dy) {
    int n = 0, x, y; *dx = *dy = 0;
    for (int i = 0; i < nlines; i++) if (sscanf(LINES[i], "H m %d %d", &x, &y) == 2) { n++; *dx += x; *dy += y; }
    return n;
}
static int hw(int *v, int *h) {
    int n = 0, a, b; *v = *h = 0;
    for (int i = 0; i < nlines; i++) if (sscanf(LINES[i], "H w %d %d", &a, &b) == 2) { n++; *v += a; *h += b; }
    return n;
}
/** A touchpad line well formed: "H m dx dy" (≤ 2000 each way, not both 0), "H b 1|0", "H w v h" (≤ 10, not both 0). */
static int h_well_formed(const char *l) {
    int a, b; char e;
    if (sscanf(l, "H m %d %d%c", &a, &b, &e) == 2) return (a || b) && abs(a) <= 2000 && abs(b) <= 2000;
    if (sscanf(l, "H w %d %d%c", &a, &b, &e) == 2) return (a || b) && abs(a) <= 10 && abs(b) <= 10;
    return !strcmp(l, "H b 1") || !strcmp(l, "H b 0");
}
static uint64_t hmix(uint64_t h, const char *s) {
    for (; *s; s++) { h ^= (uint8_t)*s; h *= 1099511628211ULL; }
    h ^= '\n'; h *= 1099511628211ULL; return h;
}
/** "Unchanged": a scripted DualSense session (buttons, sticks, PS, Mute, the touchpad's click, fingers) — its lines and its
 *  pad's events hashed, the touchpad's own lines ("H") counted apart. [on] "t 1"; [zero] no finger in any report. */
static uint64_t tp_session(int on, int zero, int *nh) {
    tp_source(TK_DSU); hx_touch_on(on);
    unsigned saved = rnd_state; rnd_state = 777;
    uint64_t h = 1469598103934665603ULL; *nh = 0;
    int fx = 900, fy = 500, fon = 0, fid = 1;
    for (int i = 0; i < 300; i++) {
        at(10000 + i * 6);
        uint8_t r[64]; memset(r, 0, sizeof r); r[0] = 0x01;
        for (int a = 0; a < 4; a++) r[1 + a] = 128;
        unsigned c = rnd();
        if (c % 5 == 0) r[1] = (uint8_t)rnd();
        r[8] = (uint8_t)(8 | ((c >> 4) % 3 == 0 ? (rnd() & 0x0F) << 4 : 0));
        r[9] = (uint8_t)((c >> 8) % 7 == 0 ? rnd() & 0x33 : 0);
        r[10] = (uint8_t)((c >> 12) % 9 == 0 ? rnd() & 0x07 : 0);              /* PS, the touchpad's click, Mute */
        unsigned m = rnd();
        if (m % 11 == 0) { fon = !fon; if (fon) fid = (fid + 1) & 0x7F; }
        fx += (int)(m % 61) - 30; fy += (int)((m >> 8) % 41) - 20;
        fx = fx < 0 ? 0 : fx > 1919 ? 1919 : fx;
        fy = fy < 0 ? 0 : fy > 1079 ? 1079 : fy;
        tpt(r + 33, fon, fid, fx, fy); tpt(r + 37, (m >> 16) % 13 == 0, fid + 1, 1919 - fx, fy);
        if (zero) { tpt(r + 33, 0, 0, 0, 0); tpt(r + 37, 0, 0, 0, 0); }
        nlines = 0; hx_report(0, r, 64);
        if (i % 7 == 0) hx_tick();
        for (int j = 0; j < nlines; j++) { if (LINES[j][0] == 'H') { (*nh)++; continue; } h = hmix(h, LINES[j]); }
        char st[64];
        for (int k = 0; k < HX.ws.nk; k++) { snprintf(st, sizeof st, "%d=%d/%d", HX.ws.keys[k], KEY[HX.ws.keys[k]], KEYEV[HX.ws.keys[k]]); h = hmix(h, st); }
        for (int k = 0; k < 8; k++) { snprintf(st, sizeof st, "a%d=%d/%d", HX_AXES[k], ABSV[HX_AXES[k]], ABSEV[HX_AXES[k]]); h = hmix(h, st); }
    }
    rnd_state = saved;
    return h;
}

int main(void) {
    // ── the Nimbus+ descriptor ─────────────────────────────────────
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS);
    CHECK(HX.nf == 21, "Nimbus+: 21 fields (4 sticks, 2 pressures, 4 D-pad, 8 buttons, 3 consumer), got %d", HX.nf);
    CHECK(!HX.S[0].ids_used, "Nimbus+: no report ids");
    int fx = hx_find(0, 1, 0x30), f7 = hx_find(0, 9, 7), fu = hx_find(0, 1, 0x90), fh = hx_find(0, 0x0C, 0x223);
    CHECK(fx >= 0 && HX.F[fx].bit == 0 && HX.F[fx].bits == 8 && HX.F[fx].lmin == -127 && HX.F[fx].sgn, "X: bits 0..7, signed");
    CHECK(f7 >= 0 && HX.F[f7].kind == K_AXIS && HX.F[f7].bit == 32 && HX.F[f7].lmax == 255, "button 7 = a pressure 0..255 at bit 32 (lmax %d)", f7 >= 0 ? HX.F[f7].lmax : -1);
    CHECK(fu >= 0 && HX.F[fu].bit == 48 && HX.F[fu].kind == K_BTN, "D-pad up at bit 48");
    CHECK(fh >= 0 && HX.F[fh].bit == 60, "Home (consumer 0x223) at bit 60");
    hx_apply_map();
    CHECK(HX.map_f[T_S] == hx_find(0, 9, 1) && HX.map_f[T_N] == hx_find(0, 9, 4), "buttons 1..4 = bottom right left top");
    CHECK(HX.map_f[T_L2] == f7 && HX.map_f[T_LT] == f7, "button 7 = L2 and its analog");
    CHECK(HX.map_f[T_ST] == hx_find(0, 0x0C, 0x209) && HX.map_f[T_SE] == hx_find(0, 0x0C, 0x204), "Start / Select from the consumer page");
    CHECK(HX.map_f[T_L3] == hx_find(0, 9, 9) && HX.map_f[T_R3] == hx_find(0, 9, 10), "buttons 9 / 10 = stick clicks");
    CHECK(HX.map_f[T_RX] == hx_find(0, 1, 0x32) && HX.map_f[T_RY] == hx_find(0, 1, 0x35), "Z / Rz = right stick");
    CHECK(HX.map_f[T_HO] == fh, "Home");

    // ── Nimbus+ → our pad (the Thor in Nintendo layout) ────────────
    nimbus(0, 0, 0, 0, 0, 0, 0);
    nimbus(127, -127, 0, 0, 0, 0, 0);
    CHECK(ABSV[WF_LX] == 32767 && ABSV[WF_LY] == -32767, "left stick right + up (%d %d)", ABSV[WF_LX], ABSV[WF_LY]);
    nimbus(0, 0, 0, 0, 0, 0, NB_1);
    CHECK(KEY[0x131] && !KEY[0x130], "bottom button = B on a Nintendo-layout Thor (as the Thor's own bottom button)");
    nimbus(0, 0, 0, 0, 0, 0, NB_2);
    CHECK(KEY[0x130] && !KEY[0x131], "right button = A");
    nimbus(0, 0, 0, 0, 255, 0, 0);
    CHECK(KEY[0x138] && ABSV[WF_BRAKE] == 32767, "L2 pressure: the key + full BRAKE (%d)", ABSV[WF_BRAKE]);
    /* dev build 17 (theme G): let go below a fifth of the pull (was: a quarter, past half = pressed) */
    nimbus(0, 0, 0, 0, 45, 0, 0);
    CHECK(!KEY[0x138] && ABSV[WF_BRAKE] > 5000 && ABSV[WF_BRAKE] < 10000, "L2 below a fifth: analog only (%d)", ABSV[WF_BRAKE]);
    nimbus(0, 0, 0, 0, 0, 0, NB_UP | NB_LEFT);
    CHECK(ABSV[WF_HY] == -1 && ABSV[WF_HX] == -1, "D-pad up-left = HAT (-1, -1)");
    nimbus(0, 0, 0, 0, 0, 0, NB_START | NB_SELECT | NB_9 | NB_10);
    CHECK(KEY[0x13b] && KEY[0x13a] && KEY[0x13d] && KEY[0x13e], "Start Select L3 R3");
    nimbus(0, 0, 0, 0, 0, 0, 0); nimbus(0, 0, 0, 0, 0, 0, NB_HOME);
    CHECK(KEY[WF_HOME], "Home");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(!KEY[0x13b] && !KEY[0x13c] && ABSV[WF_HX] == 0 && ABSV[WF_BRAKE] == 0, "all released");
    hx_face(1);
    nimbus(0, 0, 0, 0, 0, 0, NB_1);
    CHECK(KEY[0x130] && !KEY[0x131], "Xbox-layout Thor: bottom button = A");
    nimbus(0, 0, 0, 0, 0, 0, NB_4);
    CHECK(KEY[0x134] && !KEY[0x133], "Xbox-layout Thor: top button = Y (0x134), as AYN's Xbox mode reports its top button");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    // held through a change of meaning, no new report (a pad reporting only on change): pressed again (2026-10-03)
    nimbus(0, 0, 0, 0, 0, 0, NB_1);
    CHECK(KEY[0x130], "held: A (Xbox layout)");
    hx_face(0);
    CHECK(KEY[0x131] && !KEY[0x130], "face layout changed while held: pressed again as B, without a new report");
    // wfhid's P, dev build 14: swapped in place — what is held keeps its state, no edge, no new report needed
    memset(KEYEV, 0, sizeof KEYEV);
    CHECK(hx_profile("sw=1") == 0 && KEY[0x131] && !KEYEV[0x131], "a new profile while held (wfhid's P): still held, no edge");
    CHECK(hx_profile("") == 0 && KEY[0x131] && !KEYEV[0x131], "...and back: still held, no edge");
    hx_home(0); CHECK(KEY[0x131] && !KEYEV[0x131], "Home's owner changed while held: still held");
    hx_home(1);
    nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(!KEY[0x131] && !KEY[0x130], "let go: released");
    hx_face(1);

    // ── a pad that only reports changes, whose first report is a pushed stick ──
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map();
    nimbus(127, 0, 0, 0, 0, 0, 0);
    CHECK(ABSV[WF_LX] == 32767, "first report pushed right: right (%d)", ABSV[WF_LX]);
    nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(ABSV[WF_LX] == 0, "...let go: centred, not full left (%d)", ABSV[WF_LX]);
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map();
    nimbus(5, 0, 0, 0, 0, 0, 0); nimbus(5, 0, 0, 0, 0, 0, 0);
    CHECK(ABSV[WF_LX] == 0, "a first reading near the middle is the centre (its drift doesn't show)");
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map();

    // ── profiles (the input layer's engine) ────────────────────────
    hx_face(0);
    CHECK(hx_profile("k0x130=0x131") == 0, "profile: A → B accepted");
    nimbus(0, 0, 0, 0, 0, 0, NB_2);
    CHECK(KEY[0x131] && !KEY[0x130], "profile: the right button (A) now gives B");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(hx_profile("k0x130=0x999") != 0, "profile: a key the pad doesn't have is refused");
    CHECK(hx_profile("sw=1") == 0, "profile: swap sticks");
    nimbus(127, 0, 0, 0, 0, 0, 0);
    CHECK(ABSV[WF_RX] == 32767 && ABSV[WF_LX] == 0, "swap sticks: left stick drives the right (%d %d)", ABSV[WF_RX], ABSV[WF_LX]);
    nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(hx_profile("f0x130=1 tr=10") == 0, "profile: turbo on A");
    HX.ws.now_ms = 1000; nimbus(0, 0, 0, 0, 0, 0, NB_2);
    CHECK(KEY[0x130], "turbo: on at first");
    HX.ws.now_ms = 1060; hx_flush();
    CHECK(!KEY[0x130], "turbo: off half a period later");
    HX.ws.now_ms = 1110; hx_flush();
    CHECK(KEY[0x130], "turbo: on again");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(!KEY[0x130] && !wf_turbo_active(&HX.ws, &HX.wm), "turbo: released");
    // b18o (2026-10-11 — #81's turbo leftover): what the hand holds as a turbo button is said ("TU <code> <1|0> <ms>"):
    // once at its press, never at a pulse, once at its release — Wayfinder takes the pulses for no presses
    nlines = 0; HX.ws.now_ms = 3000; nimbus(0, 0, 0, 0, 0, 0, NB_2);
    CHECK(KEY[0x130] && said("TU 304 1 3000") && well_formed("TU 304 1 3000"), "b18o turbo A held: TU 304 1 3000");
    { int flips = 0, last = KEY[0x130];
      nlines = 0; for (long t = 3010; t <= 3500; t += 10) { HX.ws.now_ms = t; hx_flush(); flips += KEY[0x130] != last; last = KEY[0x130]; }
      CHECK(flips >= 8 && !said("TU "), "b18o ...half a second of pulses: %d flips on its pad, nothing said", flips); }
    nlines = 0; HX.ws.now_ms = 3600; nimbus(0, 0, 0, 0, 0, 0, NB_HOME | NB_2);
    CHECK(HX.ws.gated && !KEY[0x130] && !said("TU "), "b18o ...its Home pressed: A silent, still the hand's (nothing said)");
    nlines = 0; HX.ws.now_ms = 3700; nimbus(0, 0, 0, 0, 0, 0, NB_HOME);
    CHECK(said("TU 304 0 3700") && !said("J "), "b18o ...A let go under its Home: TU 304 0 3700, no J");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, NB_HOME | NB_2);
    CHECK(said("J 1 304 1") && !said("TU "), "b18o ...A pressed under its Home: a J press (Home + A), never TU");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, 0); nimbus(0, 0, 0, 0, 0, 0, NB_2);
    CHECK(said("TU 304 1"), "b18o ...pressed again alone: TU 304 1");
    nlines = 0; CHECK(hx_profile("") == 0 && said("TU 304 0"), "b18o ...the profile changed (no turbo) while held: TU 304 0");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    // a toggle through a new profile (dev build 14): latched while it toggles the same, off once it no longer does
    CHECK(hx_profile("f0x130=2") == 0, "profile: A a toggle");
    nimbus(0, 0, 0, 0, 0, 0, NB_2); nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(KEY[0x130], "toggle: on after one press");
    CHECK(hx_profile("f0x130=2 sw=1") == 0 && KEY[0x130], "a new profile still toggling A: still on");
    CHECK(hx_profile("sw=1") == 0 && !KEY[0x130], "a profile without that toggle: off");
    // the profile in use, sent again (the app's tick, another controller's change): nothing done, nothing said
    CHECK(hx_profile("k0x131=0x130") == 0, "profile: B → A");
    { int s0 = syncs; nlines = 0;
      CHECK(hx_profile("k0x131=0x130") == 1 && hx_profile("k305=304") == 1 && !nlines && syncs == s0,
            "the same profile again (any spelling): nothing done, nothing said, nothing written"); }
    CHECK(hx_profile("k0x131=0x999") == -1 && hx_profile("k0x131=0x130") == 0, "after a refused one, the one in use is answered again (P ok)");
    // dev build 16 (WP4): a controller's own controls re-spelled once (PadRules.canonical, "E uses") — a real DualSense:
    // the one in use, nothing done
    CHECK(hx_profile("L=x k0x133=305") == 0, "profile: its own controls as build 15 spelled them");
    { int s0 = syncs; nlines = 0;
      CHECK(hx_profile("L=x k0x133=0x131") == 1 && !nlines && syncs == s0, "...re-spelled by dev build 16: nothing done, nothing said"); }
    CHECK(hx_profile("") == 0, "profile: none");

    // ── the user's mapping (the wizard) ────────────────────────────
    CHECK(hx_parse_map("S=13 E=12") == 0, "user map accepted");
    CHECK(HX.map_f[T_S] == 13 && HX.map_f[T_E] == 12 && HX.map_f[T_N] == -1, "user map replaces the default");
    CHECK(hx_parse_map("S=999") != 0 && HX.map_f[T_S] == 13, "a field out of range: refused, the map kept");
    CHECK(hx_parse_map("Q=1") != 0 && hx_parse_map("S=") != 0 && hx_parse_map("S=1x") != 0, "bad tokens refused");
    CHECK(hx_parse_map("") == 0 && HX.map_f[T_N] == hx_find(0, 9, 4), "empty = back to the default");

    // ── learn mode ─────────────────────────────────────────────────
    HX.learn = 1; nlines = 0;
    nimbus(0, 0, 0, 0, 0, 0, 0);
    nimbus(0, 0, 0, 0, 0, 0, NB_3);
    CHECK(said("C ") && !KEY[0x134], "learn: a C line, nothing reaches the game");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, NB_3);
    CHECK(!said("C "), "learn: no change, no line");
    nlines = 0; nimbus(0, 0, 0, 100, 0, 0, NB_3);
    CHECK(said("C 3 100"), "learn: an axis away from its rest (field 3 = Rz)");
    HX.learn = 0;
    nimbus(0, 0, 0, 0, 0, 0, 0);
    hx_learn_start(); nlines = 0;
    nimbus(0, 0, 0, 0, 0, 0, NB_1);
    CHECK(said("C 10 1"), "learn: the first press right after the start is heard (a pad that reports changes only)");
    HX.learn = 0;
    // a minute without a change ends learn mode by itself (dev build 14: the wizard's page gone, the controller stayed
    // silent in games); what is still held then stays silent until let go
    nimbus(0, 0, 0, 0, 0, 0, 0);
    HX.now_ms = 100000; hx_learn_start(); nlines = 0;
    HX.now_ms = 130000; nimbus(0, 0, 0, 0, 0, 0, NB_1);
    CHECK(said("C 10 1") && HX.learn_at == 130000, "learn: a change heard, the minute starts again");
    HX.now_ms = 189000; hx_tick();
    CHECK(HX.learn && hx_wait() == 1000, "learn: still on 59 s later (wakes in %d ms)", hx_wait());
    HX.now_ms = 190000; hx_tick();
    CHECK(!HX.learn && said("L 0"), "learn: over by itself a minute after the last change, told (L 0)");
    memset(KEYEV, 0, sizeof KEYEV); nimbus(0, 0, 0, 0, 0, 0, NB_1);
    CHECK(!KEY[0x131] && !KEYEV[0x131], "...the button still held stays silent (no press nobody made)");
    nimbus(0, 0, 0, 0, 0, 0, 0); nimbus(0, 0, 0, 0, 0, 0, NB_1);
    CHECK(KEY[0x131], "...once let go, the controller drives the game again");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    hx_learn_start(); hx_learn_stop(); hx_learn_stop();
    CHECK(!HX.learn, "learn: l 0 ends it (twice: nothing more)");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    // nothing held as it ends (a pad that reports only on change: its "all let go" came while learning): the very next
    // report is a press, and it reaches the game (dev build 14: taken for one still held, it was dropped)
    hx_learn_start(); nimbus(0, 0, 0, 0, 0, 0, NB_1); nimbus(0, 0, 0, 0, 0, 0, 0); hx_learn_stop();
    memset(KEYEV, 0, sizeof KEYEV); nimbus(0, 0, 0, 0, 0, 0, NB_1);
    CHECK(KEY[0x131] && KEYEV[0x131] == 1, "learn: l 0 with nothing held, the first press right after reaches the game");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(!KEY[0x131] && KEYEV[0x131] == 2, "...and its release");

    // dev build 16 (theme E): holds while learning — a button heard before in this session held 1 s says "L k <field>"
    // (the wizard: "Not on it"), its Home held 1.5 s "L h <field>" (Cancel); once per press, never for a first press,
    // never "L k" for Home; a pad that reports only on change sends nothing while held: hx_wait() wakes it
    { int b1 = hx_find(0, 9, 1), ho = HX.map_f[T_HO]; char lk[16], lh[16];
      snprintf(lk, sizeof lk, "L k %d", b1); snprintf(lh, sizeof lh, "L h %d", ho);
      CHECK(b1 >= 0 && ho >= 0 && ho != b1, "holds: button 1 and Home are fields (%d %d)", b1, ho);
      nimbus(0, 0, 0, 0, 0, 0, 0);
      HX.now_ms = 300000; hx_learn_start(); nlines = 0; memset(KEYEV, 0, sizeof KEYEV);
      HX.now_ms = 300100; nimbus(0, 0, 0, 0, 0, 0, NB_1);
      HX.now_ms = 301600; hx_tick();
      CHECK(said("C ") && !said("L "), "holds: a first press held 1.5 s says nothing (it is being learnt)");
      HX.now_ms = 301700; nimbus(0, 0, 0, 0, 0, 0, 0);
      HX.now_ms = 302000; nimbus(0, 0, 0, 0, 0, 0, NB_1); nlines = 0;
      CHECK(hx_wait() == 1000, "holds: pressed again, the wait is the 1 s hold (%d ms)", hx_wait());
      HX.now_ms = 302900; hx_tick();
      CHECK(!said("L ") && hx_wait() == 100, "holds: 0.9 s: nothing yet, due in 100 ms (%d)", hx_wait());
      HX.now_ms = 303000; hx_tick();
      CHECK(said(lk) && nlines == 1, "holds: held 1 s: \"%s\" once (%d lines)", lk, nlines);
      nlines = 0; HX.now_ms = 305000; hx_tick();
      CHECK(!said("L ") && hx_wait() > 50000, "holds: said once per press (wait %d)", hx_wait());
      nimbus(0, 0, 0, 0, 0, 0, 0); HX.now_ms = 305100; nimbus(0, 0, 0, 0, 0, 0, NB_1); nlines = 0;
      HX.now_ms = 306100; hx_tick();
      CHECK(said(lk), "holds: let go and held again: said again");
      HX.now_ms = 306200; nimbus(0, 0, 0, 0, 0, 0, 0);
      // its Home: 1.5 s, the first press too; never "L k"; let go at 1.4 s: nothing
      nlines = 0; HX.now_ms = 310000; nimbus(0, 0, 0, 0, 0, 0, NB_HOME);
      HX.now_ms = 311400; hx_tick();
      CHECK(!said("L "), "holds: Home held 1.4 s: nothing");
      HX.now_ms = 311500; hx_tick();
      CHECK(said(lh) && !said("L k"), "holds: Home held 1.5 s: \"%s\", no L k", lh);
      nlines = 0; HX.now_ms = 313000; hx_tick();
      CHECK(!said("L "), "holds: Home said once per press");
      HX.now_ms = 313100; nimbus(0, 0, 0, 0, 0, 0, 0);
      HX.now_ms = 314000; nimbus(0, 0, 0, 0, 0, 0, NB_HOME); nlines = 0;
      HX.now_ms = 315000; hx_tick();
      CHECK(!said("L "), "holds: Home heard before, held 1 s: no L k (Home is never \"Not on it\")");
      HX.now_ms = 315400; nimbus(0, 0, 0, 0, 0, 0, 0); HX.now_ms = 316000; hx_tick();
      CHECK(!said("L "), "holds: Home let go at 1.4 s: nothing");
      CHECK(!KEY[0x131] && !KEY[WF_HOME] && !KEYEV[0x131] && !KEYEV[WF_HOME], "holds: nothing reached the game while learning");
      // learn mode over while held: nothing later; a new session forgets what was heard
      HX.now_ms = 320000; nimbus(0, 0, 0, 0, 0, 0, NB_1); hx_learn_stop(); nlines = 0;
      HX.now_ms = 322000; hx_tick();
      CHECK(!said("L "), "holds: learn mode over while held: no line later");
      nimbus(0, 0, 0, 0, 0, 0, 0);
      HX.now_ms = 330000; hx_learn_start(); nlines = 0;
      HX.now_ms = 330100; nimbus(0, 0, 0, 0, 0, 0, NB_1); HX.now_ms = 331200; hx_tick();
      CHECK(!said("L "), "holds: a new session: button 1's first press is no skip");
      HX.now_ms = 331300; nimbus(0, 0, 0, 0, 0, 0, 0); hx_learn_stop();
      // a pad without Home (map_f[T_HO] < 0): no "L h"
      CHECK(hx_parse_map("S=10 E=11") == 0 && HX.map_f[T_HO] < 0, "holds: a map without Home");
      HX.now_ms = 340000; hx_learn_start(); nlines = 0;
      HX.now_ms = 340100; nimbus(0, 0, 0, 0, 0, 0, NB_HOME); HX.now_ms = 342000; hx_tick();
      CHECK(!said("L h"), "holds: no Home in its map: no L h");
      HX.now_ms = 342100; nimbus(0, 0, 0, 0, 0, 0, 0); hx_learn_stop();
      CHECK(hx_parse_map("") == 0 && HX.map_f[T_HO] == ho, "holds: the default map back");
      nimbus(0, 0, 0, 0, 0, 0, 0); }

    // ── Joy-Con (R), captured reports ──────────────────────────────
    fresh(); hx_add_source(0, 0x057e, 0x2007, 0, 0);
    CHECK(HX.S[0].nintendo && HX.nf == 26, "Joy-Con: 22 buttons + 4 axes (%d)", HX.nf);
    hx_apply_map();
    jc(0, 0, 0, 0, 0, 0, 2060, 1785);
    CHECK(ABSV[WF_RX] == 0 && ABSV[WF_RY] == 0, "rest = centre");
    jc(0, 0x08, 0, 0, 0, 0, 2055, 1811);
    CHECK(KEY[0x130], "A (byte 3 bit 3) = A on a Nintendo-layout Thor");
    jc(0, 0, 0, 0, 0, 0, 3393, 1708);
    CHECK(ABSV[WF_RX] == 32767, "stick right = RX full (%d)", ABSV[WF_RX]);
    jc(0, 0, 0, 0, 0, 0, 2352, 2948);
    CHECK(ABSV[WF_RY] == -32767, "stick up = RY up (%d)", ABSV[WF_RY]);
    jc(0, 0x30, 0, 0, 0, 0, 2060, 1785);
    CHECK(KEY[0x132] && KEY[0x135], "SL / SR = C / Z");

    // ── … held sideways: SL + SR 0.6 s ─────────────────────────────
    jc(0, 0, 0, 0, 0, 0, 2060, 1785); nlines = 0;
    HX.now_ms = 5000; jc(0, 0x30, 0, 0, 0, 0, 2060, 1785);
    HX.now_ms = 5300; jc(0, 0x30, 0, 0, 0, 0, 2060, 1785);
    CHECK(!HX.sideways, "not yet sideways after 0.3 s");
    HX.now_ms = 5700; jc(0, 0x30, 0, 0, 0, 0, 2060, 1785);
    CHECK(HX.sideways && said("O 1"), "sideways after 0.6 s, told");
    CHECK(!KEY[0x132] && !KEY[0x135], "sideways: SL / SR are released for the change");
    jc(0, 0, 0, 0, 0, 0, 2060, 1785);
    jc(0, 0x30, 0, 0, 0, 0, 2060, 1785);
    CHECK(KEY[0x136] && KEY[0x137], "sideways: SL / SR = L1 / R1");
    jc(0, 0x08, 0, 0, 0, 0, 2060, 1785);
    CHECK(KEY[0x131] && !KEY[0x130], "sideways: A is the bottom button (B on a Nintendo-layout Thor)");
    jc(0, 0x02, 0, 0, 0, 0, 2060, 1785);
    CHECK(KEY[0x130], "sideways: X is the right button (A)");
    jc(0, 0, 0, 0, 0, 0, 2352, 2948);
    CHECK(ABSV[WF_LX] == 32767, "sideways: its up = the left stick right (%d)", ABSV[WF_LX]);
    jc(0, 0, 0, 0, 0, 0, 3393, 1708);
    CHECK(ABSV[WF_LY] == 32767, "sideways: its right = down (%d)", ABSV[WF_LY]);
    HX.now_ms = 9000; jc(0, 0x30, 0, 0, 0, 0, 2060, 1785); HX.now_ms = 9900; jc(0, 0x30, 0, 0, 0, 0, 2060, 1785);
    CHECK(HX.sideways, "SL + SR held while sideways: stays sideways (a game's L1 + R1)");
    hx_orient(0);
    CHECK(!HX.sideways && HX.map_f[T_RX] == hx_find(0, NP, 0x32), "upright again");

    // ── a Joy-Con pair ─────────────────────────────────────────────
    fresh(); hx_add_source(0, 0x057e, 0x2006, 0, 0); hx_add_source(1, 0x057e, 0x2007, 0, 0);
    CHECK(HX.nsrc == 2 && HX.nf == 52, "pair: 2 × 26 fields");
    hx_apply_map();
    CHECK(HX.map_f[T_LX] >= 0 && HX.F[HX.map_f[T_LX]].src == 0 && HX.F[HX.map_f[T_RX]].src == 1, "pair: left stick from the left one");
    jc(0, 0, 0, 0, 2000, 2000, 0, 0); jc(1, 0, 0, 0, 0, 0, 2060, 1785);
    jc(0, 0, 0, 0x02, 2000, 2000, 0, 0);
    CHECK(ABSV[WF_HY] == -1, "pair: the left one's up = D-pad up");
    jc(1, 0x08, 0, 0, 0, 0, 2060, 1785);
    CHECK(KEY[0x130] && ABSV[WF_HY] == -1, "pair: the right one's A, the left one's up still held");
    jc(0, 0, 0, 0x40, 3300, 2000, 0, 0);
    CHECK(ABSV[WF_LX] == 32767 && KEY[0x136], "pair: left stick + L");
    jc(0, 0, 0, 0x30, 2000, 2000, 0, 0);
    CHECK(!KEY[0x132] && !KEY[0x135], "pair: the rails do nothing");
    HX.now_ms = 1; jc(1, 0x30, 0, 0, 0, 0, 2060, 1785); HX.now_ms = 2000; jc(1, 0x30, 0, 0, 0, 0, 2060, 1785);
    CHECK(!HX.sideways, "pair: SL + SR never turn it sideways");

    // ── Nintendo extras ────────────────────────────────────────────
    nlines = 0; JC[2] = 0x51; JC[0] = 0x30; hx_report(1, JC, 49);
    CHECK(said("B 2 1 1"), "battery: half, charging, the right one");
    uint8_t ru[10]; hx_rumble_report(ru, sizeof ru, 3, 0, 0);
    CHECK(ru[0] == 0x10 && ru[1] == 3 && ru[2] == 0x00 && ru[3] == 0x01 && ru[4] == 0x40 && ru[5] == 0x40 && ru[9] == 0x40, "rumble: silent = the neutral bytes");
    hx_rumble_report(ru, sizeof ru, 0, 65535, 65535);
    CHECK(ru[3] == 0xC9 && ru[5] == 0x72, "rumble: full = high amp 0xC8, low amp 0x72 (%02x %02x)", ru[3], ru[5]);
    uint8_t sc[16]; int n = hx_subcommand(sc, sizeof sc, 17, 0x30, hx_player_lights(2));
    CHECK(n == 12 && sc[0] == 1 && sc[1] == 1 && sc[10] == 0x30 && sc[11] == 0x03, "player 2 lights: subcommand 0x30, pattern 0b0011");
    CHECK(hx_player_lights(4) == 0x0F && hx_player_lights(0) == 0, "lights 4 / off");

    // ── motion: aim with the gyro ──────────────────────────────────
    fresh(); hx_add_source(0, 0x057e, 0x2007, 0, 0); hx_apply_map();
    HX.ws.gyro_stick = 2; HX.gyro_sens = 10;
    for (int i = 0; i < 40; i++) { jc(0, 0, 0, 0, 0, 0, 2060, 1785); JC[13 + 24 + 10] = 30; JC[13 + 24 + 11] = 0; hx_report(0, JC, 49); }
    CHECK(HX.imu_seen && HX.ws.gyro_x == 0, "gyro: the resting offset is learnt (x %d)", HX.ws.gyro_x);
    int16_t yaw = -1640 + 30;          /* ≈ 100 °/s one way, on top of the offset */
    JC[13 + 24 + 10] = (uint8_t)(yaw & 0xFF); JC[13 + 24 + 11] = (uint8_t)((yaw >> 8) & 0xFF); hx_report(0, JC, 49);
    CHECK(HX.ws.gyro_x > 7000 && HX.ws.gyro_x < 9000, "gyro: 100 °/s ≈ a quarter of the stick (%d)", HX.ws.gyro_x);
    CHECK(ABSV[WF_RX] == HX.ws.gyro_x, "gyro: added to the right stick (%d)", ABSV[WF_RX]);

    // ── Home: the Thor's (combos) or the game's ─────────────────────
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map();
    nimbus(0, 0, 0, 0, 0, 0, 0);
    nimbus(0, 0, 0, 0, 0, 0, NB_HOME);
    CHECK(KEY[WF_HOME] && !KEY[0x13c], "Home = the Thor's Home key (0x66) by default");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, NB_HOME | NB_4);
    CHECK(!KEY[0x133] && said("J 1 307 1"), "Home + top: the top button is withheld from the game, echoed to Wayfinder (J)");
    nimbus(0, 0, 0, 0, 0, 0, NB_HOME); nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(!KEY[WF_HOME] && !KEY[0x133], "all released");
    nimbus(0, 0, 0, 0, 0, 0, NB_4);
    CHECK(KEY[0x133], "the top button works again after the combo (it was let go)");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    hx_home(0); nlines = 0;
    nimbus(0, 0, 0, 0, 0, 0, NB_HOME | NB_4);
    CHECK(KEY[0x13c] && !KEY[WF_HOME] && KEY[0x133] && !said("J "), "Home = the game's (MODE): nothing withheld");
    nimbus(0, 0, 0, 0, 0, 0, 0);

    // ── a settings change never touches what the hand holds (dev build 14) ──
    // Home + X held, then P (Wayfinder in front: another profile), x, h: no Home edge, no X edge, X withheld until let
    // go. (A release + replay sent Home up and down: its real release was a Home tap, and X reached the page it opened.)
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(hx_profile("k0x131=0x130") == 0, "a game's profile");
    nimbus(0, 0, 0, 0, 0, 0, NB_HOME); nimbus(0, 0, 0, 0, 0, 0, NB_HOME | NB_4);
    CHECK(KEY[WF_HOME] && !KEY[0x133] && HX.ws.ckey[0x133], "Home + X held: X withheld (consumed)");
    memset(KEYEV, 0, sizeof KEYEV); nlines = 0;
    CHECK(hx_profile("") == 0, "P with Home + X held: applied");
    hx_face(1); hx_home(0);
    CHECK(!KEYEV[WF_HOME] && !KEYEV[0x133] && !KEYEV[0x134] && !KEYEV[0x13c] && KEY[WF_HOME] && !said("J ") && !said("W "),
          "P, x, h with Home + X held: no Home edge, no X edge, nothing said (%d %d)", KEYEV[WF_HOME], KEYEV[0x133]);
    CHECK(HX.home_wf && HX.home_next == 0, "h 0 waits for Home's release");
    nimbus(0, 0, 0, 0, 0, 0, NB_4);
    CHECK(!KEY[WF_HOME] && KEYEV[WF_HOME] == 1 && !KEY[0x133] && !KEY[0x134], "Home let go (its own release, once): X still withheld");
    CHECK(!HX.home_wf && HX.home_next < 0, "...then its Home is the game's");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(said("J 1 307 0") && !KEYEV[0x133] && !KEYEV[0x134], "X let go: to Wayfinder (J), never to the game");
    nimbus(0, 0, 0, 0, 0, 0, NB_HOME);
    CHECK(KEY[0x13c] && !KEY[WF_HOME], "the next Home is the game's (MODE)");
    nimbus(0, 0, 0, 0, 0, 0, 0); hx_home(1); hx_face(0);
    // Home and X in ONE report (a quick chord: a Joy-Con reports every 15 ms): Home first — X pressed under it, a combo
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, NB_HOME | NB_4);
    CHECK(KEY[WF_HOME] && !KEY[0x133] && HX.ws.ckey[0x133] && said("J 1 307 1") && !said("J 1 102"),
          "Home + X in one report: X withheld and consumed, J line");
    nimbus(0, 0, 0, 0, 0, 0, NB_4);
    CHECK(!KEY[WF_HOME] && !KEY[0x133], "...Home let go first: X still silent for the game");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(said("J 1 307 0") && !KEY[0x133], "...X let go: to Wayfinder only");
    nimbus(0, 0, 0, 0, 0, 0, NB_HOME); nimbus(0, 0, 0, 0, 0, 0, NB_4);
    CHECK(!KEY[WF_HOME] && KEY[0x133], "Home let go and X pressed in one report: X is the game's (Home's code first, as on the Thor)");
    nimbus(0, 0, 0, 0, 0, 0, 0);

    // ── buttons and sticks the game's controls give to Wayfinder (keys, actions, macros) ──
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(hx_profile("k0x130=65534 k0x220=65534 apl=1") == 0, "profile with Wayfinder's buttons and a Wayfinder stick");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, NB_2);
    CHECK(!KEY[0x130] && said("W 304 1"), "the right button (A) is Wayfinder's: not to the game, W 304 1");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(said("W 304 0"), "...its release: W 304 0");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, NB_UP);
    CHECK(ABSV[WF_HY] == 0 && said("W 544 1"), "D-pad up is Wayfinder's: W 544 1, the game's D-pad stays still");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(said("W 544 0"), "...released");
    HX.now_ms = 100; nlines = 0; nimbus(127, 0, 0, 0, 0, 0, 0);
    CHECK(ABSV[WF_LX] == 0 && said("T 0 1000 0"), "the left stick is Wayfinder's: at rest for the game, T 0 1000 0");
    HX.now_ms = 102; nlines = 0; nimbus(100, 0, 0, 0, 0, 0, 0);
    CHECK(!said("T 0"), "...at most every 8 ms");
    HX.now_ms = 103; nlines = 0; nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(said("T 0 0 0"), "...its return to 0 at once");
    nimbus(0, 0, 0, 0, 0, 0, NB_2); nlines = 0; hx_release_all();
    CHECK(said("W 304 0"), "released everything: Wayfinder's held button too");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    // held through a new profile (wfhid's P, dev build 14: swapped in place): still Wayfinder's — no second press (an
    // action twice), no release; no longer Wayfinder's — released to Wayfinder, the game gets it held
    nimbus(0, 0, 0, 0, 0, 0, NB_2); nlines = 0;
    CHECK(hx_profile("k0x130=65534 k0x220=65534 apl=1 sw=1") == 0 && !said("W 304"), "a new profile, the button still Wayfinder's: no W line");
    nlines = 0;
    CHECK(hx_profile("k0x220=65534 apl=1") == 0 && said("W 304 0") && KEY[0x130], "...no longer Wayfinder's: W 304 0, the game gets it held");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(hx_vpress(0x131, 1) == 0 && KEY[0x131], "a virtual press (a macro's B) reaches the game");
    CHECK(hx_vpress(0, 0) == 0 && !KEY[0x131], "p 0 0 lets it go");
    CHECK(hx_vpress(WF_HOME, 1) != 0 && hx_vpress(0x999, 1) != 0, "Home or an unknown code refused");
    CHECK(hx_profile("") == 0, "profile cleared");

    // ── the hold-to-shift button on an external controller ──────────
    CHECK(hx_profile("sh=0x13a") == 0, "profile: Select is the shift button");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, NB_SELECT);
    CHECK(!KEY[0x13a] && said("J 1 314 1"), "shift held: not to the game, to Wayfinder (J)");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, NB_SELECT | NB_2);
    CHECK(!KEY[0x130] && said("J 1 304 1"), "A while shift is held: withheld, to Wayfinder (its shifted job)");
    nimbus(0, 0, 0, 0, 0, 0, NB_SELECT); nimbus(0, 0, 0, 0, 0, 0, 0);
    nimbus(0, 0, 0, 0, 0, 0, NB_2);
    CHECK(KEY[0x130], "A works again once shift is let go");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    HX.ws.now_ms = HX.now_ms = 50000; nimbus(0, 0, 0, 0, 0, 0, NB_SELECT);
    HX.ws.now_ms = HX.now_ms = 50100; nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(KEY[0x13a], "shift pressed alone: a short Select tap for the game");
    HX.ws.now_ms = HX.now_ms = 50300; hx_flush();
    CHECK(!KEY[0x13a], "...that ends by itself");
    CHECK(hx_profile("") == 0, "profile cleared");
    // the shift button held through a profile without it (dev build 14: Wayfinder's page came up): silent until let go
    CHECK(hx_profile("sh=0x13a") == 0, "profile: Select is the shift button");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, NB_SELECT);
    CHECK(said("J 1 314 1") && HX.ws.gated, "shift held: the gate");
    CHECK(hx_profile("") == 0 && !HX.ws.gated && !KEY[0x13a], "a profile without it, shift held: the gate closes, Select stays silent");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(!KEY[0x13a] && said("J 1 314 0"), "...let go: to Wayfinder, never a Select for the page");
    nimbus(0, 0, 0, 0, 0, 0, NB_SELECT);
    CHECK(KEY[0x13a], "...then Select is the game's again");
    // held as a profile makes it the shift button (dev build 14): said held to Wayfinder — its new profile starts with
    // no shift held, and every press under it was withheld for nothing — and no lone Select tap at its release
    nlines = 0;
    CHECK(hx_profile("sh=0x13a") == 0 && said("J 1 314 1") && HX.ws.gated && !KEY[0x13a],
          "a profile whose shift button is held: J 1 314 1, the gate, Select let go for the game");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(said("J 1 314 0") && !KEY[0x13a] && !HX.ws.gated, "...let go: J 1 314 0, no Select tap for the game");
    CHECK(hx_profile("") == 0, "profile cleared");
    nimbus(0, 0, 0, 0, 0, 0, 0);

    // ── a controller with no Home button: Select + Start held = Home ──
    CHECK(hx_parse_map("S=10 E=11 SE=20 ST=19 LX=0 LY=1") == 0, "a mapping without Home");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    HX.now_ms = 70000; nimbus(0, 0, 0, 0, 0, 0, NB_START | NB_SELECT);
    CHECK(KEY[0x13b] && KEY[0x13a] && !KEY[WF_HOME], "Select + Start: both reach the game at first");
    nlines = 0; HX.now_ms = 70700; nimbus(0, 0, 0, 0, 0, 0, NB_START | NB_SELECT);
    CHECK(KEY[WF_HOME] && !KEY[0x13b] && !KEY[0x13a], "held 0.6 s: Home (the game's Select / Start let go)");
    CHECK(said("J 1 102 1"), "...said as Select + Start's Home (J 1 102 1: its release alone is no Home tap)");
    nlines = 0; HX.now_ms = 70800; nimbus(0, 0, 0, 0, 0, 0, NB_START | NB_SELECT | NB_1);
    CHECK(said("J 1 305 1") && !KEY[0x131], "...a button with it: withheld for Wayfinder's combos (J)");
    nlines = 0; HX.now_ms = 70900; nimbus(0, 0, 0, 0, 0, 0, NB_START);
    CHECK(!KEY[WF_HOME] && !KEY[0x13b] && said("J 1 102 0"), "one let go: Home ends (J 1 102 0), Start stays silent");
    HX.now_ms = 71000; nimbus(0, 0, 0, 0, 0, 0, 0); nimbus(0, 0, 0, 0, 0, 0, NB_START);
    CHECK(KEY[0x13b], "both let go: Start works again");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    // a pad that reports only on change: held, no new report — the timer makes it Home (review 2026-10-03)
    HX.now_ms = 75000; nimbus(0, 0, 0, 0, 0, 0, NB_START | NB_SELECT);
    CHECK(hx_wait() >= 0 && !KEY[WF_HOME], "Select + Start held, no report yet: a timer waits");
    HX.now_ms = 75300; hx_tick(); CHECK(!KEY[WF_HOME], "...not before 0.6 s");
    HX.now_ms = 75650; hx_tick(); CHECK(KEY[WF_HOME] && !KEY[0x13b], "...0.6 s without a new report: Home");
    CHECK(hx_wait() < 0 || HX.wm.app_stick[0] || HX.wm.app_stick[1], "...then nothing more to wait for");
    // its Home given to the game ("h 0") while Select + Start's Home is held: at its release (dev build 14)
    memset(KEYEV, 0, sizeof KEYEV); hx_home(0);
    CHECK(HX.home_wf && KEY[WF_HOME] && !KEYEV[WF_HOME], "h 0 while Select + Start's Home is held: still the Thor's, no edge");
    nimbus(0, 0, 0, 0, 0, 0, NB_START);
    CHECK(!KEY[WF_HOME] && !KEY[0x13b] && HX.home_wf, "...one let go: Home ends, Start silent, the change still waits");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(!HX.home_wf, "...both let go: its Home is the game's");
    hx_home(0); HX.now_ms = 80000; nimbus(0, 0, 0, 0, 0, 0, NB_START | NB_SELECT); HX.now_ms = 81000; nimbus(0, 0, 0, 0, 0, 0, NB_START | NB_SELECT);
    CHECK(!KEY[WF_HOME] && KEY[0x13b], "its Home given to the game: no substitute either");
    nimbus(0, 0, 0, 0, 0, 0, 0); hx_home(1);
    CHECK(hx_parse_map("") == 0, "back to the default mapping");

    // ── a Joy-Con's own mapping when sideways; the L + R gesture ───
    fresh(); hx_add_source(0, 0x057e, 0x2007, 0, 0); hx_apply_map();
    CHECK(hx_parse_map_o("S=3", 1) == 0 && HX.map_f[T_S] == hx_find(0, NP, 0x03), "upright: the sideways mapping isn't used");
    hx_orient(1);
    CHECK(HX.map_f[T_S] == 3 && HX.map_f[T_E] == -1, "sideways: its own sideways mapping (S=3 only)");
    hx_orient(0);
    CHECK(HX.map_f[T_S] == hx_find(0, NP, 0x03), "upright again: the default");
    nlines = 0; jc(0, 0x40, 0, 0, 0, 0, 2060, 1785);
    CHECK(said("G sh 1"), "R pressed on a single right Joy-Con: G sh 1");
    nlines = 0; jc(0, 0, 0, 0, 0, 0, 2060, 1785);
    CHECK(said("G sh 0"), "...and G sh 0 on release");

    // ── PlayStation: built-in report layouts ────────────────────────
    fresh(); hx_add_source_bus(0, 5, 0x054c, 0x09cc, 0, 0); hx_apply_map();
    CHECK(HX.S[0].sony == 1 && HX.nf == 21, "DualShock 4 (Bluetooth): 21 fields (%d)", HX.nf);
    uint8_t d4[78]; memset(d4, 0, sizeof d4); d4[0] = 0x11;
    d4[3] = d4[4] = d4[5] = d4[6] = 128; d4[7] = 0x08; hx_report(0, d4, 78);       /* rest: hat 8 = none */
    d4[7] = 0x28; d4[3] = 255; hx_report(0, d4, 78);                                  /* ✕ + left stick right */
    CHECK(KEY[0x131] && ABSV[WF_LX] == 32767 && ABSV[WF_HX] == 0, "DS4 BT: ✕ = bottom (B on a Nintendo-layout Thor), stick right (%d)", ABSV[WF_LX]);
    d4[7] = 0x00; d4[3] = 128; d4[10] = 255; d4[8] = 0x04; hx_report(0, d4, 78);       /* hat up, L2 full */
    CHECK(ABSV[WF_HY] == -1 && ABSV[WF_BRAKE] == 32767 && KEY[0x138], "DS4 BT: D-pad up, L2 analog + key");
    d4[7] = 0x08; d4[8] = 0; d4[10] = 0; d4[9] = 0x01; hx_report(0, d4, 78);
    CHECK(KEY[WF_HOME] && ABSV[WF_HY] == 0, "DS4 BT: PS = Home");
    // learn mode on a pad that hasn't reported since its reader started (dev build 14: most pads report only changes —
    // the wizard took its first press for the rest and missed it): a first report that is a press is heard
    fresh(); hx_add_source_bus(0, 5, 0x054c, 0x09cc, 0, 0); hx_apply_map();
    hx_learn_start(); nlines = 0;
    d4[7] = 0x28; d4[3] = d4[4] = d4[5] = d4[6] = 128; d4[8] = d4[9] = d4[10] = 0; hx_report(0, d4, 78);   /* ✕, hat none */
    { char w[32]; snprintf(w, sizeof w, "C %d 1 0", hx_find(0, SP, 2));
      CHECK(said(w) && nlines == 1, "learn, nothing heard before: its first report a press (✕) is heard, alone (%d lines)", nlines); }
    nlines = 0; d4[7] = 0x08; hx_report(0, d4, 78);
    CHECK(nlines == 1, "...its release too, as any change");
    fresh(); hx_add_source_bus(0, 5, 0x054c, 0x09cc, 0, 0); hx_apply_map();
    hx_learn_start(); nlines = 0;
    d4[7] = 0x02; d4[3] = 200; hx_report(0, d4, 78);                                   /* first report: D-pad right, stick off */
    { char w[32]; snprintf(w, sizeof w, "C %d 2 8", hx_find(0, SP, 0x39));
      CHECK(said(w) && nlines == 1, "learn, nothing heard before: a D-pad pressed is heard (the hat rests at none); the stick is its rest (%d lines)", nlines); }
    HX.learn = 0; d4[3] = 128;
    fresh(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0); hx_apply_map();
    uint8_t ds[64]; memset(ds, 0, sizeof ds); ds[0] = 0x01; ds[1] = ds[2] = ds[3] = ds[4] = 128; ds[8] = 0x08; hx_report(0, ds, 64);
    ds[8] = 0x48; ds[9] = 0x20; ds[6] = 200; hx_report(0, ds, 64);                     /* ○ + Options + R2 */
    CHECK(KEY[0x130] && KEY[0x13b] && ABSV[WF_GAS] > 25000, "DualSense USB: ○ = right (A), Options = Start, R2 pull (%d)", ABSV[WF_GAS]);
    // dev build 15 (WP2): its Automatic face, PlayStation = the Xbox positions ("x x"): ✕ (the bottom) leaves as 0x130
    // (Android's BUTTON_A: it confirms), ○ as 0x131. A game's "L=n" while ✕ is held, then none: the layout's own change
    // each way (one edge per key), nothing on Home
    ds[8] = 0x08; ds[9] = 0; ds[6] = 0; hx_report(0, ds, 64); hx_face(1);
    ds[8] = 0x28; hx_report(0, ds, 64);
    CHECK(KEY[0x130] && !KEY[0x131], "DualSense, Xbox positions: ✕ = 0x130 (the bottom confirms)");
    memset(KEYEV, 0, sizeof KEYEV);
    CHECK(hx_profile("L=n") == 0 && KEY[0x131] && !KEY[0x130] && KEYEV[0x130] == 1 && KEYEV[0x131] == 1,
          "...✕ held through a game's L=n: B now, one edge each (%d %d)", KEYEV[0x130], KEYEV[0x131]);
    CHECK(hx_profile("") == 0 && KEY[0x130] && !KEY[0x131] && KEYEV[0x130] == 2 && KEYEV[0x131] == 2 && !KEYEV[WF_HOME],
          "...and back: A again, one edge each, nothing on Home (%d %d %d)", KEYEV[0x130], KEYEV[0x131], KEYEV[WF_HOME]);
    ds[8] = 0x48; hx_report(0, ds, 64);
    CHECK(KEY[0x131] && !KEY[0x130], "DualSense, Xbox positions: ○ = 0x131");
    ds[8] = 0x08; hx_report(0, ds, 64); hx_face(0);

    // ── Xbox layouts over Bluetooth ───────────────────────────────
    {   /* the older "DirectInput" layout (XOLD, above) */
        fresh(); hx_add_source_bus(0, 5, 0x045e, 0x02e0, XOLD, sizeof XOLD); hx_apply_map();
        CHECK(HX.map_f[T_RX] == hx_find(0, 1, 0x33) && HX.map_f[T_LT] == hx_find(0, 1, 0x32) && HX.map_f[T_RT] == hx_find(0, 1, 0x35),
              "old Xbox layout: right stick Rx / Ry, triggers Z / Rz");
        CHECK(HX.map_f[T_W] == hx_find(0, 9, 3) && HX.map_f[T_N] == hx_find(0, 9, 4) && HX.map_f[T_SE] == hx_find(0, 9, 7) &&
              HX.map_f[T_ST] == hx_find(0, 9, 8) && HX.map_f[T_L3] == hx_find(0, 9, 9), "old Xbox layout: A B X Y LB RB View Menu LS RS");
        fresh(); hx_add_source_bus(0, 5, 0x1234, 0x0001, XOLD, sizeof XOLD); hx_apply_map();
        CHECK(HX.map_f[T_RX] == hx_find(0, 1, 0x32), "the same descriptor from another maker: Z / Rz = right stick (the common layout)");
    }
    // dev build 15: the test tool's pads as wfhid reads them — its SYNTHETIC Xbox pad (XOLD with an 11th
    // button, the Xbox button; 15-byte reports) and the Nimbus+ without Home (its Home input made constant)
    {   uint8_t x11[sizeof XOLD]; memcpy(x11, XOLD, sizeof XOLD); x11[36] = 0x0b; x11[44] = 0x0b; x11[48] = 0x05;
        fresh(); hx_add_source_bus(0, 5, 0x045e, 0x0b13, x11, sizeof x11); hx_apply_map();
        CHECK(HX.nf == 18 && HX.map_f[T_HO] == hx_find(0, 9, 11) && HX.map_f[T_S] == hx_find(0, 9, 1) && HX.map_f[T_LT] == hx_find(0, 1, 0x32),
              "test Xbox pad: 18 fields, A bottom, button 11 = Home (the Xbox button), Z = LT (%d)", HX.nf);
        uint8_t r[15]; memset(r, 0, sizeof r);
        r[1] = r[3] = r[7] = r[9] = 0x80; hx_report(0, r, 15);          /* sticks centred (32768), triggers 0, hat none */
        r[12] = 0x01; hx_report(0, r, 15);
        CHECK(KEY[0x131] && !KEY[WF_HOME], "test Xbox pad: A = the bottom button");
        r[12] = 0; r[13] = 0x04; hx_report(0, r, 15);
        CHECK(KEY[WF_HOME] && !KEY[0x131], "test Xbox pad: the Xbox button = Home");
        r[13] = 0; r[14] = 1; r[0] = r[1] = 0xff; r[4] = r[5] = 0xff; hx_report(0, r, 15);
        CHECK(ABSV[WF_HY] == -1 && ABSV[WF_LX] == 32767 && ABSV[WF_BRAKE] == 32767 && !KEY[WF_HOME],
              "test Xbox pad: hat up, left stick right, LT full (%d %d %d)", ABSV[WF_HY], ABSV[WF_LX], ABSV[WF_BRAKE]);
        uint8_t nh[sizeof NIMBUS]; memcpy(nh, NIMBUS, sizeof NIMBUS);
        for (size_t i = 0; i + 4 < sizeof nh; i++)
            if (nh[i] == 0x0a && nh[i + 1] == 0x23 && nh[i + 2] == 0x02 && nh[i + 3] == 0x81 && nh[i + 4] == 0x02) nh[i + 4] = 0x03;
        fresh(); hx_add_source_bus(0, 5, 0x1209, 0x0001, nh, sizeof nh); hx_apply_map();
        int st = hx_find(0, 0x0C, 0x209);
        CHECK(HX.nf == 20 && HX.map_f[T_HO] < 0 && st >= 0 && HX.F[st].bit == 61 && HX.map_f[T_ST] == st && HX.map_f[T_SE] >= 0,
              "test pad, no Home: no Home, Start / Select where they were (its Home: Select + Start)"); }

    // ── rumble reports ─────────────────────────────────────────────
    CHECK(hx_crc32(0, (const uint8_t *)"123456789", 9) == 0xCBF43926u, "CRC-32 check value");
    CHECK(hx_crc32(hx_crc32(0, (const uint8_t *)"1234", 4), (const uint8_t *)"56789", 5) == 0xCBF43926u, "CRC-32 chains");
    fresh(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0);     /* a DualSense over USB */
    uint8_t rb[96]; int rl = hx_rumble_out(0, rb, sizeof rb, 5, 65535, 32768);
    CHECK(rl == 63 && rb[0] == 0x02 && rb[1] == 0x03 && rb[3] == 128 && rb[4] == 255, "DualSense USB rumble: report 2, weak / strong");
    HX.S[0].bt = 1; rl = hx_rumble_out(0, rb, sizeof rb, 5, 65535, 0);
    CHECK(rl == 78 && rb[0] == 0x31 && rb[1] == 0x50 && rb[2] == 0x10 && rb[6] == 255, "DualSense BT rumble: report 0x31, sequence, tag");
    { static const uint8_t a2 = 0xA2; uint32_t c = hx_crc32(hx_crc32(0, &a2, 1), rb, 74);
      CHECK(rb[74] == (uint8_t)c && rb[77] == (uint8_t)(c >> 24), "DualSense BT rumble: CRC at the end"); }
    HX.S[0].bt = 0; rl = hx_lights_out(0, rb, sizeof rb, 0, 2);
    CHECK(rl == 63 && rb[0] == 0x02 && rb[1] == 0 && rb[2] == 0x10 && rb[44] == 0x0a, "DualSense USB: player 2 LEDs, only the LED flag");
    HX.S[0].bt = 1; rl = hx_lights_out(0, rb, sizeof rb, 3, 1);
    { static const uint8_t a2 = 0xA2; uint32_t c = hx_crc32(hx_crc32(0, &a2, 1), rb, 74);
      CHECK(rl == 78 && rb[4] == 0x10 && rb[46] == 0x04 && rb[74] == (uint8_t)c, "DualSense BT: player 1 LED (centre), CRC"); }
    HX.S[0].rumble = HX_RUMBLE_DS4; rl = hx_lights_out(0, rb, sizeof rb, 0, 2);
    CHECK(rl == 78 && rb[0] == 0x11 && rb[3] == 0x02 && rb[8] == 0x40 && rb[9] == 0 && rb[10] == 0, "DS4 BT: player 2 = red lightbar, light flag only");
    HX.S[0].bt = 0; rl = hx_lights_out(0, rb, sizeof rb, 0, 1);
    CHECK(rl == 32 && rb[1] == 0x02 && rb[8] == 0x40 && rb[4] == 0, "DS4 USB: player 1 = blue, no rumble change");
    HX.S[0].bt = 1;
    HX.S[0].rumble = HX_RUMBLE_DS4; rl = hx_rumble_out(0, rb, sizeof rb, 0, 65535, 0);
    CHECK(rl == 78 && rb[0] == 0x11 && rb[1] == 0xC0 && rb[3] == 0x01 && rb[7] == 255 && rb[6] == 0, "DS4 BT rumble: report 0x11, strong motor");
    HX.S[0].bt = 0; rl = hx_rumble_out(0, rb, sizeof rb, 0, 0, 65535);
    CHECK(rl == 32 && rb[0] == 0x05 && rb[4] == 255 && rb[5] == 0, "DS4 USB rumble: report 5, weak motor");
    HX.S[0].rumble = HX_RUMBLE_XBOX; rl = hx_rumble_out(0, rb, sizeof rb, 0, 65535, 0);
    CHECK(rl == 9 && rb[0] == 0x03 && rb[1] == 0x03 && rb[4] == 100 && rb[5] == 0, "Xbox BT rumble: report 3, strong 100 %%");
    HX.S[0].rumble = HX_RUMBLE_NONE;
    CHECK(hx_rumble_out(0, rb, sizeof rb, 0, 65535, 65535) == 0, "a pad that can't rumble: nothing");
    fresh(); hx_add_source_bus(0, 5, 0x045e, 0x0b13, NIMBUS, sizeof NIMBUS);
    CHECK(HX.S[0].rumble == HX_RUMBLE_XBOX, "Xbox Series over Bluetooth rumbles");
    fresh(); hx_add_source_bus(0, 3, 0x045e, 0x0b13, NIMBUS, sizeof NIMBUS);
    CHECK(HX.S[0].rumble == HX_RUMBLE_NONE, "...not over USB (not ours there)");

    // ── robustness: what a radio can send ──────────────────────────
    // a "negative" 4-byte Report Size before a button (review 2026-10-03: drove the bit offset below 0, read before the report)
    {
        static const uint8_t NEG[][24] = {
            { 0x05,0x01, 0x09,0x05, 0xA1,0x01, 0x77,0xFF,0xFF,0xFF,0xFF, 0x95,0x01, 0x81,0x01,
              0x05,0x09, 0x19,0x01, 0x29,0x01, 0x75,0x01, 0x95 },
            { 0x05,0x01, 0x09,0x05, 0xA1,0x01, 0x77,0x00,0x00,0x00,0x80, 0x95,0x01, 0x81,0x01,
              0x05,0x09, 0x19,0x01, 0x29,0x01, 0x75,0x01, 0x95 },
        };
        for (int n = 0; n < 2; n++) {
            uint8_t d[32]; memcpy(d, NEG[n], 24); d[24] = 0x01; d[25] = 0x81; d[26] = 0x02; d[27] = 0xC0;
            fresh(); hx_add_source(0, 0x1234, 0x5678, d, 28); hx_apply_map();
            int neg = 0; for (int f = 0; f < HX.nf; f++) if (HX.F[f].bit < 0) neg = 1;
            CHECK(!neg, "negative Report Size %d: no field before the report", n);
            uint8_t r[4] = { 0xFF, 0xFF, 0xFF, 0xFF }; hx_report(0, r, 4);
        }
    }
    for (int round = 0; round < 3000; round++) {
        fresh();
        uint8_t d[300]; int len = (int)(rnd() % sizeof d);
        for (int i = 0; i < len; i++) d[i] = (uint8_t)rnd();
        if (round % 3 == 0) for (int i = 0; i + 1 < len; i += 2) d[i] = (uint8_t)(0x80 | (rnd() & 0x7C) | 1);   /* main items */
        hx_add_source(0, 0x1234, 0x5678, d, len);
        CHECK(HX.nf <= HX_MAXF, "fuzz: fields bounded");
        hx_apply_map();
        for (int k = 0; k < 20; k++) {
            uint8_t r[HX_MAXR]; int rl = (int)(rnd() % sizeof r) + 1;
            for (int i = 0; i < rl; i++) r[i] = (uint8_t)rnd();
            hx_report(0, r, rl);
        }
        for (int f = 0; f < HX.nf; f++) if ((HX.F[f].bit + HX.F[f].bits + 7) / 8 > HX_MAXR) { CHECK(0, "fuzz: a field past the longest report"); break; }
    }
    for (int kind = 0; kind < 4; kind++) {
        static const uint16_t pids[4] = { 0x05c4, 0x0ce6, 0x09cc, 0x0df2 };
        fresh(); hx_add_source_bus(0, kind & 1 ? 3 : 5, 0x054c, pids[kind], 0, 0); hx_apply_map();
        for (int k = 0; k < 2000; k++) { uint8_t r[80]; int rl2 = (int)(rnd() % 80) + 1; for (int i = 0; i < rl2; i++) r[i] = (uint8_t)rnd(); hx_report(0, r, rl2); }
    }
    CHECK(1, "fuzz: PlayStation reports of any length");
    fresh(); hx_add_source(0, 0x057e, 0x2009, 0, 0); hx_apply_map();
    for (int k = 0; k < 2000; k++) { uint8_t r[64]; int rl = (int)(rnd() % 64) + 1; for (int i = 0; i < rl; i++) r[i] = (uint8_t)rnd(); r[0] = 0x30; hx_report(0, r, rl); }
    CHECK(1, "fuzz: Nintendo reports of any length");
    fresh(); hx_add_source(0, 0x000d, 0, NIMBUS, 20);    /* a descriptor cut short */
    CHECK(HX.nf <= 4, "a truncated descriptor: what was complete only (%d)", HX.nf);

    // ── dev build 15 (C4): the controller away, its pad kept ("k 1"), the same controller back ("a") ──
    // what it held is let go as a report would: Wayfinder's own outputs (W … 0), the game's buttons
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(hx_profile("k0x130=65534") == 0, "a profile giving A to Wayfinder");
    nlines = 0; nimbus(0, 0, 0, 0, 0, 0, NB_1 | NB_2);
    CHECK(said("W 304 1") && KEY[0x131] && !KEY[0x130], "held: A (Wayfinder's: W 304 1) and B (the game's)");
    nlines = 0; hx_detach();
    CHECK(said("W 304 0") && !KEY[0x131] && !KEY[0x130] && HX.last_len == 0 && HX.idle,
          "away: W 304 0, B let go for the game, the last report forgotten");
    { int s0 = syncs; nlines = 0; hx_detach();
      CHECK(!nlines && syncs == s0 && HX.idle, "away twice: nothing more"); }
    memset(KEYEV, 0, sizeof KEYEV); nimbus(0, 0, 0, 0, 0, 0, NB_1);
    CHECK(!KEYEV[0x131], "a report while away (none can come): ignored");
    CHECK(hx_wait() < 0, "away: no timer");
    // learn mode pauses (the wizard's "Paused"); asked while away, it answers L 0 at once
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    hx_learn_start(); nlines = 0; hx_detach();
    CHECK(said("L 0") && !HX.learn && HX.idle, "away in learn mode: L 0, learn mode over");
    nlines = 0; hx_learn_start();
    CHECK(said("L 0") && !HX.learn, "learn mode asked while away: L 0 at once, not started");
    // its Home (the Thor's) held as it goes: one release, nothing for the combos
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    nimbus(0, 0, 0, 0, 0, 0, NB_HOME);
    memset(KEYEV, 0, sizeof KEYEV); nlines = 0; hx_detach();
    CHECK(!KEY[WF_HOME] && KEYEV[WF_HOME] == 1 && !said("J "), "away with Home held: one Home release, no J line (%d)", KEYEV[WF_HOME]);
    // Home + X held: X's release goes to Wayfinder (J), never to the game; Home after it
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    nimbus(0, 0, 0, 0, 0, 0, NB_HOME); nimbus(0, 0, 0, 0, 0, 0, NB_HOME | NB_4);
    memset(KEYEV, 0, sizeof KEYEV); nlines = 0; hx_detach();
    CHECK(said("J 1 307 0") && !KEY[WF_HOME] && !KEY[0x133] && !KEYEV[0x133], "away with Home + X held: J 1 307 0, X never to the game");
    // Select + Start's Home held (a pad with no Home): said let go as such
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map();
    CHECK(hx_parse_map("S=10 E=11 SE=20 ST=19 LX=0 LY=1") == 0, "a mapping without Home");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    HX.now_ms = 1000; nimbus(0, 0, 0, 0, 0, 0, NB_START | NB_SELECT); HX.now_ms = 1700; nimbus(0, 0, 0, 0, 0, 0, NB_START | NB_SELECT);
    CHECK(KEY[WF_HOME], "Select + Start held: its Home");
    nlines = 0; hx_detach();
    CHECK(said("J 1 102 0") && !KEY[WF_HOME] && !KEY[0x13a] && !KEY[0x13b], "away with Select + Start's Home: J 1 102 0, all let go");
    // Select + Start held, not yet its Home: nothing replayed while away
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map();
    hx_parse_map("S=10 E=11 SE=20 ST=19 LX=0 LY=1"); nimbus(0, 0, 0, 0, 0, 0, 0);
    HX.now_ms = 5000; nimbus(0, 0, 0, 0, 0, 0, NB_START | NB_SELECT);
    hx_detach(); nlines = 0; memset(KEYEV, 0, sizeof KEYEV);
    HX.now_ms = 7000; hx_tick();
    CHECK(!KEY[WF_HOME] && !KEYEV[WF_HOME] && !nlines && hx_wait() < 0, "away: no Select + Start Home 2 s later, no line, no timer");
    // a stick given to Wayfinder: back to 0 for the app
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(hx_profile("apl=1") == 0, "the left stick is Wayfinder's");
    HX.now_ms = 100; nimbus(127, 0, 0, 0, 0, 0, 0);
    nlines = 0; hx_detach();
    CHECK(said("T 0 0 0"), "away with Wayfinder's stick pushed: T 0 0 0");
    // aiming with motion as it goes: the stick back to rest, the setting kept
    fresh(); hx_add_source(0, 0x057e, 0x2007, 0, 0); hx_apply_map();
    HX.ws.gyro_stick = 2; HX.gyro_sens = 10;
    for (int i = 0; i < 40; i++) { jc(0, 0, 0, 0, 0, 0, 2060, 1785); JC[13 + 24 + 10] = 30; JC[13 + 24 + 11] = 0; hx_report(0, JC, 49); }
    { int16_t yw = -1640 + 30; JC[13 + 24 + 10] = (uint8_t)(yw & 0xFF); JC[13 + 24 + 11] = (uint8_t)((yw >> 8) & 0xFF); hx_report(0, JC, 49); }
    CHECK(ABSV[WF_RX] > 7000, "aiming: the right stick moved (%d)", ABSV[WF_RX]);
    hx_detach();
    CHECK(ABSV[WF_RX] == 0 && HX.ws.gyro_stick == 2, "away while aiming: the stick at rest, aim still on (%d)", ABSV[WF_RX]);
    // review N1: the hold-to-shift button held alone as it goes — its release to Wayfinder, no lone tap for the game
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(hx_profile("sh=0x13b") == 0, "profile: Start is the shift button");
    HX.ws.now_ms = HX.now_ms = 60000; nimbus(0, 0, 0, 0, 0, 0, NB_START);
    CHECK(!KEY[0x13b] && HX.ws.gated, "shift held alone: the gate, nothing for the game");
    memset(KEYEV, 0, sizeof KEYEV); nlines = 0; HX.ws.now_ms = HX.now_ms = 60500; hx_detach();
    CHECK(said("J 1 315 0") && !KEY[0x13b] && !KEYEV[0x13b] && HX.ws.shift_tap_until == 0,
          "away with the shift button held alone: J 1 315 0, no Start tap on the kept pad");
    HX.ws.now_ms = HX.now_ms = 60530; hx_flush();
    CHECK(!KEYEV[0x13b], "...nor a moment later");

    // the layout's signature: the fields, not where they sit
    { uint64_t a, b;
      fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); a = hx_sig();
      fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); b = hx_sig();
      CHECK(a == b, "signature: Nimbus+ = Nimbus+");
      fresh(); hx_add_source_bus(0, 5, 0x054c, 0x0ce6, 0, 0); a = hx_sig(); int rid5 = HX.F[0].rid, bit5 = HX.F[0].bit;
      fresh(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0); b = hx_sig();
      CHECK(a == b && rid5 != HX.F[0].rid && bit5 != HX.F[0].bit, "signature: a DualSense over Bluetooth = over USB (other report, other offsets)");
      fresh(); hx_add_source_bus(0, 5, 0x054c, 0x09cc, 0, 0); a = hx_sig();
      fresh(); hx_add_source_bus(0, 3, 0x054c, 0x09cc, 0, 0); b = hx_sig();
      CHECK(a == b, "signature: a DualShock 4 over Bluetooth = over USB");
      fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); a = hx_sig();
      fresh(); hx_add_source(0, 0x000d, 0x0000, XOLD, sizeof XOLD); b = hx_sig();
      CHECK(a != b, "signature: Nimbus+ ≠ another layout under the same ids");
      fresh(); hx_add_source(0, 0x057e, 0x2007, 0, 0); a = hx_sig();
      fresh(); hx_add_source(0, 0x057e, 0x2006, 0, 0); b = hx_sig();
      CHECK(a != b, "signature: Joy-Con (R) ≠ (L)");
      fresh(); hx_add_source(0, 0x057e, 0x2006, 0, 0); hx_add_source(1, 0x057e, 0x2007, 0, 0); b = hx_sig();
      CHECK(a != b, "signature: a pair ≠ a single"); }

    // back: the user's mapping, the lines that announce it again, driving again
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map();
    CHECK(hx_parse_map_o("S=2 E=1", 0) == 0, "a user mapping");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(hx_attach_begin() == -1 && hx_attach_end(1) == -1, "not away: no attach");
    hx_detach();
    CHECK(hx_attach_begin() == 0 && HX.nf == 0 && HX.nsrc == 0, "back: the old layout set aside");
    hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS);
    nlines = 0;
    CHECK(hx_same_ids() && hx_attach_end(1) == 0 && !HX.idle, "back: the same controller drives again");
    CHECK(HX.map_f[T_S] == 2 && HX.map_f[T_E] == 1 && HX.has_user[0], "back: the user's mapping kept");
    CHECK(said("I 5 000d 0000 21 hid 0") && said("F 20 b 1 0 1 c 204 0") && said("D S=2 E=1"), "back: I, F and D lines again");
    CHECK(hx_attach_end(1) == -1, "an end without a beginning: nothing");
    // quiet: a button held as it comes back (the press that woke it) reaches nothing until all is let go
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    hx_detach(); hx_attach_begin(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_attach_end(1);
    memset(KEYEV, 0, sizeof KEYEV); nimbus(0, 0, 0, 0, 0, 0, NB_1);
    CHECK(!KEY[0x131] && !KEYEV[0x131], "back with a button held: nothing");
    nimbus(0, 0, 0, 0, 0, 0, 0);
    CHECK(!KEYEV[0x131], "...its release: nothing");
    nimbus(0, 0, 0, 0, 0, 0, NB_1);
    CHECK(KEY[0x131] && KEYEV[0x131] == 1, "...the next press: one edge");
    nimbus(64, 0, 0, 0, 0, 0, 0);
    CHECK(ABSV[WF_LX] > 10000, "...and the stick (re-centred) drives (%d)", ABSV[WF_LX]);
    // refused: another layout under the same ids — the old one back, still away, and the right one later is taken
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    { int mn = HX.map_f[T_N];
      hx_detach(); hx_attach_begin(); hx_add_source(0, 0x000d, 0x0000, XOLD, sizeof XOLD);
      CHECK(hx_same_ids() && hx_attach_end(1) == -2, "another layout: refused (-2)");
      CHECK(HX.nf == 21 && HX.nsrc == 1 && HX.F[0].usage == 0x30 && HX.F[0].lmin == -127 && HX.map_f[T_N] == mn && HX.idle,
            "...the layout it had, its mapping, still away");
      CHECK(hx_attach_begin() == 0, "...a later try");
      hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS);
      CHECK(hx_attach_end(1) == 0 && !HX.idle, "...with the right one: back"); }
    // refused: other ids (the same fields under 1234:5678)
    hx_detach(); hx_attach_begin(); hx_add_source(0, 0x1234, 0x5678, NIMBUS, sizeof NIMBUS);
    CHECK(!hx_same_ids() && hx_attach_end(0) == -1 && HX.idle && HX.S[0].vid == 0x000d && HX.nf == 21, "other ids: refused, the old controller kept");
    // a Joy-Con pair back as one Joy-Con: refused
    fresh(); hx_add_source(0, 0x057e, 0x2006, 0, 0); hx_add_source(1, 0x057e, 0x2007, 0, 0); hx_apply_map();
    hx_detach(); hx_attach_begin(); hx_add_source(0, 0x057e, 0x2007, 0, 0);
    CHECK(!hx_same_ids() && hx_attach_end(0) == -1 && HX.nsrc == 2 && HX.nf == 52, "a pair back as one Joy-Con: refused, still a pair");
    hx_attach_begin(); hx_add_source(0, 0x057e, 0x2006, 0, 0); hx_add_source(1, 0x057e, 0x2007, 0, 0);
    CHECK(hx_same_ids() && hx_attach_end(1) == 0, "the pair back: taken");
    // what the user and the app set survives: the face layout, its Home's owner, the profile, the lights
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    hx_face(1); hx_home(0); HX.last_lights = 2;
    CHECK(hx_profile("k0x130=0x131") == 0, "a profile A → B");
    nimbus(0, 0, 0, 0, 0, 0, NB_1);
    { int a0 = KEY[0x130], b0 = KEY[0x131];
      nimbus(0, 0, 0, 0, 0, 0, 0);
      hx_detach(); hx_attach_begin(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_attach_end(1);
      nimbus(0, 0, 0, 0, 0, 0, 0); nimbus(0, 0, 0, 0, 0, 0, NB_1);
      CHECK(KEY[0x130] == a0 && KEY[0x131] == b0 && (a0 || b0), "back: the bottom button gives what it gave (%d %d)", a0, b0); }
    nimbus(0, 0, 0, 0, 0, 0, 0); nimbus(0, 0, 0, 0, 0, 0, NB_HOME);
    CHECK(KEY[0x13c] && !KEY[WF_HOME] && HX.face_x && HX.last_lights == 2, "...its Home still the game's (MODE), the Xbox face, the lights");
    nimbus(0, 0, 0, 0, 0, 0, 0);

    // ── dev build 15 (C5): our pad down and up in place ("n 0" / "n 1" / "n") ──
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    { int s0 = syncs;
      hx_pad_down(); memset(KEYEV, 0, sizeof KEYEV); memset(ABSEV, 0, sizeof ABSEV);
      nimbus(0, 0, 0, 0, 0, 0, NB_2);
      CHECK(!KEYEV[0x130] && syncs == s0, "down: a press writes nothing (no device)");
      hx_pad_up();
      int all = 1; for (int k = 0; k < 8; k++) if (ABSEV[HX_AXES[k]] != 1) all = 0;
      CHECK(KEY[0x130] && KEYEV[0x130] == 1 && all && syncs == s0 + 1, "up: A held, sent once with the whole state, one frame"); }
    nimbus(0, 0, 0, 0, 0, 0, 0);
    { int s0 = syncs; memset(KEYEV, 0, sizeof KEYEV); memset(ABSEV, 0, sizeof ABSEV);
      hx_pad_down(); hx_pad_up();
      int kc = 0, ac = 0;
      for (int i = 0; i < HX.ws.nk; i++) kc += KEYEV[HX.ws.keys[i]];
      for (int k = 0; k < 8; k++) ac += ABSEV[HX_AXES[k]];
      CHECK(kc == HX.ws.nk && ac == 8 && syncs == s0 + 1, "n (back to back): every key and axis once, one frame (%d keys, %d axes)", kc, ac); }
    CHECK(hx_profile("k0x130=65534") == 0, "A is Wayfinder's");
    hx_pad_down(); nlines = 0; nimbus(0, 0, 0, 0, 0, 0, NB_2);
    CHECK(said("W 304 1"), "down: Wayfinder's own outputs still go (W)");
    nimbus(0, 0, 0, 0, 0, 0, 0); hx_pad_up();

    // ── dev build 15: the pad's name as uinput keeps it (79 bytes, on a character boundary, " (Wayfinder)" kept) ──
    { char nm[HX_NAME_MAX + 1], in[400];
      hx_pad_name(nm, "Xbox Wireless Controller (Wayfinder)");
      CHECK(!strcmp(nm, "Xbox Wireless Controller (Wayfinder)"), "name: a short one as it is");
      hx_pad_name(nm, "A\tB\nC\x7f");
      CHECK(!strcmp(nm, "A B C "), "name: control characters become spaces");
      memset(in, 'x', 100); in[100] = 0; hx_pad_name(nm, in);
      CHECK(strlen(nm) == 79, "name: a long one cut to 79 bytes");
      memset(in, 'x', 90); strcpy(in + 90, " (Wayfinder)"); hx_pad_name(nm, in);
      CHECK(strlen(nm) == 79 && wf_wayfinder_name(nm) && nm[66] == 'x' && nm[67] == ' ', "name: cut before its \" (Wayfinder)\", kept");
      in[0] = 0; for (int i = 0; i < 50; i++) strcat(in, "\xc3\xa9"); strcat(in, " (Wayfinder)"); hx_pad_name(nm, in);
      CHECK(strlen(nm) == 66 + 12 && wf_wayfinder_name(nm) && (unsigned char)nm[65] == 0xa9, "name: 2-byte characters never split (%d)", (int)strlen(nm));
      in[0] = 0; for (int i = 0; i < 20; i++) strcat(in, "\xf0\x9f\x98\x80"); strcat(in, " (Wayfinder)"); hx_pad_name(nm, in);
      CHECK(strlen(nm) == 64 + 12 && wf_wayfinder_name(nm), "name: 4-byte characters never split (%d)", (int)strlen(nm));
      in[0] = 0; for (int i = 0; i < 45; i++) strcat(in, "\xc3\xa9"); hx_pad_name(nm, in);
      CHECK(strlen(nm) == 78, "name: without the ending, cut on a boundary too (%d)", (int)strlen(nm));
      memset(in, 'x', 67); strcpy(in + 67, " (Wayfinder)"); hx_pad_name(nm, in);
      CHECK(!strcmp(nm, in), "name: exactly 79 bytes: as it is"); }

    // ── fuzz: away and back with any descriptors ───────────────────
    for (int round = 0; round < 500; round++) {
        fresh();
        uint8_t d[300]; int len = (int)(rnd() % sizeof d);
        for (int i = 0; i < len; i++) d[i] = (uint8_t)rnd();
        if (round % 3 == 0) for (int i = 0; i + 1 < len; i += 2) d[i] = (uint8_t)(0x80 | (rnd() & 0x7C) | 1);
        hx_add_source(0, 0x1234, 0x5678, d, len); hx_apply_map();
        int nf0 = HX.nf; hx_field f0; memset(&f0, 0, sizeof f0); if (nf0) f0 = HX.F[0];
        for (int k = 0; k < 10; k++) { uint8_t r[HX_MAXR]; int rl = (int)(rnd() % sizeof r) + 1; for (int i = 0; i < rl; i++) r[i] = (uint8_t)rnd(); hx_report(0, r, rl); }
        hx_detach(); hx_attach_begin();
        if (round % 4) { len = (int)(rnd() % sizeof d); for (int i = 0; i < len; i++) d[i] = (uint8_t)rnd(); }
        hx_add_source(0, 0x1234, 0x5678, d, len);
        int r = hx_attach_end(1);
        if (HX.nf > HX_MAXF) { CHECK(0, "fuzz away and back: fields bounded"); break; }
        if (r != 0 && (HX.nf != nf0 || (nf0 && memcmp(&HX.F[0], &f0, sizeof f0)) || !HX.idle)) { CHECK(0, "fuzz: refused, the old layout back (round %d)", round); break; }
        if (r == 0 && (HX.nf != nf0 || HX.idle)) { CHECK(0, "fuzz: taken, the same layout (round %d)", round); break; }
        for (int k = 0; k < 10; k++) { uint8_t rr[HX_MAXR]; int rl = (int)(rnd() % sizeof rr) + 1; for (int i = 0; i < rl; i++) rr[i] = (uint8_t)rnd(); hx_report(0, rr, rl); }
    }
    CHECK(1, "fuzz: away and back with any descriptors");

    // ── dev build 17 (theme G): the Thor's Back and AYN button on every controller, analog triggers, the watch, Home +
    //    Back held 5 s ──
    // G1. defaults from the decoders (D4): a DualSense's touchpad click = its Back, its Mute = its AYN button; a DualShock 4
    //     has no Mute; a Sony pad read from its own descriptor: button 14 (the touchpad click)
    fresh(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0); hx_apply_map();
    CHECK(HX.map_f[T_BK] >= 0 && HX.map_f[T_BK] == hx_find(0, SP, 14) && HX.map_f[T_AY] >= 0 && HX.map_f[T_AY] == hx_find(0, SP, 15),
          "G1 DualSense: BK = the touchpad click, AY = Mute (%d %d)", HX.map_f[T_BK], HX.map_f[T_AY]);
    nlines = 0; hx_print_map();
    CHECK(saidhas("D ", " BK=") && saidhas("D ", " AY="), "G1 the D line names BK and AY");
    fresh(); hx_add_source_bus(0, 5, 0x054c, 0x09cc, 0, 0); hx_apply_map();
    CHECK(HX.map_f[T_BK] >= 0 && HX.map_f[T_BK] == hx_find(0, SP, 14) && HX.map_f[T_AY] == -1, "G1 DualShock 4: BK = the touchpad click, no AY (no Mute)");
    nlines = 0; hx_print_map();
    CHECK(saidhas("D ", " BK=") && !saidhas("D ", " AY="), "G1 ...its D line: BK, no AY");
    {   static const uint8_t S14[] = { 0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x04,
            0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x81, 0x02, 0x05, 0x09, 0x19, 0x01, 0x29, 0x0e, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x0e, 0x81, 0x02, 0x75, 0x02, 0x95, 0x01, 0x81, 0x03, 0xc0 };
        fresh(); hx_add_source_bus(0, 3, 0x054c, 0x1234, S14, sizeof S14); hx_apply_map();
        CHECK(!HX.S[0].sony && HX.map_f[T_BK] == hx_find(0, 9, 14) && HX.map_f[T_BK] >= 0 && HX.map_f[T_HO] == hx_find(0, 9, 13),
              "G1 a Sony pad from its own descriptor: button 14 = its Back, 13 its Home"); }
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map();
    CHECK(HX.map_f[T_BK] < 0 && HX.map_f[T_AY] < 0, "G1 Nimbus+: no Back, no AYN of its own (Home + Select / Start stand in, app side)");

    // G2. KEY_BACK declared on every controller's pad; a profile can't change Back
    { int has = 0; for (int i = 0; i < HX.ws.nk; i++) if (HX.ws.keys[i] == WF_BACK) has = 1;
      CHECK(has, "G2 KEY_BACK declared (Nimbus+, no Back of its own: Wayfinder hands it one)"); }
    fresh(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0); hx_apply_map();
    { int has = 0; for (int i = 0; i < HX.ws.nk; i++) if (HX.ws.keys[i] == WF_BACK) has = 1;
      CHECK(has && hx_profile("k0x9e=0x130") == -1, "G2 DualSense: KEY_BACK declared, a profile can't change it"); }

    // G3. its Back is the Thor's: KEY_BACK, it gates (a button under it is withheld for Wayfinder) unless the game's bg=1
    dsu(0, 0, 0, 8); memset(KEYEV, 0, sizeof KEYEV);
    dsu(0, 0, 2, 8);
    CHECK(KEY[WF_BACK] && KEYEV[WF_BACK] == 1, "G3 the touchpad click: KEY_BACK, one edge (%d)", KEYEV[WF_BACK]);
    nlines = 0; dsu(2, 0, 2, 8);
    CHECK(!KEY[0x131] && !KEYEV[0x131] && said("J 1 305 1"), "G3 ✕ under its Back: withheld (J 1 305 1), no edge");
    dsu(0, 0, 0, 8);
    CHECK(!KEY[WF_BACK] && KEYEV[WF_BACK] == 2 && !KEYEV[0x131], "G3 both let go: KEY_BACK 0, ✕ never reached the game");
    nlines = 0; dsu(2, 0, 2, 8);
    CHECK(KEY[WF_BACK] && !KEY[0x131] && said("J 1 305 1"), "G3 Back + ✕ in ONE report: Back first (it gates), ✕ withheld");
    dsu(0, 0, 0, 8);
    CHECK(hx_profile("bg=1") == 0, "G3 the game's profile gives Back to the game (bg=1)");
    dsu(0, 0, 2, 8); nlines = 0; dsu(2, 0, 2, 8);
    CHECK(KEY[WF_BACK] && KEY[0x131] && !said("J "), "G3 bg=1: ✕ under its Back reaches the game (RetroArch's Back hotkeys)");
    dsu(0, 0, 0, 8); CHECK(hx_profile("") == 0, "G3 profile cleared");

    // b18g (B4): its Back pressed while a game button is already held (Create + Back: RetroArch's Select + Back hotkey) goes
    //    WITH the game, as the app's backToGameNow takes it: no gate, the held button stays down (g2 R6 saw SELECT UP +
    //    KEY_BACK DOWN in one report on the Thor's copy). Pressed in the SAME report, Back comes first: it gates, as G3
    dsu(0, 16, 0, 8); memset(KEYEV, 0, sizeof KEYEV); nlines = 0;
    dsu(0, 16, 2, 8);
    CHECK(KEY[0x13a] && KEY[WF_BACK] && !KEYEV[0x13a] && !said("J ") && HX.ws.back_hot && !HX.ws.gated,
          "B4 Create held, then its Back: Select stays down (no edge, no J), KEY_BACK down");
    dsu(2, 16, 2, 8);
    CHECK(KEY[0x131] && !said("J "), "B4 ✕ under that Back: reaches the game, nothing echoed");
    dsu(0, 16, 0, 8);
    CHECK(KEY[0x13a] && !KEY[WF_BACK] && !KEY[0x131] && !KEYEV[0x13a] && !HX.ws.back_hot, "B4 Back up: Select still down, never lifted");
    dsu(0, 0, 0, 8);
    CHECK(!KEY[0x13a] && KEYEV[0x13a] == 1, "B4 Create let go: its one edge (%d)", KEYEV[0x13a]);
    nlines = 0; dsu(0, 16, 2, 8);
    CHECK(KEY[WF_BACK] && !KEY[0x13a] && said("J 1 314 1") && HX.ws.gated && !HX.ws.back_hot,
          "B4 Create + Back in ONE report: Back first, it gates (Create withheld: J 1 314 1)");
    dsu(0, 0, 0, 8);
    CHECK(!KEY[WF_BACK] && !KEY[0x13a] && !HX.ws.gated, "B4 all let go");
    nlines = 0; dsu(0, 16, 0, 8); dsu(0, 16, 2, 8); CHECK(hx_profile("sw=1") == 0 && !HX.ws.gated && KEY[0x13a],
          "B4 the game's profile changed under that Back (wfhid's P): still the game's, Select kept");
    dsu(0, 0, 0, 8); CHECK(hx_profile("") == 0 && !KEY[0x13a] && !KEY[WF_BACK], "B4 profile cleared, all let go");
    // b18h (review): Create let go in the report that presses its Back — let go first, as our pad writes it (Select UP
    //    before KEY_BACK DOWN): the app sees nothing held, so that Back gates here too. b18l: Back's press in a report of
    //    its own, after Select's release (two frames)
    dsu(0, 16, 0, 8); memset(KEYEV, 0, sizeof KEYEV); nlines = 0;
    { int s0 = syncs; nevt = 0; dsu(0, 0, 2, 8);
      CHECK(HX.ws.gated && !HX.ws.back_hot && !KEY[0x13a] && KEY[WF_BACK] && KEYEV[0x13a] == 1 && KEYEV[WF_BACK] == 1 &&
            KEYSEQ[0x13a] < KEYSEQ[WF_BACK] && syncs == s0 + 2 && rep_of(EV_KEY, 0x13a, 0) == 0 &&
            rep_of(EV_KEY, WF_BACK, 1) == 1 && rep_size(1) == 1 && !said("J "),
            "B4h Create let go + its Back in ONE report: Select UP, SYN, then KEY_BACK DOWN alone, SYN; Back gates"); }
    nlines = 0; dsu(2, 0, 2, 8);
    CHECK(!KEY[0x131] && said("J 1 305 1"), "B4h ...✕ under that Back: withheld (J 1 305 1)");
    dsu(0, 0, 0, 8); CHECK(!KEY[WF_BACK] && !KEY[0x131] && !HX.ws.gated, "B4h ...all let go");
    // ...a press in that report still comes after Back (G3 / B4 above): Create let go + ✕ pressed + Back, one report
    dsu(0, 16, 0, 8); nlines = 0; dsu(2, 0, 2, 8);
    CHECK(HX.ws.gated && !KEY[0x13a] && !KEY[0x131] && said("J 1 305 1"), "B4h Create up + ✕ down + Back, one report: ✕ withheld");
    dsu(0, 0, 0, 8);
    // b18h (review): a key the app never names (MODE: its Home given to the game, hx_home(0)) held + its Back: gates
    hx_home(0); dsu(0, 0, 1, 8);
    CHECK(KEY[0x13c] && !KEY[WF_HOME], "B4h PS = the game's MODE, held");
    nlines = 0; dsu(0, 0, 3, 8);
    CHECK(HX.ws.gated && !HX.ws.back_hot && KEY[WF_BACK] && !KEY[0x13c], "B4h MODE held + its Back: gates (the app has no MODE)");
    dsu(2, 0, 3, 8); CHECK(!KEY[0x131] && said("J 1 305 1"), "B4h ...✕ under it: withheld");
    dsu(0, 0, 0, 8); hx_home(1); CHECK(!KEY[WF_BACK] && !KEY[0x13c] && !HX.ws.gated, "B4h ...all let go, Home the Thor's again");
    // a turbo button held (the copy alternates it) + its Back: gates — the app may or may not see it held at that moment
    CHECK(hx_profile("f0x139=1") == 0, "B4h R2 turbo");
    at(5000); dsu(0, 8, 0, 8); CHECK(KEY[WF_R2], "B4h turbo R2 held: on");
    nlines = 0; at(5050); dsu(0, 8, 2, 8);
    CHECK(HX.ws.gated && !HX.ws.back_hot && !KEY[WF_R2] && KEY[WF_BACK], "B4h turbo R2 held + its Back: gates");
    dsu(0, 0, 0, 8); CHECK(hx_profile("") == 0 && !KEY[WF_R2] && !KEY[WF_BACK] && !HX.ws.gated, "B4h ...profile cleared, all let go");

    // b18l (GitHub #81, b18w B1): its Home's change goes out in a report of its own, before the gate lets go of what is held
    //    (Home's press) or gives it back (Home's release); its Back's release first, its Back's press after what that gate
    //    lets go. In one report Home came last (its code after the buttons): X let go before Home's press was no partner of
    //    it in the app (Home went home), R2 given back before Home's release made "Home + R2" (no home)
    dsu(0, 0, 0, 8); nlines = 0;
    dsu(0, 8, 0, 8); CHECK(KEY[WF_R2], "b18l R2 held");
    nevt = 0; dsu(0, 8, 1, 8);
    CHECK(KEY[WF_HOME] && !KEY[WF_R2] && reps() == 2 && rep_of(EV_KEY, WF_HOME, 1) == 0 && rep_size(0) == 1 &&
          rep_of(EV_KEY, WF_R2, 0) == 1 && !said("J "), "b18l R2 held + PS (its Home): KEY_HOME DOWN alone, SYN, then R2 UP, SYN");
    nevt = 0; dsu(0, 8, 0, 8);
    CHECK(!KEY[WF_HOME] && KEY[WF_R2] && reps() == 2 && rep_of(EV_KEY, WF_HOME, 0) == 0 && rep_size(0) == 1 &&
          rep_of(EV_KEY, WF_R2, 1) == 1, "b18l ...PS let go: KEY_HOME UP alone, SYN, then R2 given back, SYN");
    // the control: R2 let go while its Home is held — Home's release alone
    dsu(0, 8, 1, 8); dsu(0, 0, 1, 8); nevt = 0; dsu(0, 0, 0, 8);
    CHECK(!KEY[WF_HOME] && !KEY[WF_R2] && reps() == 1 && rep_of(EV_KEY, WF_HOME, 0) == 0 && rep_size(0) == 1,
          "b18l ...R2 let go under its Home: Home's release alone, nothing given back");
    // △ (the printed X) a beat before its Home: X is still held on our pad when Home goes down
    dsu(8, 0, 0, 8); CHECK(KEY[WF_X], "b18l △ held: X");
    nevt = 0; dsu(8, 0, 1, 8);
    CHECK(reps() == 2 && rep_of(EV_KEY, WF_HOME, 1) == 0 && rep_size(0) == 1 && rep_of(EV_KEY, WF_X, 0) == 1,
          "b18l △ held + PS: KEY_HOME DOWN alone, SYN, then X UP, SYN");
    nlines = 0; dsu(0, 0, 1, 8);
    CHECK(!said("J "), "b18l-r ...△ let go under it: no J (held before PS: our pad let it go at PS's press)");
    nevt = 0; dsu(0, 0, 0, 8);
    CHECK(reps() == 1 && rep_of(EV_KEY, WF_HOME, 0) == 0, "b18l ...△ let go under it, then PS: Home's release alone");
    // its Back with a turbo R2 (in its on half; Back doesn't count it): R2 let go first, then KEY_BACK DOWN; at the release
    //    KEY_BACK UP first, then R2 given back
    CHECK(hx_profile("f0x139=1") == 0, "b18l R2 turbo");
    at(7000); dsu(0, 8, 0, 8); CHECK(KEY[WF_R2], "b18l turbo R2 held: on");
    nevt = 0; dsu(0, 8, 2, 8);
    CHECK(HX.ws.gated && reps() == 2 && rep_of(EV_KEY, WF_R2, 0) == 0 && rep_of(EV_KEY, WF_BACK, 1) == 1 && rep_size(1) == 1,
          "b18l turbo R2 + its Back (gates): R2 UP, SYN, then KEY_BACK DOWN alone, SYN");
    nevt = 0; dsu(0, 8, 0, 8);
    CHECK(!HX.ws.gated && reps() == 2 && rep_of(EV_KEY, WF_BACK, 0) == 0 && rep_size(0) == 1 && rep_of(EV_KEY, WF_R2, 1) == 1,
          "b18l ...its Back let go: KEY_BACK UP alone, SYN, then R2 given back, SYN");
    dsu(0, 0, 0, 8); CHECK(hx_profile("") == 0 && !KEY[WF_R2] && !KEY[WF_BACK] && !KEY[WF_HOME], "b18l ...profile cleared, all let go");
    // Create held + its Back (B4): no gate, nothing let go — Back alone both ways
    dsu(0, 16, 0, 8); nevt = 0; dsu(0, 16, 2, 8);
    CHECK(!HX.ws.gated && KEY[0x13a] && reps() == 1 && rep_of(EV_KEY, WF_BACK, 1) == 0 && rep_size(0) == 1,
          "b18l Create held + its Back: KEY_BACK DOWN alone, Select kept");
    nevt = 0; dsu(0, 16, 0, 8); CHECK(reps() == 1 && rep_of(EV_KEY, WF_BACK, 0) == 0 && rep_size(0) == 1, "b18l ...its release alone");
    dsu(0, 0, 0, 8);
    // a Home tap alone: one report each way
    nevt = 0; dsu(0, 0, 1, 8); CHECK(reps() == 1 && rep_size(0) == 1 && rep_of(EV_KEY, WF_HOME, 1) == 0, "b18l PS alone: one report");
    nevt = 0; dsu(0, 0, 0, 8); CHECK(reps() == 1 && rep_size(0) == 1 && rep_of(EV_KEY, WF_HOME, 0) == 0, "b18l ...its release: one report");
    // b18l review — docked, or the merged seat: wfpad's engine reads our pad, and its Home gates there too. Our pad lets go of
    //    what its Home lifts in a report AFTER Home; under wfpad's own gate that release must say no "J" (the hand still holds
    //    the button) — echoed, it could overtake the copy's KEY_HOME DOWN in the app and take △ out of what Home found held
    //    (case b). (a) docked must stay: Home's release first, then R2 back — never pressed under wfpad's gate (consumed)
    dsu(0, 0, 0, 8); dock_init();
    nevt = 0; dsu(8, 0, 0, 8); dock_feed();
    CHECK(DJ == 0 && DNE == 2 && de_is(0, EV_KEY, WF_X, 1), "b18l-r docked △ held: X on the copy (%d)", DNE);
    nevt = 0; nlines = 0; dsu(8, 0, 1, 8); dock_feed();
    CHECK(!said("J ") && DJ == 0, "b18l-r docked △ held + PS: no J from us nor from wfpad (%d)", DJ);
    CHECK(DNE == 4 && de_is(0, EV_KEY, WF_HOME, 1) && de_syn(1) && de_is(2, EV_KEY, WF_X, 0) && de_syn(3),
          "b18l-r docked ...the copy: KEY_HOME DOWN, SYN, then X UP, SYN (%d)", DNE);
    nevt = 0; nlines = 0; dsu(0, 0, 1, 8); dock_feed();
    CHECK(!said("J ") && nevt == 0 && DNE == 0, "b18l-r docked ...△ let go under PS: no J, nothing on our pad or the copy");
    nevt = 0; dsu(0, 0, 0, 8); dock_feed();
    CHECK(DJ == 0 && DNE == 2 && de_is(0, EV_KEY, WF_HOME, 0), "b18l-r docked ...PS let go: the copy's Home release alone (%d)", DNE);
    nevt = 0; dsu(0, 8, 0, 8); dock_feed();
    nevt = 0; dsu(0, 8, 1, 8); dock_feed();
    CHECK(DJ == 0 && DNE == 4 && de_is(0, EV_KEY, WF_HOME, 1) && de_is(2, EV_KEY, WF_R2, 0),
          "b18l-r docked R2 held + PS: KEY_HOME DOWN, then R2 UP (%d %d)", DJ, DNE);
    nevt = 0; dsu(0, 8, 0, 8); dock_feed();
    CHECK(DJ == 0 && DNE == 4 && de_is(0, EV_KEY, WF_HOME, 0) && de_is(2, EV_KEY, WF_R2, 1) && !D.ckey[WF_R2] && DO.key[WF_R2],
          "b18l-r docked ...PS let go: KEY_HOME UP, then R2 DOWN on the copy, never consumed (%d %d)", DJ, DNE);
    nevt = 0; dsu(0, 0, 0, 8); dock_feed();
    nevt = 0; dsu(0, 0, 1, 8); dock_feed();
    nevt = 0; nlines = 0; dsu(8, 0, 1, 8); dock_feed();
    CHECK(said("J 1 307 1") && nevt == 0 && DJ == 0 && DNE == 0, "b18l-r docked PS + △ under it: our J 1 307 1, nothing for wfpad or the copy");
    nevt = 0; nlines = 0; dsu(0, 0, 1, 8); dock_feed();
    CHECK(said("J 1 307 0") && nevt == 0 && DNE == 0, "b18l-r docked ...△ let go: our J 1 307 0 (Wayfinder had its press)");
    nevt = 0; dsu(0, 0, 0, 8); dock_feed();
    CHECK(DNE == 2 && de_is(0, EV_KEY, WF_HOME, 0) && !KEY[WF_X] && !DO.key[WF_X], "b18l-r docked ...PS let go: Home's release alone");

    // G4. Home + Back + ✕ in ONE report: Home, then Back, then ✕ — withheld
    nlines = 0; dsu(2, 0, 3, 8);
    CHECK(KEY[WF_HOME] && KEY[WF_BACK] && !KEY[0x131] && said("J 1 305 1"), "G4 Home + Back + ✕ in one report: ✕ withheld (J)");
    dsu(0, 0, 0, 8);
    CHECK(!KEY[WF_HOME] && !KEY[WF_BACK] && !KEY[0x131], "G4 all let go");

    // G5. its AYN button (Mute): an "A" line, never a key; learn mode: no A; gone while held: A 0
    memset(KEYEV, 0, sizeof KEYEV); nlines = 0; dsu(0, 0, 4, 8);
    { int any = 0; for (int k = 0; k < WF_NK; k++) any |= KEYEV[k];
      CHECK(said("A 1") && nlines == 1 && !any, "G5 Mute: A 1, no key edge at all (%d lines)", nlines); }
    nlines = 0; dsu(0, 0, 4, 8);
    CHECK(!said("A "), "G5 still held: said once");
    nlines = 0; dsu(0, 0, 0, 8);
    CHECK(said("A 0") && nlines == 1, "G5 let go: A 0");
    hx_learn_start(); nlines = 0; dsu(0, 0, 4, 8); dsu(0, 0, 0, 8);
    CHECK(!said("A ") && said("C "), "G5 learn mode: no A line (Mute is a field to learn)");
    hx_learn_stop();
    dsu(0, 0, 4, 8); nlines = 0; hx_detach();
    CHECK(said("A 0") && !HX.ayn, "G5 gone with Mute held: A 0");

    // G6. a Back Wayfinder hands to the game ("p 158"): out at once, its Home held or not; it gates nothing
    fresh(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0); hx_apply_map(); dsu(0, 0, 0, 8);
    dsu(0, 0, 1, 8);
    CHECK(KEY[WF_HOME] && hx_vpress(WF_BACK, 1) == 0 && KEY[WF_BACK], "G6 p 158 1 with PS held: KEY_BACK out at once");
    CHECK(hx_vpress(WF_BACK, 0) == 0 && !KEY[WF_BACK], "G6 p 158 0: let go");
    dsu(0, 0, 0, 8);
    hx_vpress(WF_BACK, 1); dsu(2, 0, 0, 8);
    CHECK(KEY[WF_BACK] && KEY[0x131], "G6 a Back handed to the game gates nothing (✕ reaches it)");
    dsu(0, 0, 0, 8);
    CHECK(hx_vpress(0, 0) == 0 && !KEY[WF_BACK] && !HX.vback, "G6 p 0 0 lets it go too");
    hx_vpress(WF_BACK, 1); hx_release_all();
    CHECK(!KEY[WF_BACK] && !HX.vback, "G6 let go with everything (learn mode, turning, gone)");
    CHECK(hx_vpress(WF_BACK, 2) == -1 && hx_vpress(WF_HOME, 1) == -1, "G6 p 158 2 and Home refused");

    // G7. Home + Back held 5 s: its controls off ("M 1": plain buttons, no motion aim) until "u"; once per hold
    fresh(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0); hx_apply_map(); dsu(0, 0, 0, 8);
    CHECK(hx_profile("k0x131=0x130") == 0, "G7 a profile: ✕ (the bottom, B) gives A");
    dsu(2, 0, 0, 8); CHECK(KEY[0x130] && !KEY[0x131], "G7 ...✕ gives A"); dsu(0, 0, 0, 8);
    HX.ws.gyro_stick = 2;
    HX.now_ms = 10000; dsu(0, 0, 3, 8);
    HX.now_ms = 14900; nlines = 0; dsu(0, 0, 3, 8);
    CHECK(!said("M ") && !HX.muted && hx_wait() == 100, "G7 PS + touchpad 4.9 s: nothing yet, due in 100 ms (%d)", hx_wait());
    HX.now_ms = 15000; hx_tick();
    CHECK(said("M 1") && HX.muted && !HX.ws.gyro_stick, "G7 held 5 s, no new report (it reports on change): M 1, motion aim off");
    nlines = 0; HX.now_ms = 21000; dsu(0, 0, 3, 8); hx_tick();
    CHECK(!said("M ") && hx_wait() < 0, "G7 said once per hold, nothing more to wait for");
    dsu(0, 0, 0, 8);
    dsu(2, 0, 0, 8); CHECK(KEY[0x131] && !KEY[0x130], "G7 its controls off: ✕ plays its own button (B)"); dsu(0, 0, 0, 8);
    nlines = 0;
    CHECK(hx_profile("k0x131=0x130") == 1 && hx_profile("k0x131=0x999") == 1 && !nlines, "G7 profiles while off: nothing applied, nothing said");
    CHECK(hx_profile("bg=1") == 1 && HX.wm.back_free && !HX.wm.keymap[0x131], "G7 ...only the game's bg=1 follows (critique F2)");
    hx_mute(0);
    CHECK(said("M 0") && !HX.muted && HX.ws.gyro_stick == 2, "G7 u: M 0, motion aim back");
    CHECK(hx_profile("k0x131=0x130") == 0, "G7 ...the helper's profile applies again");
    dsu(2, 0, 0, 8); CHECK(KEY[0x130], "G7 ✕ gives A again"); dsu(0, 0, 0, 8);
    // docked (critique F4): the app turns it back on at once — still held, it doesn't go off again until let go
    HX.now_ms = 30000; dsu(0, 0, 3, 8); HX.now_ms = 35000; hx_tick();
    CHECK(HX.muted, "G7 held 5 s again: off");
    hx_mute(0); nlines = 0; HX.now_ms = 41000; dsu(0, 0, 3, 8); hx_tick();
    CHECK(!HX.muted && !said("M "), "G7 u while both are still held: not off again until let go");
    dsu(0, 0, 0, 8); HX.now_ms = 42000; dsu(0, 0, 3, 8); HX.now_ms = 47000; dsu(0, 0, 3, 8);
    CHECK(HX.muted, "G7 ...let go and held 5 s again: off");
    dsu(0, 0, 0, 8); hx_mute(0);
    // a pad without its own Back (Nimbus+): never (its Back is Home + Select, app side)
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    HX.now_ms = 1000; nimbus(0, 0, 0, 0, 0, 0, NB_HOME | NB_SELECT); HX.now_ms = 9000; nimbus(0, 0, 0, 0, 0, 0, NB_HOME | NB_SELECT); hx_tick();
    CHECK(!HX.muted && hx_wait() < 0, "G7 Nimbus+ Home + Select 8 s: nothing (no Back of its own)");
    nimbus(0, 0, 0, 0, 0, 0, 0);

    // G8. analog-only triggers (the test tool's Xbox pad: LT / RT are Z / Rz) press L2 / R2 at 30 %, let go below 20 %
    {   uint8_t x11[sizeof XOLD]; memcpy(x11, XOLD, sizeof XOLD); x11[36] = 0x0b; x11[44] = 0x0b; x11[48] = 0x05;
        fresh(); hx_add_source_bus(0, 5, 0x045e, 0x0b13, x11, sizeof x11); hx_apply_map();
        CHECK(HX.map_f[T_L2] < 0 && HX.map_f[T_LT] >= 0, "G8 test Xbox pad: no L2 button, LT an axis");
        xrep(0, 0, 0); memset(KEYEV, 0, sizeof KEYEV);
        xrep(25, 0, 0);
        CHECK(!KEY[WF_L2] && ABSV[WF_BRAKE] > 7000 && ABSV[WF_BRAKE] < 9000, "G8 LT 25 %%: no L2, the pull (%d)", ABSV[WF_BRAKE]);
        xrep(31, 0, 0);
        CHECK(KEY[WF_L2] && KEYEV[WF_L2] == 1, "G8 LT 31 %%: L2 pressed, one edge");
        xrep(21, 0, 0);
        CHECK(KEY[WF_L2] && KEYEV[WF_L2] == 1 && ABSV[WF_BRAKE] > 6000, "G8 LT 21 %%: still pressed, the pull followed (%d)", ABSV[WF_BRAKE]);
        xrep(19, 0, 0);
        CHECK(!KEY[WF_L2] && KEYEV[WF_L2] == 2 && ABSV[WF_BRAKE] > 5000, "G8 LT 19 %%: let go (%d)", ABSV[WF_BRAKE]);
        xrep(0, 35, 0); CHECK(KEY[WF_R2] && !KEY[WF_L2], "G8 RT 35 %%: R2");
        xrep(0, 0, 0); CHECK(!KEY[WF_R2] && !ABSV[WF_GAS], "G8 RT let go");
        // Home + LT: L2 to Wayfinder (Home + L2), never to the game
        xrep(0, 0, 0x400); nlines = 0; memset(KEYEV, 0, sizeof KEYEV);
        xrep(100, 0, 0x400);
        CHECK(KEY[WF_HOME] && said("J 1 312 1") && !KEYEV[WF_L2] && !KEY[WF_L2], "G8 Home + LT: J 1 312 1 (Home + L2), no L2 edge");
        xrep(0, 0, 0x400); xrep(0, 0, 0);
        CHECK(!KEYEV[WF_L2] && !KEY[WF_HOME], "G8 ...let go: L2 never reached the game");
        // "Triggers all-or-nothing" (td=1): the press point is a full pull; a changed L2 (L2 → A) follows the pull
        CHECK(hx_profile("td=1") == 0, "G8 profile td=1");
        xrep(35, 0, 0); CHECK(ABSV[WF_BRAKE] == 32767 && KEY[WF_L2], "G8 td=1: LT 35 %% = a full pull (%d)", ABSV[WF_BRAKE]);
        xrep(0, 0, 0);
        CHECK(hx_profile("k0x138=0x130") == 0, "G8 profile L2 → A");
        xrep(40, 0, 0); CHECK(KEY[0x130] && !KEY[WF_L2] && !ABSV[WF_BRAKE], "G8 L2 → A: LT 40 %% presses A");
        xrep(0, 0, 0); CHECK(!KEY[0x130] && hx_profile("") == 0, "G8 ...let go");
        // G14. quiet: an LT held 40 % as learn mode ends gives no L2 until let go
        hx_learn_start(); xrep(40, 0, 0); hx_learn_stop(); memset(KEYEV, 0, sizeof KEYEV);
        xrep(40, 0, 0);
        CHECK(HX.quiet && !KEY[WF_L2] && !KEYEV[WF_L2], "G14 LT held 40 %% as learn mode ends: quiet, no L2 edge");
        xrep(0, 0, 0); xrep(40, 0, 0);
        CHECK(!HX.quiet && KEY[WF_L2] && KEYEV[WF_L2] == 1, "G14 ...let go, then pulled: L2");
        xrep(0, 0, 0); }

    // G9. a Nimbus+'s L2 / R2 pressures: the same rule (was: past half)
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); nimbus(0, 0, 0, 0, 0, 0, 0);
    nimbus(0, 0, 0, 0, 89, 0, 0); CHECK(KEY[WF_L2], "G9 Nimbus+ L2 at 35 %%: pressed");
    nimbus(0, 0, 0, 0, 60, 0, 0); CHECK(KEY[WF_L2], "G9 ...24 %%: still pressed");
    nimbus(0, 0, 0, 0, 38, 0, 0); CHECK(!KEY[WF_L2], "G9 ...15 %%: let go");
    nimbus(0, 0, 0, 0, 0, 70, 0); CHECK(!KEY[WF_R2], "G9 R2 at 27 %%: not yet");
    nimbus(0, 0, 0, 0, 0, 80, 0); CHECK(KEY[WF_R2], "G9 R2 at 31 %%: pressed");
    nimbus(0, 0, 0, 0, 0, 0, 0);

    // G10. an Xbox Series pad (the test tool's "xs", synthetic): its Share (Consumer Record 0xB2) = its Back; Home = button 13,
    //      LT / RT on the Simulation page
    fresh(); hx_add_source_bus(0, 5, 0x045e, 0x0b13, XS, sizeof XS); hx_apply_map();
    CHECK(HX.nf == 23 && HX.map_f[T_BK] >= 0 && HX.map_f[T_BK] == hx_find(0, 0x0C, 0xB2), "G10 Xbox Series: Share = its Back (%d fields)", HX.nf);
    CHECK(HX.map_f[T_HO] == hx_find(0, 9, 13) && HX.map_f[T_LT] == hx_find(0, 2, 0xC5) && HX.map_f[T_RT] == hx_find(0, 2, 0xC4) &&
          HX.map_f[T_S] == hx_find(0, 9, 1) && HX.map_f[T_W] == hx_find(0, 9, 4) && HX.map_f[T_RX] == hx_find(0, 1, 0x32) && HX.map_f[T_AY] < 0,
          "G10 ...Home = button 13, LT / RT Simulation, A / X buttons 1 / 4, Z = right stick, no AYN");
    {   uint8_t r[17]; memset(r, 0, sizeof r); r[0] = 0x01; r[2] = r[4] = r[6] = r[8] = 0x80; hx_report(0, r, 17);
        r[16] = 0x01; hx_report(0, r, 17); CHECK(KEY[WF_BACK] && !KEY[WF_HOME], "G10 Share pressed: KEY_BACK");
        r[16] = 0; r[15] = 0x10; hx_report(0, r, 17); CHECK(KEY[WF_HOME] && !KEY[WF_BACK], "G10 the Xbox button: Home");
        r[15] = 0; r[10] = 0x02; hx_report(0, r, 17);
        CHECK(KEY[WF_L2] && ABSV[WF_BRAKE] > 15000 && ABSV[WF_BRAKE] < 18000, "G10 LT (Brake) half: L2 and the pull (%d)", ABSV[WF_BRAKE]);
        r[10] = 0; r[14] = 0x01; hx_report(0, r, 17);
        CHECK(KEY[0x131] && !KEY[WF_L2], "G10 A = the bottom button");
        r[14] = 0; hx_report(0, r, 17); }

    // G11. Nintendo: a pair's Capture (the left one's) = its Back; a lone left Joy-Con upright: Capture = its Home; a Pro:
    //      Capture = its Back
    fresh(); hx_add_source(0, 0x057e, 0x2006, 0, 0); hx_add_source(1, 0x057e, 0x2007, 0, 0); hx_apply_map();
    CHECK(HX.map_f[T_BK] >= 0 && HX.map_f[T_BK] == hx_find(0, NP, 0x16) && HX.map_f[T_HO] == hx_find(1, NP, 0x15) && HX.map_f[T_AY] < 0,
          "G11 a Joy-Con pair: the left one's Capture = its Back, Home the right one's, no AYN");
    fresh(); hx_add_source(0, 0x057e, 0x2006, 0, 0); hx_apply_map();
    CHECK(HX.map_f[T_HO] >= 0 && HX.map_f[T_HO] == hx_find(0, NP, 0x16) && HX.map_f[T_BK] < 0 && HX.map_f[T_AY] < 0,
          "G11 a lone left Joy-Con upright: its Capture = its Home, no Back");
    jc(0, 0, 0, 0, 2000, 2000, 0, 0); jc(0, 0, 0x20, 0, 2000, 2000, 0, 0);
    CHECK(KEY[WF_HOME], "G11 ...Capture pressed: the Thor's Home");
    jc(0, 0, 0, 0, 2000, 2000, 0, 0);
    hx_orient(1);
    CHECK(HX.map_f[T_HO] == hx_find(0, NP, 0x16) && HX.map_f[T_BK] < 0, "G11 ...sideways: Capture its Home (as before), no Back");
    fresh(); hx_add_source(0, 0x057e, 0x2009, 0, 0); hx_apply_map();
    CHECK(HX.map_f[T_BK] >= 0 && HX.map_f[T_BK] == hx_find(0, NP, 0x16) && HX.map_f[T_HO] == hx_find(0, NP, 0x15),
          "G11 a Pro Controller: Capture = its Back, Home its Home");
    fresh(); hx_add_source(0, 0x057e, 0x2007, 0, 0); hx_apply_map();
    CHECK(HX.map_f[T_BK] < 0 && HX.map_f[T_AY] < 0 && HX.map_f[T_HO] == hx_find(0, NP, 0x15), "G11 a lone right Joy-Con: no Back, no AYN of its own");

    // G12. a mapping saved by the wizard before build 17 keeps its meaning; the new targets come from the default only
    //      when it names none of them and their field is free
    fresh(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0); hx_apply_map();
    {   int tp = hx_find(0, SP, 14), mu = hx_find(0, SP, 15), ho = hx_find(0, SP, 13); char m[96];
        snprintf(m, sizeof m, "S=6 E=7 W=5 N=8 HO=%d", ho);
        CHECK(hx_parse_map(m) == 0 && HX.map_f[T_BK] == tp && HX.map_f[T_AY] == mu && HX.map_f[T_S] == 6 && HX.map_f[T_L1] == -1,
              "G12 a DualSense mapping from before build 17: its Back and AYN filled, the rest as set");
        CHECK(hx_parse_map("S=6 E=7 BK=-1") == 0 && HX.map_f[T_BK] == -1 && HX.map_f[T_AY] == mu, "G12 BK=-1 (Not on it): stays none");
        snprintf(m, sizeof m, "S=6 L3=%d", tp);
        CHECK(hx_parse_map(m) == 0 && HX.map_f[T_BK] == -1 && HX.map_f[T_L3] == tp && HX.map_f[T_AY] == mu,
              "G12 one using the touchpad click for L3: no Back filled (its field is taken)");
        snprintf(m, sizeof m, "S=6 BK=%d AY=-1", mu);
        CHECK(hx_parse_map(m) == 0 && HX.map_f[T_BK] == mu && HX.map_f[T_AY] == -1, "G12 a build-17 mapping naming both: as set");
        CHECK(hx_parse_map("") == 0 && HX.map_f[T_BK] == tp && HX.map_f[T_AY] == mu, "G12 back to the default: both");
        // kept when the controller is away and back
        CHECK(hx_parse_map("S=6 E=7") == 0, "G12 an older mapping");
        hx_detach(); hx_attach_begin(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0);
        CHECK(hx_attach_end(1) == 0 && HX.map_f[T_S] == 6 && HX.map_f[T_BK] == tp && HX.map_f[T_AY] == mu, "G12 ...back: the same filled mapping"); }
    fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map();
    CHECK(hx_parse_map("S=10 E=11") == 0 && HX.map_f[T_HO] < 0, "G12 a Nimbus+ mapping without Home: none filled (only a lone left Joy-Con's)");
    fresh(); hx_add_source(0, 0x057e, 0x2006, 0, 0); hx_apply_map();
    {   int cap = hx_find(0, NP, 0x16), minus = hx_find(0, NP, 0x11); char m[64];
        snprintf(m, sizeof m, "SE=%d L1=%d", minus, hx_find(0, NP, 0x27));
        CHECK(hx_parse_map(m) == 0 && HX.map_f[T_HO] == cap && HX.map_f[T_SE] == minus, "G12 a lone left Joy-Con's mapping without Home: Capture filled");
        snprintf(m, sizeof m, "SE=%d", cap);
        CHECK(hx_parse_map(m) == 0 && HX.map_f[T_HO] == -1, "G12 ...one using Capture for Select: no Home filled");
        snprintf(m, sizeof m, "S=%d", hx_find(0, NP, 0x24));
        CHECK(hx_parse_map_o(m, 1) == 0, "G12 ...a sideways mapping without Home");
        hx_orient(1);
        CHECK(HX.map_f[T_HO] == -1, "G12 ...sideways: not filled (upright only)");
        hx_orient(0); }

    // G13. the watch ("w"): controls said as "V" lines while the app reads them; its D-pad while its AYN is held
    fresh(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0); hx_apply_map(); dsu(0, 0, 0, 8);
    nlines = 0; dsu(0, 0, 0, 0); dsu(0, 0, 0, 8);
    CHECK(!said("V "), "G13 not watched: no V line");
    CHECK(hx_watch(" a16 a17") == 0, "G13 w a16 a17");
    nlines = 0; dsu(0, 0, 0, 0);
    CHECK(said("V 3 17 -1") && nlines == 1, "G13 its D-pad up: V 3 17 -1 (%d lines)", nlines);
    nlines = 0; dsu(0, 0, 0, 8);
    CHECK(said("V 3 17 0") && nlines == 1, "G13 let go: V 3 17 0");
    CHECK(hx_watch(" k1 k2 k3 k4 k5") == -1 && HX.watch_abs[WF_HY] && !HX.watch_key[1], "G13 five controls: refused, the set in use kept");
    CHECK(hx_watch(" a16 x") == -1 && hx_watch(" a99") == -1 && hx_watch(" k0") == -1 && hx_watch(" a") == -1 && hx_watch(" a1x") == -1 &&
          hx_watch(" k-1") == -1 && HX.watch_abs[WF_HY], "G13 bad tokens refused");
    nlines = 0; dsu(0, 0, 1, 8); dsu(0, 0, 1, 0);
    CHECK(said("J 3 17 -1") && said("V 3 17 -1"), "G13 under its Home: J and V (the app's onHat takes one)");
    dsu(0, 0, 1, 8); dsu(0, 0, 0, 8);
    dsu(0, 0, 0, 6);
    nlines = 0;
    CHECK(hx_watch("") == 0 && said("V 3 16 0") && nlines == 1, "G13 the watch dropped while left is held: V 3 16 0, once");
    nlines = 0; dsu(0, 0, 0, 8); CHECK(!said("V "), "G13 ...its release: nothing more");
    nlines = 0; dsu(0, 0, 4, 8); dsu(0, 0, 4, 2);
    CHECK(said("A 1") && said("V 3 16 1"), "G13 its AYN held: its D-pad said without w (V 3 16 1)");
    nlines = 0; dsu(0, 0, 0, 2); dsu(0, 0, 0, 8);
    CHECK(said("A 0") && said("V 3 16 0"), "G13 AYN let go first: the D-pad's release still said");
    nlines = 0; dsu(0, 0, 4, 2); CHECK(said("A 1") && said("V 3 16 1"), "G13 AYN + right in ONE report: A 1, then V");
    dsu(0, 0, 0, 8);
    nlines = 0; dsu(0, 0, 0, 2); dsu(0, 0, 0, 8); CHECK(!said("V "), "G13 without AYN, not watched: no V");
    hx_watch(" a16 a17"); dsu(0, 0, 0, 4); nlines = 0; hx_detach();
    CHECK(said("V 3 17 0"), "G13 gone with the D-pad held: V 3 17 0");
    fresh(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0); hx_apply_map(); dsu(0, 0, 0, 8);
    CHECK(hx_watch(" k305") == 0, "G13 w k305 (✕, printed B)");
    nlines = 0; dsu(2, 0, 0, 8); CHECK(said("V 1 305 1"), "G13 a watched button: V 1 305 1");
    nlines = 0; hx_learn_start(); CHECK(said("V 1 305 0"), "G13 learn mode with it held: V 1 305 0 (the state is thrown away)");
    hx_learn_stop(); dsu(0, 0, 0, 8);

    // G14. quiet (more: G8): Mute held as learn mode ends says nothing until let go
    fresh(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0); hx_apply_map(); dsu(0, 0, 0, 8);
    hx_learn_start(); dsu(0, 0, 4, 8); hx_learn_stop();
    nlines = 0; dsu(0, 0, 4, 8);
    CHECK(HX.quiet && !said("A "), "G14 Mute held as learn mode ends: quiet, no A line");
    dsu(0, 0, 0, 8); nlines = 0; dsu(0, 0, 4, 8);
    CHECK(said("A 1") && !HX.quiet, "G14 ...let go, then pressed: A 1");
    dsu(0, 0, 0, 8);

    // G15. fuzz: a DualSense, a Nimbus+ and the Xbox Series layout with build 17's commands — no crash, every line well
    //      formed, A / M said on / off in turn, nothing said held is left held once it goes
    for (int round = 0; round < 500; round++) {
        int kind = round % 3; fresh();
        if (kind == 0) hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0);
        else if (kind == 1) hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS);
        else hx_add_source_bus(0, 5, 0x045e, 0x0b13, XS, sizeof XS);
        hx_apply_map();
        int ayn = 0, mut = 0, bad = 0; long t = 1000;
        static const char *const TOK[] = { "a16", "a17", "k305", "k304", "a9", "k99999", "x", "a1x", "a63", "k1" };
        for (int k = 0; k < 80 && !bad; k++) {
            unsigned c = rnd() % 16; t += (long)(rnd() % 1800); HX.now_ms = HX.ws.now_ms = t; nlines = 0;
            if (c < 9) {
                uint8_t r[64]; int len = kind == 0 ? 64 : kind == 1 ? 8 : 17;
                for (int i = 0; i < len; i++) r[i] = (uint8_t)rnd();
                if (kind != 1) r[0] = 0x01;
                if (kind == 0 && (rnd() & 1)) r[10] |= 0x03;                 /* PS + the touchpad held: its 5 s */
                hx_report(0, r, len);
            } else if (c == 9) {
                char w[96] = ""; int n = (int)(rnd() % 6);
                for (int i = 0; i < n; i++) { strcat(w, " "); strcat(w, TOK[rnd() % 10]); }
                hx_watch(w);
            } else if (c == 10) hx_vpress(WF_BACK, (int)(rnd() % 3));
            else if (c == 11) hx_mute(0);
            else if (c == 12) hx_profile(rnd() & 1 ? "bg=1" : "k0x131=0x130 td=1");
            else if (c == 13) hx_tick();
            else if (c == 14) { if (rnd() & 1) hx_learn_start(); else hx_learn_stop(); }
            else hx_parse_map(rnd() & 1 ? "BK=-1" : "");
            for (int i = 0; i < nlines && !bad; i++) {
                if (!well_formed(LINES[i])) bad = 1;
                if (LINES[i][0] == 'A') { int v = LINES[i][2] == '1'; if (v == ayn) bad = 2; ayn = v; }
                if (LINES[i][0] == 'M') { int v = LINES[i][2] == '1'; if (v == mut) bad = 3; mut = v; }
            }
            if (nlines >= 256) bad = 4;
        }
        nlines = 0; hx_detach();
        for (int i = 0; i < nlines && !bad; i++) {
            if (!well_formed(LINES[i])) bad = 1;
            if (LINES[i][0] == 'A') { int v = LINES[i][2] == '1'; if (v == ayn) bad = 2; ayn = v; }
        }
        int left = HX.ayn || HX.vback || ayn || KEY[WF_BACK];
        for (int i = 0; i < WF_NK; i++) left |= HX.vsaid_key[i];
        for (int a = 0; a < WF_NA; a++) left |= HX.vsaid_abs[a] != 0;
        if (bad || left) { CHECK(0, "G15 fuzz round %d: bad %d, left held %d (%s)", round, bad, left, nlines ? LINES[0] : "-"); break; }
    }
    CHECK(1, "G15 fuzz: build 17's lines well formed, A / M in turn, nothing left held");

    // ── dev build 18f: the touchpad moves the pointer ("t 1"; PlayStation pads) ─────────────────────
    {
        static const char *KN[4] = { "DualSense USB", "DualSense Bluetooth", "DualShock 4 USB", "DualShock 4 Bluetooth" };
        static const int RID[4] = { 0x01, 0x31, 0x01, 0x11 };
        hx_tpt q[2]; int dx, dy, v, h, n;
        // decode: one point's 4 bytes
        {   uint8_t b[8] = { 0x05, 0x34, 0x12, 0xAB, 0x85, 0x34, 0x12, 0xAB };
            n = hx_tp_points(b, q);
            CHECK(n == 1 && q[0].id == 5 && q[0].x == 0x234 && q[0].y == 0xAB1, "T decode: id 5, x 0x234, y 0xAB1; bit 7 = no finger (%d: %d %d %d)",
                  n, q[0].id, q[0].x, q[0].y);
            b[0] = 0x80; CHECK(hx_tp_points(b, q) == 0, "T decode: both bits 7 set: no finger");
            b[4] = 0x7F; CHECK(hx_tp_points(b, q) == 1 && q[0].id == 127 && q[0].x == 0x234 && q[0].y == 0xAB1, "T decode: point 1 alone"); }
        // each layout: where the points are, x / y / id, a drag right and a drag down
        for (int k = 0; k < 4; k++) {
            tp_source(k); TID = 0x7F; f1(10000, 1919, 1079);
            CHECK(HX.tp.n == 1 && HX.tp.lx == 1919 && HX.tp.ly == 1079 && HX.tp.lid == 127, "T %s: x / y / id decoded (%d %d %d)",
                  KN[k], HX.tp.lx, HX.tp.ly, HX.tp.lid);
            up(10010); CHECK(HX.tp.n == 0, "T %s: the finger bit", KN[k]);
            TID = 3; nlines = 0;
            for (int i = 0; i <= 10; i++) f1(10100 + i * 8, 500 + i * 40, 300);
            at(10300); hx_tick(); n = hm(&dx, &dy);
            CHECK(n > 0 && dx > 0 && dy == 0, "T %s: a drag right moves the pointer right (%d lines, %d %d)", KN[k], n, dx, dy);
            up(10400); nlines = 0;
            for (int i = 0; i <= 10; i++) f1(10500 + i * 8, 900, 200 + i * 40);
            at(10700); hx_tick(); n = hm(&dx, &dy);
            CHECK(n > 0 && dx == 0 && dy > 0, "T %s: a drag down moves it down (%d %d)", KN[k], dx, dy);
            up(10800);
        }
        // ids by bus, as hx_sony_fields: the other bus's report id moves nothing
        static const int OTHER[4] = { TK_DSB, TK_DSU, TK_D4B, TK_D4U };
        for (int k = 0; k < 4; k++) {
            tp_source(k); nlines = 0;
            for (int i = 0; i <= 10; i++) {
                at(10000 + i * 8); tsample s = { 1, 1, 500 + 40 * i, 500, 0, 0, 0, 0 };
                hx_report(0, TPR, tp_build(OTHER[k], 0, &s, 1));
            }
            at(10200); hx_tick();
            CHECK(!said("H ") && HX.tp.n == 0, "T %s: a report 0x%02x (the other bus's) moves nothing", KN[k], RID[OTHER[k]]);
        }
        // reports one byte short of each point: no line, nothing read past the end (the bytes there say "far away")
        static const int SHORT[4][3] = { { 36, 40, 0 }, { 37, 41, 0 }, { 33, 42, 51 }, { 35, 44, 53 } };
        for (int k = 0; k < 4; k++) {
            tp_source(k); TID = 2; f1(10000, 500, 500); f1(10008, 600, 500); f1(10016, 650, 500);
            for (int j = 0; j < 3; j++) {
                int L = SHORT[k][j]; if (!L) continue;
                tsample s[2] = { { 1, 2, 650, 500, 0, 0, 0, 0 }, { 1, 2, 1700, 900, 0, 0, 0, 0 } };
                if (k <= TK_DSB) s[0] = s[1];                 /* a DualSense: its one sample, far */
                tp_build(k, 0, s, 2);
                nlines = 0; at(10030 + j * 10); hx_report(0, TPR, L); at(10100 + j * 10); hx_tick();
                CHECK(!said("H m") && HX.tp.lx == 650 && HX.tp.n == 1, "T %s, %d bytes: no line, nothing read past the end (%d)", KN[k], L, HX.tp.lx);
            }
        }
        // DualShock 4: several touch reports in one input report — the bookkeeping from each, the motion from the newest
        // only, over the time since the previous input report (samples arriving together, at 2 ms each, would over-accelerate)
        {   static const int SPAN[2] = { 30, 6 }, DT[2] = { 4, 8 };
            int tot[2][2];
            for (int c = 0; c < 2; c++) for (int run = 0; run < 2; run++) {
                tp_source(TK_D4U); TID = 3; f1(10000, 500, 500); f1(10004, 600, 500);
                long t3 = 10004 + DT[c]; int s = SPAN[c];
                nlines = 0; at(t3);
                if (run == 0) {
                    tsample b[3] = { { 1, 3, 600 + s, 500, 0, 0, 0, 0 }, { 1, 3, 600 + 2 * s, 500, 0, 0, 0, 0 }, { 1, 3, 600 + 3 * s, 500, 0, 0, 0, 0 } };
                    hx_report(0, TPR, tp_build(TK_D4U, 0, b, 3));
                } else f1(t3, 600 + 3 * s, 500);
                at(t3 + 100); hx_tick(); hm(&dx, &dy); tot[c][run] = dx;
            }
            CHECK(tot[0][0] == tot[0][1] && tot[0][0] == 144, "T DS4 burst: 3 touch reports 30 apart, 4 ms after the last report = one 90-unit sample over 4 ms (%d %d)",
                  tot[0][0], tot[0][1]);
            CHECK(tot[1][0] == tot[1][1] && tot[1][0] == 18, "T DS4 burst, linear range: 3 × 6 units over 8 ms = one 18-unit sample (%d %d)", tot[1][0], tot[1][1]); }
        // the slop
        tp_source(TK_DSU); nlines = 0;
        f1(10000, 960, 540); f1(10050, 1000, 540); f1(10100, 960, 540); up(10350); at(10400); hx_tick();
        CHECK(!said("H "), "T slop: a 40-unit wiggle moves nothing (and 350 ms is no tap)");
        // the rate: a 300 ms drag at 4 ms reports
        {   tp_source(TK_DSB); nlines = 0;
            long last_m = -1000; int close = 0, nmv = 0;
            for (int i = 0; i <= 76; i++) {
                int before = nlines;
                if (i < 76) f1(10000 + i * 4, 400 + i * 8, 600); else { at(10400); hx_tick(); }
                for (int j = before; j < nlines; j++) if (!strncmp(LINES[j], "H m", 3)) { if (HX.now_ms - last_m < 8) close++; last_m = HX.now_ms; nmv++; }
            }
            hm(&dx, &dy);
            CHECK(!close && nmv > 10, "T rate: no two H m within 8 ms over a 300 ms drag at 4 ms reports (%d lines, %d close)", nmv, close);
            CHECK(dx > 240 && dx < 1020 && dy == 0, "T rate: their sum has the drag's sign and size (%d px for 600 units)", dx); }
        // leftovers: said by the timer (a pad reporting only on change sends nothing more)
        tp_source(TK_DSU); f1(10000, 500, 500); f1(10004, 600, 500);
        nlines = 0; f1(10007, 613, 500);
        CHECK(!said("H m") && HX.tp.pend_x == 20, "T leftovers: 20 px held back 3 ms after the last H m (%d)", HX.tp.pend_x);
        n = hx_wait(); CHECK(n >= 0 && n <= 5, "T leftovers: hx_wait %d ms (≤ 5)", n);
        at(10012); hx_tick(); CHECK(said("H m 20 0"), "T leftovers: hx_tick says the 20 px");
        // acceleration: the same distance, fast and slow
        {   int fast, slow;
            tp_source(TK_DSU); nlines = 0; for (int i = 0; i <= 5; i++) f1(10000 + i * 4, 500 + i * 40, 500);
            at(10200); hx_tick(); hm(&fast, &dy);
            tp_source(TK_DSU); nlines = 0; for (int i = 0; i <= 10; i++) f1(10000 + i * 50, 500 + i * 20, 500);
            at(11000); hx_tick(); hm(&slow, &dy);
            CHECK(fast > slow * 2 && slow > 0, "T acceleration: 200 units fast (%d px) > slow (%d px)", fast, slow); }
        // a tap
        tp_source(TK_DSU); nlines = 0;
        f1(20000, 960, 540); f1(20060, 970, 540); f1(20110, 980, 540); up(20120);
        CHECK(said("H b 1") && !said("H b 0") && !said("H m"), "T tap: 120 ms, 20 units → H b 1, no motion");
        n = hx_wait(); CHECK(n > 0 && n <= 40, "T tap: its release due in ≤ 40 ms (%d)", n);
        nlines = 0; at(20150); hx_tick(); CHECK(!said("H b 0"), "T tap: still held 30 ms later");
        at(20160); hx_tick(); CHECK(said("H b 0"), "T tap: H b 0 from hx_tick 40 ms later");
        // no tap when: 300 ms, 100 units, two fingers, the pad pressed, t 0, learn mode, muted
        tp_source(TK_DSU); nlines = 0; f1(20000, 960, 540); up(20300); CHECK(!said("H b"), "T no tap: 300 ms");
        tp_source(TK_DSU); nlines = 0; f1(20000, 960, 540); f1(20050, 1060, 540); up(20100); CHECK(!said("H b"), "T no tap: 100 units");
        tp_source(TK_DSU); nlines = 0; f2(20000, 900, 500, 1100, 500); up(20100); CHECK(!said("H "), "T no tap: two fingers");
        tp_source(TK_DSU); nlines = 0; f2(20000, 900, 500, 1100, 500); f1(20030, 900, 500); f1(20060, 1100, 500); up(20090);
        CHECK(!said("H "), "T two fingers then one: no tap, and the finger left moves nothing");
        tp_source(TK_DSU); nlines = 0; f1(20000, 960, 540); fc(20040, 960, 540); f1(20080, 960, 540); up(20100);
        CHECK(!said("H b"), "T no tap: the pad pressed during the touch");
        tp_source(TK_DSU); hx_touch_on(0); nlines = 0; f1(20000, 960, 540); up(20100); f1(20200, 960, 540); f1(20210, 1200, 540); at(20300); hx_tick();
        CHECK(!said("H "), "T t 0: no tap, no motion");
        tp_source(TK_DSU); hx_learn_start(); nlines = 0; f1(20000, 960, 540); up(20100); f1(20200, 960, 540); f1(20210, 1200, 540); at(20300); hx_tick();
        CHECK(!said("H "), "T learn mode: no tap, no motion"); hx_learn_stop();
        tp_source(TK_DSU); hx_mute(1); nlines = 0; f1(20000, 960, 540); up(20100); f1(20200, 960, 540); f1(20210, 1200, 540); at(20300); hx_tick();
        CHECK(!said("H "), "T muted: no tap, no motion"); hx_mute(0);
        // the pad pressed freezes the pointer for the rest of that touch
        tp_source(TK_DSU); nlines = 0;
        f1(20000, 500, 500); fc(20008, 500, 500); fc(20016, 700, 500); fc(20024, 900, 500); f1(20032, 1100, 500); f1(20040, 1300, 500);
        at(20100); hx_tick();
        CHECK(!said("H m") && !said("H b"), "T the pad pressed: no pointer motion for the rest of that touch, no tap");
        up(20200); nlines = 0; f1(20300, 500, 500); f1(20308, 700, 500); at(20400); hx_tick(); hm(&dx, &dy);
        CHECK(dx > 0, "T ...the next touch moves again (%d)", dx);
        // two fingers scroll, naturally: down → v > 0, right → h < 0
        tp_source(TK_DSU); nlines = 0;
        for (int i = 0; i <= 10; i++) f2(30000 + i * 20, 860, 300 + i * 40, 1060, 300 + i * 40);
        at(30400); hx_tick(); n = hw(&v, &h);
        CHECK(n > 0 && v > 0 && h == 0 && !said("H m"), "T two fingers down: H w, v > 0 (%d notches), no pointer", v);
        up(30500); nlines = 0;
        for (int i = 0; i <= 10; i++) f2(31000 + i * 20, 400 + i * 40, 500, 600 + i * 40, 500);
        at(31400); hx_tick(); n = hw(&v, &h);
        CHECK(n > 0 && h < 0 && v == 0, "T two fingers right: h < 0 (%d)", h);
        // a held tap's click let go when the state is thrown away
        tp_source(TK_DSU); f1(20000, 960, 540); up(20050); nlines = 0; hx_detach();
        CHECK(said("H b 0") && !HX.tp.btn, "T away with a tap's click held: H b 0");
        tp_source(TK_DSU); f1(20000, 960, 540); up(20050); nlines = 0; hx_learn_start();
        CHECK(said("H b 0"), "T learn mode with it held: H b 0"); hx_learn_stop();
        tp_source(TK_DSU); f1(20000, 960, 540); up(20050); nlines = 0; hx_mute(1);
        CHECK(said("H b 0"), "T muted with it held: H b 0"); hx_mute(0);
        tp_source(TK_DSU); f1(20000, 960, 540); up(20050); nlines = 0; hx_touch_on(0);
        CHECK(said("H b 0"), "T t 0 with it held: H b 0");
        // hx_tick's replay of the last report: nothing moves, nothing changes
        tp_source(TK_DSU); f1(20000, 500, 500); f1(20008, 600, 500);
        {   hx_tp_t before; memcpy(&before, &HX.tp, sizeof before);
            nlines = 0; at(20100); hx_report(HX.last_src, HX.last_rep, HX.last_len);
            CHECK(!said("H ") && !memcmp(&before, &HX.tp, sizeof before), "T the replay of the last report moves nothing, changes nothing"); }
        // a pad that isn't a PlayStation one never says H
        {   int bad = 0;
            for (int kind = 0; kind < 3; kind++) {
                fresh();
                if (kind == 0) hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS);
                else if (kind == 1) hx_add_source_bus(0, 5, 0x045e, 0x0b13, XS, sizeof XS);
                else hx_add_source(0, 0x057e, 0x2009, 0, 0);
                hx_apply_map(); hx_touch_on(1);
                for (int i = 0; i < 400; i++) {
                    uint8_t r[80]; int len = (int)(rnd() % 80) + 1;
                    for (int j = 0; j < len; j++) r[j] = (uint8_t)rnd();
                    if (kind == 1) r[0] = 0x01;
                    else if (kind == 2) r[0] = 0x30;
                    at(40000 + i * 4); nlines = 0; hx_report(0, r, len); hx_tick();
                    if (said("H ")) bad = 1;
                }
            }
            CHECK(!bad, "T a Nimbus+ / Xbox / Nintendo pad never says H"); }
        // fuzz: any length, any bytes, on all four ids
        {   int bad = 0;
            for (int round = 0; round < 500 && !bad; round++) {
                int k = round % 4, btn = 0; long t = 10000;
                tp_source(k);
                tsample s[4]; memset(s, 0, sizeof s);
                for (int i = 0; i < 4; i++) { s[i].x0 = 900; s[i].y0 = 500; s[i].x1 = 1100; s[i].y1 = 500; s[i].id0 = 1; s[i].id1 = 2; }
                for (int i = 0; i < 60 && !bad; i++) {
                    t += (long)(rnd() % 30); at(t); nlines = 0;
                    unsigned c = rnd() % 8;
                    if (c < 3) {                                    /* random bytes */
                        uint8_t r[80]; int len = (int)(rnd() % 80) + 1;
                        for (int j = 0; j < len; j++) r[j] = (uint8_t)rnd();
                        if (rnd() % 4) r[0] = (uint8_t)RID[k];
                        hx_report(0, r, len);
                    } else if (c < 7) {                             /* fingers walking, landing, lifting; any length */
                        int ns = (int)(rnd() % 4) + 1;
                        for (int j = 0; j < 4; j++) {
                            if (rnd() % 9 == 0) s[j].on0 = !s[j].on0;
                            if (rnd() % 15 == 0) s[j].on1 = !s[j].on1;
                            if (rnd() % 20 == 0) s[j].id0 = (int)(rnd() % 128);
                            s[j].x0 = (s[j].x0 + (int)(rnd() % 121) - 60 + 1920) % 1920; s[j].y0 = (s[j].y0 + (int)(rnd() % 81) - 40 + 1080) % 1080;
                            s[j].x1 = (s[j].x1 + (int)(rnd() % 121) - 60 + 1920) % 1920;
                        }
                        int len = tp_build(k, rnd() % 6 == 0, s, ns);
                        if (rnd() % 5 == 0) len = (int)(rnd() % (unsigned)len) + 1;
                        hx_report(0, TPR, len);
                    } else hx_tick();
                    for (int j = 0; j < nlines && !bad; j++) {
                        if (LINES[j][0] != 'H') continue;
                        if (!h_well_formed(LINES[j])) bad = 1;
                        if (!strcmp(LINES[j], "H b 1")) { if (btn) bad = 2; btn = 1; }
                        if (!strcmp(LINES[j], "H b 0")) { if (!btn) bad = 3; btn = 0; }
                    }
                    if (nlines >= 256) bad = 5;
                }
                nlines = 0; hx_detach();
                for (int j = 0; j < nlines; j++) if (!strcmp(LINES[j], "H b 0")) { if (!btn) bad = 3; btn = 0; }
                if (!bad && (btn || HX.tp.btn)) bad = 4;
                if (bad) CHECK(0, "T fuzz round %d (%s): bad %d (%s)", round, KN[k], bad, nlines ? LINES[0] : "-");
            }
            CHECK(!bad, "T fuzz: 500 rounds, any length / bytes on all four ids — lines well formed, the click down / up in turn, let go when it goes"); }
        // unchanged: "t" never sent (or "t 0") = today, line for line — the touch data changes nothing; with "t 1" only the H lines
        // are added
        {   int na, nb, nc;
            uint64_t a = tp_session(0, 0, &na), b = tp_session(0, 1, &nb), c = tp_session(1, 0, &nc);
            CHECK(a == b && na == 0 && nb == 0, "T unchanged: without t 1 the fingers change nothing — the same lines and pad events");
            CHECK(a == c && nc > 0, "T unchanged: with t 1 only H lines are added (%d of them), the rest identical", nc); }
        {   fresh(); hx_add_source_bus(0, 3, 0x054c, 0x0ce6, 0, 0); hx_apply_map();
            CHECK(!HX.tp_on, "T a new reader: the touchpad moves nothing until t 1"); }
    }

    // ── b18o (2026-10-11 — #81's turbo leftover): "TU" lines under a fuzz — turbo / toggle profiles changing, A / B / X
    //    and its Home pressed at random, the clock moving (pulses), then the controller gone. Every TU line well formed, each
    //    button said held / let go in turn, what's said is the engine's count ([wf_turbo_held]), and none is left held ─────────
    {   fresh(); hx_add_source(0, 0x000d, 0x0000, NIMBUS, sizeof NIMBUS); hx_apply_map(); hx_face(0);
        static const char *PROF[] = { "f0x130=1", "f0x130=1 f0x131=1 tr=20", "k0x131=0x130 f0x131=1", "f0x130=2", "" };
        static const uint32_t BITS[] = { NB_2, NB_1, NB_HOME, NB_3 };
        unsigned char st[WF_NK]; memset(st, 0, sizeof st);
        int bad = 0, said1 = 0; HX.ws.now_ms = 10000;
        for (int step = 0; step < 4000 && !bad; step++) {
            nlines = 0;
            unsigned c = rnd() % 10;
            if (c == 0) hx_profile(PROF[rnd() % 5]);
            else if (c == 1) { HX.ws.now_ms += 7; hx_flush(); }
            else { uint32_t b = 0; for (int i = 0; i < 4; i++) if (rnd() & 1) b |= BITS[i]; HX.ws.now_ms += 3; nimbus(0, 0, 0, 0, 0, 0, b); }
            for (int i = 0; i < nlines && !bad; i++) {
                int code, v; long t;
                if (LINES[i][0] != 'T' || LINES[i][1] != 'U') continue;
                if (!well_formed(LINES[i]) || sscanf(LINES[i], "TU %d %d %ld", &code, &v, &t) != 3 || t != HX.ws.now_ms) { bad = 1; break; }
                if (st[code] == v) bad = 2;                                   // said twice the same way
                st[code] = (unsigned char)v; said1 += v;
            }
            for (int d = WF_APP_LO; d <= WF_APP_HI && !bad; d++) if (st[d] != HX.tsaid[d]) bad = 3;
            if (nlines >= 256) bad = 4;
        }
        nlines = 0; hx_detach();
        for (int i = 0; i < nlines; i++) { int code, v; long t; if (sscanf(LINES[i], "TU %d %d %ld", &code, &v, &t) == 3) st[code] = (unsigned char)v; }
        int left = 0; for (int d = WF_APP_LO; d <= WF_APP_HI; d++) left += st[d];
        CHECK(!bad && said1 > 50 && !left, "b18o TU fuzz: 4000 steps — well formed, each said held / let go in turn, the engine's count "
              "(bad %d, %d holds said), none left held once the controller went (%d)", bad, said1, left);
    }

    printf("%s: %d checks, %d failed\n", fails ? "FAILED" : "OK", checks, fails);
    return fails != 0;
}
