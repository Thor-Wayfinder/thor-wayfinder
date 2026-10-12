// wfmerge_test — unit tests of the merged feed (fx/wfmerge.h, dev build 18f), run ON the Thor:
//   tools/build_wfpad.sh test   → pushes and runs /data/local/tmp/wfmerge_test (after wfmap_test)
// A fake pad with the Thor's real keys and axes (as fx/wfmap_test.c); the engine fed as wfpad's engine_event feeds it
// (wf_event, then wf_fire_edge for a key), its output by wf_compute.
#include <stdio.h>
#include "wfmerge.h"

static int fails = 0, checks = 0;
#define CHECK(cond, ...) do { checks++; if (!(cond)) { fails++; printf("FAIL %s:%d ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static const int KEYS[] = { 0x66, 0x72, 0x73, 0x9e, 0x130, 0x131, 0x132, 0x133, 0x134, 0x135, 0x136, 0x137,
                            0x138, 0x139, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e, 0x220, 0x221, 0x222, 0x223, 0x244 };
#define NKEYS ((int)(sizeof KEYS / sizeof *KEYS))
static const int AXES[] = { 0, 1, 2, 5, 9, 10, 16, 17 };
static wf_state S; static wf_map M; static wf_out O; static wf_merge MG;
enum { A = WFM_AYN, E = WFM_EXT };

static void padinit(wf_state *s) {
    memset(s, 0, sizeof *s);
    s->nk = NKEYS;
    for (int i = 0; i < s->nk; i++) s->keys[i] = (unsigned short)KEYS[i];
    s->na = 8;
    for (int i = 0; i < 8; i++) {
        int a = AXES[i];
        s->axes[i] = (unsigned char)a;
        s->info[a].minimum = a >= 16 ? -1 : (a == 9 || a == 10) ? 0 : -32767;
        s->info[a].maximum = a >= 16 ? 1 : 32767;
    }
    wf_state_rest(s);
}

/* what the engine got (wfpad's engine_event): every event, the key edges (values 0 / 1), the axis events, the echoes */
static int nev, keyedge[WF_NK], absev[WF_NA], echoes, last_echo;
static void eng(void *ctx, int type, int code, int value) {
    wf_state *s = ctx ? (wf_state *)ctx : &S;
    int p; last_echo = wf_event(s, type, code, value, &p);
    if (last_echo) echoes++;
    if (type == EV_KEY) { if (value != 2 && code < WF_NK) keyedge[code]++; wf_fire_edge(s, &M, p, value); }
    if (type == EV_ABS && code < WF_NA) absev[code]++;
    nev++;
}
static void fresh(void) {
    padinit(&S); wf_map_reset(&M); wfm_reset(&MG, &S);
    nev = echoes = last_echo = 0; memset(keyedge, 0, sizeof keyedge); memset(absev, 0, sizeof absev);
}
static int key(int src, int code, int v) { return wfm_event(&MG, &S, src, EV_KEY, code, v, eng, 0); }
static int ab(int src, int a, int v) { return wfm_event(&MG, &S, src, EV_ABS, a, v, eng, 0); }
static void frame(void) { wf_compute(&S, &M, &O); }

static unsigned rnd_state = 4242;
static unsigned rnd(void) { rnd_state = rnd_state * 1103515245u + 12345u; return rnd_state >> 8; }

/* a kernel-like value change for axis [a] (never the value it has): mostly small moves near rest (the bands), sometimes
   anywhere in its range */
static int new_value(const wf_state *s, int a, int cur) {
    int mn = s->info[a].minimum, mx = s->info[a].maximum, v = cur;
    for (int tries = 0; v == cur && tries < 16; tries++) {
        unsigned c = rnd() % 4;
        if (wfm_is_hat(a)) v = (int)(rnd() % 3) - 1;
        else if (c == 0) v = mn + (int)(rnd() % (unsigned)(mx - mn + 1));
        else if (c == 1) v = wf_rest(s, a) + (int)(rnd() % 2401) - 1200;            /* around the still band */
        else if (c == 2) v = wf_rest(s, a) + (int)(rnd() % 9001) - 4500;            /* around the rest band */
        else v = cur + (int)(rnd() % 1601) - 800;                                    /* jitter */
        if (v < mn) v = mn;
        if (v > mx) v = mx;
    }
    return v == cur ? (cur == mn ? mx : mn) : v;
}

/* test 10's direct path and its record of what the merged path gave the engine */
static wf_state S1, S2;
static int rec_n, rec_type, rec_code, rec_value, rec_echo, rec_p;
static void rec(void *ctx, int type, int code, int value) {
    (void)ctx; rec_n++; rec_type = type; rec_code = code; rec_value = value;
    rec_echo = wf_event(&S2, type, code, value, &rec_p);
    if (type == EV_KEY) wf_fire_edge(&S2, &M, rec_p, value);
}
static void drop_cb(void *ctx, int type, int code, int value) { eng(ctx, type, code, value); }

int main(void) {
    // ── the bands ───────────────────────────────────────────────
    fresh();
    CHECK(wfm_partner(WF_LX) == WF_LY && wfm_partner(WF_RY) == WF_RX && wfm_partner(WF_HX) == -1 && wfm_partner(WF_GAS) == -1,
          "partners: LX-LY, RX-RY; the hat and the triggers alone");
    CHECK(wfm_rest(&S, WF_LX, 4095) && !wfm_rest(&S, WF_LX, 4096) && wfm_rest(&S, WF_LY, -4095), "stick rest band: range/16 (4095)");
    CHECK(wfm_still(&S, WF_LX, 1023) && !wfm_still(&S, WF_LX, 1024) && wfm_still(&S, WF_RX, -514), "stick still band: range/64 (1023), a DualSense's jitter inside");
    CHECK(wfm_rest(&S, WF_GAS, 1638) && !wfm_rest(&S, WF_GAS, 1639) && !wfm_still(&S, WF_GAS, 983), "trigger: rest range/20, 3 %% not still");
    CHECK(wfm_rest(&S, WF_HX, 0) && !wfm_rest(&S, WF_HX, -1) && !wfm_still(&S, WF_HY, 1), "hat: rest / still = 0");

    // 1. keys: held by either; one press, one release
    fresh(); key(A, WF_A, 1);
    CHECK(keyedge[WF_A] == 1, "1 AYN presses A: one engine press");
    key(E, WF_A, 1); key(E, WF_A, 0); frame();
    CHECK(keyedge[WF_A] == 1 && O.key[WF_A], "1 EXT presses and releases A: no engine event, A stays out");
    key(A, WF_A, 0); frame();
    CHECK(keyedge[WF_A] == 2 && !O.key[WF_A], "1 AYN lets go: one release");
    key(E, WF_B, 0);
    CHECK(keyedge[WF_B] == 0, "1 a release from a source that didn't hold the key: nothing");

    // 2. repeats
    fresh(); key(A, WF_A, 1); nev = 0;
    CHECK(key(A, WF_A, 2) == 1 && nev == 1, "2 a repeat from the holder passes");
    CHECK(key(E, WF_A, 2) == 0 && nev == 1, "2 a repeat from a non-holder is dropped");

    // 3. sticks: one owner per stick, its two axes together
    fresh(); ab(E, WF_LX, 20000); frame();
    CHECK(O.abs[WF_LX] == 20000 && MG.owner[WF_LX] == E && MG.owner[WF_LY] == E, "3 EXT pushes LX 20000: it takes the stick");
    int n0 = nev;
    ab(A, WF_LX, 1500); ab(A, WF_LX, -1500); ab(A, WF_LY, 1500); frame();
    CHECK(O.abs[WF_LX] == 20000 && O.abs[WF_LY] == 0 && nev == n0, "3 AYN at rest wiggling ±1500: ignored, no engine event");
    n0 = nev; CHECK(ab(A, WF_LX, -25000) == 2, "3 AYN takes over: both axes change at once"); frame();
    CHECK(O.abs[WF_LX] == -25000 && O.abs[WF_LY] == 1500 && nev == n0 + 2, "3 ...both LX and LY are AYN's (%d %d)", O.abs[WF_LX], O.abs[WF_LY]);
    ab(A, WF_LX, 0); frame();
    CHECK(O.abs[WF_LX] == 20000 && O.abs[WF_LY] == 0 && MG.owner[WF_LX] == E, "3 AYN back at rest: EXT's held 20000 / its LY back at once");
    ab(E, WF_LX, 0); frame();
    /* review fix: EXT now still, AYN's LY left at 1500 by its wiggle (beyond the still band, inside the rest band) is rule
       3's gentle hold — handed back at once: both at rest, the owner's value passes (the profile's deadzone eats it) */
    CHECK(O.abs[WF_LX] == 0 && O.abs[WF_LY] == 1500 && MG.owner[WF_LY] == A && wfm_rest(&S, WF_LY, O.abs[WF_LY]),
          "3 EXT at rest: rest — AYN's resting 1500 on LY passes (%d %d)", O.abs[WF_LX], O.abs[WF_LY]);
    ab(A, WF_LY, 0); frame();
    CHECK(O.abs[WF_LX] == 0 && O.abs[WF_LY] == 0, "3 both at their exact rest: 0, 0");
    CHECK(MG.owner[WF_RX] == A && MG.owner[WF_GAS] == A, "3 the other groups untouched");

    // 4. jitter never steals a held stick
    fresh(); ab(A, WF_LX, 30000); ab(E, WF_LX, -20000); frame();
    CHECK(O.abs[WF_LX] == -20000, "4 EXT pushes while AYN holds 30000: EXT's");
    n0 = nev; ab(A, WF_LX, 30800); ab(A, WF_LX, 29200); ab(A, WF_LX, 30500); ab(A, WF_LY, 700); ab(A, WF_LY, -600); frame();
    CHECK(O.abs[WF_LX] == -20000 && nev == n0, "4 AYN held at 30000 ± 800: stays EXT's");
    ab(A, WF_LX, 25000); frame();
    CHECK(O.abs[WF_LX] == 25000, "4 ...a real move (≥ range/16 from where it was) takes it back (rule 2)");

    // 5. gentle moves (rule 3)
    fresh(); ab(A, WF_LX, 300); ab(A, WF_LX, -300); ab(E, WF_LX, 2949); frame();
    CHECK(O.abs[WF_LX] == 2949, "5 AYN owns and rests (±300): EXT's 9 %% comes out (%d)", O.abs[WF_LX]);
    fresh(); ab(E, WF_LX, 20000); ab(E, WF_LX, 300); ab(E, WF_LX, -300); frame();
    CHECK(MG.owner[WF_LX] == E && O.abs[WF_LX] == -300, "5 swapped: EXT owns and rests");
    ab(A, WF_LX, 2949); frame();
    CHECK(O.abs[WF_LX] == 2949, "5 swapped: AYN's 9 %% comes out (%d)", O.abs[WF_LX]);
    ab(A, WF_LX, 3100); frame(); CHECK(O.abs[WF_LX] == 3100, "5 ...and follows");
    ab(E, WF_LX, 500); frame(); CHECK(O.abs[WF_LX] == 3100, "5 ...the still one's jitter can't take it back");
    fresh(); ab(A, WF_LX, 2000); ab(E, WF_LX, 2949); frame();
    CHECK(O.abs[WF_LX] == 2000, "5 AYN owning at 2000 (drift, not still): EXT's 9 %% does NOT take (documented degradation)");
    fresh(); ab(E, WF_LX, 2949); ab(A, WF_LX, 5000); frame();
    CHECK(O.abs[WF_LX] == 5000 && MG.owner[WF_LX] == A, "5 EXT holds 9 %%, AYN pushes 5000: AYN's");
    n0 = nev; ab(A, WF_LX, 0); frame();
    CHECK(O.abs[WF_LX] == 2949 && MG.owner[WF_LX] == E && MG.owner[WF_LY] == E && nev == n0 + 1,
          "5 AYN lets go: EXT's gentle 9 %% back at once, with no new EXT event (%d)", O.abs[WF_LX]);
    n0 = nev; ab(A, WF_LX, 400); ab(A, WF_LX, -400); frame();
    CHECK(O.abs[WF_LX] == 2949 && nev == n0, "5 ...and AYN's still jitter can't take it back (no flap)");
    fresh(); ab(A, WF_LX, 2000); ab(E, WF_LX, 2949); ab(A, WF_LX, 0); frame();
    CHECK(O.abs[WF_LX] == 2949 && MG.owner[WF_LX] == E, "5 AYN's drift settles still: EXT's gentle hold comes out");

    // 6. triggers
    fresh(); ab(A, WF_GAS, 32767); frame();
    CHECK(O.abs[WF_GAS] == 32767, "6 EXT at 0, AYN pulls 32767: 32767");
    ab(A, WF_GAS, 0); frame(); CHECK(O.abs[WF_GAS] == 0, "6 AYN lets go: EXT's value (0)");
    ab(E, WF_GAS, 12000); ab(A, WF_GAS, 32767); frame();
    CHECK(O.abs[WF_GAS] == 32767, "6 EXT holds 12000, AYN pulls: AYN's");
    ab(A, WF_GAS, 0); frame(); CHECK(O.abs[WF_GAS] == 12000, "6 AYN lets go: EXT's 12000 back");
    fresh(); ab(E, WF_BRAKE, 983); frame();
    CHECK(O.abs[WF_BRAKE] == 983, "6 AYN still, EXT at 3 %%: out (%d)", O.abs[WF_BRAKE]);
    CHECK(MG.owner[WF_GAS] == A, "6 each trigger alone");
    fresh(); ab(E, WF_GAS, 983); ab(A, WF_GAS, 5000); frame();
    CHECK(O.abs[WF_GAS] == 5000 && MG.owner[WF_GAS] == A, "6 EXT holds 3 %%, AYN pulls 5000: AYN's");
    n0 = nev; ab(A, WF_GAS, 0); frame();
    CHECK(O.abs[WF_GAS] == 983 && MG.owner[WF_GAS] == E && nev == n0 + 1,
          "6 AYN lets go: EXT's gentle 3 %% back at once, with no new EXT event (%d)", O.abs[WF_GAS]);

    // 7. the D-pad, per axis (four buttons held by either source)
    fresh(); ab(E, WF_HY, -1); n0 = absev[WF_HY];
    ab(A, WF_HX, 1); frame();
    CHECK(O.abs[WF_HX] == 1 && O.abs[WF_HY] == -1 && absev[WF_HY] == n0, "7 EXT holds up, AYN presses right: (1, -1), no HY event");
    ab(A, WF_HX, 0); frame();
    CHECK(O.abs[WF_HX] == 0 && O.abs[WF_HY] == -1 && absev[WF_HY] == n0, "7 AYN lets go: (0, -1), still no HY event");
    fresh(); ab(E, WF_HX, -1); ab(A, WF_HX, 1); frame();
    CHECK(O.abs[WF_HX] == 1, "7 opposites: EXT holds left, AYN presses right: +1");
    ab(A, WF_HX, 0); frame(); CHECK(O.abs[WF_HX] == -1, "7 AYN lets go: -1 (EXT's left back)");
    ab(E, WF_HX, 0); frame(); CHECK(O.abs[WF_HX] == 0, "7 EXT lets go: 0");
    fresh(); ab(A, WF_HY, 1); ab(A, WF_HY, -1); frame();
    CHECK(O.abs[WF_HY] == -1, "7 one source down → up directly: up");
    fresh(); ab(E, WF_HY, 1); ab(A, WF_HY, -1); ab(E, WF_HY, -1); ab(E, WF_HY, 0); frame();
    CHECK(O.abs[WF_HY] == -1, "7 both up, EXT lets go: AYN's up stays");

    // 8. the gate across both sources (one engine)
    fresh(); key(A, WF_HOME, 1); echoes = 0; key(E, WF_X, 1); frame();
    CHECK(echoes == 1 && !O.key[WF_X], "8 AYN holds Home, EXT presses X: echoed (J), X not out");
    key(A, WF_HOME, 0); frame();
    CHECK(!O.key[WF_X], "8 Home let go: X stays consumed until released");
    key(E, WF_X, 0); key(E, WF_X, 1); frame();
    CHECK(O.key[WF_X], "8 ...released and pressed again: out");
    fresh(); key(E, WF_HOME, 1); key(A, WF_A, 1); frame();
    CHECK(!O.key[WF_A] && O.key[WF_HOME], "8 the other way: EXT's Home (the Thor's) gates AYN's A");

    // 9. a source dropped (wfpad: its pad gone, the feed stopped)
    fresh(); ab(A, WF_LX, -500); key(E, WF_A, 1); ab(E, WF_LX, 20000); ab(E, WF_HX, 1); frame();
    CHECK(O.key[WF_A] && O.abs[WF_LX] == 20000 && O.abs[WF_HX] == 1, "9 EXT holds A + LX + right");
    wfm_drop(&MG, &S, E, drop_cb, 0); frame();
    CHECK(!O.key[WF_A] && O.abs[WF_LX] == -500 && O.abs[WF_HX] == 0, "9 EXT dropped: A goes, LX = AYN's value (%d), the hat centred", O.abs[WF_LX]);
    for (int i = 0; i < 8; i++) CHECK(S.abs[AXES[i]] == MG.abs[A][AXES[i]] && MG.owner[AXES[i]] == A, "9 after the drop the merged state IS AYN's (axis %d)", AXES[i]);
    fresh(); key(E, WF_A, 1); key(A, WF_A, 1); key(A, WF_B, 1); ab(A, WF_RY, 30000); n0 = keyedge[WF_A];
    wfm_drop(&MG, &S, E, drop_cb, 0); frame();
    CHECK(O.key[WF_A] && keyedge[WF_A] == n0 && O.key[WF_B] && O.abs[WF_RY] == 30000, "9 dropped while the other holds A: A stays, no edge");
    wfm_drop(&MG, &S, A, drop_cb, 0); frame();
    CHECK(!O.key[WF_A] && !O.key[WF_B] && O.abs[WF_RY] == 0, "9 then AYN dropped: all released");
    fresh(); key(A, WF_HOME, 1); key(E, WF_X, 1); key(E, WF_A, 1); wfm_drop(&MG, &S, E, drop_cb, 0); frame();
    CHECK(S.key[WF_HOME] && S.gated && O.key[WF_HOME], "9 EXT dropped while the Thor's Home is held: the gate stays");

    // resync: owners from the values read again (wfpad merge_resync)
    fresh(); MG.abs[A][WF_LX] = 30000; MG.abs[E][WF_RX] = -20000; MG.abs[E][WF_LY] = 900; MG.abs[E][WF_HY] = -1; MG.abs[A][WF_HY] = 1;
    MG.abs[E][WF_GAS] = 20000; wfm_decide(&MG, &S);
    CHECK(MG.owner[WF_RX] == E && MG.owner[WF_RY] == E && MG.owner[WF_LX] == A && MG.owner[WF_LY] == A && MG.owner[WF_HY] == E &&
          MG.owner[WF_HX] == A && MG.owner[WF_GAS] == E && MG.owner[WF_BRAKE] == A, "resync: EXT owns what it holds away from rest, AYN the rest");
    CHECK(wfm_value(&MG, WF_LX) == 30000 && wfm_value(&MG, WF_LY) == 0 && wfm_value(&MG, WF_HY) == -1, "resync: the owners' values");

    // 10. single source (Thor-only): AYN alone through the merge = AYN fed directly, event for event, frame for frame
    {
        padinit(&S1); padinit(&S2); wfm_reset(&MG, &S2); wf_map_reset(&M);
        M.keymap[WF_A] = WF_B; M.fire[0x137] = 2; M.fire[0x136] = 1; M.shift = 0x13a; M.dz[0] = 8; M.keymap[WF_HAT_UP] = WF_X;
        S1.shift = S2.shift = M.shift;
        unsigned char down[WF_NK]; memset(down, 0, sizeof down);
        int bad = 0, frames = 0; long t = 1000;
        for (int k = 0; k < 10000 && !bad; k++) {
            t += (long)(rnd() % 20); S1.now_ms = S2.now_ms = t;
            unsigned c = rnd() % 10;
            if (c < 4) {                                         /* a key: press when up, release / repeat when down */
                int code = KEYS[rnd() % NKEYS], v;
                if (!down[code]) v = 1; else v = rnd() % 3 ? 0 : 2;
                if (v != 2) down[code] = (unsigned char)v;
                int p1, e1 = wf_event(&S1, EV_KEY, code, v, &p1); wf_fire_edge(&S1, &M, p1, v);
                rec_n = 0; wfm_event(&MG, &S2, A, EV_KEY, code, v, rec, 0);
                if (rec_n != 1 || rec_type != EV_KEY || rec_code != code || rec_value != v || rec_echo != e1 || rec_p != p1) bad = 1;
            } else if (c < 8) {                                  /* an axis: only when its value changes */
                int a = AXES[rnd() % 8], v = new_value(&S1, a, S1.abs[a]);
                int p1, e1 = wf_event(&S1, EV_ABS, a, v, &p1);
                rec_n = 0; wfm_event(&MG, &S2, A, EV_ABS, a, v, rec, 0);
                if (rec_n != 1 || rec_type != EV_ABS || rec_code != a || rec_value != v || rec_echo != e1) bad = 2;
            } else {                                             /* SYN: the frame */
                wf_out o1, o2; memset(&o1, 0, sizeof o1); memset(&o2, 0, sizeof o2);
                wf_compute(&S1, &M, &o1); wf_compute(&S2, &M, &o2); frames++;
                if (memcmp(o1.key, o2.key, sizeof o1.key)) bad = 3;
                for (int i = 0; i < 8; i++) if (o1.abs[AXES[i]] != o2.abs[AXES[i]]) bad = 4;
                if (S1.gated != S2.gated) bad = 5;
            }
        }
        CHECK(!bad && frames > 500, "10 AYN alone, 10 000 kernel-like events: the engine's events and every frame identical (bad %d, %d frames)", bad, frames);
    }

    // 11. fairness fuzz: the merged state is (k0 || k1), an engine edge for each change of it
    {
        fresh();
        unsigned char k[2][WF_NK]; memset(k, 0, sizeof k);
        int changes = 0, bad = 0, edges0 = 0;
        for (int i = 0; i < NKEYS; i++) edges0 += keyedge[KEYS[i]];
        for (int step = 0; step < 20000 && !bad; step++) {
            int src = (int)(rnd() % 2), code = KEYS[rnd() % NKEYS], v;
            if (!k[src][code]) v = 1; else v = rnd() % 4 ? 0 : 2;
            int before = k[0][code] || k[1][code];
            if (v != 2) k[src][code] = (unsigned char)v;
            int after = k[0][code] || k[1][code];
            if (before != after) changes++;
            key(src, code, v);
            if (S.key[code] != after) bad = 1;
        }
        int edges = -edges0; for (int i = 0; i < NKEYS; i++) edges += keyedge[KEYS[i]];
        CHECK(!bad && edges == changes, "11 fairness: 20 000 interleaved key events, merged = k0 || k1 at every step, %d edges for %d changes (bad %d)", edges, changes, bad);
    }

    // 12. axis fuzz: every output axis is one source's value, a stick's two axes from one source, never out of range
    {
        fresh();
        int bad = 0;
        for (int step = 0; step < 20000 && !bad; step++) {
            int src = (int)(rnd() % 2), a = AXES[rnd() % 8], v = new_value(&S, a, MG.abs[src][a]);
            int ax[2], vl[2], n = wfm_abs(&MG, &S, src, a, v, ax, vl);
            for (int j = 0; j < n; j++) eng(0, EV_ABS, ax[j], vl[j]);
            if (n > 2) bad = 1;
            for (int i = 0; i < 8 && !bad; i++) {
                int x = AXES[i], o = S.abs[x];
                if (o != MG.abs[0][x] && o != MG.abs[1][x]) bad = 2;
                if (o != wfm_value(&MG, x)) bad = 3;
                if (o < S.info[x].minimum || o > S.info[x].maximum) bad = 4;
            }
            if (MG.owner[WF_LX] != MG.owner[WF_LY] || MG.owner[WF_RX] != MG.owner[WF_RY]) bad = 5;
        }
        CHECK(!bad, "12 axis fuzz, 20 000 events from both: one source's value, sticks whole, in range (bad %d)", bad);
    }

    // b18l review: the merged seat. The fed pad's reader gates on its own Home too and lets go of what that gate lifts in a
    // report AFTER its Home (b18l): under the merged engine's gate that release is no "J" line (the hand still holds the
    // button; the copy let it go at Home's press) — echoed, it could overtake the copy's HOME DOWN in the app (case b)
    {   fresh(); key(E, WF_X, 1); key(E, WF_HOME, 1);
        int n1 = nev, e1 = echoes; key(E, WF_X, 0);
        CHECK(nev == n1 + 1 && echoes == e1 && S.gated, "b18l-r merged: the fed pad's Home, then its X let go after it: no echo");
        // the Thor's Home, the fed pad's X held before it and let go by the hand: no echo; pressed again under it: echoed
        fresh(); key(E, WF_X, 1); key(A, WF_HOME, 1);
        n1 = nev; e1 = echoes; key(E, WF_X, 0);
        CHECK(nev == n1 + 1 && echoes == e1, "b18l-r merged: the Thor's Home, the fed pad's X (held before) let go: no echo");
        key(E, WF_X, 1); CHECK(last_echo == 1 && S.ckey[WF_X], "b18l-r merged: ...X pressed again under that Home: echoed (Home + X)");
        key(E, WF_X, 0); CHECK(last_echo == 1, "b18l-r merged: ...its release: echoed");
    }

    printf("%s: %d checks, %d failed\n", fails ? "FAILED" : "OK", checks, fails);
    return fails != 0;
}
