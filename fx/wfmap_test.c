// wfmap_test — unit tests of the mapping engine (fx/wfmap.h), run ON the Thor:
//   tools/build_wfpad.sh test   → pushes and runs /data/local/tmp/wfmap_test
// A fake pad with the Thor's real capabilities (getevent -p, 2026-09-24).
#include <stdio.h>
#include "wfmap.h"

static int fails = 0, checks = 0;
#define CHECK(cond, ...) do { checks++; if (!(cond)) { fails++; printf("FAIL %s:%d ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static const int KEYS[] = { 0x66, 0x72, 0x73, 0x9e, 0x130, 0x131, 0x132, 0x133, 0x134, 0x135, 0x136, 0x137,
                            0x138, 0x139, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e, 0x220, 0x221, 0x222, 0x223, 0x244 };
static wf_state S; static wf_map M; static wf_out O;

static void pad(int xbox) {
    memset(&S, 0, sizeof S); wf_map_reset(&M);
    S.nk = sizeof KEYS / sizeof *KEYS;
    for (int i = 0; i < S.nk; i++) S.keys[i] = (unsigned short)KEYS[i];
    int ax[] = { 0, 1, 2, 5, 9, 10, 16, 17 };
    S.na = 8;
    for (int i = 0; i < 8; i++) {
        S.axes[i] = (unsigned char)ax[i];
        S.info[ax[i]].minimum = ax[i] >= 16 ? -1 : (ax[i] == 9 || ax[i] == 10) ? 0 : -32767;
        S.info[ax[i]].maximum = ax[i] >= 16 ? 1 : 32767;
    }
    S.xbox = xbox;
    wf_state_rest(&S);
}
/** Send a source event; returns the echo flag. */
static int ev(int type, int code, int value) { int p; return wf_event(&S, type, code, value, &p); }
static int key(int code, int v) { return ev(EV_KEY, code, v); }
static int ab(int code, int v) { return ev(EV_ABS, code, v); }
static void frame(void) { wf_compute(&S, &M, &O); }
/** b18l: the events a copy written event by event gets from the last frame ([O]) to the next ([wf_frame_events]): E, NE */
static struct input_event E[256]; static int NE;
static void step(void) { wf_out cur = O; frame(); NE = wf_frame_events(&cur, &O, S.keys, S.nk, S.axes, S.na, 0, E, 256); }
static int is_ev(const struct input_event *e, int type, int code, int value) { return e->type == type && e->code == code && e->value == value; }
static int is_syn(const struct input_event *e) { return e->type == EV_SYN && e->code == SYN_REPORT && e->value == 0; }
/** b18l review — the docked / merged chain: a controller's reader (its own engine R: wfhid's, its Home the Thor's) writes its
 *  pad in b18l's reports ([wf_frame_events]); wfpad's engine (S) reads that pad event by event as its loop does (wf_event: CJ
 *  = the "J" lines wfpad says), its copy computed at each SYN_REPORT (the copy's events: E, NE). [chain] returns the
 *  reader's own echo (its "J" line). */
static wf_state R; static wf_map RM; static wf_out RO; static int CJ;
static void chain_init(void) { pad(0); R = S; wf_map_reset(&RM); wf_compute(&R, &RM, &RO); frame(); }
static int chain(int code, int v) {
    int p, rj = wf_event(&R, EV_KEY, code, v, &p);
    wf_out cur = RO; wf_compute(&R, &RM, &RO);
    struct input_event re[64]; int rn = wf_frame_events(&cur, &RO, R.keys, R.nk, R.axes, R.na, 0, re, 64);
    CJ = 0; NE = 0;
    for (int i = 0; i < rn; i++) {
        if (re[i].type != EV_SYN) { CJ += ev(re[i].type, re[i].code, re[i].value); continue; }
        wf_out c2 = O; frame(); NE += wf_frame_events(&c2, &O, S.keys, S.nk, S.axes, S.na, 0, E + NE, 256 - NE);
    }
    return rj;
}
/** b18n — a resync as wfpad's (the state reset, the profile's parts set again, the keys held replayed: fx/wfmap.h
 *  wf_replay). [old] 1: the state before is passed (the replay keeps its order); 0: none (code order, as before b18n).
 *  [drop] / [add]: a key let go / pressed during the drop (source code; 0 = none). */
static wf_state W;
static void resync(int old, int drop, int add) {
    unsigned char held[WF_NK] = {0};
    for (int i = 0; i < S.nk; i++) held[S.keys[i]] = S.key[wf_in(&S, S.keys[i])];
    if (drop) held[drop] = 0;
    if (add) held[add] = 1;
    W = S; wf_state_rest(&S); wf_state_map(&S, &M);
    wf_replay(&S, held, old ? &W : NULL);
}
/** b18n — random presses / releases over Home, Back, A, B, X, Select and L1 (the shift button in 2 runs of 3), on S and on a
 *  twin T never resynced; S is resynced now and then with nothing dropped ([old] as in [resync]). Returns the steps where S
 *  and T differed: an echo, the copy, the gate, Back's flag, or what's consumed of a held key. */
static wf_state T;
static int twin_fuzz(int old, int runs) {
    static const int FK[] = { WF_HOME, WF_BACK, WF_A, WF_B, WF_X, 0x13a, 0x136 };
    unsigned seed = 1806u; int bad = 0;
    for (int run = 0; run < runs; run++) {
        pad(run & 1); M.shift = run % 3 ? 0x136 : 0; wf_state_map(&S, &M); T = S;
        for (int st = 0; st < 40; st++) {
            seed = seed * 1103515245u + 12345u;
            int k = FK[(seed >> 16) % 7], v = !S.key[wf_in(&S, k)], p;
            int e1 = wf_event(&S, EV_KEY, k, v, &p), e2 = wf_event(&T, EV_KEY, k, v, &p);
            wf_out o1, o2; memset(&o1, 0, sizeof o1); memset(&o2, 0, sizeof o2);   // wf_compute sets only the pad's axes
            wf_compute(&S, &M, &o1); wf_compute(&T, &M, &o2);
            int differ = e1 != e2 || memcmp(&o1, &o2, sizeof o1) || S.gated != T.gated || S.back_hot != T.back_hot ||
                         S.shift_tap_until != T.shift_tap_until;
            for (int i = 0; i < S.nk; i++) { int q = wf_in(&S, S.keys[i]); if (S.key[q] && S.ckey[q] != T.ckey[q]) differ = 1; }
            bad += differ;
            if (((seed >> 8) & 3) == 0) resync(old, 0, 0);
        }
    }
    return bad;
}
/** b18n review — AYN's pad re-made while keys are held, as wfpad lives it: the old pad goes, then the new one's held keys
 *  are replayed (wf_replay) with the state before as [old]. [kept] 1: what the old pad held is kept till then (wfpad's
 *  lose_source since the review: S and the copy untouched); 0: let go first and the copy written (source_rest, before the
 *  review) — [old] is then a cleared state. [drop]: a key let go while no pad was there (source code; 0 = none). The copy's
 *  events of both steps: E, NE. */
static void remake(int kept, int drop) {
    unsigned char held[WF_NK] = {0};
    for (int i = 0; i < S.nk; i++) held[S.keys[i]] = S.key[wf_in(&S, S.keys[i])];
    if (drop) held[drop] = 0;
    wf_out cur = O; NE = 0;
    if (!kept) { wf_state_rest(&S); frame(); NE = wf_frame_events(&cur, &O, S.keys, S.nk, S.axes, S.na, 0, E, 256); cur = O; }
    W = S; wf_state_rest(&S); wf_state_map(&S, &M);
    wf_replay(&S, held, &W);
    frame(); NE += wf_frame_events(&cur, &O, S.keys, S.nk, S.axes, S.na, 0, E + NE, 256 - NE);
}
/** The first of the copy's events [E] that is (type, code, value); -1 = none. */
static int find_ev(int type, int code, int value) { for (int i = 0; i < NE; i++) if (is_ev(&E[i], type, code, value)) return i; return -1; }

int main(int argc, char **argv) {
    // ── passthrough ───────────────────────────────────────────────
    pad(0); key(WF_A, 1); ab(WF_LX, 20000); ab(WF_GAS, 30000); ab(WF_HY, -1); frame();
    CHECK(O.key[WF_A] && !O.key[WF_B], "A passes");
    CHECK(O.abs[WF_LX] == 20000 && O.abs[WF_GAS] == 30000 && O.abs[WF_HY] == -1, "axes pass");
    key(WF_A, 0); frame();
    CHECK(!O.key[WF_A], "A released");

    // ── face layout ──────────────────────────────────────────────
    pad(0); M.layout = 'x'; key(WF_A, 1); frame();                  // printed A, Xbox wanted
    CHECK(O.key[WF_B] && !O.key[WF_A], "Nintendo pad + Xbox layout: printed A → B code");
    pad(1); key(WF_B, 1); frame();                                  // Xbox pad: code B = printed A
    CHECK(O.key[WF_B], "Xbox pad, follow AYN: unchanged");
    M.layout = 'n'; frame();
    CHECK(O.key[WF_A] && !O.key[WF_B], "Xbox pad + Nintendo layout: printed A → A code");
    pad(1); M.layout = 'x'; key(WF_Y, 1); frame();                  // Xbox pad code Y = printed X
    CHECK(O.key[WF_Y], "Xbox pad + Xbox layout: unchanged");

    // ── remaps ───────────────────────────────────────────────────
    pad(0); M.keymap[WF_A] = WF_B; M.keymap[WF_B] = WF_NONE; key(WF_A, 1); key(WF_B, 1); frame();
    CHECK(O.key[WF_B] && !O.key[WF_A], "A → B, B → none");
    pad(0); M.keymap[0x13b] = WF_HAT_UP; key(0x13b, 1); frame();
    CHECK(O.abs[WF_HY] == -1 && !O.key[0x13b], "Start → D-pad up");
    pad(0); M.keymap[WF_L2] = WF_R2; key(WF_L2, 1); ab(WF_BRAKE, 12345); frame();
    CHECK(O.key[WF_R2] && O.abs[WF_GAS] == 12345 && O.abs[WF_BRAKE] == 0, "L2 → R2 carries the analog");
    pad(0); M.keymap[WF_L2] = WF_A; key(WF_L2, 1); ab(WF_BRAKE, 30000); frame();
    CHECK(O.key[WF_A] && O.abs[WF_BRAKE] == 0, "L2 → A drops the analog");
    pad(0); M.keymap[WF_X] = WF_R2; key(WF_X, 1); frame();
    CHECK(O.key[WF_R2] && O.abs[WF_GAS] == 32767, "X → R2 = full press");
    pad(0); M.trig_digital = 1; key(WF_R2, 1); ab(WF_GAS, 9000); frame();
    CHECK(O.abs[WF_GAS] == 32767, "digital triggers");
    pad(0); M.swap_sticks = 1; M.inv_ry = 1; ab(WF_LX, 100); ab(WF_LY, 200); frame();
    CHECK(O.abs[WF_RX] == 100 && O.abs[WF_RY] == -200 && O.abs[WF_LX] == 0, "swap sticks + invert right Y");
    pad(0); M.dpad_ls = 1; ab(WF_HX, 1); ab(WF_LY, -30000); frame();
    CHECK(O.abs[WF_LX] == 32767 && O.abs[WF_HY] == -1 && O.abs[WF_HX] == 0, "D-pad ↔ left stick");
    pad(0); M.keymap[WF_A] = WF_B; key(WF_A, 1); frame();
    wf_map_reset(&M); frame();                                      // profile switch mid-press
    CHECK(O.key[WF_A] && !O.key[WF_B], "profile switch: old output released, no stuck B");

    // ── Wayfinder outputs (keyboard key / action) ────────────────
    pad(0); M.keymap[WF_X] = WF_EXT; key(WF_X, 1); frame();
    CHECK(!O.key[WF_X] && wf_ext_event(&S, &M, WF_X, 1), "X → keyboard: nothing for the game, press to Wayfinder");
    CHECK(wf_ext_event(&S, &M, WF_X, 0) && !wf_ext_event(&S, &M, WF_A, 1), "release too; unmapped buttons not");
    pad(0); M.keymap[WF_X] = WF_EXT; key(WF_HOME, 1); key(WF_X, 1);
    CHECK(!wf_ext_event(&S, &M, WF_X, 1), "during Home: the press is the combo's, not the key's");
    key(WF_HOME, 0);
    CHECK(wf_ext_event(&S, &M, WF_X, 0), "its release still goes out (harmless)");
    pad(0); M.keymap[WF_L2] = WF_EXT; key(WF_L2, 1); ab(WF_BRAKE, 30000); frame();
    CHECK(O.abs[WF_BRAKE] == 0 && !O.key[WF_L2], "L2 → keyboard drops its analog too");
    CHECK(wf_parse(&M, &S, "k0x133=65534") == 0 && M.keymap[WF_X] == WF_EXT, "parse ext");

    // ── how it fires: toggle / turbo ─────────────────────────────
    pad(0); M.fire[WF_A] = 2;
    key(WF_A, 1); wf_fire_edge(&S, &M, WF_A, 1); frame(); CHECK(O.key[WF_A], "toggle: press → on");
    key(WF_A, 0); frame(); CHECK(O.key[WF_A], "toggle: released, still on");
    key(WF_A, 1); wf_fire_edge(&S, &M, WF_A, 1); key(WF_A, 0); frame(); CHECK(!O.key[WF_A], "toggle: second press → off");
    key(WF_A, 1); wf_fire_edge(&S, &M, WF_A, 1); key(WF_A, 0); key(WF_HOME, 1); frame();
    CHECK(!O.key[WF_A], "toggle: released while Home is held");
    key(WF_HOME, 0); frame(); CHECK(O.key[WF_A], "toggle: back on after Home");
    pad(0); M.fire[WF_B] = 1; M.turbo_hz = 10; S.now_ms = 1000;
    key(WF_B, 1); wf_fire_edge(&S, &M, WF_B, 1); frame(); CHECK(O.key[WF_B] && wf_turbo_active(&S, &M), "turbo: on at the press");
    S.now_ms = 1060; frame(); CHECK(!O.key[WF_B], "turbo: off after half a period (50 ms at 10/s)");
    S.now_ms = 1110; frame(); CHECK(O.key[WF_B], "turbo: on again");
    key(WF_B, 0); frame(); CHECK(!O.key[WF_B] && !wf_turbo_active(&S, &M), "turbo: stops on release");
    pad(0); M.fire[WF_X] = 1; M.keymap[WF_X] = WF_Y; S.now_ms = 0; key(WF_X, 1); wf_fire_edge(&S, &M, WF_X, 1); frame();
    CHECK(O.key[WF_Y], "turbo on a remapped button fires its target");
    CHECK(wf_parse(&M, &S, "f0x130=2 f0x131=1 tr=15") == 0 && M.fire[WF_A] == 2 && M.fire[WF_B] == 1 && M.turbo_hz == 15, "parse fire");
    CHECK(wf_parse(&M, &S, "f0x66=1") != 0 && wf_parse(&M, &S, "f0x130=3") != 0 && wf_parse(&M, &S, "tr=99") != 0, "fire refusals");

    // ── virtual presses (phase 3) ────────────────────────────────
    pad(0);
    CHECK(wf_vpress(&S, WF_A, 1) == 0, "vpress A accepted"); frame(); CHECK(O.key[WF_A], "vpress A → A");
    CHECK(wf_vpress(&S, WF_HOME, 1) == -1 && wf_vpress(&S, 0x2f0, 1) == -1 && wf_vpress(&S, WF_A, 2) == -1, "vpress refuses Home, unknown codes, bad values");
    wf_vpress(&S, 0, 0); CHECK(wf_vpress(&S, WF_BACK, 1) == 0, "vpress accepts Back (1.4, #43)"); frame();
    CHECK(O.key[WF_BACK] && !S.gated, "vpress Back → the copy's Back, no gate"); wf_vpress(&S, WF_BACK, 0); frame();
    CHECK(!O.key[WF_BACK], "vpress Back released"); wf_vpress(&S, WF_A, 1);
    wf_vpress(&S, WF_HAT_LEFT, 1); wf_vpress(&S, WF_R2, 1); frame();
    CHECK(O.abs[WF_HX] == -1 && O.key[WF_R2] && O.abs[WF_GAS] == 32767, "vpress D-pad left + R2 full pull");
    key(WF_HOME, 1); frame(); CHECK(!O.key[WF_A] && O.abs[WF_HX] == 0 && O.abs[WF_GAS] == 0, "vpress silent while Home is held");
    key(WF_HOME, 0); frame(); CHECK(O.key[WF_A], "vpress back after the gate");
    wf_vpress(&S, 0, 0); frame(); CHECK(!O.key[WF_A] && !O.key[WF_R2] && O.abs[WF_HX] == 0, "p 0 0 releases all");
    { wf_map x; wf_map_reset(&x); x.layout = 'x'; wf_vpress(&S, WF_A, 1); wf_compute(&S, &x, &O);
      CHECK(O.key[WF_B] && !O.key[WF_A], "vpress follows the face layout (Xbox)"); wf_vpress(&S, 0, 0); }

    // ── gyro as a stick ──────────────────────────────────────────
    pad(0); S.gyro_stick = 2; S.gyro_x = 12000; S.gyro_y = -5000; ab(WF_RX, 1000); frame();
    CHECK(O.abs[WF_RX] == 13000 && O.abs[WF_RY] == -5000 && O.abs[WF_LX] == 0, "gyro adds to the right stick");
    S.gyro_x = 40000; frame(); CHECK(O.abs[WF_RX] == 32767, "gyro + stick clamps");
    S.gyro_stick = 1; S.gyro_x = -3000; S.gyro_y = 0; frame(); CHECK(O.abs[WF_LX] == -3000 && O.abs[WF_RX] == 1000, "gyro on the left stick");
    key(WF_HOME, 1); frame(); CHECK(O.abs[WF_LX] == 0, "no gyro while Home is held");

    // ── Home / Back gate ─────────────────────────────────────────
    pad(0); key(WF_HOME, 1); frame();
    CHECK(O.key[WF_HOME], "Home itself reaches Android");
    CHECK(key(0x137, 1) == 1, "R1 during Home is echoed"); frame();
    CHECK(!O.key[0x137], "Home + R1: R1 withheld");
    CHECK(ab(WF_GAS, 32000) == 1, "R2 analog echoed"); frame();
    CHECK(O.abs[WF_GAS] == 0, "Home + R2: analog withheld (no brightness leak)");
    CHECK(ab(WF_RY, -32000) == 1, "right stick echoed"); frame();
    CHECK(O.abs[WF_RY] == 0, "Home + right stick flick: stick withheld");
    key(WF_HOME, 0); frame();
    CHECK(!O.key[WF_HOME] && !O.key[0x137], "Home up, R1 still held → still silent");
    CHECK(O.abs[WF_GAS] == 0 && O.abs[WF_RY] == 0, "consumed trigger/stick stay silent after Home");
    CHECK(ab(WF_RY, -32000) == 1, "consumed stick keeps echoing");
    CHECK(key(0x137, 0) == 1, "R1 release echoed");
    ab(WF_GAS, 0); ab(WF_RY, 0); frame();
    key(0x137, 1); ab(WF_GAS, 20000); frame();
    CHECK(O.key[0x137] && O.abs[WF_GAS] == 20000, "after release: R1 / R2 normal again");

    pad(0); key(WF_R2, 1); ab(WF_GAS, 32767); ab(WF_LX, 30000); frame();    // driving
    key(WF_HOME, 1); frame();
    CHECK(!O.key[WF_R2] && O.abs[WF_GAS] == 0 && O.abs[WF_LX] == 0, "Home while driving: game sees all released");
    key(WF_HOME, 0); frame();
    CHECK(O.key[WF_R2] && O.abs[WF_GAS] == 32767 && O.abs[WF_LX] == 30000, "Home released: held-before controls come back");

    pad(0); key(WF_HOME, 1); key(0x137, 1); key(0x137, 0); key(WF_HOME, 0); frame();   // Home + R1 tap
    key(0x137, 1); frame();
    CHECK(O.key[0x137], "a combo tapped inside one Home hold doesn't stay consumed");

    pad(0); key(WF_BACK, 1); key(WF_A, 1); frame();
    CHECK(O.key[WF_BACK] && !O.key[WF_A], "Back gates too");

    // ── 1.3: bg=1 — Back goes to the game (RetroArch's Back hotkeys) ─────
    { wf_map mb; CHECK(wf_parse(&mb, &S, "bg=1") == 0 && mb.back_free == 1, "bg=1 parses"); }
    { wf_map mb; CHECK(wf_parse(&mb, &S, "k0x220=0x130 k0x221=0xfffe k0x222=65535 k0x223=0x220 apl=1 apr=0") == 0 &&
          mb.keymap[WF_HAT_UP] == WF_A && mb.keymap[WF_HAT_DOWN] == WF_EXT && mb.keymap[WF_HAT_LEFT] == WF_NONE &&
          mb.keymap[WF_HAT_RIGHT] == WF_HAT_UP && mb.app_stick[0] == 1 && mb.app_stick[1] == 0, "D-pad sources + apl parse"); }
    { wf_map mb; CHECK(wf_parse(&mb, &S, "apl=2") == -1, "apl=2 refused"); }
    pad(0); S.back_free = 1; key(WF_BACK, 1); CHECK(key(WF_A, 1) == 0, "bg=1: A during Back not echoed"); frame();
    CHECK(O.key[WF_BACK] && O.key[WF_A], "bg=1: Back + A both reach the game");
    key(WF_A, 0); key(WF_BACK, 0); frame();
    CHECK(!O.key[WF_BACK] && !O.key[WF_A], "bg=1: both released");
    pad(0); S.back_free = 1; key(WF_HOME, 1); key(WF_A, 1); frame();
    CHECK(!O.key[WF_A], "bg=1: Home still gates");

    // ── b18g (B4): Select + Back — Back pressed while a game button is held goes WITH the game ──
    // (RetroArch: hotkey enable = Select, menu toggle = Back. g2 R6 saw SELECT UP + KEY_BACK DOWN in one report.)
    pad(0); key(0x13a, 1); frame();
    CHECK(O.key[0x13a] && !S.back_hot, "B4 Select held: the game has it");
    CHECK(key(WF_BACK, 1) == 0 && S.back_hot && !S.gated, "B4 Back pressed while Select is held: that Back doesn't gate");
    frame();
    CHECK(O.key[0x13a] && O.key[WF_BACK] && wf_live_key(&S, 0x13a), "B4 the game sees Select + Back (Select never lifted)");
    CHECK(key(WF_A, 1) == 0, "B4 A under that Back: not echoed (no J)"); frame();
    CHECK(O.key[WF_A] && O.key[0x13a], "B4 ...A reaches the game too"); key(WF_A, 0);
    key(WF_BACK, 0); frame();
    CHECK(O.key[0x13a] && !O.key[WF_BACK] && !S.back_hot && !S.gated, "B4 Back up: Select still down, the flag cleared");
    CHECK(key(0x13a, 0) == 0, "B4 Select's release: not echoed"); frame();
    CHECK(!O.key[0x13a], "B4 Select up");
    key(WF_BACK, 1); frame();
    CHECK(S.gated && !S.back_hot && O.key[WF_BACK], "B4 per press: the next Back alone gates again");
    key(WF_BACK, 0);
    pad(0); key(0x13a, 1); key(WF_BACK, 1);
    CHECK(key(0x13a, 0) == 0 && key(0x13a, 1) == 0, "B4 Select let go and pressed again under that Back: not echoed"); frame();
    CHECK(O.key[0x13a] && O.key[WF_BACK] && S.back_hot, "B4 ...it reaches the game: the whole Back press is the game's");
    pad(0); key(WF_B, 1); key(WF_BACK, 1); frame();
    CHECK(S.back_hot && O.key[WF_B], "B4 any game button counts (B held + Back)");
    pad(1); key(WF_B, 1); key(WF_BACK, 1); frame();                  // AYN's Xbox mode: code B = printed A
    CHECK(S.back_hot && O.key[WF_B] && !O.key[WF_A], "B4 AYN's Xbox mode: printed A held + Back, the game keeps it");
    // Back alone, and a button pressed AFTER Back: the gate as before (#81: Back came first)
    pad(0); key(WF_BACK, 1);
    CHECK(S.gated && !S.back_hot, "B4 Back alone: the gate");
    CHECK(key(WF_A, 1) == 1, "B4 A during a plain Back: echoed (J)"); frame();
    CHECK(!O.key[WF_A] && O.key[WF_BACK], "B4 ...and withheld from the game");
    pad(0); key(WF_BACK, 1);
    CHECK(key(0x13a, 1) == 1, "B4 Select pressed AFTER Back: echoed"); frame();
    CHECK(!O.key[0x13a] && O.key[WF_BACK] && !S.back_hot, "B4 ...withheld: Back came first (#81)");
    key(WF_BACK, 0); frame();
    CHECK(!O.key[0x13a] && !S.gated, "B4 ...still silent after Back's release (consumed until let go)");
    // a withheld (consumed) button doesn't make Back the game's until it is let go, even after Home is — as ButtonEngine's
    // `withheld` (b18h review: its `gatedDown`, spent at Home's release, said otherwise)
    pad(0); key(WF_HOME, 1); key(WF_A, 1); key(WF_HOME, 0); key(WF_BACK, 1); frame();
    CHECK(S.gated && !S.back_hot && !O.key[WF_A], "B4 A withheld under Home, still held + Back: Back gates");
    // the profile's shift button held + Back: its gate was already open, so no flag (wf_event asks only with no gate open)
    pad(0); M.shift = 0x13a; S.shift = 0x13a; key(0x13a, 1); key(WF_BACK, 1);
    CHECK(S.gated && !S.back_hot, "B4 shift held + Back: the shift's gate was open, no flag");
    CHECK(key(WF_A, 1) == 1, "B4 ...A under them echoed"); frame();
    CHECK(!O.key[WF_A] && !O.key[0x13a], "B4 ...nothing for the game");
    // Home: under Home, Back never goes with the game (Home + Back = the emergency switch); Home still gates under it
    pad(0); key(0x13a, 1); key(WF_HOME, 1); key(WF_BACK, 1);
    CHECK(S.gated && !S.back_hot, "B4 Select held, Home, then Back: no flag (the gate was open)");
    key(WF_HOME, 0); frame();
    CHECK(S.gated && !O.key[0x13a], "B4 ...Home let go: Back still gates");
    pad(0); key(0x13a, 1); key(WF_BACK, 1); key(WF_HOME, 1); frame();
    CHECK(S.gated && S.back_hot && !O.key[0x13a] && O.key[WF_HOME], "B4 Home pressed under a Back that went with the game: Home gates");
    CHECK(key(WF_X, 1) == 1, "B4 ...X under Home: echoed"); key(WF_X, 0);
    key(WF_HOME, 0); frame();
    CHECK(!S.gated && O.key[0x13a] && O.key[WF_BACK], "B4 ...Home let go: Select back (held before Home), Back still the game's");
    // the D-pad (a HAT, not a key) and the sticks don't count: Back gates, as ButtonEngine's keys
    pad(0); ab(WF_HX, 1); ab(WF_LX, 30000); key(WF_BACK, 1); frame();
    CHECK(S.gated && !S.back_hot && O.abs[WF_HX] == 0 && O.abs[WF_LX] == 0, "B4 D-pad / stick held + Back: the gate as before");
    // Wayfinder's virtual presses: silent under a plain Back (as before), played under a Back that went with the game
    pad(0); key(WF_BACK, 1); wf_vpress(&S, WF_A, 1); frame();
    CHECK(!O.key[WF_A], "B4 vpress silent under a plain Back (unchanged)");
    pad(0); key(0x13a, 1); key(WF_BACK, 1); wf_vpress(&S, WF_A, 1); frame();
    CHECK(O.key[WF_A] && O.key[0x13a] && O.key[WF_BACK], "B4 vpress plays under a Back that went with the game (the vkeys line)");
    // bg=1 unchanged; a reset clears the flag
    pad(0); S.back_free = 1; key(0x13a, 1); key(WF_BACK, 1); frame();
    CHECK(!S.gated && O.key[0x13a] && O.key[WF_BACK], "B4 bg=1 + Select + Back: as before");
    pad(0); key(0x13a, 1); key(WF_BACK, 1); wf_state_rest(&S);
    CHECK(!S.back_hot && !S.gated, "B4 wf_state_rest clears the flag");
    // a profile switch under it (wfpad's "m": S.gated = wf_gate(&S)): the flag holds for that press
    pad(0); key(0x13a, 1); key(WF_BACK, 1); S.back_free = 0; S.gated = wf_gate(&S); frame();
    CHECK(!S.gated && O.key[0x13a], "B4 a profile switch under it: still the game's Back");

    // ── b18h (review): "held" as the app sees it on the copy — ButtonEngine decides the same Back from the copy's keys ──
    // the shift button itself, with no gate open (wf_game_key_held asked directly): not a game button
    pad(0); M.shift = 0x13a; wf_state_map(&S, &M); S.key[0x13a] = 1; S.gated = 0;
    CHECK(!wf_game_key_held(&S), "B4h the shift button held, no gate: not a game button (direct)");
    S.key[WF_A] = 1; CHECK(wf_game_key_held(&S), "B4h ...A held beside it: a game button (direct)");
    // wf_state_map copies the profile's parts: shift, bg=1, what the copy shows of each key
    pad(0); CHECK(wf_parse(&M, &S, "k0x130=0x131 k0x131=65535 k0x133=0xfffe k0x134=0x220 f0x139=1 f0x138=2 sh=0x13b bg=1") == 0,
                  "B4h a profile with remaps, none, Wayfinder, a D-pad target, turbo, toggle, shift, bg=1");
    wf_state_map(&S, &M);
    CHECK(S.shift == 0x13b && S.back_free == 1 && S.seen_as[WF_A] == WF_B && S.seen_as[WF_B] == WF_NONE &&
          S.seen_as[WF_X] == WF_NONE && S.seen_as[WF_Y] == WF_HAT_UP && S.seen_as[WF_R2] == WF_NONE &&
          S.seen_as[WF_L2] == WF_NONE && S.seen_as[0x136] == 0, "B4h wf_state_map: shift, bg, seen_as per key");
    wf_map_reset(&M); wf_state_map(&S, &M);
    CHECK(!S.shift && !S.back_free && S.seen_as[WF_B] == 0 && S.seen_as[WF_R2] == 0, "B4h ...an empty profile clears them");
    // turbo R2 held: the copy alternates it, so it never makes Back the game's (on or off half) — the gate, as before b18g
    pad(0); M.fire[WF_R2] = 1; M.turbo_hz = 12; wf_state_map(&S, &M); S.now_ms = 1000;
    key(WF_R2, 1); wf_fire_edge(&S, &M, WF_R2, 1); frame(); CHECK(O.key[WF_R2], "B4h turbo R2: on at the press");
    S.now_ms = 1050; frame(); CHECK(!O.key[WF_R2], "B4h ...off half (41 ms at 12/s)");
    key(WF_BACK, 1); frame();
    CHECK(S.gated && !S.back_hot && !O.key[WF_R2] && O.key[WF_BACK], "B4h turbo R2 held, Back in its off half: Back gates");
    CHECK(key(WF_A, 1) == 1, "B4h ...A under it: withheld (J)"); key(WF_A, 0); key(WF_BACK, 0);
    S.now_ms = 1090; frame(); CHECK(O.key[WF_R2], "B4h ...on half again");
    key(WF_BACK, 1); frame();
    CHECK(S.gated && !S.back_hot && !O.key[WF_R2], "B4h turbo R2 held, Back in its on half: Back gates too");
    // a toggle: the copy shows its latch, not the finger — never counted
    pad(0); M.fire[WF_L2] = 2; wf_state_map(&S, &M);
    key(WF_L2, 1); wf_fire_edge(&S, &M, WF_L2, 1); key(WF_L2, 0); key(WF_L2, 1); wf_fire_edge(&S, &M, WF_L2, 1); frame();
    CHECK(S.key[WF_L2] && !O.key[WF_L2], "B4h toggle L2: pressed again (latch off), finger still down");
    key(WF_BACK, 1); CHECK(S.gated && !S.back_hot, "B4h ...+ Back: gates (the copy shows no L2)");
    pad(0); M.fire[WF_L2] = 2; wf_state_map(&S, &M); key(WF_L2, 1); wf_fire_edge(&S, &M, WF_L2, 1); frame();
    key(WF_BACK, 1); frame(); CHECK(S.gated && !S.back_hot && !O.key[WF_L2], "B4h toggle L2 latched + Back: gates, as before b18g");
    // sent to nothing / to Wayfinder: the copy shows nothing
    pad(0); M.keymap[WF_B] = WF_NONE; wf_state_map(&S, &M); key(WF_B, 1); key(WF_BACK, 1);
    CHECK(S.gated && !S.back_hot, "B4h B → nothing, held + Back: gates");
    pad(0); M.keymap[WF_X] = WF_EXT; wf_state_map(&S, &M); key(WF_X, 1); key(WF_BACK, 1);
    CHECK(S.gated && !S.back_hot, "B4h X → Wayfinder (a key / an action), held + Back: gates");
    // a remap shows its target: a button the app names, or a D-pad direction (the app holds the copy's D-pad too)
    pad(0); M.keymap[WF_X] = WF_B; wf_state_map(&S, &M); key(WF_X, 1); key(WF_BACK, 1); frame();
    CHECK(!S.gated && S.back_hot && O.key[WF_B], "B4h X → B held + Back: the game's (the copy holds B)");
    pad(0); M.keymap[WF_Y] = WF_HAT_UP; wf_state_map(&S, &M); key(WF_Y, 1); key(WF_BACK, 1); frame();
    CHECK(!S.gated && S.back_hot && O.abs[WF_HY] == -1, "B4h Y → D-pad up held + Back: the game's (the copy holds up)");
    // codes the app never names (MODE, C, Z): as a key held, or as a remap's target
    pad(0); key(0x13c, 1); key(WF_BACK, 1); CHECK(S.gated && !S.back_hot, "B4h MODE held + Back: gates (the app has no MODE)");
    pad(0); key(0x132, 1); key(WF_BACK, 1); CHECK(S.gated && !S.back_hot, "B4h C held + Back: gates");
    pad(0); key(0x135, 1); key(WF_BACK, 1); CHECK(S.gated && !S.back_hot, "B4h Z held + Back: gates");
    pad(0); M.keymap[WF_A] = 0x13c; wf_state_map(&S, &M); key(WF_A, 1); key(WF_BACK, 1);
    CHECK(S.gated && !S.back_hot, "B4h A → MODE held + Back: gates");
    // every other button the app names still counts
    { static const int NAMED[] = { WF_A, WF_B, WF_X, WF_Y, 0x136, 0x137, WF_L2, WF_R2, 0x13a, 0x13b, 0x13d, 0x13e };
      int all = 1;
      for (int i = 0; i < 12; i++) { pad(0); key(NAMED[i], 1); key(WF_BACK, 1); if (S.gated || !S.back_hot) all = 0; }
      CHECK(all, "B4h each button the app names, held + Back: the game's"); }

    pad(0); key(WF_A, 1); key(WF_HOME, 1); key(WF_A, 0); key(WF_A, 1); key(WF_HOME, 0); frame();
    CHECK(!O.key[WF_A], "released and re-pressed during Home → consumed");

    // ── b18l (GitHub #81, b18w B1): Home / Back's change goes out in a report of its own ──
    // The gate lets go at Home's press and gives back at its release in the SAME frame. In one report the UHID copy wrote R2's
    // give-back before Home's release (the app made "Home + R2": Brighter, no home) and X's let-go before Home's press (no
    // partner: Home went home). (a) R2 and its pull held 1 s, then a Home tap:
    pad(0); key(WF_R2, 1); ab(WF_GAS, 30000); frame();
    key(WF_HOME, 1); step();
    CHECK(NE == 5 && is_ev(&E[0], EV_KEY, WF_HOME, 1) && is_syn(&E[1]) && is_ev(&E[2], EV_KEY, WF_R2, 0) &&
          is_ev(&E[3], EV_ABS, WF_GAS, 0) && is_syn(&E[4]), "b18l R2 held + Home: HOME DOWN, SYN, then R2 + its pull let go, SYN (%d)", NE);
    key(WF_HOME, 0); step();
    CHECK(NE == 5 && is_ev(&E[0], EV_KEY, WF_HOME, 0) && is_syn(&E[1]) && is_ev(&E[2], EV_KEY, WF_R2, 1) &&
          is_ev(&E[3], EV_ABS, WF_GAS, 30000) && is_syn(&E[4]), "b18l ...Home let go: HOME UP, SYN, then R2 given back, SYN (%d)", NE);
    { wf_out cur = O, mid[2]; key(WF_HOME, 1); frame();
      CHECK(wf_frames(&cur, &O, mid) == 1 && mid[0].key[WF_HOME] && mid[0].key[WF_R2] && mid[0].abs[WF_GAS] == 30000 &&
            !O.key[WF_R2] && O.abs[WF_GAS] == 0, "b18l wf_frames: Home's press alone first (R2 still held), then the rest"); }
    // the control: R2 let go while Home is held — nothing comes back, Home's release alone
    key(WF_R2, 0); ab(WF_GAS, 0); key(WF_HOME, 0); step();
    CHECK(NE == 2 && is_ev(&E[0], EV_KEY, WF_HOME, 0) && is_syn(&E[1]), "b18l ...R2 let go under Home: Home's release alone (%d)", NE);
    // (b) X about 40 ms before Home: X is still held on the copy when Home goes down
    pad(0); key(WF_X, 1); frame();
    key(WF_HOME, 1); step();
    CHECK(NE == 4 && is_ev(&E[0], EV_KEY, WF_HOME, 1) && is_syn(&E[1]) && is_ev(&E[2], EV_KEY, WF_X, 0) && is_syn(&E[3]),
          "b18l X held + Home: HOME DOWN, SYN, then X let go, SYN (%d)", NE);
    CHECK(key(WF_X, 0) == 0, "b18l ...X let go under Home: not echoed (the copy let it go at Home's press — b18l review)");
    step(); CHECK(NE == 0, "b18l ...nothing on the copy");
    key(WF_HOME, 0); step(); CHECK(NE == 2 && is_ev(&E[0], EV_KEY, WF_HOME, 0), "b18l ...Home's release alone (%d)", NE);
    // Home and X in one frame, Home first (WI-2's injector): X withheld, Home alone
    pad(0); frame(); key(WF_HOME, 1); key(WF_X, 1); step();
    CHECK(NE == 2 && is_ev(&E[0], EV_KEY, WF_HOME, 1) && is_syn(&E[1]), "b18l Home + X in one frame: Home alone, X withheld (%d)", NE);
    // a Home tap alone, and a frame with no Home / Back change: one report each, as before (keys, then axes)
    pad(0); frame(); key(WF_HOME, 1); step(); CHECK(NE == 2 && is_ev(&E[0], EV_KEY, WF_HOME, 1) && is_syn(&E[1]), "b18l Home alone: one report");
    key(WF_HOME, 0); step(); CHECK(NE == 2 && is_ev(&E[0], EV_KEY, WF_HOME, 0) && is_syn(&E[1]), "b18l ...its release: one report");
    key(WF_A, 1); ab(WF_LX, 1000); step();
    CHECK(NE == 3 && is_ev(&E[0], EV_KEY, WF_A, 1) && is_ev(&E[1], EV_ABS, WF_LX, 1000) && is_syn(&E[2]), "b18l no Home / Back change: one report");
    { wf_out cur = O, mid[2]; key(WF_A, 0); frame(); CHECK(wf_frames(&cur, &O, mid) == 0, "b18l wf_frames: 0 with no Home / Back change"); }
    // (c) Back: its release first, then what its gate gives back; its press AFTER what its gate lets go (b18h WI-4: the app
    //     sees nothing held at a Back that gates). Turbo R2 in its on half — Back doesn't count it:
    pad(0); M.fire[WF_R2] = 1; M.turbo_hz = 12; wf_state_map(&S, &M); S.now_ms = 1000;
    key(WF_R2, 1); wf_fire_edge(&S, &M, WF_R2, 1); frame(); CHECK(O.key[WF_R2], "b18l turbo R2: on");
    key(WF_BACK, 1); step();
    CHECK(S.gated && NE == 4 && is_ev(&E[0], EV_KEY, WF_R2, 0) && is_syn(&E[1]) && is_ev(&E[2], EV_KEY, WF_BACK, 1) && is_syn(&E[3]),
          "b18l turbo R2 + a Back that gates: R2 let go, SYN, then BACK DOWN, SYN (%d)", NE);
    key(WF_BACK, 0); step();
    CHECK(NE == 4 && is_ev(&E[0], EV_KEY, WF_BACK, 0) && is_syn(&E[1]) && is_ev(&E[2], EV_KEY, WF_R2, 1) && is_syn(&E[3]),
          "b18l ...Back let go: BACK UP, SYN, then R2 given back, SYN (%d)", NE);
    // Select held + Back (B4): no gate, nothing let go — Back alone both ways, Select kept
    pad(0); key(0x13a, 1); frame(); key(WF_BACK, 1); step();
    CHECK(!S.gated && O.key[0x13a] && NE == 2 && is_ev(&E[0], EV_KEY, WF_BACK, 1), "b18l Select held + Back: Back alone, Select kept (%d)", NE);
    key(WF_BACK, 0); step(); CHECK(NE == 2 && is_ev(&E[0], EV_KEY, WF_BACK, 0), "b18l ...its release alone (%d)", NE);
    // Select let go in the frame that presses Back (b18h review): Select's release first, then Back (which gates)
    pad(0); key(0x13a, 1); frame(); key(0x13a, 0); key(WF_BACK, 1); step();
    CHECK(S.gated && NE == 4 && is_ev(&E[0], EV_KEY, 0x13a, 0) && is_syn(&E[1]) && is_ev(&E[2], EV_KEY, WF_BACK, 1) && is_syn(&E[3]),
          "b18l Select let go + Back in one frame: SELECT UP, SYN, then BACK DOWN, SYN (%d)", NE);
    // X held, Home and Back in one frame: Home, then X let go, then Back
    pad(0); key(WF_X, 1); frame(); key(WF_HOME, 1); key(WF_BACK, 1); step();
    CHECK(NE == 6 && is_ev(&E[0], EV_KEY, WF_HOME, 1) && is_syn(&E[1]) && is_ev(&E[2], EV_KEY, WF_X, 0) && is_syn(&E[3]) &&
          is_ev(&E[4], EV_KEY, WF_BACK, 1) && is_syn(&E[5]), "b18l X held, Home + Back in one frame: HOME, X let go, BACK (%d)", NE);
    // Back let go and Home pressed in one frame: both in the first report
    pad(0); key(WF_BACK, 1); frame(); key(WF_BACK, 0); key(WF_HOME, 1); step();
    CHECK(NE == 3 && is_ev(&E[0], EV_KEY, WF_HOME, 1) && is_ev(&E[1], EV_KEY, WF_BACK, 0) && is_syn(&E[2]), "b18l Back up + Home down: one report (%d)", NE);
    // a fresh copy: every control in one report; and never more than [cap] events
    NE = wf_frame_events(&O, &O, S.keys, S.nk, S.axes, S.na, 1, E, 256);
    { int syns = 0; for (int i = 0; i < NE; i++) syns += is_syn(&E[i]);
      CHECK(NE == S.nk + S.na + 1 && syns == 1 && is_syn(&E[NE - 1]), "b18l full: every key and axis, one report (%d)", NE); }
    pad(0); key(WF_R2, 1); ab(WF_GAS, 30000); frame();
    { wf_out cur = O; key(WF_HOME, 1); frame(); NE = wf_frame_events(&cur, &O, S.keys, S.nk, S.axes, S.na, 0, E, 3);
      CHECK(NE <= 3 && is_ev(&E[0], EV_KEY, WF_HOME, 1) && is_syn(&E[1]), "b18l a small cap: Home's report kept, never past it (%d)", NE); }

    // ── b18l review: a button held before the gate is not echoed when let go under it ──
    // The gate let it go on the copy at its open, and the app took that from the copy. Echoed, the line could overtake the
    // copy's own HOME DOWN on its way to the app (ButtonEngine.onGated took X out of what Home found held: case b again).
    pad(0); key(WF_X, 1); frame(); key(WF_HOME, 1); frame();
    CHECK(key(WF_X, 0) == 0, "b18l-r X held before Home, let go under it: not echoed");
    CHECK(key(WF_X, 1) == 1 && S.ckey[WF_X], "b18l-r ...pressed again under Home: echoed and consumed (Home + X)");
    CHECK(key(WF_X, 0) == 1, "b18l-r ...that press's release: echoed");
    key(WF_HOME, 0); frame();
    // a button consumed under one Home and still held into the next: its release is echoed (Wayfinder had its press)
    pad(0); key(WF_HOME, 1); key(WF_X, 1); key(WF_HOME, 0); frame(); key(WF_HOME, 1); frame();
    CHECK(S.ckey[WF_X] && key(WF_X, 0) == 1, "b18l-r X consumed under a Home, held into the next, let go: echoed");
    // a release outside any gate: not echoed (as ever); a press under the gate: echoed (as ever)
    pad(0); key(WF_X, 1); CHECK(key(WF_X, 0) == 0, "b18l-r no gate: a release isn't echoed");
    // Back that gates (a turbo R2 held: Back doesn't count it) and the shift button: the same
    pad(0); M.fire[WF_R2] = 1; wf_state_map(&S, &M); key(WF_R2, 1); key(WF_BACK, 1);
    CHECK(S.gated && key(WF_R2, 0) == 0, "b18l-r turbo R2 held, a Back that gates, R2 let go under it: not echoed");
    CHECK(key(WF_A, 1) == 1 && key(WF_A, 0) == 1, "b18l-r ...A pressed and let go under that Back: echoed both ways");
    pad(0); M.shift = 0x13a; wf_state_map(&S, &M); key(0x137, 1); key(0x13a, 1);
    CHECK(S.gated && key(0x137, 0) == 0, "b18l-r R1 held, the shift button pressed, R1 let go under it: not echoed");
    CHECK(key(0x137, 1) == 1 && key(0x137, 0) == 1, "b18l-r ...R1 pressed again under the shift: echoed both ways");
    // the docked / merged chain (the review's trace): the reader's Home gates too and lets go of X in a report after its Home;
    // wfpad reads that let-go under its own gate. (b) △/X about 40 ms before Home:
    chain_init(); chain(WF_X, 1);
    CHECK(CJ == 0 && NE == 2 && is_ev(&E[0], EV_KEY, WF_X, 1), "b18l-r chain: X pressed reaches the copy (%d)", NE);
    chain(WF_HOME, 1);
    CHECK(CJ == 0, "b18l-r chain X held + Home: wfpad says no J for the reader's let-go (the hand still holds X)");
    CHECK(NE == 4 && is_ev(&E[0], EV_KEY, WF_HOME, 1) && is_syn(&E[1]) && is_ev(&E[2], EV_KEY, WF_X, 0) && is_syn(&E[3]),
          "b18l-r chain ...the copy: HOME DOWN, SYN, then X UP, SYN (%d)", NE);
    CHECK(chain(WF_X, 0) == 0 && CJ == 0 && NE == 0, "b18l-r chain ...X let go by the hand under Home: no J from either, nothing on the copy");
    chain(WF_HOME, 0);
    CHECK(CJ == 0 && NE == 2 && is_ev(&E[0], EV_KEY, WF_HOME, 0), "b18l-r chain ...Home let go: its release alone (%d)", NE);
    // X still held through Home's release: Home's release, then X back on the copy (a press of its own, after it)
    chain_init(); chain(WF_X, 1); chain(WF_HOME, 1); chain(WF_HOME, 0);
    CHECK(CJ == 0 && NE == 4 && is_ev(&E[0], EV_KEY, WF_HOME, 0) && is_syn(&E[1]) && is_ev(&E[2], EV_KEY, WF_X, 1) && is_syn(&E[3]),
          "b18l-r chain X held through a Home tap: HOME UP, SYN, then X DOWN, SYN (%d)", NE);
    // (a) R2 held, a Home tap: no J, R2 never consumed, back on the copy after Home's release
    chain_init(); chain(WF_R2, 1); chain(WF_HOME, 1);
    CHECK(CJ == 0 && NE == 4 && is_ev(&E[0], EV_KEY, WF_HOME, 1) && is_ev(&E[2], EV_KEY, WF_R2, 0), "b18l-r chain R2 held + Home: HOME DOWN, then R2 UP (%d)", NE);
    chain(WF_HOME, 0);
    CHECK(CJ == 0 && !S.ckey[WF_R2] && O.key[WF_R2] && NE == 4 && is_ev(&E[0], EV_KEY, WF_HOME, 0) && is_ev(&E[2], EV_KEY, WF_R2, 1),
          "b18l-r chain ...Home let go: HOME UP, then R2 DOWN, not consumed (%d)", NE);
    // Home + X (X pressed under Home): the reader's J, nothing on its pad, so nothing from wfpad
    chain_init(); chain(WF_HOME, 1);
    CHECK(chain(WF_X, 1) == 1 && CJ == 0 && NE == 0, "b18l-r chain Home + X: the reader's J, nothing for wfpad or the copy");
    CHECK(chain(WF_X, 0) == 1 && CJ == 0 && NE == 0, "b18l-r chain ...X let go: the reader's J");
    chain(WF_HOME, 0);
    // Select held + Back (B4): neither gates — Back alone on the copy, no J
    chain_init(); chain(0x13a, 1); chain(WF_BACK, 1);
    CHECK(CJ == 0 && !S.gated && S.back_hot && !R.gated && NE == 2 && is_ev(&E[0], EV_KEY, WF_BACK, 1),
          "b18l-r chain Select held + Back: no gate on either side, Back alone (%d)", NE);

    // ── b18n: a resync keeps the order the keys went down in (wf_replay; b18h WI-4 "Not changed" 1) ──
    // Select + Back (WI-4): code order replayed Back (0x9e) before Select (0x13a) — Back gated and withheld Select, while the
    // app had given that Back to the game
    wf_out was;
    pad(0); key(0x13a, 1); key(WF_BACK, 1); frame(); was = O; resync(1, 0, 0); frame();
    CHECK(S.back_hot && !S.gated && !S.ckey[0x13a] && !memcmp(&O, &was, sizeof O),
          "b18n Select + Back resynced: still the game's Back, Select still down, the copy unchanged");
    CHECK(key(WF_A, 1) == 0, "b18n ...A pressed after it: not echoed"); frame();
    CHECK(O.key[WF_A] && O.key[0x13a] && O.key[WF_BACK], "b18n ...A reaches the game too");
    pad(0); key(0x13a, 1); key(WF_BACK, 1); resync(0, 0, 0); frame();
    CHECK(S.gated && !S.back_hot && S.ckey[0x13a] && !O.key[0x13a], "b18n control: code order gates and withholds Select (the gap)");
    // the same press keeps its decision: Select let go during the drop, that Back is still the game's
    pad(0); key(0x13a, 1); key(WF_BACK, 1); resync(1, 0x13a, 0); frame();
    CHECK(S.back_hot && !S.gated && O.key[WF_BACK] && !O.key[0x13a], "b18n Select let go during the drop: still the game's Back");
    CHECK(key(WF_B, 1) == 0, "b18n ...B then reaches the game (no J)");
    // Back first, Select under it (a Back combo): still gates, Select still withheld
    pad(0); key(WF_BACK, 1); key(0x13a, 1); frame(); was = O; resync(1, 0, 0); frame();
    CHECK(S.gated && !S.back_hot && S.ckey[0x13a] && !memcmp(&O, &was, sizeof O), "b18n Back, then Select under it: unchanged");
    key(WF_BACK, 0); frame();
    CHECK(!S.gated && !O.key[0x13a], "b18n ...Back let go: Select silent until let go (consumed)");
    CHECK(key(0x13a, 0) == 1, "b18n ...its release echoed (the app's withheld press)");
    // Home: R2 held before it comes back at its release, X pressed under it stays withheld
    pad(0); key(WF_R2, 1); key(WF_HOME, 1); key(WF_X, 1); frame(); was = O; resync(1, 0, 0); frame();
    CHECK(S.gated && !S.ckey[WF_R2] && S.ckey[WF_X] && !memcmp(&O, &was, sizeof O),
          "b18n R2 held, Home, X under it: R2 live, X withheld, the copy unchanged");
    key(WF_HOME, 0); frame();
    CHECK(O.key[WF_R2] && !O.key[WF_X], "b18n ...Home let go: R2 back for the game, X still silent");
    pad(0); key(WF_R2, 1); key(WF_HOME, 1); resync(0, 0, 0); key(WF_HOME, 0); frame();
    CHECK(!O.key[WF_R2], "b18n control: code order withheld R2 (held before Home) until let go");
    // a button withheld under Home, Home let go, the button still held: still withheld after a resync (no gate open)
    pad(0); key(WF_HOME, 1); key(WF_X, 1); key(WF_HOME, 0); frame(); resync(1, 0, 0); frame();
    CHECK(!S.gated && S.ckey[WF_X] && !O.key[WF_X], "b18n X from Home + X, still held: still withheld");
    CHECK(key(WF_X, 0) == 1, "b18n ...its release echoed");
    pad(0); key(WF_HOME, 1); key(WF_X, 1); key(WF_HOME, 0); resync(0, 0, 0); frame();
    CHECK(O.key[WF_X], "b18n control: code order gave it to the game");
    // a Back that went with the game, then Home: Home gates; let go, Select back and Back still the game's
    pad(0); key(0x13a, 1); key(WF_BACK, 1); key(WF_HOME, 1); frame(); was = O; resync(1, 0, 0); frame();
    CHECK(S.gated && S.back_hot && !memcmp(&O, &was, sizeof O), "b18n Select + Back, then Home: Home gates, the flag kept");
    key(WF_HOME, 0); frame();
    CHECK(!S.gated && O.key[0x13a] && O.key[WF_BACK], "b18n ...Home let go: Select back, no gate");
    // the shift button (L1): A held before it, Back pressed under its gate (no flag), B under it. A gating Back still gates
    // (code order made it the game's: nothing held yet at its replay, then A withheld under it)
    pad(0); M.shift = 0x136; wf_state_map(&S, &M); key(WF_A, 1); key(0x136, 1); key(WF_BACK, 1); key(WF_B, 1); frame(); was = O;
    resync(1, 0, 0); frame();
    CHECK(S.gated && !S.back_hot && !S.ckey[WF_A] && S.ckey[WF_B] && S.shift_used && !memcmp(&O, &was, sizeof O),
          "b18n shift + Back under it + B: unchanged (A live, B withheld, the shift used)");
    key(0x136, 0); frame();
    CHECK(S.gated && !O.key[WF_A] && !O.key[WF_B], "b18n ...the shift let go: Back still gates (its decision kept)");
    pad(0); M.shift = 0x136; wf_state_map(&S, &M); key(WF_A, 1); key(0x136, 1); key(WF_BACK, 1); resync(0, 0, 0);
    CHECK(S.ckey[WF_A], "b18n control: code order withheld A, held before everything");
    // B pressed under the shift with a code below it (B 0x131 < L1 0x136): still withheld
    pad(0); M.shift = 0x136; wf_state_map(&S, &M); key(0x136, 1); key(WF_B, 1); resync(1, 0, 0); frame();
    CHECK(S.gated && S.ckey[WF_B] && !O.key[WF_B], "b18n B under the shift: still withheld");
    key(0x136, 0); frame();
    CHECK(!O.key[WF_B], "b18n ...the shift let go: B silent until let go");
    // the shift used under it (A pressed and let go), resynced: its release sends no tap
    pad(0); M.shift = 0x136; wf_state_map(&S, &M); S.now_ms = 1000; key(0x136, 1); key(WF_A, 1); key(WF_A, 0); resync(1, 0, 0);
    key(0x136, 0); frame();
    CHECK(S.shift_tap_until == 0 && !O.key[0x136], "b18n the shift used, resynced: no tap at its release");
    pad(0); M.shift = 0x136; wf_state_map(&S, &M); S.now_ms = 1000; key(0x136, 1); key(WF_A, 1); key(WF_A, 0); resync(0, 0, 0);
    key(0x136, 0);
    CHECK(S.shift_tap_until != 0, "b18n control: code order forgot it (a tap the hand didn't make)");
    // a key pressed during the drop: code order, as before b18n (under Back held alone: withheld)
    pad(0); key(WF_BACK, 1); resync(1, 0, WF_A); frame();
    CHECK(S.gated && S.ckey[WF_A] && !O.key[WF_A], "b18n A pressed during the drop under a plain Back: withheld");
    // AYN's Xbox mode: printed codes kept (code B = printed A held, then Back)
    pad(1); key(WF_B, 1); key(WF_BACK, 1); frame(); was = O; resync(1, 0, 0); frame();
    CHECK(S.back_hot && !S.gated && !memcmp(&O, &was, sizeof O), "b18n Xbox mode: printed A + Back resynced, unchanged");
    // with no state before (a new source): exactly the code order of before b18n
    {
        static wf_state A2, B2;
        unsigned seed = 18u; int differ = 0;
        for (int r = 0; r < 3000; r++) {
            pad(r & 1); M.shift = r % 3 ? 0x136 : 0; wf_state_map(&S, &M);
            unsigned char held[WF_NK] = {0};
            for (int i = 0; i < S.nk; i++) { seed = seed * 1103515245u + 12345u; held[S.keys[i]] = ((seed >> 16) & 3) == 0; }
            A2 = S; B2 = S;
            wf_replay(&A2, held, NULL);
            for (int i = 0; i < B2.nk; i++) if (held[B2.keys[i]]) { int p; wf_event(&B2, EV_KEY, B2.keys[i], 1, &p); }
            differ += memcmp(&A2, &B2, sizeof A2) != 0;
        }
        CHECK(differ == 0, "b18n no state before: the code order of before b18n, byte for byte (%d of 3000 differ)", differ);
    }
    // a resync with nothing dropped changes nothing, now or later — and code order did (the check can fail)
    CHECK(twin_fuzz(1, 5000) == 0, "b18n fuzz: a resync keeping the order changes nothing, now or later (5000 x 40 steps)");
    CHECK(twin_fuzz(0, 600) > 0, "b18n fuzz control: code order does change something");

    // ── b18n review: AYN's pad re-made (its controller-style switch, a per-game layout) while keys are held ──
    // Select + Back that went with the game: kept till the new pad is read — nothing on the copy, still the game's Back
    pad(0); key(0x13a, 1); key(WF_BACK, 1); frame(); remake(1, 0);
    CHECK(NE == 0 && S.back_hot && !S.gated && !S.ckey[0x13a], "b18n-r re-made, kept: Select + Back unchanged on the copy (%d events)", NE);
    CHECK(key(WF_A, 1) == 0, "b18n-r ...A pressed after it: not echoed"); frame();
    CHECK(O.key[WF_A] && O.key[0x13a] && O.key[WF_BACK], "b18n-r ...A reaches the game too");
    // control (before the review): let go first — the copy wrote Back and Select UP, then the replay (nothing held before)
    // pressed Back again for the game and withheld Select: two Backs for one press
    pad(0); key(0x13a, 1); key(WF_BACK, 1); frame(); remake(0, 0);
    CHECK(find_ev(EV_KEY, WF_BACK, 0) >= 0 && find_ev(EV_KEY, 0x13a, 0) >= 0 && find_ev(EV_KEY, WF_BACK, 1) > find_ev(EV_KEY, WF_BACK, 0) &&
          S.gated && !S.back_hot && S.ckey[0x13a], "b18n-r control: let go first = Back and Select UP, then Back again, Select withheld");
    // Select let go while no pad was there: kept, the same press — still the game's Back, Select UP alone on the copy
    pad(0); key(0x13a, 1); key(WF_BACK, 1); frame(); remake(1, 0x13a);
    CHECK(NE >= 1 && find_ev(EV_KEY, 0x13a, 0) >= 0 && find_ev(EV_KEY, WF_BACK, 0) < 0 && S.back_hot && !S.gated,
          "b18n-r re-made, Select let go meanwhile: Select UP only, still the game's Back");
    // R2 held, then Home: kept — no Home UP on the copy (no Home tap), R2 back for the game at Home's release
    pad(0); key(WF_R2, 1); key(WF_HOME, 1); frame(); remake(1, 0);
    CHECK(NE == 0 && S.gated && !S.ckey[WF_R2], "b18n-r re-made, kept: R2 + Home unchanged (%d events)", NE);
    key(WF_HOME, 0); frame();
    CHECK(O.key[WF_R2] && !O.key[WF_HOME], "b18n-r ...Home let go: R2 back for the game");
    pad(0); key(WF_R2, 1); key(WF_HOME, 1); frame(); remake(0, 0);
    CHECK(find_ev(EV_KEY, WF_HOME, 0) >= 0 && find_ev(EV_KEY, WF_HOME, 1) > find_ev(EV_KEY, WF_HOME, 0) && S.ckey[WF_R2],
          "b18n-r control: let go first = a Home tap on the copy, R2 withheld");
    // a Back that gates (Back first, X under it): kept — still gates, X still withheld, nothing on the copy
    pad(0); key(WF_BACK, 1); key(WF_X, 1); frame(); remake(1, 0);
    CHECK(NE == 0 && S.gated && !S.back_hot && S.ckey[WF_X], "b18n-r re-made, kept: Back + X under it unchanged (%d events)", NE);
    // AYN's Xbox mode on the new pad (its style switched): printed codes — printed A (code B) + Back unchanged
    pad(1); key(WF_B, 1); key(WF_BACK, 1); frame(); remake(1, 0);
    CHECK(NE == 0 && S.back_hot && !S.gated, "b18n-r re-made, Xbox mode: printed A + Back unchanged (%d events)", NE);

    // ── 1.3: D-pad directions as sources ─────────────────────────
    pad(0); M.keymap[WF_HAT_UP] = WF_A; ab(WF_HY, -1); frame();
    CHECK(O.key[WF_A] && O.abs[WF_HY] == 0, "D-pad up → A: A pressed, the game's D-pad stays centred");
    ab(WF_HY, 1); frame();
    CHECK(!O.key[WF_A] && O.abs[WF_HY] == 1, "D-pad down (not remapped) still works");
    ab(WF_HY, 0); frame(); CHECK(!O.key[WF_A] && O.abs[WF_HY] == 0, "D-pad released");
    pad(0); M.keymap[WF_HAT_UP] = WF_HAT_DOWN; ab(WF_HY, -1); frame();
    CHECK(O.abs[WF_HY] == 1, "D-pad up → down");
    pad(0); M.keymap[WF_HAT_LEFT] = WF_NONE; ab(WF_HX, -1); frame();
    CHECK(O.abs[WF_HX] == 0, "D-pad left → nothing");
    ab(WF_HX, 1); frame(); CHECK(O.abs[WF_HX] == 1, "D-pad right still works");
    pad(0); M.keymap[WF_HAT_RIGHT] = WF_L2; ab(WF_HX, 1); frame();
    CHECK(O.key[WF_L2] && O.abs[WF_BRAKE] == 32767 && O.abs[WF_HX] == 0, "D-pad right → L2: a full pull");
    pad(0); M.keymap[WF_HAT_UP] = WF_A; ab(WF_HY, -1); ab(WF_HX, 1); frame();
    CHECK(O.key[WF_A] && O.abs[WF_HY] == 0 && O.abs[WF_HX] == 1, "diagonal: up remapped, right kept");
    pad(0); M.dpad_ls = 1; M.keymap[WF_HAT_UP] = WF_A; ab(WF_HY, -1); frame();
    CHECK(O.key[WF_A] && O.abs[WF_LY] == 0, "D-pad ↔ stick: a remapped direction doesn't move the stick");
    {   // Wayfinder plays a direction ("X" lines)
        int dc[4], dv[4], dn;
        pad(0); M.keymap[WF_HAT_DOWN] = WF_EXT; ab(WF_HY, 1); frame();
        CHECK(O.abs[WF_HY] == 0, "D-pad down → Wayfinder: nothing for the game");
        dn = wf_dir_ext(&S, &M, WF_HY, 0, 1, dc, dv);
        CHECK(dn == 1 && dc[0] == WF_HAT_DOWN && dv[0] == 1, "D-pad down press → X line");
        ab(WF_HY, 0); dn = wf_dir_ext(&S, &M, WF_HY, 1, 0, dc, dv);
        CHECK(dn == 1 && dc[0] == WF_HAT_DOWN && dv[0] == 0, "D-pad down release → X line");
        dn = wf_dir_ext(&S, &M, WF_HY, 0, -1, dc, dv);
        CHECK(dn == 0, "D-pad up (not Wayfinder's): no X line");
        M.keymap[WF_HAT_UP] = WF_EXT; ab(WF_HY, 1);
        dn = wf_dir_ext(&S, &M, WF_HY, 1, -1, dc, dv);
        CHECK(dn == 2 && dv[0] == 0 && dc[0] == WF_HAT_DOWN && dv[1] == 1 && dc[1] == WF_HAT_UP, "down → up in one move: release first");
        pad(0); M.keymap[WF_HAT_DOWN] = WF_EXT; key(WF_HOME, 1); ab(WF_HY, 1);
        dn = wf_dir_ext(&S, &M, WF_HY, 0, 1, dc, dv);
        CHECK(dn == 0, "Home held: the direction is Wayfinder's shortcut, no X press");
    }
    // ── 1.3: a stick that is Wayfinder's (mouse / scroll / keys) ─────
    {
        int x, y;
        pad(0); M.app_stick[0] = 1; ab(WF_LX, 32767); ab(WF_RX, 20000); frame();
        CHECK(O.abs[WF_LX] == 0 && O.abs[WF_RX] == 20000, "left stick is Wayfinder's: centred for the game, right untouched");
        wf_app_stick(&S, &M, 0, &x, &y);
        CHECK(x == 1000 && y == 0, "full right → 1000, 0");
        ab(WF_LX, 3000); wf_app_stick(&S, &M, 0, &x, &y);
        CHECK(x == 0 && y == 0, "a 9 %% drift → exactly 0 (at least a 10 %% deadzone)");
        M.dz[0] = 20; ab(WF_LX, 5000); wf_app_stick(&S, &M, 0, &x, &y);
        CHECK(x == 0, "the stick's own bigger deadzone (20 %%) applies");
        M.dz[0] = 0; ab(WF_LX, 0); ab(WF_LY, 32767); wf_app_stick(&S, &M, 0, &x, &y);
        CHECK(x == 0 && y == 1000, "down → y 1000");
        key(WF_HOME, 1); wf_app_stick(&S, &M, 0, &x, &y);
        CHECK(x == 0 && y == 0, "Home held: 0 (Home + stick is a shortcut)");
        pad(0); wf_app_stick(&S, &M, 1, &x, &y);
        CHECK(x == 0 && y == 0, "a stick that isn't Wayfinder's: 0");
        pad(0); M.app_stick[1] = 1; M.swap_sticks = 1; ab(WF_RX, 32767); ab(WF_LX, 10000); frame();
        CHECK(O.abs[WF_RX] == 10000 && O.abs[WF_LX] == 0, "swap + right stick Wayfinder's: the physical right one is taken");
    }

    // ── 1.3: invert left / right (GitHub #19) ─────────────────────
    pad(0); M.inv_lx = 1; ab(WF_LX, 20000); ab(WF_LY, 10000); ab(WF_RX, 5000); frame();
    CHECK(O.abs[WF_LX] == -20000 && O.abs[WF_LY] == 10000 && O.abs[WF_RX] == 5000, "xl: left X mirrored, Y and the right stick untouched");
    pad(0); M.inv_rx = 1; ab(WF_RX, -32767); frame();
    CHECK(O.abs[WF_RX] == 32767, "xr: right X mirrored");
    { wf_map mx; CHECK(wf_parse(&mx, &S, "xl=1 xr=1") == 0 && mx.inv_lx && mx.inv_rx, "xl / xr parse"); }

    // ── profile parsing ──────────────────────────────────────────
    pad(0);
    CHECK(wf_parse(&M, &S, "L=x k0x130=0x131 k0x131=65535 k0x13b=0x220 sw=1 td=1") == 0 &&
          M.layout == 'x' && M.keymap[WF_A] == WF_B && M.keymap[WF_B] == WF_NONE && M.swap_sticks && M.trig_digital, "parse ok");
    wf_map before = M;
    CHECK(wf_parse(&M, &S, "k0x66=0x130") != 0, "Home can't be remapped");
    CHECK(wf_parse(&M, &S, "k0x130=0x66") != 0, "nothing can become Home");
    CHECK(wf_parse(&M, &S, "k0x130=0x2") != 0, "unknown target refused");
    CHECK(wf_parse(&M, &S, "L=q") != 0 && wf_parse(&M, &S, "sw=2") != 0 && wf_parse(&M, &S, "zz=1") != 0, "bad tokens refused");
    CHECK(!memcmp(&M, &before, sizeof M), "a refused line leaves the map untouched");
    CHECK(wf_parse(&M, &S, "") == 0 && M.layout == 0 && M.keymap[WF_A] == 0, "empty line = no remap");

    // ── round 8: stick shape + trigger range ──────────────────────
    pad(0); M.dz[0] = 10; ab(WF_LX, 2000); ab(WF_LY, -1500); frame();               // ~7.6 % drift
    CHECK(O.abs[WF_LX] == 0 && O.abs[WF_LY] == 0, "drift inside the deadzone -> centre");
    ab(WF_LX, 32767); ab(WF_LY, 0); frame();
    CHECK(O.abs[WF_LX] == 32767, "full push still full (%d)", O.abs[WF_LX]);
    ab(WF_LX, 16384); frame();                                                     // 50 % -> (50-10)/90 = 44 %
    CHECK(O.abs[WF_LX] > 14000 && O.abs[WF_LX] < 15000, "rescaled after the deadzone (%d)", O.abs[WF_LX]);
    pad(0); M.oz[1] = 80; ab(WF_RX, 26214); frame();                               // 80 % = full
    CHECK(O.abs[WF_RX] == 32767, "outer: 80 %% reaches full (%d)", O.abs[WF_RX]);
    pad(0); M.cv[0] = 1; ab(WF_LX, 16384); frame();                                 // precise: 50 % -> 25 %
    CHECK(O.abs[WF_LX] > 8000 && O.abs[WF_LX] < 8400, "precise centre (%d)", O.abs[WF_LX]);
    pad(0); M.cv[0] = 2; ab(WF_LX, 8192); frame();                                  // fast: 25 % -> 50 %
    CHECK(O.abs[WF_LX] > 16200 && O.abs[WF_LX] < 16600, "fast (%d)", O.abs[WF_LX]);
    pad(0); M.dz[0] = 10; M.swap_sticks = 1; ab(WF_LX, 2000); frame();              // the PHYSICAL left stick
    CHECK(O.abs[WF_RX] == 0, "deadzone follows the physical stick through a swap (%d)", O.abs[WF_RX]);
    pad(0); M.tlo = 10; M.thi = 80; ab(WF_GAS, 2000); frame();                      // 6 % -> nothing
    CHECK(O.abs[WF_GAS] == 0, "trigger below its start -> 0 (%d)", O.abs[WF_GAS]);
    ab(WF_GAS, 27000); frame();                                                    // 82 % -> full
    CHECK(O.abs[WF_GAS] == 32767, "trigger past its end -> full (%d)", O.abs[WF_GAS]);
    ab(WF_GAS, 14745); frame();                                                    // 45 % -> 50 %
    CHECK(O.abs[WF_GAS] > 16000 && O.abs[WF_GAS] < 16800, "trigger rescaled (%d)", O.abs[WF_GAS]);
    pad(0);
    CHECK(wf_parse(&M, &S, "dzl=8 dzr=12 ozl=90 cvr=2 tlo=5 thi=95") == 0 && M.dz[0] == 8 && M.dz[1] == 12 &&
          M.oz[0] == 90 && M.cv[1] == 2 && M.tlo == 5 && M.thi == 95, "parse shape");
    CHECK(wf_parse(&M, &S, "dzl=31") != 0 && wf_parse(&M, &S, "ozl=60") != 0 && wf_parse(&M, &S, "cvl=3") != 0 &&
          wf_parse(&M, &S, "tlo=51") != 0 && wf_parse(&M, &S, "thi=49") != 0, "shape out of range refused");

    // ── hold-to-shift (§6l) ────────────────────────────────────────
    pad(0); M.shift = 0x13a; S.shift = 0x13a; S.now_ms = 1000;
    CHECK(key(0x13a, 1) == 1, "the shift button is echoed to Wayfinder");
    frame(); CHECK(!O.key[0x13a], "held shift: not sent to the game");
    CHECK(key(WF_A, 1) == 1, "a press while shift is held is echoed"); ab(WF_LX, 20000); frame();
    CHECK(!O.key[WF_A] && O.abs[WF_LX] == 0, "nothing reaches the game while shift is held");
    key(WF_A, 0); ab(WF_LX, 0); S.now_ms = 1200; key(0x13a, 0); frame();
    CHECK(!O.key[0x13a] && S.shift_tap_until == 0, "shift used for a combo: no tap on release");
    key(WF_A, 1); frame(); CHECK(O.key[WF_A], "after shift: A reaches the game again"); key(WF_A, 0);
    S.now_ms = 2000; key(0x13a, 1); S.now_ms = 2150; key(0x13a, 0); frame();
    CHECK(O.key[0x13a], "shift alone: a tap reaches the game on release");
    S.now_ms = 2250; frame(); CHECK(!O.key[0x13a], "the tap ends after 60 ms");
    pad(0); M.shift = 0x13a; S.shift = 0x13a; M.keymap[0x13a] = WF_B; S.now_ms = 10; key(0x13a, 1); key(0x13a, 0); frame();
    CHECK(O.key[WF_B] && !O.key[0x13a], "the tap follows the shift button's remap");
    pad(0); key(WF_A, 1); M.shift = 0x13a; S.shift = 0x13a; key(0x13a, 1); frame();
    CHECK(!O.key[WF_A], "held before shift: paused while shift is held (like Home)");
    key(0x13a, 0); S.now_ms = 1000000; frame(); CHECK(O.key[WF_A], "...and back when shift is let go");
    pad(0);
    CHECK(wf_parse(&M, &S, "sh=0x13a") == 0 && M.shift == 0x13a, "parse sh");
    CHECK(wf_parse(&M, &S, "sh=0x66") != 0 && wf_parse(&M, &S, "sh=0x220") != 0 && wf_parse(&M, &S, "sh=0x2") != 0,
          "sh: Home, the D-pad or an unknown key refused");
    CHECK(wf_parse(&M, &S, "sh=0") == 0 && M.shift == 0, "sh=0 = none");
    pad(0); M.shift = 0x13a; S.shift = 0x13a; key(0x13a, 1); wf_vpress(&S, WF_A, 1); frame();
    CHECK(O.key[WF_A], "a Shift-layer button press (virtual) reaches the game while shift is held");
    pad(0); key(WF_HOME, 1); wf_vpress(&S, WF_A, 1); frame();
    CHECK(!O.key[WF_A], "virtual presses stay silent while Home is held");

    // dev build 15: a "… (Wayfinder)" name is never AYN's pad (wfpad ayn_like: AYN's copy of a pad of ours)
    CHECK(wf_wayfinder_name("Xbox Wireless Controller (Wayfinder)") && wf_wayfinder_name("Joy-Con pair (Wayfinder)"),
          "names ending \" (Wayfinder)\" are Wayfinder's");
    CHECK(!wf_wayfinder_name("Odin Controller") && !wf_wayfinder_name("Xbox Wireless Controller") && !wf_wayfinder_name(""),
          "AYN's own pad names are not");
    CHECK(!wf_wayfinder_name("(Wayfinder)") && !wf_wayfinder_name("Pad (wayfinder)") && !wf_wayfinder_name("Pad (Wayfinder) 2"),
          "only the exact ending counts");

    // dev build 16 (WP4): one spelling, one map — a controller's own controls re-spelled (PadRules.canonical: another order,
    // an overwritten duplicate gone, k codes in hex) parse to the very same map: wfhid answers "the one in use" (P ok, nothing
    // done). argv[1]: the JVM test's pairs (PadMigrationTest, app/build/wp4/spec_pairs.txt: raw TAB canonical)
    {
        static const char *PAIRS[][2] = {
            { "L=x k0x133=305", "L=x k0x133=0x131" },                                 // a real DualSense
            { "k0x13d=0x131 k0x13a=304", "k0x13a=0x130 k0x13d=0x131" },
            { "sw=0 k304=305 sw=1 k0x130=65535", "k0x130=0xffff sw=1" },
            { "f0x220=1 k0x132=0x13c", "k0x132=0x13c f0x220=1" },
        };
        wf_map a, b;
        for (unsigned i = 0; i < sizeof PAIRS / sizeof *PAIRS; i++) {
            pad(0); memset(&a, 0, sizeof a); memset(&b, 0, sizeof b);
            CHECK(wf_parse(&a, &S, PAIRS[i][0]) == 0 && wf_parse(&b, &S, PAIRS[i][1]) == 0 && !memcmp(&a, &b, sizeof a),
                  "re-spelled, the same map: %s", PAIRS[i][0]);
        }
        pad(0); memset(&a, 0, sizeof a); memset(&b, 0, sizeof b);
        CHECK(wf_parse(&a, &S, "k0x130=0x131") == 0 && wf_parse(&b, &S, "k0x130=0x134") == 0 && memcmp(&a, &b, sizeof a),
              "...and another mapping is another map (the check can fail)");
        if (argc > 1) {
            FILE *f = fopen(argv[1], "r");
            int n = 0, bad = 0;
            char line[4096];
            CHECK(f != 0, "the pairs file %s opens", argv[1]);
            while (f && fgets(line, sizeof line, f)) {
                char *tab = strchr(line, '\t'), *canon;
                if (!tab) continue;
                *tab = 0; canon = tab + 1; canon[strcspn(canon, "\r\n")] = 0;
                pad(0); memset(&a, 0, sizeof a); memset(&b, 0, sizeof b); n++;
                if (!(wf_parse(&a, &S, line) == 0 && wf_parse(&b, &S, canon) == 0 && !memcmp(&a, &b, sizeof a)) && bad++ < 3)
                    printf("differ: [%s] [%s]\n", line, canon);
            }
            if (f) fclose(f);
            CHECK(n > 0 && bad == 0, "the JVM test's %d re-spelled lines: the same map (%d differ)", n, bad);
        }
    }

    // ── b18o (2026-10-11 — GitHub #81's turbo leftover): the copy's buttons a hand holds a turbo button for, told to
    //    Wayfinder ("TU", wf_turbo_held) as wfpad's emit does after each frame — once at the press, never at a pulse, once at
    //    the release; under a gate still held; never a consumed press, the shift button or a job the app doesn't name ─────────
    {
        unsigned char said[WF_NK]; int c[16], v[16], n = 0;
#define TU() (n = wf_turbo_held(&S, &M, said, c, v, 16))
        pad(0); memset(said, 0, sizeof said);
        CHECK(wf_parse(&M, &S, "f0x139=1 tr=12") == 0, "b18o profile: R2 turbo"); wf_state_map(&S, &M);
        frame(); CHECK(TU() == 0, "b18o nothing held: nothing said");
        S.now_ms = 1000; key(WF_R2, 1); wf_fire_edge(&S, &M, WF_R2, 1); frame();
        CHECK(O.key[WF_R2] && TU() == 1 && c[0] == WF_R2 && v[0] == 1, "b18o R2 (turbo) pressed: on the copy, TU R2 1 once");
        {   int flips = 0, more = 0, last = O.key[WF_R2];
            for (long t = 1000; t <= 2000; t += 10) { S.now_ms = t; frame(); flips += O.key[WF_R2] != last; last = O.key[WF_R2]; more += TU() != 0; }
            CHECK(flips >= 20 && more == 0, "b18o ...a second of pulses: %d flips on the copy, nothing said (%d)", flips, more); }
        key(WF_HOME, 1); frame();
        CHECK(S.gated && !O.key[WF_R2] && TU() == 0, "b18o ...Home held: R2 silent on the copy, still the hand's (nothing said)");
        key(WF_HOME, 0); frame(); CHECK(TU() == 0, "b18o ...Home let go: still held (nothing said)");
        resync(1, 0, 0); frame(); CHECK(TU() == 0, "b18o ...a resync (b18n's replay): still held (nothing said)");
        key(WF_HOME, 1); frame(); key(WF_R2, 0); frame();
        CHECK(TU() == 1 && c[0] == WF_R2 && v[0] == 0, "b18o ...let go under Home: TU R2 0");
        key(WF_R2, 1); frame(); CHECK(S.ckey[WF_R2] && TU() == 0, "b18o R2 pressed under Home (consumed — Home + R2): not said");
        key(WF_HOME, 0); frame(); CHECK(!O.key[WF_R2] && TU() == 0, "b18o ...Home let go, R2 still withheld: silent, not said");
        key(WF_R2, 0); frame(); CHECK(TU() == 0, "b18o ...its release: nothing to say");
        // its JOB is said: R1 → R2 on turbo; two turbo buttons on one job: one hold, let go when neither holds it
        pad(0); memset(said, 0, sizeof said);
        CHECK(wf_parse(&M, &S, "k0x137=0x139 f0x137=1 f0x139=1") == 0, "b18o profile: R1 → R2 turbo, R2 turbo"); wf_state_map(&S, &M);
        key(0x137, 1); frame(); CHECK(TU() == 1 && c[0] == WF_R2 && v[0] == 1, "b18o ...R1 held: TU R2 1 (its job)");
        key(WF_R2, 1); frame(); CHECK(TU() == 0, "b18o ...R2 too: the same hold (nothing said)");
        key(0x137, 0); frame(); CHECK(TU() == 0, "b18o ...R1 let go, R2 still held: nothing said");
        key(WF_R2, 0); frame(); CHECK(TU() == 1 && c[0] == WF_R2 && v[0] == 0, "b18o ...R2 let go too: TU R2 0");
        // never: a job that is nothing / Wayfinder's / a D-pad direction / MODE (not a button the app names), the shift button,
        // a toggle; a turbo the profile drops while held is said let go, a new profile's turbo said held
        static const char *NOJOB[] = { "k0x130=0xffff f0x130=1", "k0x130=0xfffe f0x130=1", "k0x130=0x220 f0x130=1",
                                       "k0x130=0x13c f0x130=1", "sh=0x130 f0x130=1", "f0x130=2" };
        for (unsigned i = 0; i < sizeof NOJOB / sizeof *NOJOB; i++) {
            pad(0); memset(said, 0, sizeof said);
            CHECK(wf_parse(&M, &S, NOJOB[i]) == 0, "b18o profile %s", NOJOB[i]); wf_state_map(&S, &M);
            key(WF_A, 1); frame(); CHECK(TU() == 0, "b18o ...A held with %s: not said", NOJOB[i]);
        }
        pad(0); memset(said, 0, sizeof said); wf_parse(&M, &S, "f0x130=1"); wf_state_map(&S, &M);
        key(WF_A, 1); frame(); CHECK(TU() == 1 && c[0] == WF_A && v[0] == 1, "b18o A turbo held: TU A 1");
        wf_parse(&M, &S, ""); wf_state_map(&S, &M); frame();
        CHECK(TU() == 1 && c[0] == WF_A && v[0] == 0 && O.key[WF_A], "b18o ...a profile without turbo: TU A 0 (A steady on the copy)");
        wf_parse(&M, &S, "f0x130=1"); wf_state_map(&S, &M); frame();
        CHECK(TU() == 1 && c[0] == WF_A && v[0] == 1, "b18o ...turbo again while held: TU A 1");
        wf_state_rest(&S); frame(); CHECK(TU() == 1 && c[0] == WF_A && v[0] == 0, "b18o ...the pad gone (at rest): TU A 0");
        // AYN's Xbox mode: the printed button is said (the source code is its position)
        pad(1); memset(said, 0, sizeof said); wf_parse(&M, &S, "f0x130=1"); wf_state_map(&S, &M);
        key(WF_B, 1); frame(); CHECK(TU() == 1 && c[0] == WF_A && v[0] == 1, "b18o Xbox mode: printed A (code B) held: TU A 1");
        // the cap: never more than asked, the rest said next time
        pad(0); memset(said, 0, sizeof said); wf_parse(&M, &S, "f0x130=1 f0x131=1 f0x133=1"); wf_state_map(&S, &M);
        key(WF_A, 1); key(WF_B, 1); key(WF_X, 1); frame();
        n = wf_turbo_held(&S, &M, said, c, v, 2);
        CHECK(n == 2 && wf_turbo_held(&S, &M, said, c, v, 16) == 1 && c[0] == WF_X, "b18o the cap: 2, then the third");
#undef TU
    }

    printf("%s: %d checks, %d failed\n", fails ? "FAILED" : "OK", checks, fails);
    return fails != 0;
}
