// wfmap — the input layer's mapping engine.
//
// Pure and STATE-based: the copy's whole output state is a function of
//   (AYN pad state, the app's profile, "Home/Back held" gate)
// recomputed at every frame (SYN_REPORT); only the differences are written. So a profile
// switch or a gate can never leave a key stuck — whatever is no longer produced is released.
//
// Buttons are handled as PRINTED buttons: AYN's Xbox mode (product 0x0112) reports A/B and
// X/Y by position (the key layout files of both identities are identical; the codes swap),
// so its codes are swapped back on the way in, and the face-button layout is applied on the
// way out: follow AYN's mode, or force Nintendo (as printed) / Xbox (A at the bottom).
//
// Gate: while Home or Back is held, every other control is released / centred for the game
// (Wayfinder's shortcuts, nothing leaks — plan §4) and its raw events are echoed to Wayfinder
// instead. A control that becomes active during the gate is "consumed": silent for the game
// until it returns to rest, even after the gate ends (Home + R2, release Home first → R2 does
// not reach the game). Controls already held before the gate come back when it ends.
// b18g (B4): a Back pressed while a game button is already held (Select + Back: RetroArch's menu hotkey) goes WITH the
// game, as ButtonEngine takes it (`backToGameNow`): that press doesn't gate, so the held button stays down ([wf_state.back_hot]).
// b18h: "held" as the app sees it on the copy ([wf_game_key_held]).
// b18l (GitHub #81, b18w B1): Home / Back's change goes out in a report of its own, apart from what the gate does to the rest
// in that frame ([wf_frames]): the app reads the copy's events in the order written. b18l review: a button held before the gate
// isn't echoed when let go under it — the copy let it go at the gate's open ([wf_event]).
// b18n: a resync replays what's held in the order it went down, as far as the state before tells it ([wf_replay]) — a Back
// that went with the game keeps doing so, the button held before it isn't withheld.
// b18o: the copy's buttons a hand holds a turbo button for, told to Wayfinder ([wf_turbo_held]) — their on / off pulses are
// no presses there (a Home tap with a turbo button held goes home).
//
// Header-only so fx/wfmap_test.c can test it on its own.
#ifndef WFMAP_H
#define WFMAP_H

#include <linux/input.h>
#include <string.h>
#include <stdlib.h>

#define WF_NK 0x300               // key codes tracked (KEY_MAX is 0x2ff)
#define WF_NA 0x40                // abs codes tracked (ABS_MAX is 0x3f)
#define WF_NONE 0xffff            // keymap: "this button does nothing"
#define WF_EXT 0xfffe             // keymap: handled by Wayfinder (a keyboard key, an action):
                                  // nothing for the copy, "X" lines to the app instead
#define WF_HAT_UP 0x220           // keymap targets for "button → D-pad direction"
#define WF_HAT_DOWN 0x221         // (the codes AYN's key layouts name DPAD_*; the D-pad
#define WF_HAT_LEFT 0x222         //  itself is the HAT axis, so these are emitted as HAT)
#define WF_HAT_RIGHT 0x223

// Thor pad (evdev): printed buttons in Nintendo/"Odin" codes
#define WF_A 0x130
#define WF_B 0x131
#define WF_X 0x133
#define WF_Y 0x134
#define WF_L2 0x138
#define WF_R2 0x139
#define WF_HOME 0x66
#define WF_BACK 0x9e
#define WF_LX 0x00
#define WF_LY 0x01
#define WF_RX 0x02
#define WF_RY 0x05
#define WF_GAS 0x09               // R2 analog
#define WF_BRAKE 0x0a             // L2 analog
#define WF_HX 0x10
#define WF_HY 0x11

typedef struct { unsigned char key[WF_NK]; int abs[WF_NA]; } wf_out;

typedef struct {
    int layout;                   // 0 = follow AYN · 'n' = Nintendo (printed) · 'x' = Xbox
    unsigned short keymap[WF_NK]; // printed src → printed dst (0 = itself, WF_NONE, WF_HAT_*)
    int swap_sticks, dpad_ls, inv_ly, inv_ry, trig_digital;
    int inv_lx, inv_rx;           // 1.3 (`xl=1` / `xr=1`, GitHub #19): left / right inverted
    unsigned char fire[WF_NK];    // how a button fires: 0 normal · 1 turbo · 2 toggle
    int turbo_hz;                 // turbo presses per second (default 12)
    // round 8 (2026-09-25) — sticks: inner deadzone %, full deflection at %, response 0 linear ·
    // 1 precise centre · 2 fast; [0] left, [1] right. Triggers: start / full at % (both).
    int dz[2], oz[2], cv[2];
    int tlo, thi;
    // hold-to-shift (2026-09-26): this PRINTED button, held, works like Home /
    // Back's gate for this profile — nothing reaches the game, presses go to Wayfinder (its "With
    // Shift" layer); pressed alone it reaches the game as a short tap on release. 0 = none (default).
    int shift;
    // 1.3 (`bg=1`): Back goes straight to the game (RetroArch's Back hotkeys): holding Back doesn't
    // gate — the other buttons keep reaching the game. Home still gates. 0 = Back gates (default).
    int back_free;
    // 1.3 (`apl=1` / `apr=1`): this PHYSICAL stick is Wayfinder's (mouse, scroll, 4 keys): centred for the
    // game, its shaped position sent to the app ([wf_app_stick], "T" lines). [0] left, [1] right.
    int app_stick[2];
} wf_map;

typedef struct {
    int nk, na;                   // the pad's keys / axes (from its capabilities)
    unsigned short keys[WF_NK];
    unsigned char axes[WF_NA];
    struct input_absinfo info[WF_NA];
    int xbox;                     // source identity is AYN's Xbox mode (codes by position)
    unsigned char key[WF_NK];     // source state, PRINTED codes
    int abs[WF_NA];
    // gate
    int gated;
    unsigned char ckey[WF_NK], cabs[WF_NA];   // consumed (became active during a gate)
    unsigned char akey_prev[WF_NK], aabs_prev[WF_NA];
    // how it fires: toggle latches, turbo phase (set [now_ms] before wf_compute)
    unsigned char latch[WF_NK];
    long pressed_at[WF_NK];
    long now_ms;
    // gyro as a stick (from Wayfinder's gyro engine): added to that stick, 1 = left, 2 = right
    int gyro_stick, gyro_x, gyro_y;
    // virtual presses from Wayfinder (macros, combos, long / double press, chords — phase 3):
    // printed outputs held on top of the pad's own, silent while Home / Back are held
    unsigned char vkey[WF_NK];
    // hold-to-shift: the profile's shift button (copied from the map), whether another button was
    // pressed while it was held, and until when its lone tap is sent to the game (now_ms)
    int shift, shift_used;
    long shift_tap_until;
    int back_free;                // copied from the map (`bg=1`): Back held doesn't gate
    // b18g (B4): THIS Back press goes with the game — at its press a game button was already held (not withheld, not the
    // shift button), so it doesn't gate: Select + Back reaches the game as Select + Back. Set at Back's press, cleared at
    // its release ([wf_event]) and by [wf_state_rest]. Each Back press decides afresh.
    int back_hot;
    // b18h (review): what the copy shows of each held pad key (printed code), for Back's check ([wf_game_key_held]): 0 = the
    // key itself, a key / D-pad direction = the profile's remap, WF_NONE = nothing the app sees held (the profile sends it to
    // nothing or to Wayfinder, or it is turbo / toggle: the copy follows its rhythm or its latch, not the finger). Copied
    // from the profile with its shift button and `bg=1` ([wf_state_map]); all 0 = no profile.
    unsigned short seen_as[WF_NK];
} wf_state;


static inline int wf_swap_face(int code) {
    switch (code) {
        case WF_A: return WF_B; case WF_B: return WF_A;
        case WF_X: return WF_Y; case WF_Y: return WF_X;
        default: return code;
    }
}
/** Source code → printed button. */
static inline int wf_in(const wf_state *s, int code) { return s->xbox ? wf_swap_face(code) : code; }
/** Printed button → the code written to the copy. */
static inline int wf_outcode(const wf_state *s, const wf_map *m, int printed) {
    int xbox_out = m->layout == 'x' || (m->layout == 0 && s->xbox);
    return xbox_out ? wf_swap_face(printed) : printed;
}

static inline int wf_rest(const wf_state *s, int a) {
    const struct input_absinfo *i = &s->info[a];
    if (a == WF_GAS || a == WF_BRAKE) return i->minimum;
    return (i->minimum + i->maximum) / 2;   // sticks: centre · hat: 0
}
static inline int wf_range(const wf_state *s, int a) {
    int r = s->info[a].maximum - s->info[a].minimum; return r > 0 ? r : 1;
}
/** Away from rest enough to count as "being used" (sticks 25 %, triggers 10 %, hat any). */
static inline int wf_abs_active(const wf_state *s, int a) {
    int d = abs(s->abs[a] - wf_rest(s, a));
    if (a == WF_HX || a == WF_HY) return d != 0;
    if (a == WF_GAS || a == WF_BRAKE) return d * 10 > wf_range(s, a);
    return d * 8 > wf_range(s, a);           // half-range is range/2 → 25 % of a half = range/8
}

static inline void wf_map_reset(wf_map *m) { memset(m, 0, sizeof *m); }

/** The profile's parts the source state keeps, set with every profile: its shift button, `bg=1`, and what the copy shows of
 *  each held key ([wf_state.seen_as], b18h review). */
static inline void wf_state_map(wf_state *s, const wf_map *m) {
    s->shift = m->shift; s->back_free = m->back_free;
    for (int k = 0; k < WF_NK; k++) {
        int d = m->keymap[k];
        s->seen_as[k] = (unsigned short)(d == WF_NONE || d == WF_EXT || m->fire[k] ? WF_NONE : d);
    }
}

static inline int wf_is_system(int printed) { return printed == WF_HOME || printed == WF_BACK; }

/** 1.3 — the D-pad's directions are sources of their own (printed codes WF_HAT_UP..RIGHT, keymap
 *  like a button): is [dir] pressed, for a HAT at (hx, hy)? */
static inline int wf_dir_on(int hx, int hy, int dir) {
    switch (dir) {
        case WF_HAT_UP: return hy < 0; case WF_HAT_DOWN: return hy > 0;
        case WF_HAT_LEFT: return hx < 0; case WF_HAT_RIGHT: return hx > 0;
    }
    return 0;
}

/** Back held gates: not when the profile gives Back to the game (`bg=1`), nor for a Back press that went with the game
 *  (b18g: a game button was held before it, [wf_state.back_hot]). */
static inline int wf_back_gates(const wf_state *s) {
    return s->key[WF_BACK] && !s->back_free && !s->back_hot;
}

/** The gate is open (nothing reaches the game): Home held, Back held (unless the profile gives Back
 *  to the game, or this Back press went with the game), or the profile's shift button held. */
static inline int wf_gate(const wf_state *s) {
    return s->key[WF_HOME] || wf_back_gates(s) || (s->shift && s->key[s->shift]);
}

/** The gate follows Home / Back / the shift button ([wf_gate]) — at [wf_event]'s Home / Back / shift change, and when a
 *  resync's replay keeps a Back's decision ([wf_replay], b18n). At its open, what's held now is NOT consumed; at its close,
 *  whatever is back at rest is free again. */
static inline void wf_gate_follow(wf_state *s) {
    int g = wf_gate(s);
    if (g && !s->gated) {         // gate opens: what's held now is NOT consumed
        for (int i = 0; i < s->nk; i++) s->akey_prev[s->keys[i]] = s->key[s->keys[i]];
        for (int i = 0; i < s->na; i++) s->aabs_prev[s->axes[i]] = (unsigned char)wf_abs_active(s, s->axes[i]);
    }
    if (!g && s->gated) {         // gate closes: whatever is back at rest is free again
        for (int i = 0; i < s->nk; i++) if (!s->key[s->keys[i]]) s->ckey[s->keys[i]] = 0;
        for (int i = 0; i < s->na; i++) if (!wf_abs_active(s, s->axes[i])) s->cabs[s->axes[i]] = 0;
    }
    s->gated = g;
}

/** b18h (review): a copy's key code the app's ButtonEngine names as a button (ThorButton: the face buttons, L1 / R1, L2 / R2,
 *  L3 / R3, Select, Start). Not MODE (a connected controller's Home given to the game), C or Z (its SL / SR): Android reads
 *  them as BUTTON_MODE / C / Z, which the engine never counts. */
static inline int wf_app_button(int code) {
    switch (code) {
        case WF_A: case WF_B: case WF_X: case WF_Y: case 0x136: case 0x137: case WF_L2: case WF_R2:
        case 0x13a: case 0x13b: case 0x13d: case 0x13e: return 1;
    }
    return 0;
}

/** b18g (B4): a game button is held now, AS THE APP SEES IT ON THE COPY. ButtonEngine decides the same Back from the copy's
 *  keys (`backToGameNow`), so both must agree (b18h review). A pad key counts when it is held and live and the copy shows it
 *  as a button the app names, or as a D-pad direction ([wf_state.seen_as]). Never Home / Back, never a withheld key
 *  (consumed: pressed during a gate — Back came first, GitHub #81 — until it is let go, as ButtonEngine's `withheld`). The
 *  D-pad and the sticks themselves don't count. Counting less never splits the two: a Back that gates releases everything
 *  in that frame, in a report before Back's ([wf_frames], b18l — b18h: the UHID copy wrote the buttons first in Back's own
 *  report), so the app sees nothing held either. */
static inline int wf_game_key_held(const wf_state *s) {
    for (int i = 0; i < s->nk; i++) {
        int k = s->keys[i];
        // the shift button isn't a game button. Defensive: its gate is open while it is held, and [wf_event] asks only with
        // no gate open
        if (wf_is_system(k) || k == s->shift || !s->key[k] || s->ckey[k]) continue;
        int d = s->seen_as[k] ? s->seen_as[k] : k;
        if (d != WF_NONE && (wf_app_button(d) || (d >= WF_HAT_UP && d <= WF_HAT_RIGHT))) return 1;
    }
    return 0;
}

/** One output of the copy: a printed button (layout applied), a D-pad direction, or — for a
 *  non-trigger source — a full pull of L2 / R2. */
static inline void wf_put(const wf_state *s, const wf_map *m, wf_out *o, int d, int from_trigger,
                          int *hx, int *hy, int *trig_full) {
    if (d == WF_HAT_UP) { *hy = -1; return; }
    if (d == WF_HAT_DOWN) { *hy = 1; return; }
    if (d == WF_HAT_LEFT) { *hx = -1; return; }
    if (d == WF_HAT_RIGHT) { *hx = 1; return; }
    if (d < WF_NK) o->key[wf_outcode(s, m, d)] = 1;
    if ((d == WF_L2 || d == WF_R2) && !from_trigger) trig_full[d == WF_R2] = 1;
}

/** Source state at rest (a new pad, or the pad gone): nothing held, axes at rest. */
static inline void wf_state_rest(wf_state *s) {
    memset(s->key, 0, sizeof s->key);
    for (int i = 0; i < s->na; i++) s->abs[s->axes[i]] = wf_rest(s, s->axes[i]);
    s->gated = 0;
    memset(s->ckey, 0, sizeof s->ckey); memset(s->cabs, 0, sizeof s->cabs);
    memset(s->akey_prev, 0, sizeof s->akey_prev); memset(s->aabs_prev, 0, sizeof s->aabs_prev);
    memset(s->latch, 0, sizeof s->latch);
    memset(s->vkey, 0, sizeof s->vkey);
    s->back_hot = 0;
}

/** A virtual press / release (`p <code> <0|1>`): a pad button the pad has (not Home) or a
 *  D-pad direction. `p 0 0` releases them all. Returns -1 for anything else.
 *  1.4 (GitHub #43): Back too — Wayfinder hands a short Back to the game as the pad's own press. */
static inline int wf_vpress(wf_state *s, int code, int v) {
    if (code == 0 && v == 0) { memset(s->vkey, 0, sizeof s->vkey); return 0; }
    if (v != 0 && v != 1) return -1;
    int ok = code >= WF_HAT_UP && code <= WF_HAT_RIGHT;
    for (int i = 0; !ok && i < s->nk; i++) if (s->keys[i] == code && code != WF_HOME) ok = 1;
    if (!ok) return -1;
    s->vkey[code] = (unsigned char)v;
    return 0;
}

/**
 * After [wf_event] for a key, with the profile: a press the game would get (not during a
 * gate, not consumed) flips a TOGGLE button's latch and starts a TURBO button's clock.
 */
static inline void wf_fire_edge(wf_state *s, const wf_map *m, int printed, int value) {
    if (printed < 0 || printed >= WF_NK || value != 1 || s->gated || s->ckey[printed]) return;
    if (m->fire[printed] == 2) s->latch[printed] ^= 1;
    if (m->fire[printed] == 1) s->pressed_at[printed] = s->now_ms;
}
/** A turbo button is held: the caller must keep calling wf_compute (half a period apart). */
static inline int wf_turbo_active(const wf_state *s, const wf_map *m) {
    for (int i = 0; i < s->nk; i++) { int p = s->keys[i]; if (m->fire[p] == 1 && s->key[p] && !s->gated && !s->ckey[p]) return 1; }
    return 0;
}

/** b18o: the codes [wf_app_button] names — the only jobs [wf_turbo_held] tells. */
#define WF_APP_LO 0x130
#define WF_APP_HI 0x13e
/**
 * b18o (2026-10-11 — GitHub #81's turbo leftover): the copy's buttons a hand holds a TURBO button for, as Wayfinder
 * must know them. A turbo button's job goes on and off on the copy while it is held, and ButtonEngine reads the copy's keys:
 * each on-pulse looked like a new press, so a Home tap within 150 ms of one was "Home + that button" (no tap) about half the
 * time. Told instead ("TU <code> <1|0> <ms>"), it keeps the hand's press time and counts the button as held till the hand
 * lets go; the pulses are no presses. [said] (the caller's, by the copy's printed code, kept across resyncs and profiles:
 * whatever no longer counts is said let go): the jobs said held. A job counts while a turbo button whose job it is (its
 * keymap target, or itself) is held and not consumed — under a gate too (the hand holds it, the gate only silences it).
 * Never Home / Back or the shift button; never a job that is nothing, Wayfinder's, a D-pad direction or not a button the
 * app names. Two turbo buttons on one job: one hold, let go when neither counts. Writes the changes into [codes] / [vals],
 * releases first, at most [cap]; returns how many. Pure.
 */
static inline int wf_turbo_held(const wf_state *s, const wf_map *m, unsigned char *said, int *codes, int *vals, int cap) {
    unsigned char now[WF_APP_HI - WF_APP_LO + 1];
    memset(now, 0, sizeof now);
    for (int i = 0; i < s->nk; i++) {
        int p = s->keys[i];
        if (wf_is_system(p) || (s->shift && p == s->shift) || m->fire[p] != 1 || !s->key[p] || s->ckey[p]) continue;
        int d = m->keymap[p] ? m->keymap[p] : p;
        if (d >= WF_APP_LO && d <= WF_APP_HI && wf_app_button(d)) now[d - WF_APP_LO] = 1;
    }
    int n = 0;
    for (int d = WF_APP_LO; d <= WF_APP_HI && n < cap; d++) if (said[d] && !now[d - WF_APP_LO]) { said[d] = 0; codes[n] = d; vals[n++] = 0; }
    for (int d = WF_APP_LO; d <= WF_APP_HI && n < cap; d++) if (!said[d] && now[d - WF_APP_LO]) { said[d] = 1; codes[n] = d; vals[n++] = 1; }
    return n;
}


/**
 * Feed one source event. Returns 1 if it must be ECHOED to Wayfinder (withheld from the game
 * — during a gate, or a consumed control until it rests). *printed gets the printed code.
 * b18l (review): not a button's release under the gate when it was held since before the gate opened — the copy already
 * let it go there.
 */
static inline int wf_event(wf_state *s, int type, int code, int value, int *printed) {
    *printed = code;
    if (type == EV_KEY && code >= 0 && code < WF_NK) {
        int p = wf_in(s, code); *printed = p;
        s->key[p] = value != 0;
        int is_shift = s->shift && p == s->shift;
        if (wf_is_system(p) || is_shift) {
            if (is_shift && value == 2) return 1;
            if (is_shift && value == 1) s->shift_used = 0;
            // released alone (no other button, no Home / Back meanwhile): the game gets a short tap
            if (is_shift && value == 0 && !s->shift_used && !s->key[WF_HOME] && !s->key[WF_BACK])
                s->shift_tap_until = s->now_ms + 60;
            // b18g (B4): Back pressed while a game button is held (Select + Back) goes with the game — no gate, the held
            // button stays down. Not under a gate already open (Home, the shift button): Home + Back stays Wayfinder's.
            if (p == WF_BACK && value == 1 && !s->gated) s->back_hot = wf_game_key_held(s);
            if (p == WF_BACK && value == 0) s->back_hot = 0;
            wf_gate_follow(s);
            return is_shift;              // Home/Back themselves always reach Android; the shift button is Wayfinder's
        }
        if (value == 1 && s->shift && s->key[s->shift]) s->shift_used = 1;
        // b18l (review): never the release of a button held since before the gate opened (not pressed again under it, not
        // consumed). The gate let it go on the copy at its open, and the app took that from the copy. Echoed, its line could
        // overtake the copy's own Home DOWN on the way to the app and take the button out of what Home found held (case b).
        // A docked / merged controller's reader lets go of it in a report after its Home (b18l), so wfpad read each such
        // let-go under its own gate and said a release the hand never made
        int echo = s->ckey[p] || (s->gated && !(value == 0 && s->akey_prev[p]));
        if (s->gated && value && !s->akey_prev[p]) s->ckey[p] = 1;
        if (s->gated) s->akey_prev[p] = value != 0;
        if (!s->gated && !value) s->ckey[p] = 0;
        return echo;
    }
    if (type == EV_ABS && code >= 0 && code < WF_NA) {
        s->abs[code] = value;
        int act = wf_abs_active(s, code);
        int echo = s->gated || s->cabs[code];
        if (s->gated && act && !s->aabs_prev[code]) s->cabs[code] = 1;
        if (s->gated) s->aabs_prev[code] = (unsigned char)act;
        if (!s->gated && !act) s->cabs[code] = 0;
        return echo;
    }
    return 0;
}

/**
 * b18n (b18h WI-4, "Not changed" 1): a resync — events dropped, AYN's pad re-made, a feed's start or switch — replays the
 * keys held now ([held]: by source code, the pad's keys only) through [wf_event] as presses, on a state just reset. [old]:
 * the state before the reset (NULL: none — then code order, as before b18n). The replay keeps the order the keys went down
 * in, as far as [old] tells it. In code order alone Back (0x9e) came before Select (0x13a): a Back that went with the game
 * (Select + Back) gated on the replay and withheld its Select, while the app had given that Back to the game. Three passes,
 * each in code order:
 *  1. a button [old] held and let through (live) — it went down before any Home / Back / shift button held then;
 *  2. Home, Back, the profile's shift button, and a key [old] didn't hold (pressed during the drop) — as before b18n;
 *  3. a button [old] held but withheld (consumed: pressed under a gate) — it stays consumed, silent until let go.
 * A Back [old] held is the same press on the copy, so it keeps its decision ([wf_state.back_hot]): one that went with the
 * game stays so even if its game button was let go meanwhile; one that gated still gates. So does the shift button: used
 * under it ([wf_state.shift_used]), its release sends no tap.
 */
static inline void wf_replay(wf_state *s, const unsigned char *held, const wf_state *old) {
    int fresh = 0;                                // a key [old] didn't hold, replayed under the held shift button
    for (int pass = 0; pass < 3; pass++)
        for (int i = 0; i < s->nk; i++) {
            int k = s->keys[i], p = wf_in(s, k), q;
            if (!held[k]) continue;
            int gate = wf_is_system(p) || (s->shift && p == s->shift);
            int at = !(old && old->key[p] && !gate) ? 1 : old->ckey[p] ? 2 : 0;
            if (at != pass) continue;
            if (at == 1 && !gate && s->shift && s->key[s->shift]) fresh = 1;
            wf_event(s, EV_KEY, k, 1, &q);
            if (at == 2) s->ckey[p] = 1;          // the same press: still withheld until let go, gate or not
            if (p == WF_BACK && old && old->key[WF_BACK]) { s->back_hot = old->back_hot; wf_gate_follow(s); }
        }
    // the shift button held through: used as before (a button withheld from an earlier gate, replayed under it, isn't a
    // use), or by a key pressed under it during the drop
    if (old && s->shift && old->shift == s->shift && old->key[s->shift] && s->key[s->shift]) s->shift_used = old->shift_used || fresh;
}

/**
 * After [wf_event] for a key: must this press/release go to Wayfinder as its own output
 * (the button is mapped to a keyboard key or an action)? Presses only when the game would
 * have got them (not during a gate, not consumed); releases always (a stray release is
 * harmless, a lost one would leave a keyboard key held).
 */
static inline int wf_ext_event(const wf_state *s, const wf_map *m, int printed, int value) {
    if (printed < 0 || printed >= WF_NK || m->keymap[printed] != WF_EXT) return 0;
    if (value == 0) return 1;
    return value == 1 && !s->gated && !s->ckey[printed];
}

/**
 * 1.3 — after [wf_event] for a HAT axis that moved from [oldv] to [newv]: the D-pad directions it
 * presses / releases that Wayfinder plays (keymap WF_EXT) — "X <dir> <1|0>" lines, like buttons.
 * Presses only when the game would have got them (no gate, not consumed); releases always.
 * Writes up to 4 (code, value) pairs, releases first; returns how many.
 */
static inline int wf_dir_ext(const wf_state *s, const wf_map *m, int axis, int oldv, int newv, int *codes, int *vals) {
    if (axis != WF_HX && axis != WF_HY) return 0;
    int dirs[2] = { axis == WF_HX ? WF_HAT_LEFT : WF_HAT_UP, axis == WF_HX ? WF_HAT_RIGHT : WF_HAT_DOWN };
    int was[2] = { oldv < 0, oldv > 0 }, now[2] = { newv < 0, newv > 0 };
    int n = 0;
    for (int i = 0; i < 2; i++) if (was[i] && !now[i] && m->keymap[dirs[i]] == WF_EXT) { codes[n] = dirs[i]; vals[n++] = 0; }
    for (int i = 0; i < 2; i++) if (!was[i] && now[i] && m->keymap[dirs[i]] == WF_EXT && !s->gated && !s->cabs[axis]) { codes[n] = dirs[i]; vals[n++] = 1; }
    return n;
}

static inline int wf_clamp(const wf_state *s, int a, int v) {
    if (v < s->info[a].minimum) v = s->info[a].minimum;
    if (v > s->info[a].maximum) v = s->info[a].maximum;
    return v;
}
static inline int wf_live_key(const wf_state *s, int p) { return s->key[p] && !s->gated && !s->ckey[p]; }
static inline int wf_live_abs(const wf_state *s, int a, int *v) {
    if (s->gated || s->cabs[a]) { *v = wf_rest(s, a); return 0; }
    *v = s->abs[a]; return 1;
}

/** Round 8: a stick's shape — radial deadzone (drift), outer "full at", response curve.
 *  No libm: __builtin_sqrtf is one instruction on arm64. */
static inline void wf_shape(const wf_state *s, int ax, int ay, int *x, int *y, int dz, int oz, int cv) {
    if (!dz && (!oz || oz >= 100) && !cv) return;
    int cx = wf_rest(s, ax), cy = wf_rest(s, ay);
    float hx = (float)(s->info[ax].maximum - cx), hy = (float)(s->info[ay].maximum - cy);
    if (hx <= 0 || hy <= 0) return;
    float fx = (*x - cx) / hx, fy = (*y - cy) / hy;
    float r = __builtin_sqrtf(fx * fx + fy * fy);
    if (r < 1e-6f) return;
    float lo = dz / 100.f, hi = (oz > 0 ? oz : 100) / 100.f;
    float t = r <= lo ? 0.f : (r >= hi ? 1.f : (r - lo) / (hi - lo));
    if (cv == 1) t = t * t;                       // precise centre: small moves stay small
    else if (cv == 2) t = __builtin_sqrtf(t);     // fast: a little push goes far
    float k = t / r, nx = fx * k * hx, ny = fy * k * hy;
    *x = cx + (int)(nx >= 0 ? nx + .5f : nx - .5f);
    *y = cy + (int)(ny >= 0 ? ny + .5f : ny - .5f);
}
/** Round 8: a trigger's range — nothing below [tlo] %, a full pull from [thi] %. */
static inline int wf_trig_range(const wf_state *s, const wf_map *m, int a, int v) {
    if (m->tlo <= 0 && (m->thi <= 0 || m->thi >= 100)) return v;
    int mn = s->info[a].minimum, mx = s->info[a].maximum, span = mx - mn;
    if (span <= 0) return v;
    int lo = mn + span * m->tlo / 100, hi = mn + span * (m->thi > 0 ? m->thi : 100) / 100;
    if (hi <= lo) return v;
    if (v <= lo) return mn;
    if (v >= hi) return mx;
    return mn + (int)((long)(v - lo) * span / (hi - lo));
}

/** The copy's output state for the current source state. */
static inline void wf_compute(const wf_state *s, const wf_map *m, wf_out *o) {
    memset(o->key, 0, sizeof o->key);
    for (int i = 0; i < s->na; i++) o->abs[s->axes[i]] = wf_rest(s, s->axes[i]);
    int hx = 0, hy = 0, trig_full[2] = {0, 0};            // [0] = L2 (BRAKE), [1] = R2 (GAS)
    for (int i = 0; i < s->nk; i++) {
        int p = s->keys[i];
        if (wf_is_system(p)) { if (s->key[p]) o->key[p] = 1; continue; }   // never remapped
        if (s->shift && p == s->shift) {                   // held = the gate; its lone tap = a short press
            int d = m->keymap[p] ? m->keymap[p] : p;
            if (s->shift_tap_until && s->now_ms < s->shift_tap_until && d != WF_NONE && d != WF_EXT)
                wf_put(s, m, o, d, p == WF_L2 || p == WF_R2, &hx, &hy, trig_full);
            continue;
        }
        if (m->fire[p] == 2) {                             // toggle: the latch, not the finger
            if (!s->latch[p] || s->gated) continue;
        } else if (!wf_live_key(s, p)) {
            continue;
        } else if (m->fire[p] == 1) {                      // turbo: on for half a period, off for half
            int hz = m->turbo_hz > 0 ? m->turbo_hz : 12;
            long half = 500 / hz; if (half < 10) half = 10;
            if (((s->now_ms - s->pressed_at[p]) / half) % 2) continue;
        }
        int d = m->keymap[p] ? m->keymap[p] : p;
        if (d == WF_NONE || d == WF_EXT) continue;
        // a non-trigger button sent to L2/R2 = a full press of that trigger
        wf_put(s, m, o, d, p == WF_L2 || p == WF_R2, &hx, &hy, trig_full);
    }
    // Wayfinder's virtual presses (a trigger pressed this way = a full pull)
    // (silent under Home / Back; under the shift button they ARE its layer's outputs — a button target; b18g: not silent
    // under a Back that went with the game)
    if (!s->key[WF_HOME] && !wf_back_gates(s)) for (int c = 1; c < WF_NK; c++) if (s->vkey[c]) wf_put(s, m, o, c, 0, &hx, &hy, trig_full);
    // 1.3: a remapped D-pad direction leaves the game's D-pad for its target (another button, a
    // direction, nothing, or Wayfinder); the other directions stay as they are
    int hat_x_off = 0, hat_y_off = 0;
    if (s->info[WF_HX].maximum) {
        static const int DIRS[4] = { WF_HAT_UP, WF_HAT_DOWN, WF_HAT_LEFT, WF_HAT_RIGHT };
        int dx0, dy0; wf_live_abs(s, WF_HX, &dx0); wf_live_abs(s, WF_HY, &dy0);
        for (int i = 0; i < 4; i++) {
            int d = DIRS[i], t = m->keymap[d];
            if (!t || !wf_dir_on(dx0, dy0, d)) continue;
            if (d == WF_HAT_UP || d == WF_HAT_DOWN) hat_y_off = 1; else hat_x_off = 1;
            if (t != WF_NONE && t != WF_EXT) wf_put(s, m, o, t, 0, &hx, &hy, trig_full);
        }
    }
    // Trigger analog follows its button: L2 → R2 moves BRAKE to GAS; L2 → A drops the analog.
    // Both triggers on one output (L2 → R2, R2 stays R2): the one pressed further wins.
    for (int t = 0; t < 2; t++) {
        int out_a = t ? WF_GAS : WF_BRAKE, out_k = t ? WF_R2 : WF_L2;
        if (!s->info[out_a].maximum) continue;             // no such axis on this pad
        int v = s->info[out_a].minimum;
        for (int src = 0; src < 2; src++) {
            int src_k = src ? WF_R2 : WF_L2, in_a = src ? WF_GAS : WF_BRAKE;
            int d = m->keymap[src_k] ? m->keymap[src_k] : src_k;
            if (d != out_k) continue;
            int iv;
            if (m->trig_digital) iv = wf_live_key(s, src_k) ? s->info[out_a].maximum : s->info[out_a].minimum;
            else if (!wf_live_abs(s, in_a, &iv)) continue;
            else iv = wf_trig_range(s, m, in_a, iv);
            if (iv > v) v = iv;
        }
        if (trig_full[t]) v = s->info[out_a].maximum;
        o->abs[out_a] = wf_clamp(s, out_a, v);
    }
    // Sticks and D-pad.
    int lx, ly, rx, ry, sx, sy;
    wf_live_abs(s, WF_LX, &lx); wf_live_abs(s, WF_LY, &ly);
    wf_live_abs(s, WF_RX, &rx); wf_live_abs(s, WF_RY, &ry);
    wf_live_abs(s, WF_HX, &sx); wf_live_abs(s, WF_HY, &sy);
    if (hat_x_off) sx = 0;
    if (hat_y_off) sy = 0;
    // the PHYSICAL stick's shape (its drift), before any swap
    if (s->info[WF_LX].maximum) wf_shape(s, WF_LX, WF_LY, &lx, &ly, m->dz[0], m->oz[0], m->cv[0]);
    if (s->info[WF_RX].maximum) wf_shape(s, WF_RX, WF_RY, &rx, &ry, m->dz[1], m->oz[1], m->cv[1]);
    // 1.3: a stick that is Wayfinder's (mouse / scroll / keys) rests for the game
    if (m->app_stick[0]) { lx = wf_rest(s, WF_LX); ly = wf_rest(s, WF_LY); }
    if (m->app_stick[1]) { rx = wf_rest(s, WF_RX); ry = wf_rest(s, WF_RY); }
    if (m->swap_sticks) { int t; t = lx; lx = rx; rx = t; t = ly; ly = ry; ry = t; }
    if (m->inv_ly) ly = 2 * wf_rest(s, WF_LY) - ly;
    if (m->inv_ry) ry = 2 * wf_rest(s, WF_RY) - ry;
    if (m->inv_lx) lx = 2 * wf_rest(s, WF_LX) - lx;
    if (m->inv_rx) rx = 2 * wf_rest(s, WF_RX) - rx;
    if (m->dpad_ls) {
        // D-pad drives the left stick (full deflection); the left stick drives the D-pad (50 %).
        int cx = wf_rest(s, WF_LX), cy = wf_rest(s, WF_LY);
        int half_x = s->info[WF_LX].maximum - cx, half_y = s->info[WF_LY].maximum - cy;
        int nx = sx ? cx + sx * half_x : cx, ny = sy ? cy + sy * half_y : cy;
        int dx = abs(lx - cx) * 2 > half_x ? (lx > cx ? 1 : -1) : 0;
        int dy = abs(ly - cy) * 2 > half_y ? (ly > cy ? 1 : -1) : 0;
        lx = nx; ly = ny; sx = dx; sy = dy;
    }
    if (hx) sx = hx;
    if (hy) sy = hy;
    // gyro: added to its stick (camera / steering), never while Home/Back hold everything back
    if (s->gyro_stick && !s->gated) {
        if (s->gyro_stick == 1) { lx += s->gyro_x; ly += s->gyro_y; } else { rx += s->gyro_x; ry += s->gyro_y; }
    }
    if (s->info[WF_LX].maximum) { o->abs[WF_LX] = wf_clamp(s, WF_LX, lx); o->abs[WF_LY] = wf_clamp(s, WF_LY, ly); }
    if (s->info[WF_RX].maximum) { o->abs[WF_RX] = wf_clamp(s, WF_RX, rx); o->abs[WF_RY] = wf_clamp(s, WF_RY, ry); }
    if (s->info[WF_HX].maximum) { o->abs[WF_HX] = sx; o->abs[WF_HY] = sy; }
    // Any other axis the pad has: passed through (or at rest while gated / consumed).
    for (int i = 0; i < s->na; i++) {
        int a = s->axes[i];
        if (a == WF_LX || a == WF_LY || a == WF_RX || a == WF_RY || a == WF_HX || a == WF_HY || a == WF_GAS || a == WF_BRAKE) continue;
        int v; wf_live_abs(s, a, &v); o->abs[a] = v;
    }
}

/**
 * b18l (GitHub #81, b18w B1): the copy's states between two frames when Home or Back changes. The gate lets go of what is
 * held at Home / Back's press and gives it back at their release, in the SAME frame. Written as one report, the order was
 * the report's (the UHID copy: the buttons before Home and Back; wfhid's pad: Home last), and ButtonEngine reads it
 * literally: R2 given back before Home's release made "Home + R2" (Brighter, no home); X let go before Home's press was no
 * partner of it (Home went home). So the frame goes out in up to three reports:
 *  1. Home's change (press or release) and Back's release: first, alone;
 *  2. everything else: what the gate lets go of or gives back, other buttons, the axes;
 *  3. Back's press: last. The app decides that Back from what it sees held then (`backToGameNow`), this layer from what it
 *     counts ([wf_game_key_held]); a Back that gates lets go only of what it doesn't count, so the app must see that go
 *     first (b18h WI-4).
 * [mid] gets the states before [nxt]: the copy goes cur → mid[0] → … → nxt, one report each; a report that changes nothing
 * on the device is skipped by the caller. Returns how many (0..2); 0 = one report, as before.
 */
static inline int wf_frames(const wf_out *cur, const wf_out *nxt, wf_out mid[2]) {
    int home = cur->key[WF_HOME] != nxt->key[WF_HOME];
    int back_up = cur->key[WF_BACK] && !nxt->key[WF_BACK], back_down = !cur->key[WF_BACK] && nxt->key[WF_BACK];
    int n = 0;
    if (home || back_up) {                        // 1. Home's change, Back's release
        mid[n] = *cur;
        mid[n].key[WF_HOME] = nxt->key[WF_HOME];
        if (back_up) mid[n].key[WF_BACK] = 0;
        n++;
    }
    if (back_down) {                              // 2. the rest — 3. Back's press after it
        mid[n] = *nxt;
        mid[n].key[WF_BACK] = 0;
        n++;
    }
    return n;
}

/**
 * b18l: [wf_frames] as the events of a copy written event by event (wfpad's uinput copy, wfhid's pad): each report's
 * changes over [keys] then [axes] (in those orders), then SYN_REPORT; a report with no change is left out. [full]: every
 * control, one report (a fresh copy). Returns the number of events in [ev], at most [cap]; 3 × (nk + na + 1) is enough.
 */
static inline int wf_frame_events(const wf_out *cur, const wf_out *nxt, const unsigned short *keys, int nk,
                                  const unsigned char *axes, int na, int full, struct input_event *ev, int cap) {
    wf_out mid[2];
    int nm = full ? 0 : wf_frames(cur, nxt, mid), c = 0;
    const wf_out *prev = cur;
    for (int s = 0; s <= nm; s++) {
        const wf_out *st = s < nm ? &mid[s] : nxt;
        int c0 = c;
        for (int i = 0; i < nk && c < cap - 1; i++) {
            int k = keys[i];
            if (!full && st->key[k] == prev->key[k]) continue;
            memset(&ev[c], 0, sizeof ev[c]);
            ev[c].type = EV_KEY; ev[c].code = (unsigned short)k; ev[c].value = st->key[k]; c++;
        }
        for (int i = 0; i < na && c < cap - 1; i++) {
            int a = axes[i];
            if (!full && st->abs[a] == prev->abs[a]) continue;
            memset(&ev[c], 0, sizeof ev[c]);
            ev[c].type = EV_ABS; ev[c].code = (unsigned short)a; ev[c].value = st->abs[a]; c++;
        }
        if (c > c0) { memset(&ev[c], 0, sizeof ev[c]); ev[c].type = EV_SYN; ev[c].code = SYN_REPORT; c++; }
        prev = st;
    }
    return c;
}

/**
 * 1.3 — a stick given to Wayfinder ([wf_map.app_stick]): its position for the app, −1000..1000 each
 * way (y: down = +), through the stick's shape with a deadzone of AT LEAST 10 % — a resting or
 * drifting stick sends exactly 0, so nothing moves or scrolls by itself. Centred while gated or
 * consumed (Home + that stick is a Wayfinder shortcut).
 */
static inline void wf_app_stick(const wf_state *s, const wf_map *m, int side, int *nx, int *ny) {
    int ax = side ? WF_RX : WF_LX, ay = side ? WF_RY : WF_LY;
    *nx = *ny = 0;
    if (!m->app_stick[side] || !s->info[ax].maximum) return;
    int x, y;
    if (!wf_live_abs(s, ax, &x) || !wf_live_abs(s, ay, &y)) return;
    int dz = m->dz[side] < 10 ? 10 : m->dz[side];
    wf_shape(s, ax, ay, &x, &y, dz, m->oz[side], m->cv[side]);
    int cx = wf_rest(s, ax), cy = wf_rest(s, ay);
    int hx = s->info[ax].maximum - cx, hy = s->info[ay].maximum - cy;
    if (hx <= 0 || hy <= 0) return;
    long vx = (long)(x - cx) * 1000 / hx, vy = (long)(y - cy) * 1000 / hy;
    *nx = (int)(vx > 1000 ? 1000 : vx < -1000 ? -1000 : vx);
    *ny = (int)(vy > 1000 ? 1000 : vy < -1000 ? -1000 : vy);
}

/**
 * Parse a profile line: "L=n|x|0 k<src>=<dst> sw=0|1 dl=0|1 il=0|1 ir=0|1 td=0|1" (codes in
 * hex or decimal; dst 0 = itself, 65535 = none, 0x220..0x223 = D-pad). Home/Back can't be
 * remapped. Returns 0 on success; on any bad token the map is left untouched.
 */
static inline int wf_parse(wf_map *out, const wf_state *s, const char *line) {
    wf_map m; wf_map_reset(&m);
    char buf[2048]; strncpy(buf, line, sizeof buf - 1); buf[sizeof buf - 1] = 0;
    for (char *save = 0, *t = strtok_r(buf, " \t\r\n", &save); t; t = strtok_r(0, " \t\r\n", &save)) {
        char *eq = strchr(t, '='); if (!eq) return -1;
        *eq = 0; const char *k = t, *v = eq + 1;
        char *end; long n = strtol(v, &end, 0);
        if (!strcmp(k, "L")) {
            if (!strcmp(v, "n")) m.layout = 'n'; else if (!strcmp(v, "x")) m.layout = 'x';
            else if (!strcmp(v, "0")) m.layout = 0; else return -1;
            continue;
        }
        if (*end || end == v) return -1;
        if (k[0] == 'k') {
            char *e2; long src = strtol(k + 1, &e2, 0);
            if (*e2 || e2 == k + 1 || src <= 0 || src >= WF_NK || wf_is_system((int)src)) return -1;
            int ok = n == 0 || n == WF_NONE || n == WF_EXT || (n >= WF_HAT_UP && n <= WF_HAT_RIGHT);
            for (int i = 0; !ok && i < s->nk; i++) if (s->keys[i] == n && !wf_is_system((int)n)) ok = 1;
            if (!ok) return -1;
            m.keymap[src] = (unsigned short)n;
            continue;
        }
        if (k[0] == 'f' && k[1] == '0') {                  // f<src>=0|1|2 : how it fires
            char *e2; long src = strtol(k + 1, &e2, 0);
            if (*e2 || src <= 0 || src >= WF_NK || wf_is_system((int)src) || n < 0 || n > 2) return -1;
            m.fire[src] = (unsigned char)n;
            continue;
        }
        if (!strcmp(k, "tr")) { if (n < 2 || n > 30) return -1; m.turbo_hz = (int)n; continue; }
        // round 8: dzl/dzr 0..30 · ozl/ozr 70..100 · cvl/cvr 0..2 · tlo 0..50 · thi 50..100
        if ((!strcmp(k, "dzl") || !strcmp(k, "dzr"))) { if (n < 0 || n > 30) return -1; m.dz[k[2] == 'r'] = (int)n; continue; }
        if ((!strcmp(k, "ozl") || !strcmp(k, "ozr"))) { if (n < 70 || n > 100) return -1; m.oz[k[2] == 'r'] = (int)n; continue; }
        if ((!strcmp(k, "cvl") || !strcmp(k, "cvr"))) { if (n < 0 || n > 2) return -1; m.cv[k[2] == 'r'] = (int)n; continue; }
        if (!strcmp(k, "tlo")) { if (n < 0 || n > 50) return -1; m.tlo = (int)n; continue; }
        if (!strcmp(k, "sh")) {                            // a pad button (not Home / Back, not the D-pad), or 0
            int ok = n == 0;
            for (int i = 0; !ok && i < s->nk; i++) if (s->keys[i] == n && !wf_is_system((int)n)) ok = 1;
            if (!ok || n >= WF_HAT_UP) return -1;
            m.shift = (int)n; continue;
        }
        if (!strcmp(k, "thi")) { if (n < 50 || n > 100) return -1; m.thi = (int)n; continue; }
        if (n != 0 && n != 1) return -1;
        if (!strcmp(k, "sw")) m.swap_sticks = (int)n;
        else if (!strcmp(k, "dl")) m.dpad_ls = (int)n;
        else if (!strcmp(k, "il")) m.inv_ly = (int)n;
        else if (!strcmp(k, "ir")) m.inv_ry = (int)n;
        else if (!strcmp(k, "xl")) m.inv_lx = (int)n;
        else if (!strcmp(k, "xr")) m.inv_rx = (int)n;
        else if (!strcmp(k, "td")) m.trig_digital = (int)n;
        else if (!strcmp(k, "bg")) m.back_free = (int)n;
        else if (!strcmp(k, "apl")) m.app_stick[0] = (int)n;
        else if (!strcmp(k, "apr")) m.app_stick[1] = (int)n;
        else return -1;
    }
    *out = m;
    return 0;
}

/** dev build 15: a device name ending " (Wayfinder)" — a pad Wayfinder made (an external controller's, the copy with
 *  the opt-in name) or AYN's copy of one (2020:0111, no location, the same name). AYN's own pad is never named so. */
static inline int wf_wayfinder_name(const char *name) {
    static const char suf[] = " (Wayfinder)";
    size_t n = strlen(name), k = sizeof suf - 1;
    return n >= k && !strcmp(name + n - k, suf);
}

#endif
