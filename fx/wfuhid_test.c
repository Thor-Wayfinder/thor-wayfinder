// wfuhid_test — unit tests of the UHID copy's layout, report descriptor and reports (fx/wfuhid.h), run ON the Thor:
//   tools/build_wfpad.sh test   → pushes and runs /data/local/tmp/wfuhid_test (after wfmap_test)
// The descriptor is checked by walking it as the kernel's HID parser would: the Input fields it declares must add up to
// the report wfu_report() writes, field by field (usage, size, logical range).
#include <stdio.h>
#include "wfuhid.h"
#include "wfmap.h"          // b18l: the copy's frames (wf_frames), as wfpad's emit() writes them

static int fails = 0, checks = 0;
#define CHECK(cond, ...) do { checks++; if (!(cond)) { fails++; printf("FAIL %s:%d ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

#define NB(x) ((x) / (8 * sizeof(long)) + 1)
static unsigned long evb[NB(EV_MAX)], keyb[NB(KEY_MAX)], absb[NB(ABS_MAX)];
static struct input_absinfo info[ABS_MAX + 1];
static void set(unsigned long *a, int b) { a[b / (8 * sizeof(long))] |= 1UL << (b % (8 * sizeof(long))); }

/** AYN's pad as the Thor has it (getevent -p, 2026-09-24): its keys, sticks ±32767, triggers 0..32767, a -1..1 hat. */
static const int KEYS[] = { 0x66, 0x72, 0x73, 0x9e, 0x130, 0x131, 0x132, 0x133, 0x134, 0x135, 0x136, 0x137,
                            0x138, 0x139, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e, 0x220, 0x221, 0x222, 0x223, 0x244 };
static void thor(void) {
    memset(evb, 0, sizeof evb); memset(keyb, 0, sizeof keyb); memset(absb, 0, sizeof absb); memset(info, 0, sizeof info);
    set(evb, EV_SYN); set(evb, EV_KEY); set(evb, EV_ABS); set(evb, EV_MSC);
    for (unsigned i = 0; i < sizeof KEYS / sizeof *KEYS; i++) set(keyb, KEYS[i]);
    int ax[] = { ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y };
    for (int i = 0; i < 8; i++) {
        set(absb, ax[i]);
        info[ax[i]].minimum = ax[i] >= ABS_HAT0X ? -1 : (ax[i] == ABS_GAS || ax[i] == ABS_BRAKE) ? 0 : -32767;
        info[ax[i]].maximum = ax[i] >= ABS_HAT0X ? 1 : 32767;
    }
}

// ---- a small HID report descriptor walker: the Input fields in order ----
typedef struct { int page, usage, bits, lmin, lmax, constant; } field;
static field F[256]; static int nf;
static int depth_max, depth_end, bad_item;
static int sval(const unsigned char *p, int sz) {
    if (sz == 1) return (signed char)p[0];
    if (sz == 2) return (short)(p[0] | p[1] << 8);
    if (sz == 4) return (int)(p[0] | p[1] << 8 | p[2] << 16 | (unsigned)p[3] << 24);
    return 0;
}
static unsigned uval(const unsigned char *p, int sz) {
    unsigned v = 0; for (int i = 0; i < sz; i++) v |= (unsigned)p[i] << (8 * i); return v;
}
static void walk(const unsigned char *d, int n) {
    nf = 0; depth_max = 0; bad_item = 0;
    int page = 0, lmin = 0, lmax = 0, size = 0, count = 0, depth = 0;
    int usages[64], nu = 0;
    for (int i = 0; i < n;) {
        int b = d[i], sz = b & 3; if (sz == 3) sz = 4;
        if (i + 1 + sz > n) { bad_item = 1; break; }
        const unsigned char *p = d + i + 1;
        int tag = b & 0xFC;
        switch (tag) {
        case 0x04: page = (int)uval(p, sz); break;
        case 0x14: lmin = sval(p, sz); break;
        case 0x24: lmax = sval(p, sz); break;
        case 0x74: size = (int)uval(p, sz); break;
        case 0x94: count = (int)uval(p, sz); break;
        case 0x08: if (nu < 64) usages[nu++] = (int)uval(p, sz); break;
        case 0xA0: depth++; if (depth > depth_max) depth_max = depth; nu = 0; break;
        case 0xC0: depth--; nu = 0; break;
        case 0x80:
            for (int k = 0; k < count && nf < 256; k++) {
                field f = { page, k < nu ? usages[k] : nu ? usages[nu - 1] : 0, size, lmin, lmax, (int)(uval(p, sz) & 1) };
                F[nf++] = f;
            }
            nu = 0; break;
        case 0x34: case 0x44: case 0x64: break;      // physical range, unit
        default: bad_item = 1;
        }
        i += 1 + sz;
    }
    depth_end = depth;
}

// ---- b18l: wfpad's emit() on the UHID copy — a report per wf_frames state, each only when it changes the one before ----
static wfu_layout UT;
static wf_state UWS; static wf_map UWM; static wf_out UCUR;
static unsigned char UREP[4][WFU_MAXREP]; static int NREP;
static void uev(int type, int code, int value) { int p; wf_event(&UWS, type, code, value, &p); }
/** The frame's reports (UREP, NREP), from the copy's last state (UCUR) — as wfpad.c emit() writes them. */
static void uframe(void) {
    static wf_out nxt, mid[2];
    wf_compute(&UWS, &UWM, &nxt);
    unsigned char last[WFU_MAXREP];
    int nm = wf_frames(&UCUR, &nxt, mid), len = wfu_report(&UT, UCUR.key, WF_NK, UCUR.abs, WF_NA, last);
    NREP = 0;
    for (int s = 0; s <= nm; s++) {
        const wf_out *st = s < nm ? &mid[s] : &nxt;
        wfu_report(&UT, st->key, WF_NK, st->abs, WF_NA, UREP[NREP]);
        if (memcmp(UREP[NREP], last, (size_t)len)) { memcpy(last, UREP[NREP], (size_t)len); NREP++; }
    }
    UCUR = nxt;
}
/** [code]'s bit in report [r] (-1: not in the layout) · the GAS axis's value in it */
static int rbit(const unsigned char *r, int code) {
    for (int i = 0; i < UT.nk; i++) if (UT.key[i] == code) return (r[i / 8] >> (i % 8)) & 1;
    return -1;
}
static int rgas(const unsigned char *r) {
    int o = UT.keybytes;
    for (int i = 0; i < UT.na; i++) { if (UT.axis[i] == ABS_GAS) return (short)(r[o] | r[o + 1] << 8); o += UT.abits[i] / 8; }
    return -99999;
}

int main(void) {
    char why[64]; wfu_layout L;

    // ── the Thor's pad ───────────────────────────────────────────
    thor();
    CHECK(wfu_plan(&L, evb, keyb, absb, info, why, sizeof why) == 0, "Thor pad planned (%s)", why);
    CHECK(L.nk == 20, "20 keys (24 minus the 4 BTN_DPAD it never sends): %d", L.nk);
    CHECK(L.nbtn == 15, "15 buttons 0x130..0x13e: %d", L.nbtn);
    CHECK(L.key[0] == 0x130 && L.key[14] == 0x13e && L.key[15] == KEY_HOME, "buttons first, then Home (keyboard page)");
    CHECK(L.na == 6 && L.hat, "6 axes + the hat: %d", L.na);
    CHECK(L.keybytes == 3 && L.replen == 3 + 6 * 2 + 1, "report 16 bytes: %d", L.replen);
    for (int i = 0; i < L.na; i++) CHECK(L.abits[i] == 16, "axis %d is 16 bits", L.axis[i]);
    int dpad_in = 0; for (int i = 0; i < L.nk; i++) if (L.key[i] >= BTN_DPAD_UP && L.key[i] <= BTN_DPAD_RIGHT) dpad_in = 1;
    CHECK(!dpad_in, "no BTN_DPAD in the copy");

    // ── its descriptor, walked ───────────────────────────────────
    unsigned char d[WFU_MAXDESC];
    int dn = wfu_descriptor(&L, d, sizeof d);
    CHECK(dn > 0 && dn <= WFU_MAXDESC, "descriptor fits: %d", dn);
    CHECK(d[0] == 0x05 && d[1] == 0x01 && d[2] == 0x09 && d[3] == 0x08 && d[4] == 0xA1 && d[5] == 0x01,
          "Multi-axis application (no fuzz / flat from hid-input)");
    walk(d, dn);
    CHECK(!bad_item, "only known items");
    CHECK(depth_end == 0 && depth_max == 2, "collections balanced (application + physical gamepad): %d %d", depth_end, depth_max);
    int bits = 0; for (int i = 0; i < nf; i++) bits += F[i].bits;
    CHECK(bits == L.replen * 8, "declared bits = the report: %d vs %d", bits, L.replen * 8);
    for (int i = 0; i < L.nk; i++)
        CHECK(F[i].page == L.kpage[i] && F[i].usage == L.kusage[i] && F[i].bits == 1 && F[i].lmin == 0 && F[i].lmax == 1 && !F[i].constant,
              "key field %d (0x%x): page %x usage %x", i, L.key[i], F[i].page, F[i].usage);
    int fi = L.nk;
    if (L.nk % 8) { CHECK(F[fi].constant && F[fi].bits == 1, "padding after the keys"); fi += 8 - L.nk % 8; }
    for (int i = 0; i < L.na; i++, fi++)
        CHECK(F[fi].page == L.apage[i] && F[fi].usage == L.ausage[i] && F[fi].bits == 16 && F[fi].lmin == L.amin[i] && F[fi].lmax == L.amax[i],
              "axis field %d: %x/%x %d bits %d..%d", i, F[fi].page, F[fi].usage, F[fi].bits, F[fi].lmin, F[fi].lmax);
    CHECK(F[fi].page == 1 && F[fi].usage == 0x39 && F[fi].bits == 4 && F[fi].lmin == 0 && F[fi].lmax == 7, "hat field");
    CHECK(F[fi + 1].constant && F[fi + 1].bits == 4, "hat padding");
    CHECK(fi + 2 == nf, "nothing after: %d %d", fi + 2, nf);

    // ── reports ──────────────────────────────────────────────────
    unsigned char kv[0x300]; int av[ABS_MAX + 1]; unsigned char r[WFU_MAXREP];
    memset(kv, 0, sizeof kv); memset(av, 0, sizeof av);
    int rn = wfu_report(&L, kv, sizeof kv, av, ABS_MAX + 1, r);
    CHECK(rn == L.replen, "report length %d", rn);
    CHECK(r[0] == 0 && r[1] == 0 && r[2] == 0 && r[rn - 1] == 8, "at rest: no keys, hat null (8)");
    kv[0x130] = 1; kv[KEY_HOME] = 1; kv[KEY_APPSELECT] = 1;
    wfu_report(&L, kv, sizeof kv, av, ABS_MAX + 1, r);
    int ih = -1, ia = -1; for (int i = 0; i < L.nk; i++) { if (L.key[i] == KEY_HOME) ih = i; if (L.key[i] == KEY_APPSELECT) ia = i; }
    CHECK((r[0] & 1) && (r[ih / 8] >> (ih % 8) & 1) && (r[ia / 8] >> (ia % 8) & 1), "A, Home, AppSelect bits");
    int set_bits = 0; for (int i = 0; i < L.keybytes; i++) for (int b = 0; b < 8; b++) set_bits += r[i] >> b & 1;
    CHECK(set_bits == 3, "only those 3 bits: %d", set_bits);
    memset(kv, 0, sizeof kv);
    av[ABS_X] = -32767; av[ABS_Y] = 32767; av[ABS_Z] = -1; av[ABS_RZ] = 99999; av[ABS_GAS] = -5; av[ABS_BRAKE] = 12345;
    wfu_report(&L, kv, sizeof kv, av, ABS_MAX + 1, r);
    int o = L.keybytes;
    #define A16(i) ((short)(r[o + 2 * (i)] | r[o + 2 * (i) + 1] << 8))
    CHECK(A16(0) == -32767 && A16(1) == 32767 && A16(2) == -1, "sticks little-endian, signed: %d %d %d", A16(0), A16(1), A16(2));
    CHECK(A16(3) == 32767 && A16(4) == 0 && A16(5) == 12345, "out-of-range values clamped: %d %d", A16(3), A16(4));
    // the hat: every D-pad position
    static const int HX[] = { 0, 1, 1, 1, 0, -1, -1, -1, 0 }, HY[] = { -1, -1, 0, 1, 1, 1, 0, -1, 0 };
    for (int h = 0; h < 9; h++) {
        av[ABS_HAT0X] = HX[h]; av[ABS_HAT0Y] = HY[h];
        wfu_report(&L, kv, sizeof kv, av, ABS_MAX + 1, r);
        CHECK(r[rn - 1] == h, "hat (%d,%d) → %d: %d", HX[h], HY[h], h, r[rn - 1]);
    }
    CHECK(wfu_hat(5, -9) == 1, "hat values beyond ±1 count as ±1");

    // ── what it refuses (wfpad then keeps the uinput copy) ───────
    thor(); set(evb, EV_FF);
    CHECK(wfu_plan(&L, evb, keyb, absb, info, why, sizeof why) < 0 && strstr(why, "event type"), "force feedback refused: %s", why);
    thor(); set(keyb, KEY_A);
    CHECK(wfu_plan(&L, evb, keyb, absb, info, why, sizeof why) < 0 && strstr(why, "key"), "an unknown key refused: %s", why);
    thor(); absb[0] &= ~(1UL << ABS_HAT0Y);
    CHECK(wfu_plan(&L, evb, keyb, absb, info, why, sizeof why) < 0 && strstr(why, "half"), "half a hat refused: %s", why);
    thor(); info[ABS_HAT0X].maximum = 2;
    CHECK(wfu_plan(&L, evb, keyb, absb, info, why, sizeof why) < 0 && strstr(why, "hat range"), "a hat beyond ±1 refused: %s", why);
    thor(); set(absb, ABS_MISC); info[ABS_MISC].maximum = 10;
    CHECK(wfu_plan(&L, evb, keyb, absb, info, why, sizeof why) < 0 && strstr(why, "axis"), "an unknown axis refused: %s", why);
    thor(); info[ABS_Z].minimum = info[ABS_Z].maximum = 0;
    CHECK(wfu_plan(&L, evb, keyb, absb, info, why, sizeof why) < 0 && strstr(why, "range"), "an empty axis range refused: %s", why);

    // ── wide axes: 32 bits, encoded as such ──────────────────────
    thor(); info[ABS_X].minimum = -100000; info[ABS_X].maximum = 100000;
    CHECK(wfu_plan(&L, evb, keyb, absb, info, why, sizeof why) == 0 && L.abits[0] == 32 && L.replen == 18, "a ±100000 axis: 32 bits");
    dn = wfu_descriptor(&L, d, sizeof d); walk(d, dn);
    bits = 0; for (int i = 0; i < nf; i++) bits += F[i].bits;
    CHECK(!bad_item && bits == L.replen * 8, "wide: declared bits = the report");
    CHECK(F[24].bits == 32 && F[24].lmin == -100000 && F[24].lmax == 100000, "wide axis field: %d %d..%d", F[24].bits, F[24].lmin, F[24].lmax);
    memset(av, 0, sizeof av); av[ABS_X] = -99999; av[ABS_HAT0X] = 0; av[ABS_HAT0Y] = 0;
    wfu_report(&L, kv, sizeof kv, av, ABS_MAX + 1, r);
    int x32 = (int)(r[3] | r[4] << 8 | r[5] << 16 | (unsigned)r[6] << 24);
    CHECK(x32 == -99999, "wide value: %d", x32);

    // ── the biggest layout it accepts still fits ─────────────────
    memset(evb, 0, sizeof evb); memset(keyb, 0, sizeof keyb); memset(absb, 0, sizeof absb); memset(info, 0, sizeof info);
    set(evb, EV_KEY); set(evb, EV_ABS);
    for (int k = 0x130; k <= 0x13f; k++) set(keyb, k);
    int extra[] = { KEY_HOME, KEY_F12, KEY_BACK, KEY_APPSELECT, KEY_VOLUMEUP, KEY_VOLUMEDOWN, KEY_MENU };
    for (unsigned i = 0; i < sizeof extra / sizeof *extra; i++) set(keyb, extra[i]);
    int axes[] = { ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_GAS, ABS_BRAKE, ABS_THROTTLE, ABS_RUDDER };
    for (unsigned i = 0; i < sizeof axes / sizeof *axes; i++) { set(absb, axes[i]); info[axes[i]].minimum = -2000000000; info[axes[i]].maximum = 2000000000; }
    set(absb, ABS_HAT0X); set(absb, ABS_HAT0Y); info[ABS_HAT0X].minimum = info[ABS_HAT0Y].minimum = -1; info[ABS_HAT0X].maximum = info[ABS_HAT0Y].maximum = 1;
    CHECK(wfu_plan(&L, evb, keyb, absb, info, why, sizeof why) == 0 && L.nk == 23 && L.na == 10, "every key and axis it knows: %s", why);
    dn = wfu_descriptor(&L, d, sizeof d);
    CHECK(dn <= WFU_MAXDESC, "biggest descriptor fits: %d of %d", dn, WFU_MAXDESC);
    CHECK(dn <= 4096, "and within UHID's limit (HID_MAX_DESCRIPTOR_SIZE)");
    walk(d, dn); bits = 0; for (int i = 0; i < nf; i++) bits += F[i].bits;
    CHECK(!bad_item && depth_end == 0 && bits == L.replen * 8, "biggest: walks clean, bits = report (%d / %d)", bits, L.replen * 8);
    CHECK(L.replen <= WFU_MAXREP, "biggest report fits: %d", L.replen);

    // ── b18l (GitHub #81, b18w B1): Home / Back's change in a report of its own, on the UHID copy ──
    // One report put the buttons (page 9) before Home (page 7) and Back (page 0x0C), and the kernel sends a report's events in
    // that order: R2's give-back reached the app before Home's release ("Home + R2"). wfpad's emit(), as written there: a
    // report per wf_frames state, each only when it changes the report before it.
    {
        thor();
        CHECK(wfu_plan(&UT, evb, keyb, absb, info, why, sizeof why) == 0, "b18l the Thor's layout (%s)", why);
        memset(&UWS, 0, sizeof UWS); wf_map_reset(&UWM);
        for (unsigned i = 0; i < sizeof KEYS / sizeof *KEYS; i++) UWS.keys[UWS.nk++] = (unsigned short)KEYS[i];
        int ax[] = { ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y };
        for (int i = 0; i < 8; i++) { UWS.axes[UWS.na++] = (unsigned char)ax[i]; UWS.info[ax[i]] = info[ax[i]]; }
        wf_state_rest(&UWS); wf_compute(&UWS, &UWM, &UCUR);
        uev(EV_KEY, WF_R2, 1); uev(EV_ABS, WF_GAS, 30000); uframe();
        uev(EV_KEY, WF_HOME, 1); uframe();
        CHECK(NREP == 2 && rbit(UREP[0], KEY_HOME) == 1 && rbit(UREP[0], WF_R2) == 1 && rgas(UREP[0]) == 30000 &&
              rbit(UREP[1], KEY_HOME) == 1 && rbit(UREP[1], WF_R2) == 0 && rgas(UREP[1]) == 0,
              "b18l R2 held + Home: report 1 = Home down, R2 still held; report 2 = R2 let go (%d)", NREP);
        uev(EV_KEY, WF_HOME, 0); uframe();
        CHECK(NREP == 2 && rbit(UREP[0], KEY_HOME) == 0 && rbit(UREP[0], WF_R2) == 0 &&
              rbit(UREP[1], KEY_HOME) == 0 && rbit(UREP[1], WF_R2) == 1 && rgas(UREP[1]) == 30000,
              "b18l ...Home let go: report 1 = Home up alone; report 2 = R2 given back (%d)", NREP);
        uev(EV_KEY, WF_R2, 0); uev(EV_ABS, WF_GAS, 0); uframe();
        uev(EV_KEY, WF_X, 1); uframe();
        uev(EV_KEY, WF_HOME, 1); uframe();
        CHECK(NREP == 2 && rbit(UREP[0], KEY_HOME) == 1 && rbit(UREP[0], WF_X) == 1 && rbit(UREP[1], WF_X) == 0,
              "b18l X held + Home: report 1 = Home down, X still held; report 2 = X let go (%d)", NREP);
        uev(EV_KEY, WF_X, 0); uev(EV_KEY, WF_HOME, 0); uframe();
        CHECK(NREP == 1 && rbit(UREP[0], KEY_HOME) == 0, "b18l ...X let go under Home, then Home: one report (%d)", NREP);
        // Back: what its gate lets go first, then Back's press (b18h WI-4) — Select let go in the frame that presses Back
        uev(EV_KEY, 0x13a, 1); uframe();
        uev(EV_KEY, 0x13a, 0); uev(EV_KEY, WF_BACK, 1); uframe();
        CHECK(UWS.gated && NREP == 2 && rbit(UREP[0], 0x13a) == 0 && rbit(UREP[0], KEY_BACK) == 0 && rbit(UREP[1], KEY_BACK) == 1,
              "b18l Select let go + Back: report 1 = Select up; report 2 = Back down (%d)", NREP);
        uev(EV_KEY, WF_BACK, 0); uframe();
        CHECK(NREP == 1 && rbit(UREP[0], KEY_BACK) == 0, "b18l ...Back let go: one report (%d)", NREP);
    }

    printf("%s: %d checks, %d failed\n", fails ? "FAILED" : "OK", checks, fails);
    return fails != 0;
}
