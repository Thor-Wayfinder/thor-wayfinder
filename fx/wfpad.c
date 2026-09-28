// wfpad — Wayfinder's input layer: AYN's pad → mapping engine → a copy that KEEPS AYN's slot.
//
// Clones an evdev controller (name, ids, key bits, axis ranges, properties) into a new
// uinput device, GRABS the original (EVIOCGRAB: from then on only we receive it), and
// writes, at every frame, the copy's state computed by the mapping engine (fx/wfmap.h):
// the app's profile (remaps, Nintendo/Xbox face buttons) and the Home/Back gate (while one
// is held nothing else reaches the game; those events are echoed to Wayfinder instead).
// Runs as root, launched by Wayfinder's root helper.
//
//   wfpad <source /dev/input/eventN> <grab|nograb> <clone phys tag> <race ms>
//
// Controller number. Android numbers pads 1, 2, … at the moment they appear (lowest free
// number) and emulators key their mappings on it (Dolphin: "Android/1/Odin Controller"). A
// copy made next to AYN's pad would be #2 and break every saved mapping, so the copy must
// take AYN's place instead. <race ms> > 0 = takeover: the copy is fully prepared but not yet
// created ("A" line); the caller then makes AYN rebuild its pad (layout switch), and the
// copy is created the instant AYN's pad goes away — before AYN's new one, so it inherits
// #1 and AYN's new pad gets #2. Measured on device: 20/20 wins with real-time priority and
// a busy-wait (a sleeping wait lost 6 in 20). After that the copy STAYS: when AYN rebuilds
// its pad again (per-game layout, sleep…) wfpad just grabs the new one; the copy is only
// re-made if AYN's identity really changed (Odin ↔ Xbox for > 2 s), and then it is destroyed
// and re-created back to back, so it takes the number it just freed.
//
// stdout, one line each:  "A <name>"         armed, waiting for AYN's pad to go (takeover)
//                         "R <name> <eventN>" copy created (its node)
//                         "S <name> <eventN>" a (new) AYN pad grabbed as the source
//                         "W"                 source gone, copy kept, waiting for AYN's next pad
//                         "L <n> <p50_us> <p99_us> <max_us>"  every 5 s: added latency
//                         "J <type> <code> <value>"  an event withheld from the game (gate):
//                                             for Wayfinder's shortcuts; keys as PRINTED codes
//                         "X <code> <1|0>"    a button mapped to a keyboard key / action
//                                             (printed code; 0x220..0x223 = a D-pad direction): Wayfinder performs it
//                         "T <0|1> <x> <y>"   1.3: a stick that is Wayfinder's (0 left, 1 right): its
//                                             shaped position, −1000..1000, at most every 8 ms
//                         "M ok|bad"          answer to a profile line
//                         "E <reason>"        fatal; then exit: 2 = no source for 10 s,
//                                             4 = takeover: AYN's pad never went away,
//                                             5 = emergency: Home + Back held 5 s → layer off
//                         "V <type> <code> <value>"  a watched control (gyro activation), raw
// stdin, one line each:  "m <profile>" (see wf_parse) · "g <0|l|r> <x> <y>" gyro as a stick ·
//                        "w [k<code>] [a<axis>]…" watch controls · "p <code> <0|1>" virtual
//                        press ("p 0 0" = all up) · "q" = quit cleanly.
//   EOF on stdin = the helper died → quit too (never outlive it).
// On any exit the grab is released and the copy destroyed (the kernel does both anyway if
// we are killed: closing the fds ungrabs and destroys).
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

#include "wfmap.h"

#define NBITS(x) ((((x) - 1) / (8 * sizeof(long))) + 1)
#define TEST(bit, arr) ((arr[(bit) / (8 * sizeof(long))] >> ((bit) % (8 * sizeof(long)))) & 1)

static int src = -1, ufd = -1, grab = 1;
static const char *phys = "wayfinder-clone";
static char src_name[UINPUT_MAX_NAME_SIZE], clone_name[UINPUT_MAX_NAME_SIZE];
static struct input_id src_id, clone_id;
static char clone_node[32];
static int out_full = 1;          // next frame writes the copy's whole state (fresh copy)
static unsigned char watch_key[0x300], watch_abs[0x40];   // reported as "V" lines (gyro activation)

static void out(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void out(const char *fmt, ...) {
    // one line per message: a device name with control characters (a Bluetooth pad can name itself
    // anything) must never split into extra lines the app would read as events or commands
    char b[512];
    va_list ap; va_start(ap, fmt); vsnprintf(b, sizeof b, fmt, ap); va_end(ap);
    for (char *c = b; *c; c++) if ((unsigned char)*c < 0x20 || *c == 0x7f) *c = ' ';
    fputs(b, stdout); putchar('\n'); fflush(stdout);
}

static void app_resend(void);
static void ext_release(void);

static void drop_source(void) { if (src >= 0) { ioctl(src, EVIOCGRAB, 0); close(src); src = -1; } }
static void drop_clone(void) { if (ufd >= 0) { ioctl(ufd, UI_DEV_DESTROY); close(ufd); ufd = -1; } }
static void cleanup(void) { drop_source(); drop_clone(); }
static void on_signal(int s) { (void)s; cleanup(); _exit(0); }

static long now_us(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000L + ts.tv_nsec / 1000;
}
static int cmp_long(const void *a, const void *b) {
    long x = *(const long *)a, y = *(const long *)b; return (x > y) - (x < y);
}

/** Open an input node as a source: its name/ids into src_name/src_id. */
static int open_source(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return -1;
    int clk = CLOCK_MONOTONIC;
    ioctl(fd, EVIOCSCLOCKID, &clk);              // event times comparable with now_us()
    memset(src_name, 0, sizeof src_name); memset(&src_id, 0, sizeof src_id);
    ioctl(fd, EVIOCGNAME(sizeof src_name - 1), src_name);
    ioctl(fd, EVIOCGID, &src_id);
    return fd;
}

/** A uinput device with every capability of the source, set up but NOT created yet. */
static int prepare_clone(int from) {
    int u = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (u < 0) return -1;
    unsigned long ev[NBITS(EV_MAX + 1)], bits[NBITS(KEY_MAX + 1)], props[NBITS(INPUT_PROP_MAX + 1)];
    memset(ev, 0, sizeof ev); ioctl(from, EVIOCGBIT(0, sizeof ev), ev);
    for (int t = 0; t <= EV_MAX; t++) if (TEST(t, ev)) ioctl(u, UI_SET_EVBIT, t);
    if (TEST(EV_KEY, ev)) {
        memset(bits, 0, sizeof bits); ioctl(from, EVIOCGBIT(EV_KEY, sizeof bits), bits);
        for (int k = 0; k <= KEY_MAX; k++) if (TEST(k, bits)) ioctl(u, UI_SET_KEYBIT, k);
    }
    if (TEST(EV_ABS, ev)) {
        memset(bits, 0, sizeof bits); ioctl(from, EVIOCGBIT(EV_ABS, sizeof bits), bits);
        for (int a = 0; a <= ABS_MAX; a++) if (TEST(a, bits)) {
            struct uinput_abs_setup s; memset(&s, 0, sizeof s);
            s.code = a; ioctl(from, EVIOCGABS(a), &s.absinfo);
            ioctl(u, UI_SET_ABSBIT, a); ioctl(u, UI_ABS_SETUP, &s);
        }
    }
    if (TEST(EV_MSC, ev)) {
        memset(bits, 0, sizeof bits); ioctl(from, EVIOCGBIT(EV_MSC, sizeof bits), bits);
        for (int m = 0; m <= MSC_MAX; m++) if (TEST(m, bits)) ioctl(u, UI_SET_MSCBIT, m);
    }
    memset(props, 0, sizeof props); ioctl(from, EVIOCGPROP(sizeof props), props);
    for (int p = 0; p <= INPUT_PROP_MAX; p++) if (TEST(p, props)) ioctl(u, UI_SET_PROPBIT, p);
    ioctl(u, UI_SET_PHYS, phys);
    struct uinput_setup us; memset(&us, 0, sizeof us);
    us.id = src_id;                               // same bus/vendor/product/version → same key layout
    strncpy(us.name, src_name, UINPUT_MAX_NAME_SIZE - 1);
    if (ioctl(u, UI_DEV_SETUP, &us) < 0) { close(u); return -1; }
    memcpy(clone_name, src_name, sizeof clone_name); clone_id = src_id;
    return u;
}

/** After UI_DEV_CREATE: our eventN node (so the caller can find the copy in Android's list). */
static void find_clone_node(void) {
    char sys[64] = "", dir[128];
    clone_node[0] = 0;
    if (ioctl(ufd, UI_GET_SYSNAME(sizeof sys), sys) < 0) return;
    snprintf(dir, sizeof dir, "/sys/devices/virtual/input/%s", sys);
    /* the eventN child can appear a moment after UI_DEV_CREATE (a busy system right after an
       app install: it wasn't there, the app got "?" — release test 2026-09-24): up to ~1 s */
    for (int tries = 0; tries < 20 && !clone_node[0]; tries++) {
        DIR *d = opendir(dir);
        if (d) {
            struct dirent *e;
            while ((e = readdir(d))) if (!strncmp(e->d_name, "event", 5)) { snprintf(clone_node, sizeof clone_node, "%s", e->d_name); break; }
            closedir(d);
        }
        if (!clone_node[0]) usleep(50000);
    }
}

static int create_clone(void) {
    if (ioctl(ufd, UI_DEV_CREATE) < 0) return -1;
    out_full = 1;                                 // a fresh copy: write its whole state
    find_clone_node();
    out("R %s %s", clone_name, clone_node[0] ? clone_node : "?");
    return 0;
}

static int same_identity(void) {
    return !strcmp(src_name, clone_name) && src_id.vendor == clone_id.vendor
        && src_id.product == clone_id.product && src_id.version == clone_id.version;
}

static int read_sys(const char *path, char *buf, size_t n) {
    FILE *f = fopen(path, "re"); if (!f) return -1;
    size_t k = fread(buf, 1, n - 1, f); fclose(f); buf[k] = 0;
    while (k && (buf[k - 1] == '\n' || buf[k - 1] == ' ')) buf[--k] = 0;
    return 0;
}

// ---- mapping engine state ------------------------------------------------------------------
static wf_state S;                // AYN's pad (current source), printed codes
static wf_map M;                  // the app's profile (kept across sources and copies)
static wf_out OUT;                // what the copy currently shows

/** Capabilities + current state of a (new) source. */
static void init_state(int fd) {
    memset(&S, 0, sizeof S);
    unsigned long kb[NBITS(KEY_MAX + 1)] = {0}, ab[NBITS(ABS_MAX + 1)] = {0}, ks[NBITS(KEY_MAX + 1)] = {0};
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof kb), kb);
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof ab), ab);
    for (int k = 0; k < WF_NK && k <= KEY_MAX; k++) if (TEST(k, kb)) S.keys[S.nk++] = (unsigned short)k;
    for (int a = 0; a < WF_NA && a <= ABS_MAX; a++) if (TEST(a, ab)) {
        S.axes[S.na++] = (unsigned char)a;
        ioctl(fd, EVIOCGABS(a), &S.info[a]);
    }
    S.xbox = src_id.product == 0x0112;
    wf_state_rest(&S);
    for (int i = 0; i < S.na; i++) S.abs[S.axes[i]] = S.info[S.axes[i]].value;
    // keys already held (e.g. AYN rebuilt its pad mid-press): through the engine, so a held
    // Home opens the gate as it should
    S.shift = M.shift; S.back_free = M.back_free;
    ioctl(fd, EVIOCGKEY(sizeof ks), ks);
    for (int i = 0; i < S.nk; i++) if (TEST(S.keys[i], ks)) { int p; wf_event(&S, EV_KEY, S.keys[i], 1, &p); }
}

/** Events were dropped (SYN_DROPPED): re-read the pad's state, but keep what Wayfinder set —
 *  toggle latches, the gyro stick, its held virtual presses. A plain re-init wiped them: the gyro
 *  stayed dead until it moved, macro keys were silently released (review 2026-09-25). */
static void resync_state(int fd) {
    ext_release();
    unsigned char latch[WF_NK], vkey[WF_NK];
    memcpy(latch, S.latch, sizeof latch); memcpy(vkey, S.vkey, sizeof vkey);
    int gs = S.gyro_stick, gx = S.gyro_x, gy = S.gyro_y;
    init_state(fd);
    memcpy(S.latch, latch, sizeof latch); memcpy(S.vkey, vkey, sizeof vkey);
    S.gyro_stick = gs; S.gyro_x = gx; S.gyro_y = gy;
}

/** 1.3: before the source state is thrown away (pad rebuilt, events dropped): a release for every button
 *  and D-pad direction Wayfinder plays that is down now, so no keyboard key stays held. */
static void ext_release(void) {
    for (int i = 0; i < S.nk; i++) { int p = S.keys[i]; if (S.key[p] && M.keymap[p] == WF_EXT) out("X %d 0", p); }
    static const int DIRS[4] = { WF_HAT_UP, WF_HAT_DOWN, WF_HAT_LEFT, WF_HAT_RIGHT };
    for (int i = 0; i < 4; i++) if (M.keymap[DIRS[i]] == WF_EXT && wf_dir_on(S.abs[WF_HX], S.abs[WF_HY], DIRS[i])) out("X %d 0", DIRS[i]);
}

/** Write the copy's new state: only what changed (everything after a fresh copy). */
static void emit(void) {
    if (ufd < 0) return;
    S.now_ms = now_us() / 1000;
    wf_out n; wf_compute(&S, &M, &n);
    struct input_event ev[160]; int c = 0;
    memset(ev, 0, sizeof ev);
    for (int i = 0; i < S.nk && c < 150; i++) {
        int k = S.keys[i];
        if (out_full || n.key[k] != OUT.key[k]) { ev[c].type = EV_KEY; ev[c].code = (unsigned short)k; ev[c].value = n.key[k]; c++; }
    }
    for (int i = 0; i < S.na && c < 158; i++) {
        int a = S.axes[i];
        if (out_full || n.abs[a] != OUT.abs[a]) { ev[c].type = EV_ABS; ev[c].code = (unsigned short)a; ev[c].value = n.abs[a]; c++; }
    }
    if (c) {
        ev[c].type = EV_SYN; ev[c].code = SYN_REPORT; c++;
        if (write(ufd, ev, c * sizeof *ev) < 0 && errno != EAGAIN) out("E write: %s", strerror(errno));
    }
    OUT = n; out_full = 0;
}

/** The source is gone: the game sees everything released (no stuck keys across AYN rebuilds). */
static void source_rest(void) { ext_release(); wf_state_rest(&S); emit(); }

/** 1.3 (GitHub #4): AYN re-emits every EXTERNAL pad under its own ids (2020:0111) with the external's
 *  name — that copy is the player's controller, never AYN's pad. True if a non-AYN device has [name]. */
static int foreign_name(const char *name) {
    DIR *d = opendir("/sys/class/input"); if (!d) return 0;
    struct dirent *e; int hit = 0; char p[160], v[128];
    while (!hit && (e = readdir(d))) {
        if (strncmp(e->d_name, "event", 5)) continue;
        snprintf(p, sizeof p, "/sys/class/input/%s/device/id/vendor", e->d_name);
        if (read_sys(p, v, sizeof v) < 0 || strtol(v, NULL, 16) == 0x2020) continue;
        snprintf(p, sizeof p, "/sys/class/input/%s/device/name", e->d_name);
        if (read_sys(p, v, sizeof v) == 0 && !strcmp(v, name)) hit = 1;
    }
    closedir(d);
    return hit;
}

/** Look for AYN's (new) pad: same vendor as the copy, not the copy itself. Grab it. */
static int find_source(void) {
    DIR *d = opendir("/sys/class/input"); if (!d) return -1;
    struct dirent *e; int found = -1;
    while ((e = readdir(d)) && found < 0) {
        if (strncmp(e->d_name, "event", 5) || !strcmp(e->d_name, clone_node)) continue;
        char p[160], v[64];
        snprintf(p, sizeof p, "/sys/class/input/%s/device/phys", e->d_name);
        if (read_sys(p, v, sizeof v) == 0 && !strcmp(v, phys)) continue;
        snprintf(p, sizeof p, "/sys/class/input/%s/device/id/vendor", e->d_name);
        if (read_sys(p, v, sizeof v) < 0 || strtol(v, NULL, 16) != clone_id.vendor) continue;
        {   // an external pad's AYN copy (2020:0111 under a name a non-AYN device has): not AYN's pad
            char nm[128];
            snprintf(p, sizeof p, "/sys/class/input/%s/device/id/product", e->d_name);
            long prod = read_sys(p, v, sizeof v) == 0 ? strtol(v, NULL, 16) : 0;
            snprintf(p, sizeof p, "/sys/class/input/%s/device/name", e->d_name);
            if (prod != 0x0112 && read_sys(p, nm, sizeof nm) == 0 && foreign_name(nm)) continue;
        }
        snprintf(p, sizeof p, "/dev/input/%s", e->d_name);
        int fd = open_source(p);                  // may fail for a moment: node not made yet
        if (fd < 0) continue;
        // A PAD: AYN also has "ODIN Station Virtual Mouse" under the same vendor id.
        unsigned long ev[NBITS(EV_MAX + 1)] = {0}, keys[NBITS(KEY_MAX + 1)] = {0};
        ioctl(fd, EVIOCGBIT(0, sizeof ev), ev); ioctl(fd, EVIOCGBIT(EV_KEY, sizeof keys), keys);
        if (!TEST(EV_ABS, ev) || !TEST(BTN_SOUTH, keys)) { close(fd); continue; }
        if (grab && ioctl(fd, EVIOCGRAB, 1) < 0) { close(fd); continue; }
        src = fd; found = 0;
        init_state(fd); emit();
        out("S %s %s", src_name, e->d_name);
    }
    closedir(d);
    return found;
}

/** Leaving the wait for AYN's next pad: forget the old source, release everything. */
static void lose_source(long *waiting_since, long *mismatch_since) {
    drop_source(); source_rest();
    *mismatch_since = 0; *waiting_since = now_us(); out("W");
}

/** One command line from the helper. Returns 0 to quit. */
static int command(char *line) {
    if (line[0] == 'q') return 0;
    // g <0|l|r> <x> <y> — the gyro as a stick (Wayfinder's gyro engine, ~200 per second)
    if (line[0] == 'g' && line[1] == ' ') {
        char which = 0; int x = 0, y = 0;
        if (sscanf(line + 2, "%c %d %d", &which, &x, &y) == 3 && (which == '0' || which == 'l' || which == 'r')
            && x >= -32767 && x <= 32767 && y >= -32767 && y <= 32767) {
            S.gyro_stick = which == 'l' ? 1 : which == 'r' ? 2 : 0; S.gyro_x = x; S.gyro_y = y; emit();
        }
        return 1;
    }
    // p <code> <0|1> — a virtual press from Wayfinder (macros, combos…); "p 0 0" = release all
    if (line[0] == 'p' && line[1] == ' ') {
        int code = -1, v = -1;
        if (sscanf(line + 2, "%i %d", &code, &v) == 2 && wf_vpress(&S, code, v) == 0) emit();
        return 1;
    }
    // w [k<code>] [a<axis>]… — controls whose raw state Wayfinder needs ("V" lines): the gyro's
    // "while holding / toggle / trigger pull" button. "w" alone = none.
    if (line[0] == 'w' && (line[1] == ' ' || line[1] == 0)) {
        memset(watch_key, 0, sizeof watch_key); memset(watch_abs, 0, sizeof watch_abs);
        char buf[512]; strncpy(buf, line + 1, sizeof buf - 1); buf[sizeof buf - 1] = 0;
        for (char *save = 0, *t = strtok_r(buf, " ", &save); t; t = strtok_r(0, " ", &save)) {
            long n = strtol(t + 1, NULL, 0);
            if (t[0] == 'k' && n > 0 && n < 0x300) watch_key[n] = 1;
            if (t[0] == 'a' && n >= 0 && n < 0x40) watch_abs[n] = 1;
        }
        return 1;
    }
    if (line[0] == 'm' && (line[1] == ' ' || line[1] == 0)) {
        wf_map m;
        if (wf_parse(&m, &S, line + 1) == 0) {
            M = m; memset(S.latch, 0, sizeof S.latch); memset(S.vkey, 0, sizeof S.vkey);
            // a new shift button (or none): the gate follows what's held now
            S.shift = M.shift; S.shift_tap_until = 0; S.back_free = M.back_free;
            S.gated = wf_gate(&S);
            emit(); out("M ok");
            app_resend();                              // Wayfinder released everything: say where the sticks are
        }
        else out("M bad");
    }
    return 1;
}

/** 1.3 — the sticks that are Wayfinder's: their position, when it changed ("T" lines). A move is
 *  sent at most every 8 ms (the app samples it for the mouse / wheel); a return to 0 at once. */
static int app_last[2][2];
/** The next [app_sticks] sends both positions again (after Wayfinder released everything). */
static void app_resend(void) { app_last[0][0] = app_last[1][0] = -99999; }
static long app_at[2];
static int app_pending;
static void app_sticks(long t_us) {
    app_pending = 0;
    for (int side = 0; side < 2; side++) {
        int x, y; wf_app_stick(&S, &M, side, &x, &y);
        if (x == app_last[side][0] && y == app_last[side][1]) continue;
        if ((x || y) && t_us - app_at[side] < 8000) { app_pending = 1; continue; }
        app_last[side][0] = x; app_last[side][1] = y; app_at[side] = t_us;
        out("T %d %d %d", side, x, y);
    }
}

int main(int argc, char **argv) {
    if (argc < 5) { out("E usage"); return 1; }
    grab = strcmp(argv[2], "grab") == 0;
    phys = argv[3];
    int race_ms = atoi(argv[4]);
    signal(SIGTERM, on_signal); signal(SIGINT, on_signal); signal(SIGPIPE, on_signal);
    wf_map_reset(&M);

    int ino = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
    if (ino >= 0) inotify_add_watch(ino, "/dev/input", IN_CREATE);
    src = open_source(argv[1]);
    if (src < 0) { out("E open %s: %s", argv[1], strerror(errno)); return 2; }
    init_state(src);
    ufd = prepare_clone(src);
    if (ufd < 0) { out("E uinput: %s", strerror(errno)); cleanup(); return 1; }

    if (race_ms > 0) {
        // Takeover: busy-wait at real-time priority until AYN's pad goes, then create at once.
        struct sched_param sp = { .sched_priority = 50 };
        int rt = sched_setscheduler(0, SCHED_FIFO, &sp) == 0;
        out("A %s", src_name);
        long end = now_us() + race_ms * 1000L;
        struct pollfd pf = { .fd = src, .events = POLLIN };
        struct input_event junk[32];
        int gone = 0;
        while (now_us() < end) {
            if (poll(&pf, 1, 0) <= 0) continue;
            if (pf.revents & (POLLHUP | POLLERR | POLLNVAL)) { gone = 1; break; }
            if ((pf.revents & POLLIN) && read(src, junk, sizeof junk) < 0 && errno == ENODEV) { gone = 1; break; }
        }
        if (gone && create_clone() < 0) { out("E create: %s", strerror(errno)); cleanup(); return 1; }
        sp.sched_priority = 0;
        if (rt) sched_setscheduler(0, SCHED_OTHER, &sp);
        if (!gone) { out("E no-switch"); cleanup(); return 4; }
        drop_source(); source_rest();
    } else {
        if (create_clone() < 0) { out("E create: %s", strerror(errno)); cleanup(); return 1; }
        if (grab && ioctl(src, EVIOCGRAB, 1) < 0) { out("E grab: %s", strerror(errno)); cleanup(); return 1; }
        emit();
        out("S %s %s", src_name, argv[1] + strlen("/dev/input/"));
    }
    setpriority(PRIO_PROCESS, 0, -10);            // forwarding: ahead of ordinary app threads

    // added latency: source event time → our write, per SYN_REPORT frame
    enum { NS = 4096 };
    static long lat[NS]; int nl = 0; long last_report = now_us();
    long waiting_since = src < 0 ? now_us() : 0, mismatch_since = 0, both_since = 0;
    int dropped = 0;
    if (src < 0) { out("W"); find_source(); if (src >= 0) waiting_since = 0; }
    struct input_event buf[64];
    char cmd[4096]; size_t cl = 0;
    for (;;) {
        struct pollfd pf[3] = { { .fd = 0, .events = POLLIN }, { .fd = ino, .events = POLLIN }, { .fd = src, .events = POLLIN } };
        int turbo = src >= 0 && (wf_turbo_active(&S, &M) || S.shift_tap_until);   // a shift tap needs its release on time
        int tmo = turbo ? 10 : app_pending ? 8 : src < 0 || mismatch_since || both_since ? 250 : 1000;
        int r = poll(pf, src >= 0 ? 3 : 2, tmo);
        if (r < 0) { if (errno == EINTR) continue; break; }
        if (pf[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t k = read(0, cmd + cl, sizeof cmd - 1 - cl);
            if (k <= 0) break;                         // EOF = the helper is gone
            cl += (size_t)k; cmd[cl] = 0;
            int quit = 0; char *nl_at;
            while (!quit && (nl_at = strchr(cmd, '\n'))) {
                *nl_at = 0;
                if (!command(cmd)) quit = 1;
                size_t used = (size_t)(nl_at - cmd) + 1;
                memmove(cmd, cmd + used, cl - used + 1); cl -= used;
            }
            if (quit) break;
            if (cl >= sizeof cmd - 1) cl = 0;          // an over-long line: drop it
        }
        if (pf[1].revents & POLLIN) { char b[1024]; while (read(ino, b, sizeof b) > 0) {} }
        if (src >= 0 && (pf[2].revents & (POLLERR | POLLHUP | POLLNVAL))) {
            lose_source(&waiting_since, &mismatch_since);
        } else if (src >= 0 && (pf[2].revents & POLLIN)) {
            ssize_t n = read(src, buf, sizeof buf);
            if (n <= 0) {
                if (!(n < 0 && errno == EAGAIN)) lose_source(&waiting_since, &mismatch_since);
            } else {
                size_t cnt = (size_t)n / sizeof(struct input_event);
                for (size_t i = 0; i < cnt; i++) {
                    struct input_event *e = &buf[i];
                    if (e->type == EV_SYN && e->code == SYN_DROPPED) { dropped = 1; continue; }
                    if (e->type == EV_SYN && e->code == SYN_REPORT) {
                        if (dropped) { resync_state(src); dropped = 0; }   // resync from the kernel's state
                        emit();
                        long src_us = e->input_event_sec * 1000000L + e->input_event_usec;
                        if (nl < NS) lat[nl++] = now_us() - src_us;
                        continue;
                    }
                    if (dropped || (e->type != EV_KEY && e->type != EV_ABS)) continue;
                    int p;
                    if (e->type == EV_KEY) S.now_ms = now_us() / 1000;   // before: the shift tap is timed from it
                    int hat_old = e->type == EV_ABS && (e->code == WF_HX || e->code == WF_HY) ? S.abs[e->code] : 0;
                    if (wf_event(&S, e->type, e->code, e->value, &p)) out("J %d %d %d", e->type, p, e->value);
                    if (e->type == EV_KEY && wf_ext_event(&S, &M, p, e->value)) out("X %d %d", p, e->value);
                    if (e->type == EV_ABS && (e->code == WF_HX || e->code == WF_HY)) {   // 1.3: D-pad directions Wayfinder plays
                        int dc[4], dv[4], dn = wf_dir_ext(&S, &M, e->code, hat_old, e->value, dc, dv);
                        for (int j = 0; j < dn; j++) out("X %d %d", dc[j], dv[j]);
                    }
                    if ((e->type == EV_KEY && p >= 0 && p < 0x300 && watch_key[p] && e->value != 2) ||
                        (e->type == EV_ABS && e->code < 0x40 && watch_abs[e->code])) out("V %d %d %d", e->type, p, e->value);
                    if (e->type == EV_KEY) { S.now_ms = now_us() / 1000; wf_fire_edge(&S, &M, p, e->value); }
                }
            }
        }
        long t = now_us();
        if (turbo) { S.now_ms = t / 1000; emit(); }       // turbo: the output flips with time
        app_sticks(t);                                    // 1.3: sticks that are Wayfinder's
        if (S.shift_tap_until && S.now_ms >= S.shift_tap_until) S.shift_tap_until = 0;   // the tap is over (released above)
        // Emergency exit: Home + Back held together for 5 s → the layer turns itself off.
        if (src >= 0 && S.key[WF_HOME] && S.key[WF_BACK]) {
            if (!both_since) both_since = t;
            else if (t - both_since > 5000000L) { out("E emergency-off"); cleanup(); return 5; }
        } else both_since = 0;
        if (src < 0) {
            if (find_source() == 0) { waiting_since = 0; if (!same_identity()) mismatch_since = t; }
            else if (t - waiting_since > 10000000L) { out("E source lost"); cleanup(); return 2; }
        }
        // AYN's pad now has another identity (Odin ↔ Xbox) and kept it: re-make the copy as
        // that identity, destroy + create back to back so it takes the number it frees.
        if (src >= 0 && mismatch_since && t - mismatch_since > 2000000L) {
            mismatch_since = 0;
            if (!same_identity()) {
                int u = prepare_clone(src);
                if (u < 0) { out("E uinput: %s", strerror(errno)); break; }
                drop_clone(); ufd = u;
                if (create_clone() < 0) { out("E create: %s", strerror(errno)); break; }
                emit();
            }
        }
        if (src >= 0 && !mismatch_since && !same_identity()) mismatch_since = t;
        if (t - last_report >= 5000000L) {
            if (nl > 0) {
                qsort(lat, nl, sizeof(long), cmp_long);
                out("L %d %ld %ld %ld", nl, lat[nl / 2], lat[(nl * 99) / 100], lat[nl - 1]);
            }
            nl = 0; last_report = t;
        }
    }
    cleanup();
    return 0;
}
