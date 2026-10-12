// wfhid — external controllers for the Thor. The logic: fx/wfhid_core.h.
//
// AYN's rsinput grabs every external gamepad and re-publishes a copy that drops what its layout doesn't have
// (a Joy-Con's stick, a Nimbus+'s D-pad / L2 / R2 / Start / Select…). The HID reports themselves still reach
// /dev/hidrawN untouched. wfhid reads them and publishes ONE complete pad through uinput, in the Thor's own layout
// (2020:0111, AYN's key layout: what games already read right), through the input layer's mapping engine.
//
//   wfhid <hidraw node>[,<hidraw node>] <phys tag> <pad name>      (two nodes: a Joy-Con pair, one controller)
//
// stdin (one command per line):
//   m <target>=<field>[i] …   the user's mapping (replaces the default); "m" alone = back to the default
//   M <target>=<field>[i] …   the same for a single Joy-Con held sideways
//   h 1 | h 0                 its Home is the Thor's Home (Wayfinder's combos; default) / the game's (MODE); while
//                             its Home is held, at its release
//   P <profile>               the game's profile for this controller (fx/wfmap.h tokens); "P" alone = none. Swapped
//                             in place: what is held stays held. The one in use again: no answer
//   l 1 | l 0                 learn mode: report every field change ("C" lines) — for the setup wizard; it ends by
//                             itself after a minute without a change ("L 0")
//   c                         re-centre the sticks (the next reading is the centre)
//   x n | x x                 the Thor's face-button layout (Nintendo / Xbox): same place = same button
//   o 0 | o 1                 a single Joy-Con upright / sideways
//   L <0..4>                  player lights (Nintendo controllers; DualSense player LEDs; DualShock 4 lightbar colour)
//   i 0 | i 1                 motion sensors off / on (Nintendo controllers)
//   g <0|1|2> <sens> <flags>  aim with motion: off / left / right stick; sensitivity 1..50; flags 1 invert x,
//                             2 invert y, 4 swap the axes
//   p <code> <0|1>            a virtual press from Wayfinder (its macros, turbo for a key / action button); p 0 0 = all go.
//                             dev build 17: "p 158 <0|1>" = a Back handed to the game, out even while its Home is held
//   b <ms>                    a short rumble (the page's "find this controller")
//                             (rumble: Nintendo, DualShock 4, DualSense, Xbox over Bluetooth)
//   k 1 | k 0                 dev build 15: keep the pad when the controller goes away — it stays, at rest, so games
//                             keep it and its place ("S 0"); "k 0" while it is away = gone (exit 6)
//   a <node>[,<node>]         the same controller back (only while away): the same ids and the same fields (another
//                             bus is fine) — it drives the kept pad again ("I", "F", "D", "S 1"); else "E attach …"
//   n | n 0 | n 1             our pad made again now, back to back (same name, ids, location: it takes its number again)
//                             / taken down / up again (the player order); down 10 s without "n 1": up by itself
//   n r                       still down on purpose: its 10 s start again (the helper, while a player order waits for
//                             the Thor's copy — review N2 / P3); never takes a pad down
//   w [k<code>|a<axis>]…      dev build 17: say these controls ("V"), as wfpad's "w" — the app asks for the D-pad (a16
//                             a17) while it reads it; "w" alone = none (at most 4)
//   u                         dev build 17: its controls back after Home + Back held 5 s ("M 0")
//   t 1 | t 0                 dev build 18f: its touchpad moves Android's pointer ("H" lines; PlayStation pads — any other
//                             ignores it) / not (default: a reader moves nothing until told); any other value: "E touch"
//   q                         quit
// stdout (one line each):
//   I <bus> <vid> <pid> <fields> <hid|nintendo|sony> <src>   a controller (src 0, or 1 for the second of a pair) —
//                             again with its fields when it is back ("a")
//   F <idx> <b|a|h> <bits> <min> <max> <page> <usage> <src>   one field (b = button bit, a = value, h = hat)
//   D <target>=<field>[i] …   the mapping in use
//   R <eventN>                our pad is created (again: "A", "n", "n 1")
//   S 0 | S 1                 the controller went away, its pad kept at rest ("k 1") / it is back ("a")
//   E attach <why>            "a" refused, the pad still kept: busy (not away) · usage · count · open <errno> · info ·
//                             desc · ids · layout
//   E pad-up timeout          down ("n 0") for 10 s without "n 1" / "n r": up by itself (a lost "n 1" never leaves a
//                             controller dead)
//   C <idx> <value> <rest>    learn mode: a button / hat changed, or a value left its rest (a sixth of its range)
//   L 0                       learn mode ended by itself (a minute without a change): the controller drives again
//   O <0|1>                   a single Joy-Con turned sideways (SL + SR held) / upright
//   B <0..4> <charging> <src> battery (Nintendo controllers)
//   U 1                       it can rumble (Nintendo, DualShock 4, DualSense, Xbox over Bluetooth)
//   P ok | P bad              the profile was applied / refused
//   J <type> <code> <value>   an event withheld from the game while its Home (the Thor's) is held — for Wayfinder's combos
//                             (b18l review: never the release of a button held since before the gate opened — our pad
//                             let it go then)
//   J 1 102 <1|0>             a controller with no Home button: Select + Start held became its Home / let it go — a
//                             modifier only (its release alone is no Home tap: Start + Select is a game's combo too)
//   TU <code> <1|0> <ms>      b18o: a hand holds (1) / let go of (0) a turbo button whose job is its pad's button <code>
//                             (printed), at <ms> (CLOCK_MONOTONIC) — Wayfinder takes the pulses for no presses
//   W <code> <0|1>           a button (or D-pad direction) the game's controls give to Wayfinder: a key, an action, a macro
//   T <side> <x> <y>          a stick given to Wayfinder (mouse, wheel, keys): its position, −1000..1000
//   G sh <0|1>                a single Joy-Con's main shoulder (L / R) pressed / let go (the L + R pairing gesture)
//   A <0|1>                   dev build 17: its AYN button (a spare one, D4: a DualSense's Mute) pressed / let go —
//                             never on its pad
//   V <type> <code> <value>   dev build 17: a watched control ("w"), raw; its D-pad also while its AYN is held (a
//                             control said away from rest is always said back at rest)
//   M <1|0>                   dev build 17: its Home + Back held 5 s — Wayfinder's controls for it off (plain buttons,
//                             no motion aim) / back ("u")
//   H m <dx> <dy>             dev build 18f ("t 1"): its touchpad moved the pointer — pixels, after acceleration, at most
//                             every 8 ms (each way ≤ 2000)
//   H b <1|0>                 dev build 18f: a light tap on its touchpad — the click pressed / let go (40 ms later)
//   H w <v> <h>               dev build 18f: two fingers dragging — wheel notches (natural: the page follows the
//                             fingers; v > 0 fingers down, h < 0 fingers right), at most every 16 ms (each ≤ 10)
//   E <message>               an error (then the exit code says why)
// exit: 0 stdin closed / q · 2 bad arguments · 3 a controller couldn't be opened · 4 no usable fields · 5 uinput (our
//       pad not made, or not made again: "A", "n") · 6 a controller went away without "k 1" (a normal disconnect, not a
//       failure)
//
// Our own code from public protocol documentation (HID 1.11 report descriptors; the Switch controllers' reports,
// subcommands and rumble encoding). Every length is checked: the reports come over the air from any Bluetooth device.

#include <errno.h>
#include <fcntl.h>
#include <linux/hidraw.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#include "wfhid_core.h"

static int out_fd = -1;
static int src_fd[HX_MAXSRC] = { -1, -1 };
static int pkt[HX_MAXSRC];
static int rumble_ok;                       /* our pad declares rumble (a Nintendo controller) */

static void put_line(const char *s) { printf("%s\n", s); fflush(stdout); }

static void emit(int type, int code, int value) {
    if (out_fd < 0) return;
    struct input_event e; memset(&e, 0, sizeof e);
    e.type = (unsigned short)type; e.code = (unsigned short)code; e.value = value;
    if (write(out_fd, &e, sizeof e) != (ssize_t)sizeof e) { /* a full queue: the next frame carries the state */ }
}

static long now_ms(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000L + t.tv_nsec / 1000000L;
}

/* ---- output reports (Nintendo) ---------------------------------------------------------------------------- */
static void write_out(int src, const uint8_t *b, int n) {
    if (src_fd[src] < 0 || n <= 0) return;
    if (write(src_fd[src], b, (size_t)n) < 0) { /* read-only node, or the controller busy: nothing to do */ }
}
static void subcommand(uint8_t cmd, uint8_t arg) {
    for (int s = 0; s < HX.nsrc; s++) if (HX.S[s].nintendo) {
        uint8_t b[16]; int n = hx_subcommand(b, sizeof b, pkt[s]++, cmd, arg); write_out(s, b, n);
    }
}

/* ---- rumble: uinput force feedback → the controller ------------------------------------------------------- */
#define NEFF 16
static struct { int used, strong, weak, len; long until; int playing; } eff[NEFF];
static int rum_lo, rum_hi; static long rum_sent, buzz_until;

static void rumble_update(long now) {
    int lo = 0, hi = 0;
    for (int i = 0; i < NEFF; i++) {
        if (!eff[i].playing) continue;
        if (eff[i].until && now >= eff[i].until) { eff[i].playing = 0; continue; }
        if (eff[i].strong > lo) lo = eff[i].strong;
        if (eff[i].weak > hi) hi = eff[i].weak;
    }
    if (buzz_until > now) { lo = hi = 40000; }
    /* a change, or every 0.5 s while it plays (a controller that missed a report doesn't buzz forever / stop) */
    if (lo == rum_lo && hi == rum_hi && (!(lo | hi) || now - rum_sent < 500)) return;
    rum_lo = lo; rum_hi = hi; rum_sent = now;
    for (int s = 0; s < HX.nsrc; s++) if (HX.S[s].rumble) {
        uint8_t b[96]; int n = hx_rumble_out(s, b, sizeof b, pkt[s]++, lo, hi); write_out(s, b, n);
    }
}
static void rumble_stop(long now) { memset(eff, 0, sizeof eff); buzz_until = 0; rumble_update(now); }
/* dev build 15: the controller went away — it stops, the game's effects stay uploaded (a game uploads to a pad once:
   the kept pad is the same device when the controller is back) */
static void rumble_quiet(long now) {
    for (int i = 0; i < NEFF; i++) { eff[i].playing = 0; eff[i].until = 0; }
    buzz_until = 0; rumble_update(now);
}

static void uinput_event(long now) {
    struct input_event e;
    while (read(out_fd, &e, sizeof e) == (ssize_t)sizeof e) {
        if (e.type == EV_UINPUT && e.code == UI_FF_UPLOAD) {
            struct uinput_ff_upload up; memset(&up, 0, sizeof up); up.request_id = (unsigned)e.value;
            if (ioctl(out_fd, UI_BEGIN_FF_UPLOAD, &up) < 0) continue;
            int id = up.effect.id;
            if (up.effect.type == FF_RUMBLE && id >= 0 && id < NEFF) {
                eff[id].used = 1;
                eff[id].strong = up.effect.u.rumble.strong_magnitude;
                eff[id].weak = up.effect.u.rumble.weak_magnitude;
                eff[id].len = up.effect.replay.length;
                up.retval = 0;
            } else up.retval = -EINVAL;
            ioctl(out_fd, UI_END_FF_UPLOAD, &up);
        } else if (e.type == EV_UINPUT && e.code == UI_FF_ERASE) {
            struct uinput_ff_erase er; memset(&er, 0, sizeof er); er.request_id = (unsigned)e.value;
            if (ioctl(out_fd, UI_BEGIN_FF_ERASE, &er) < 0) continue;
            if (er.effect_id < NEFF) memset(&eff[er.effect_id], 0, sizeof eff[0]);
            er.retval = 0;
            ioctl(out_fd, UI_END_FF_ERASE, &er);
        } else if (e.type == EV_FF && e.code < NEFF && eff[e.code].used) {
            eff[e.code].playing = e.value > 0;
            eff[e.code].until = e.value > 0 && eff[e.code].len > 0 ? now + eff[e.code].len : 0;
        }
    }
    rumble_update(now);
}

/* ---- uinput: our pad, in the Thor's own layout --------------------------------------------------------- */
/** The product id of the pad made (main: an optional 4th argument; AYN's 0x0111 by default). */
static int pad_product = 0x0111;


static int create_pad(const char *phys, const char *name) {
    int u = open("/dev/uinput", O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (u < 0) { hx_sayf("E uinput %d", errno); return -1; }
    ioctl(u, UI_SET_EVBIT, EV_KEY); ioctl(u, UI_SET_EVBIT, EV_ABS); ioctl(u, UI_SET_EVBIT, EV_SYN);
    for (int i = 0; i < HX.ws.nk; i++) ioctl(u, UI_SET_KEYBIT, HX.ws.keys[i]);
    for (int k = 0; k < 8; k++) {
        struct uinput_abs_setup s; memset(&s, 0, sizeof s);
        s.code = (unsigned short)HX_AXES[k]; s.absinfo = HX.ws.info[HX_AXES[k]];
        ioctl(u, UI_SET_ABSBIT, HX_AXES[k]);
        if (ioctl(u, UI_ABS_SETUP, &s) < 0) { hx_sayf("E abs %d", errno); close(u); return -1; }
    }
    if (rumble_ok) { ioctl(u, UI_SET_EVBIT, EV_FF); ioctl(u, UI_SET_FFBIT, FF_RUMBLE); }
    ioctl(u, UI_SET_PHYS, phys);
    struct uinput_setup us; memset(&us, 0, sizeof us);
    us.id.bustype = BUS_USB; us.id.vendor = 0x2020; us.id.product = (unsigned short)pad_product; us.id.version = 1;
    us.ff_effects_max = rumble_ok ? NEFF : 0;
    strncpy(us.name, name, UINPUT_MAX_NAME_SIZE - 1);
    if (ioctl(u, UI_DEV_SETUP, &us) < 0 || ioctl(u, UI_DEV_CREATE) < 0) { hx_sayf("E create %d", errno); close(u); return -1; }
    char sys[64] = "", node[32] = "?";
    if (ioctl(u, UI_GET_SYSNAME(sizeof sys), sys) >= 0) {
        char path[160];
        for (int tries = 0; tries < 20 && node[0] == '?'; tries++) {
            for (int n = 0; n < 64 && node[0] == '?'; n++) {
                snprintf(path, sizeof path, "/sys/devices/virtual/input/%s/event%d", sys, n);
                if (access(path, F_OK) == 0) snprintf(node, sizeof node, "event%d", n);
            }
            if (node[0] == '?') usleep(50000);
        }
    }
    out_fd = u;
    hx_sayf("R %s", node);
    return 0;
}

/* AYN's mapping service (com.odin.mapping) takes the first gamepad that isn't AYN's own (2020:0111 / 0112) while its
   one "external" slot is free (tested 2026-10-06: every other id was taken): Android is made to ignore it (it isn't in
   Android's device list, no process reads it) and AYN shows a 2020:0111 copy that gets nothing. The helper says "A":
   the pad is made again under AYN's id, which AYN leaves alone (dev build 15: only as a last resort — AYN's hold list
   keeps our pads' names, so AYN taking one is rare).
   0, or exit code 5: not made again — no reader runs without a pad (dev build 14: AYN's copy stayed hidden, the
   controller dead); the helper gives the controller back and starts a reader again. */
static const char *pad_phys, *pad_name;
static int remake_as_ayn(void) {
    if (pad_product == 0x0111) return 0;
    if (out_fd < 0) {                              /* down for a moment ("n 0"): it comes up under AYN's id */
        if (HX.pad_down) { pad_product = 0x0111; hx_say("I ayn-took-pad 0111"); }
        return 0;
    }
    hx_say("I ayn-took-pad 0111");
    ioctl(out_fd, UI_DEV_DESTROY); close(out_fd); out_fd = -1;
    pad_product = 0x0111;
    if (create_pad(pad_phys, pad_name) < 0) return 5;
    HX.have_out = 0; hx_flush();                  /* the new pad gets the whole state */
    return 0;
}

/* ---- dev build 15 (C5): our pad down and up in place (the player order) --------------------------------------- */
#define PAD_DOWN_MS 10000
static long down_since;
/** "n 0": our pad's device goes (its number is free); the engine keeps reading the controller, nothing is written. */
static void pad_down(long now) {
    if (HX.pad_down || out_fd < 0) return;
    rumble_stop(now);                              /* the device goes, and its effects with it */
    ioctl(out_fd, UI_DEV_DESTROY); close(out_fd); out_fd = -1;
    hx_pad_down(); down_since = now;
}
/** "n 1": made again — the same name, ids and location; it gets the whole state. 0, or 5: not made (no reader runs
 *  without a pad, as [remake_as_ayn]). */
static int pad_up(void) {
    if (!HX.pad_down) return 0;
    if (create_pad(pad_phys, pad_name) < 0) return 5;
    hx_pad_up();
    return 0;
}

/** Open controller [src]'s hidraw [path] and read its fields ([hx_add_source_bus]) into [fdp]. 0, or the exit code —
 *  an "[err] …" line said why. */
static int open_hidraw(int src, const char *path, int *fdp, const char *err) {
    if (strncmp(path, "/dev/hidraw", 11) != 0 || strlen(path) > 20 || !path[11]) { hx_sayf("%s usage", err); return 2; }
    for (const char *c = path + 11; *c; c++) if (*c < '0' || *c > '9') { hx_sayf("%s usage", err); return 2; }
    int fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { hx_sayf("%s open %d", err, errno); return 3; }
    struct hidraw_devinfo info; memset(&info, 0, sizeof info);
    if (ioctl(fd, HIDIOCGRAWINFO, &info) < 0) { hx_sayf("%s info %d", err, errno); close(fd); return 3; }
    int dsz = 0;
    if (ioctl(fd, HIDIOCGRDESCSIZE, &dsz) < 0 || dsz <= 0 || dsz > HID_MAX_DESCRIPTOR_SIZE) { hx_sayf("%s desc size", err); close(fd); return 3; }
    struct hidraw_report_descriptor rd; memset(&rd, 0, sizeof rd); rd.size = (unsigned)dsz;
    if (ioctl(fd, HIDIOCGRDESC, &rd) < 0) { hx_sayf("%s desc %d", err, errno); close(fd); return 3; }
    hx_add_source_bus(src, (int)info.bustype, (uint16_t)info.vendor, (uint16_t)info.product, rd.value, dsz);
    *fdp = fd;
    return 0;
}

static int open_source(int src, const char *path) {
    int rc = open_hidraw(src, path, &src_fd[src], "E");
    if (rc) return rc;
    hx_print_source(src);
    if (HX.S[src].rumble) rumble_ok = 1;
    return 0;
}

/** Player lights [n] (0..4): Nintendo controllers (subcommand 0x30), PlayStation pads (player LEDs / lightbar). Kept
 *  for when the controller is back. */
static void lights(int n) {
    HX.last_lights = n;
    subcommand(0x30, hx_player_lights(n));
    for (int s = 0; s < HX.nsrc; s++) if (HX.S[s].sony) {
        uint8_t b[96]; int len = hx_lights_out(s, b, sizeof b, pkt[s]++, n); write_out(s, b, len);
    }
}

/* the poll set: the controller's sources, stdin, our pad */
static struct pollfd P[HX_MAXSRC + 2];
static int isrc[HX_MAXSRC], istdin, iout, np;

/* ---- dev build 15 (C4): the controller away and back ----------------------------------------------------------- */
static int keep;                                   /* "k 1": the pad stays when the controller goes away */

/** It went away with "k 1": every source closed (both of a Joy-Con pair), its pad kept at rest ("S 0"). */
static void detach(long now) {
    hx_detach();
    rumble_quiet(now);
    for (int s = 0; s < HX.nsrc; s++) { if (src_fd[s] >= 0) close(src_fd[s]); src_fd[s] = -1; P[isrc[s]].fd = -1; }
    hx_say("S 0");
}

/** "a <node>[,<node>]" while away: the same controller back — as many nodes, the same ids, the same fields — drives
 *  the kept pad again ("S 1"). Refused: "E attach <why>", nothing opened stays open, still away (the helper then
 *  starts a fresh reader). */
static void attach(const char *arg) {
    if (!HX.idle) { hx_say("E attach busy"); return; }
    if (strlen(arg) > 50) { hx_say("E attach usage"); return; }
    char buf[64], *path[HX_MAXSRC + 1]; int n = 0;
    snprintf(buf, sizeof buf, "%s", arg);
    for (char *save = 0, *t = strtok_r(buf, ",", &save); t && n <= HX_MAXSRC; t = strtok_r(0, ",", &save)) path[n++] = t;
    if (n != HX.nsrc) { hx_say("E attach count"); return; }
    int fd[HX_MAXSRC] = { -1, -1 }, ok = 1;
    hx_attach_begin();
    for (int s = 0; s < n && ok; s++) if (open_hidraw(s, path[s], &fd[s], "E attach") != 0) ok = 0;
    if (ok && !hx_same_ids()) { hx_say("E attach ids"); ok = 0; }
    int r = hx_attach_end(ok);                    /* "I", "F", "D" when it is the same */
    if (r == -2) hx_say("E attach layout");
    if (r != 0) { for (int s = 0; s < n; s++) if (fd[s] >= 0) close(fd[s]); return; }
    for (int s = 0; s < n; s++) { src_fd[s] = fd[s]; P[isrc[s]].fd = fd[s]; }
    if (HX.imu_on) subcommand(0x40, 1);           /* its motion sensors on again (Nintendo controllers) */
    if (HX.last_lights >= 0) lights(HX.last_lights);
    hx_say("S 1");
}

/** One command line from the helper. 0, or the exit code to quit with (our pad couldn't be made again). */
static int command(char *line, long now) {
    if (!strcmp(line, "c")) { HX.recentre = 1; return 0; }
    if (!strcmp(line, "x n") || !strcmp(line, "x x")) { hx_face(line[2] == 'x'); return 0; }
    if (!strcmp(line, "l 1")) { hx_learn_start(); return 0; }
    if (!strcmp(line, "l 0")) { hx_learn_stop(); return 0; }
    if (!strcmp(line, "o 0") || !strcmp(line, "o 1")) { hx_orient(line[2] == '1'); hx_sayf("O %d", HX.sideways); hx_print_map(); return 0; }
    if ((line[0] == 'm' || line[0] == 'M') && (line[1] == 0 || line[1] == ' ')) {
        if (hx_parse_map_o(line + (line[1] ? 2 : 1), line[0] == 'M') != 0) hx_say("E map");
        hx_print_map(); return 0;
    }
    if (!strcmp(line, "h 0") || !strcmp(line, "h 1")) { hx_home(line[2] == '1'); return 0; }
    if (line[0] == 'p' && line[1] == ' ') {
        int c, v;
        if (sscanf(line + 2, "%d %d", &c, &v) != 2 || hx_vpress(c, v) != 0) hx_say("E press");
        return 0;
    }
    if (line[0] == 'A' && line[1] == 0) return remake_as_ayn();         /* AYN took our pad (see remake_as_ayn) */
    /* dev build 17 (theme G): controls said as "V" lines (the app reads a controller's D-pad: Recents, a capture); its
       controls back after Home + Back held 5 s */
    if (line[0] == 'w' && (line[1] == 0 || line[1] == ' ')) { if (hx_watch(line + 1) != 0) hx_say("E watch"); return 0; }
    if (!strcmp(line, "u")) { hx_mute(0); return 0; }
    /* dev build 18f: the touchpad moves the pointer / not */
    if (line[0] == 't' && (line[1] == 0 || line[1] == ' ')) {
        if (!strcmp(line, "t 1") || !strcmp(line, "t 0")) hx_touch_on(line[2] == '1'); else hx_say("E touch");
        return 0;
    }
    if (line[0] == 'P' && (line[1] == 0 || line[1] == ' ')) {
        /* swapped in place (dev build 14): no release and replay — Home held through it went up and down */
        int r = hx_profile(line + (line[1] ? 2 : 1));
        if (r <= 0) hx_say(r == 0 ? "P ok" : "P bad");
        return 0;
    }
    if (line[0] == 'L' && line[1] == ' ' && line[2] >= '0' && line[2] <= '4' && !line[3]) { lights(line[2] - '0'); return 0; }
    /* dev build 15 (C4): keep the pad while the controller is away; the same controller back */
    if (!strcmp(line, "k 1")) { keep = 1; return 0; }
    if (!strcmp(line, "k 0")) {
        keep = 0;
        if (HX.idle) { hx_say("E gone -"); return 6; }  /* away already: gone now, as without "k 1" */
        return 0;
    }
    if (line[0] == 'a' && line[1] == ' ') { attach(line + 2); return 0; }
    /* dev build 15 (C5): our pad made again in place / down / up */
    if (!strcmp(line, "n 0")) { pad_down(now); return 0; }
    if (!strcmp(line, "n 1")) return pad_up();
    if (!strcmp(line, "n")) { pad_down(now); return pad_up(); }
    /* review N2 / P3: the helper's player order still waits (the Thor's copy slow): the self-heal's 10 s start again. Only
       while down — after a self-heal it is nothing (a refresh can never take a pad down: no race with "E pad-up timeout") */
    if (!strcmp(line, "n r")) { if (HX.pad_down) down_since = now; return 0; }
    if (!strcmp(line, "i 0") || !strcmp(line, "i 1")) {
        HX.imu_on = line[2] == '1';
        subcommand(0x40, (uint8_t)HX.imu_on);
        if (!HX.imu_on) { HX.ws.gyro_x = HX.ws.gyro_y = 0; hx_flush(); }
        return 0;
    }
    if (line[0] == 'g' && line[1] == ' ') {
        int st, sens, fl;
        if (sscanf(line + 2, "%d %d %d", &st, &sens, &fl) == 3 && st >= 0 && st <= 2 && sens >= 1 && sens <= 50 && fl >= 0 && fl <= 7) {
            /* dev build 17 (theme G): its controls off (Home + Back held 5 s): kept for "u", no motion aim meanwhile */
            HX.gyro_want = st; if (!HX.muted) HX.ws.gyro_stick = st;
            HX.gyro_sens = sens; HX.gyro_flags = fl;
            if (!HX.ws.gyro_stick) { HX.ws.gyro_x = HX.ws.gyro_y = 0; hx_flush(); }
        } else hx_say("E gyro");
        return 0;
    }
    if (line[0] == 'b' && line[1] == ' ') {
        int ms = atoi(line + 2);
        if (ms > 0 && ms <= 2000) { buzz_until = now + ms; rumble_update(now); }
        return 0;
    }
    return 0;
}

static int finish(int code) {
    hx_release_all();
    rumble_stop(now_ms());
    return code;
}

int main(int argc, char **argv) {
    /* the name: cut to what uinput keeps, on a character boundary (hx_pad_name) — dev build 15: more than 79 bytes (a long
       non-ASCII name) was refused here, and that controller never got a reader */
    if ((argc != 4 && argc != 5) || strlen(argv[1]) > 50 || strlen(argv[2]) > 60 || strlen(argv[3]) > 400) { put_line("E usage"); return 2; }
    /* the pad's product id (hex): its own per controller, so Android's descriptor for it doesn't depend on which other
       2020:0111 devices exist when it appears (a reconnect changed it: Eden lost the controller, 2026-10-03) */
    if (argc == 5) {
        char *end = NULL; long v = strtol(argv[4], &end, 16);
        if (!end || *end || v <= 0 || v > 0xFFFF) { put_line("E usage"); return 2; }
        pad_product = (int)v;
    }
    signal(SIGPIPE, SIG_IGN);
    hx_say = put_line; hx_emit = emit;
    hx_reset();
    char nodes[64]; snprintf(nodes, sizeof nodes, "%s", argv[1]);
    char *comma = strchr(nodes, ',');
    if (comma) *comma = 0;
    int rc = open_source(0, nodes);
    if (rc) return rc;
    if (comma && (rc = open_source(1, comma + 1)) != 0) return rc;
    if (HX.nf == 0) { hx_say("E no fields"); return 4; }
    if (rumble_ok) hx_say("U 1");                 /* it can rumble: the page offers "Find it" */
    hx_apply_map(); hx_print_map();
    char name[HX_NAME_MAX + 1]; hx_pad_name(name, argv[3]);
    pad_phys = argv[2]; pad_name = name;
    if (create_pad(argv[2], name) < 0) return 5;
    hx_flush();

    char line[2200]; int ll = 0;
    uint8_t rep[HX_MAXR + 1];
    for (int s = 0; s < HX.nsrc; s++) { isrc[s] = np; P[np].fd = src_fd[s]; P[np].events = POLLIN; np++; }
    istdin = np; P[np].fd = 0; P[np].events = POLLIN; np++;
    iout = np; P[np].fd = out_fd; P[np].events = POLLIN; np++;
    for (;;) {
        /* timers: turbo flips, the shift button's tap, rumble (an effect's end, the refresh), our pad down too long */
        long now = now_ms();
        int timeout = -1;
        if (wf_turbo_active(&HX.ws, &HX.wm) || HX.ws.shift_tap_until > now) timeout = 15;
        if (!HX.idle) {                            /* (away: nothing to rumble — an effect started meanwhile plays once it is back) */
            if ((buzz_until > now || rum_lo > 0 || rum_hi > 0) && (timeout < 0 || timeout > 50)) timeout = 50;
            for (int i = 0; i < NEFF; i++) if (eff[i].playing && (timeout < 0 || timeout > 50)) timeout = 50;
        }
        { int w = hx_wait(); if (w >= 0 && (timeout < 0 || timeout > w)) timeout = w; }   /* Select + Start, a stick's "T" */
        if (HX.pad_down) {
            long left = down_since + PAD_DOWN_MS - now; int w = left < 0 ? 0 : left > PAD_DOWN_MS ? PAD_DOWN_MS : (int)left;
            if (timeout < 0 || timeout > w) timeout = w;
        }
        for (int i = 0; i < np; i++) P[i].revents = 0;
        P[iout].fd = out_fd;                         /* (made again: "A", "n" — a new fd; -1 while down) */
        if (poll(P, (nfds_t)np, timeout) < 0) { if (errno == EINTR) continue; break; }
        now = now_ms();
        HX.now_ms = now; HX.ws.now_ms = now;
        for (int s = 0; s < HX.nsrc; s++) {
            struct pollfd *q = &P[isrc[s]];
            if (src_fd[s] < 0) continue;                 /* away (dev build 15) */
            /* gone (switched off, out of range, unplugged: hidraw says HUP / ERR, a read EIO / ENODEV): its own exit code,
               a normal disconnect — 3 made the helper back off from the controller's next connection (dev build 14) */
            int gone = (q->revents & (POLLERR | POLLHUP | POLLNVAL)) != 0, why = s;
            if (!gone && (q->revents & POLLIN)) {
                ssize_t n = read(src_fd[s], rep, sizeof rep);
                if (n < 0) { if (errno == EINTR || errno == EAGAIN) continue; gone = 1; why = errno; }
                else if (n > 0) hx_report(s, rep, (int)n);
            }
            if (gone) {
                if (!keep) { hx_sayf("E gone %d", why); return finish(6); }
                /* dev build 15 (C4): with "k 1" its pad stays, at rest (games keep it and its place) until it is back */
                detach(now);
                break;
            }
        }
        if (P[iout].revents & POLLIN) uinput_event(now);
        if (P[istdin].revents & (POLLIN | POLLHUP)) {
            char buf[512]; ssize_t n = read(0, buf, sizeof buf);
            if (n <= 0) return finish(0);
            for (ssize_t k = 0; k < n; k++) {
                char c = buf[k];
                if (c != '\n') { if (ll < (int)sizeof line - 1) line[ll++] = c; continue; }
                line[ll] = 0; ll = 0;
                if (!strcmp(line, "q")) return finish(0);
                if ((rc = command(line, now)) != 0) return finish(rc);
            }
        }
        if (HX.pad_down && now - down_since >= PAD_DOWN_MS) {    /* a lost "n 1" never leaves a controller dead */
            hx_say("E pad-up timeout");
            if ((rc = pad_up()) != 0) return finish(rc);
        }
        hx_tick();
        if (timeout >= 0) { hx_flush(); rumble_update(now); }
    }
    return finish(0);
}
