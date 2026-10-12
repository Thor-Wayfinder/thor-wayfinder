// wfmerge — two sources merged into the ONE stream the input layer's engine sees (dev build 18f: a connected controller
// that "plays as the Thor's controller" — dev build 18f).
//
// wfpad's merged feed (stdin "s <eventN> m"): AYN's pad (the Thor's own buttons, WFM_AYN) and a connected controller's
// pad (fx/wfhid.c, phys "wayfinder-ext-N", WFM_EXT) BOTH drive the Thor's copy. One wf_state (fx/wfmap.h) is fed the
// merged stream, so the engine — the Home / Back gate, consumed controls, the shift button, toggles, turbo, the J / X /
// V lines — runs unchanged on it: the Thor's Home gates the controller's buttons and the other way round.
//
//   keys      held = held by either source. An edge only when that changes: the first holder's press, the LAST
//             holder's release. A repeat (2) only from a source that holds the key. One source's release never drops
//             the other's hold.
//   D-pad     per axis (HX, HY): each source's −1 / +1 is a held direction. A non-owner's change to a direction takes
//             the axis (−1 → +1 directly too); an owner back at 0 hands it to the other if that one holds a direction.
//             So the D-pad acts as four buttons held by either source, the last pressed winning between opposites, and
//             one source's up is never released by the other's right.
//   sticks,   one owner per group — a stick's two axes together (one hand moves both: per-axis would mix two hands into
//   triggers  one direction); each trigger and any other axis alone. A non-owner takes the group when
//               1. its group LEAVES rest (it was at rest before this event, and isn't now),
//               2. it moved ≥ range/16 from where it was when the owner was last decided, while away from rest, or
//               3. the owner's group is still and the non-owner's isn't (small steering, a slow walk, a motion aim's
//                  fine moves come out whenever the other hand is off its stick).
//             An owner back at rest hands the group to the other if that one is away from rest, or — the owner now
//             still — if that one holds a gentle move beyond the still band (its held values come back at once, with no
//             new event from it). A resting stick never fights a held one: a held stick is beyond the still band, so
//             rule 3 can't apply against it, and its jitter (a few hundred units) is far under range/16.
//
// Codes are SOURCE codes (before wf_in): both sources send AYN-style codes (the fed controller's reader is told the
// Thor's face, as docked). Axis values are in the copy's ranges (wfpad rescales the fed pad's first).
//
// Pure (no I/O), header-only: fx/wfmerge_test.c tests it on its own (tools/build_wfpad.sh test).
#ifndef WFMERGE_H
#define WFMERGE_H

#include "wfmap.h"

enum { WFM_AYN = 0, WFM_EXT = 1 };
/* "at rest": |v − rest| ≤ range/16 (≈ 12.5 % of a half) — sticks; triggers range/20 */
#define WFM_REST_DIV      16
#define WFM_TRIG_REST_DIV 20
/* "still": |v − rest| ≤ range/64 (≈ 3.1 % of a half, 1024): above the DualSense's 8-bit jitter (±514); device step A5a
   checks AYN's drift stays under it before release, else 32 */
#define WFM_STILL_DIV     64

typedef struct {
    unsigned char key[2][WF_NK];   /* each source's held keys (source codes) */
    int abs[2][WF_NA];             /* each source's axes, in the copy's ranges */
    unsigned char owner[WF_NA];    /* which source drives the axis' group now (kept on every axis of the group) */
    int seen[2][WF_NA];            /* each source's value when the group's owner was last decided (rule 2) */
} wf_merge;

/** What the merge gives the engine: one event (EV_KEY / EV_ABS, code, the merged value). */
typedef void (*wfm_fn)(void *ctx, int type, int code, int value);

/** A stick axis' partner (LX ↔ LY, RX ↔ RY); -1 = alone (the hat's axes, the triggers, any other axis). */
static inline int wfm_partner(int a) {
    switch (a) {
        case WF_LX: return WF_LY; case WF_LY: return WF_LX;
        case WF_RX: return WF_RY; case WF_RY: return WF_RX;
    }
    return -1;
}
static inline int wfm_is_hat(int a) { return a == WF_HX || a == WF_HY; }

/** At rest: the hat at 0 · a trigger within range/20 of its rest · any other axis within range/16. */
static inline int wfm_rest(const wf_state *s, int a, int v) {
    if (wfm_is_hat(a)) return v == 0;
    long d = labs((long)v - wf_rest(s, a)), r = wf_range(s, a);
    if (a == WF_GAS || a == WF_BRAKE) return d * WFM_TRIG_REST_DIV <= r;
    return d * WFM_REST_DIV <= r;
}
/** Still: the hat at 0 · any other axis within range/64 of its rest. */
static inline int wfm_still(const wf_state *s, int a, int v) {
    if (wfm_is_hat(a)) return v == 0;
    long d = labs((long)v - wf_rest(s, a));
    return d * WFM_STILL_DIV <= wf_range(s, a);
}

/** [a]'s group into [g]: itself, then its partner. Returns how many (1 or 2). */
static inline int wfm_group(int a, int g[2]) {
    g[0] = a; g[1] = wfm_partner(a);
    return g[1] >= 0 ? 2 : 1;
}
static inline int wfm_group_rest(const wf_merge *m, const wf_state *s, int src, const int *g, int n) {
    for (int i = 0; i < n; i++) if (!wfm_rest(s, g[i], m->abs[src][g[i]])) return 0;
    return 1;
}
static inline int wfm_group_still(const wf_merge *m, const wf_state *s, int src, const int *g, int n) {
    for (int i = 0; i < n; i++) if (!wfm_still(s, g[i], m->abs[src][g[i]])) return 0;
    return 1;
}
/** The group's owner is decided: [src] drives all of its axes; [seen] = both sources' values now. */
static inline void wfm_own(wf_merge *m, const int *g, int n, int src) {
    for (int i = 0; i < n; i++) {
        m->owner[g[i]] = (unsigned char)src;
        m->seen[0][g[i]] = m->abs[0][g[i]]; m->seen[1][g[i]] = m->abs[1][g[i]];
    }
}

/** The owner's value of axis [a] (what the engine has). */
static inline int wfm_value(const wf_merge *m, int a) {
    if (a < 0 || a >= WF_NA) return 0;
    return m->abs[m->owner[a] ? 1 : 0][a];
}

/** Both sources at rest (nothing held, every axis at its rest — [s]'s ranges), AYN the owner everywhere. */
static inline void wfm_reset(wf_merge *m, const wf_state *s) {
    memset(m, 0, sizeof *m);
    for (int a = 0; a < WF_NA; a++) {
        int r = wf_rest(s, a);                      /* an axis the pad hasn't: 0 */
        m->abs[0][a] = m->abs[1][a] = m->seen[0][a] = m->seen[1][a] = r;
    }
}

/** A key event of [src]. 1 with [*out] the value the engine gets: the merged state changed (0 → 1 at the first holder's
 *  press, 1 → 0 when the LAST holder lets go), or a repeat (2) from a source that holds the key. Else 0: nothing. */
static inline int wfm_key(wf_merge *m, int src, int code, int v, int *out) {
    if (src < 0 || src > 1 || code < 0 || code >= WF_NK) return 0;
    if (v == 2) {
        if (!m->key[src][code]) return 0;
        *out = 2; return 1;
    }
    int was = m->key[0][code] || m->key[1][code];
    m->key[src][code] = v != 0;
    int now = m->key[0][code] || m->key[1][code];
    if (now == was) return 0;
    *out = now; return 1;
}

/** An axis event of [src] ([v] in the copy's range). Writes the merged axes that changed (≤ 2: a stick's group switches
 *  owner as a whole) to [axes] / [vals]; returns how many. */
static inline int wfm_abs(wf_merge *m, const wf_state *s, int src, int a, int v, int *axes, int *vals) {
    if (src < 0 || src > 1 || a < 0 || a >= WF_NA) return 0;
    int o = 1 - src, g[2], n = wfm_group(a, g), before[2];
    for (int i = 0; i < n; i++) before[i] = wfm_value(m, g[i]);
    if (wfm_is_hat(a)) {
        int old = m->abs[src][a];
        m->abs[src][a] = v;
        if (m->owner[a] == src) { if (v == 0 && m->abs[o][a] != 0) wfm_own(m, g, 1, o); }   /* back at 0: the other's held direction */
        else if (v != 0 && v != old) wfm_own(m, g, 1, src);                                  /* a direction pressed: it takes the axis */
    } else {
        int was_rest = wfm_group_rest(m, s, src, g, n);
        m->abs[src][a] = v;
        if (m->owner[a] == src) {
            /* back at rest: the other's held stick comes back — held away from rest, or a gentle hold (beyond the still
               band) once this one is still (rule 3's state, so it can't flap: the new owner isn't still) */
            if (wfm_group_rest(m, s, src, g, n) &&
                (!wfm_group_rest(m, s, o, g, n) || (wfm_group_still(m, s, src, g, n) && !wfm_group_still(m, s, o, g, n))))
                wfm_own(m, g, n, o);
        } else {
            int away = !wfm_group_rest(m, s, src, g, n), take = away && was_rest;          /* 1. leaves rest */
            for (int i = 0; away && !take && i < n; i++)                                     /* 2. a real move */
                if (labs((long)m->abs[src][g[i]] - m->seen[src][g[i]]) * WFM_REST_DIV >= wf_range(s, g[i])) take = 1;
            if (!take && wfm_group_still(m, s, o, g, n) && !wfm_group_still(m, s, src, g, n)) take = 1;   /* 3. gentle */
            if (take) wfm_own(m, g, n, src);
        }
    }
    int k = 0;
    for (int i = 0; i < n; i++) {
        int nv = wfm_value(m, g[i]);
        if (nv != before[i]) { axes[k] = g[i]; vals[k] = nv; k++; }
    }
    return k;
}

/** One event of [src] (EV_KEY / EV_ABS; anything else: nothing) → the merged changes, each given to [fn]. Returns how
 *  many. */
static inline int wfm_event(wf_merge *m, const wf_state *s, int src, int type, int code, int value, wfm_fn fn, void *ctx) {
    if (type == EV_KEY) {
        int o;
        if (!wfm_key(m, src, code, value, &o)) return 0;
        fn(ctx, EV_KEY, code, o);
        return 1;
    }
    if (type != EV_ABS) return 0;
    int ax[2], vl[2], n = wfm_abs(m, s, src, code, value, ax, vl);
    for (int i = 0; i < n; i++) fn(ctx, EV_ABS, ax[i], vl[i]);
    return n;
}

/** After both sources' values were set from scratch (a resync: [key] / [abs] read from the kernels): each group's owner
 *  is EXT when its group is away from rest (a hat axis: EXT's value non-zero), else AYN; [seen] from now. */
static inline void wfm_decide(wf_merge *m, const wf_state *s) {
    for (int a = 0; a < WF_NA; a++) {
        int g[2], n = wfm_group(a, g);
        int ext = wfm_is_hat(a) ? m->abs[1][a] != 0 : !wfm_group_rest(m, s, 1, g, n);
        m->owner[a] = (unsigned char)ext;
        m->seen[0][a] = m->abs[0][a]; m->seen[1][a] = m->abs[1][a];
    }
}

/** Source [src] is gone (its pad removed, the feed stopped): every key it holds is let go (an edge only where the other
 *  doesn't hold it), its axes rest, and the other source owns every axis — after the drop the merged state IS the
 *  other's, exactly. Each change goes to [fn]. */
static inline void wfm_drop(wf_merge *m, const wf_state *s, int src, wfm_fn fn, void *ctx) {
    if (src < 0 || src > 1) return;
    int o = 1 - src;
    for (int k = 0; k < WF_NK; k++)
        if (m->key[src][k]) { int v; if (wfm_key(m, src, k, 0, &v)) fn(ctx, EV_KEY, k, v); }
    for (int a = 0; a < WF_NA; a++) {
        int before = wfm_value(m, a);
        m->abs[src][a] = wf_rest(s, a);
        m->owner[a] = (unsigned char)o;
        m->seen[0][a] = m->abs[0][a]; m->seen[1][a] = m->abs[1][a];
        int nv = wfm_value(m, a);
        if (nv != before) fn(ctx, EV_ABS, a, nv);
    }
}

#endif
