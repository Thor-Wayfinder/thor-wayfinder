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
// its pad again (per-game layout, sleep…) wfpad just grabs the new one (b18n review: what the old one held is kept till
// then, at most 1 s — lose_source); the copy is only
// re-made if AYN's identity really changed (Odin ↔ Xbox for > 2 s), and then it is destroyed
// and re-created back to back, so it takes the number it just freed.
//
// stdout, one line each:  "A <name>"         armed, waiting for AYN's pad to go (takeover)
//                         "R <name> <eventN>" copy created (its node)
//                         "S <name> <eventN>" a (new) AYN pad grabbed as the source
//                         "W"                 source gone, copy kept, waiting for AYN's next pad
//                         "L <n> <p50_us> <p99_us> <max_us>"  every 5 s: added latency
//                         "J <type> <code> <value>"  an event withheld from the game (gate):
//                                             for Wayfinder's shortcuts; keys as PRINTED codes (b18l review:
//                                             never the release of a button held before the gate opened —
//                                             the copy let it go then)
//                         "X <code> <1|0>"    a button mapped to a keyboard key / action
//                                             (printed code; 0x220..0x223 = a D-pad direction): Wayfinder performs it
//                         "T <0|1> <x> <y>"   1.3: a stick that is Wayfinder's (0 left, 1 right): its
//                                             shaped position, −1000..1000, at most every 8 ms
//                         "M ok|bad"          answer to a profile line
//                         "E <reason>"        fatal; then exit: 2 = no source for 10 s,
//                                             4 = takeover: AYN's pad never went away,
//                                             5 = emergency: Home + Back held 5 s → layer off
//                         "V <type> <code> <value>"  a watched control (gyro activation), raw
//                         "TU <code> <1|0> <ms>"  b18o: a hand holds (1) / let go of (0) a turbo button whose job is the
//                                             copy's button <code> (printed), at <ms> (CLOCK_MONOTONIC, the key events'
//                                             clock) — its on / off pulses are no presses (fx/wfmap.h wf_turbo_held)
// stdin, one line each:  "m <profile>" (see wf_parse) · "g <0|l|r> <x> <y>" gyro as a stick ·
//                        "w [k<code>] [a<axis>]…" watch controls · "p <code> <0|1>" virtual
//                        press ("p 0 0" = all up) · "q" = quit cleanly ·
//                        "s <eventN>" docked: the copy is fed by Wayfinder's pad for an external controller (wfhid,
//                        phys "wayfinder-ext-N") instead of AYN's pad — the SAME copy (name, number, buttons) for games,
//                        so nothing is re-made and a running game keeps its controller · "s -" back to AYN's pad.
//                        dev build 18f: "s <eventN> m" = the MERGED feed (a controller that plays as the Thor's, undocked):
//                        AYN's pad AND that pad both drive the copy, one engine on the merged stream (fx/wfmerge.h) — the
//                        Thor's own buttons keep working. Sent for the node already fed: the other mode switches in place
//                        (no second grab); for another node: that one, in the new request's mode. Any other trailing word
//                        = "E feed bad node".
//                        Replies "F on <eventN>" (docked: AYN's pad drained) / "F on <eventN> m" (merged) / "F off" (also
//                        when that pad goes away).
//   EOF on stdin = the helper died → quit too (never outlive it).
//
// Dev build 13 — argv[6] "uhid": about 1.5 s after the copy is made (AYN's pad settled at #2), the uinput copy is
// swapped for a UHID one with a serial (fx/wfuhid.h): destroyed and re-made back to back, it takes the #1 it frees, and
// with a serial Android's descriptor for it never changes (uinput can't carry one). Lines "I copy uhid" / "I copy uinput
// <why>" say which it is; on anything the UHID copy can't do (a code it can't express, the device not appearing, /dev/uhid
// failing) the uinput copy comes back for the rest of the run. A second "R" line announces the swapped copy.
// argv[7] "uniq=<serial>" (tests: a copy that isn't the real one).
// On any exit the grab is released and the copy destroyed (the kernel does both anyway if
// we are killed: closing the fds ungrabs and destroys).
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uhid.h>
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
#include "wfmerge.h"
#include "wfuhid.h"

#define NBITS(x) ((((x) - 1) / (8 * sizeof(long))) + 1)
#define TEST(bit, arr) ((arr[(bit) / (8 * sizeof(long))] >> ((bit) % (8 * sizeof(long)))) & 1)

static int src = -1, ufd = -1, grab = 1;
/* docked (ExtPads): the external controller's pad feeding the copy; its axes' ranges, to rescale into AYN's */
static int xsrc = -1;
static struct input_absinfo xinfo[ABS_MAX + 1];
static char xnode[32];
/* dev build 18f: the merged feed ("s <eventN> m"; only while xsrc ≥ 0) — each source's state (fx/wfmerge.h), and events
   dropped on each (SYN_DROPPED: both re-read at its SYN_REPORT) */
static int xmerge;
static wf_merge MG;
static int dropped2[2];
static const char *phys = "wayfinder-clone";
/* argv[5] "named": the copy SHOWS "<name> (Wayfinder)" (games list the hidden original too — Eden: two "Odin Controller")
   — the identity compared with a new AYN pad (clone_name) stays the original name. Opt-in: emulators keyed saved
   mappings on the name. */
static int named;
static char src_name[UINPUT_MAX_NAME_SIZE], clone_name[UINPUT_MAX_NAME_SIZE];
static struct input_id src_id, clone_id;
static char clone_node[32];
static int out_full = 1;          // next frame writes the copy's whole state (fresh copy)
// the copy as UHID (dev build 13): hfd ≥ 0 = it is (ufd is then -1)
static int want_uhid = 0, uhid_failed = 0, hfd = -1;
static wfu_layout HL;
static unsigned char hlast[WFU_MAXREP];
static int hlast_ok = 0;
static const char *uniq = "wayfinder-thor";      // the serial: the same on every Thor, so its descriptor is too
static long copy_made_us = 0, src_since_us = 0;  // when the copy / the current source appeared
static int test_hooks = 0, test_uhid_fail = 0;    // argv "test-hooks" / "test-uhidfail": failure paths, for tests
static const char *test_src_name = NULL;          // argv "test-src=<name>": a test instance takes only that pad
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
static void resync_from_x(const wf_state *old);
static void merge_resync(const wf_state *old);
static void merge_drop(int which);

static int src_input = -1;               // sysfs number of the current source (input_num)
static void drop_source(void) { if (src >= 0) { ioctl(src, EVIOCGRAB, 0); close(src); src = -1; } src_input = -1; }
static void drop_uhid(void) {
    if (hfd < 0) return;
    struct uhid_event e; memset(&e, 0, sizeof e); e.type = UHID_DESTROY;
    if (write(hfd, &e, sizeof e) < 0) { /* the kernel destroys it when the fd closes anyway */ }
    close(hfd); hfd = -1; hlast_ok = 0;
    clone_node[0] = 0;          // its eventN is free now — AYN's next pad may get it (never "the copy" then)
}
static void drop_clone(void) { if (ufd >= 0) { ioctl(ufd, UI_DEV_DESTROY); close(ufd); ufd = -1; clone_node[0] = 0; } drop_uhid(); }
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
    /* AYN's ids, always: AYN's mapping service grabs (and re-emits under 0111) any gamepad that isn't 2020:0111 / 0112
       while its external slot is free — a copy under its own id went dead (tried 2026-10-06) */
    if (named) snprintf(us.name, UINPUT_MAX_NAME_SIZE, "%.66s (Wayfinder)", src_name);
    else strncpy(us.name, src_name, UINPUT_MAX_NAME_SIZE - 1);
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
    copy_made_us = now_us();
    find_clone_node();
    out("R %s %s", clone_name, clone_node[0] ? clone_node : "?");
    return 0;
}

/* ---- the copy as UHID (dev build 13, fx/wfuhid.h) ---- */
static int read_sys(const char *path, char *buf, size_t n);
static void emit(void);

/** A uinput copy of the source, made and filled — or none at all (ufd -1), never a half-made one. 0 = made. */
static int make_uinput_copy(void) {
    if (src < 0) return -1;
    if ((ufd = prepare_clone(src)) < 0) return -1;
    if (create_clone() < 0) { close(ufd); ufd = -1; return -1; }
    emit();
    return 0;
}

/** The UHID copy's layout from the source (the same capabilities the uinput copy takes). 0 = it can. */
static int uhid_plan(int from, char *why, int whyn) {
    unsigned long ev[NBITS(EV_MAX + 1)], keys[NBITS(KEY_MAX + 1)], abs[NBITS(ABS_MAX + 1)];
    static struct input_absinfo info[ABS_MAX + 1];
    memset(ev, 0, sizeof ev); memset(keys, 0, sizeof keys); memset(abs, 0, sizeof abs); memset(info, 0, sizeof info);
    ioctl(from, EVIOCGBIT(0, sizeof ev), ev);
    ioctl(from, EVIOCGBIT(EV_KEY, sizeof keys), keys);
    ioctl(from, EVIOCGBIT(EV_ABS, sizeof abs), abs);
    for (int a = 0; a <= ABS_MAX; a++) if (TEST(a, abs)) ioctl(from, EVIOCGABS(a), &info[a]);
    unsigned long props[NBITS(INPUT_PROP_MAX + 1)]; memset(props, 0, sizeof props);
    ioctl(from, EVIOCGPROP(sizeof props), props);
    for (int p = 0; p <= INPUT_PROP_MAX; p++) if (TEST(p, props)) { snprintf(why, (size_t)whyn, "property %d", p); return -1; }
    return wfu_plan(&HL, ev, keys, abs, info, why, whyn);
}

/** Our UHID copy's eventN: the input device at our location with our serial (the newest, if two). */
/** sysfs's number for an input device ("input366" → 366): it only ever grows, so the highest is the newest. -1 = none. */
static int input_num(const char *ev) {
    char p[96], l[192];
    snprintf(p, sizeof p, "/sys/class/input/%s/device", ev);
    ssize_t k = readlink(p, l, sizeof l - 1);
    if (k <= 0) return -1;
    l[k] = 0;
    const char *b = strrchr(l, '/'); b = b ? b + 1 : l;
    return strncmp(b, "input", 5) ? -1 : atoi(b + 5);
}

/** The highest sysfs input number now (a device made later has a higher one). */
static int max_input(void) {
    DIR *d = opendir("/sys/class/input"); if (!d) return -1;
    struct dirent *e; int best = -1;
    while ((e = readdir(d))) if (!strncmp(e->d_name, "input", 5)) { int n = atoi(e->d_name + 5); if (n > best) best = n; }
    closedir(d);
    return best;
}

/** Our UHID copy's eventN: phys + serial, and made after [above] (another instance's copy may still be there). */
static int find_uhid_node(int above) {
    for (int tries = 0; tries < 60; tries++) {   // ≤ 1.5 s: the kernel makes it in the background
        DIR *d = opendir("/sys/class/input"); int best = -1; char node[32] = "";
        if (d) {
            struct dirent *e; char p[160], v[96];
            while ((e = readdir(d))) {
                if (strncmp(e->d_name, "event", 5)) continue;
                snprintf(p, sizeof p, "/sys/class/input/%s/device/phys", e->d_name);
                if (read_sys(p, v, sizeof v) < 0 || strcmp(v, phys)) continue;
                snprintf(p, sizeof p, "/sys/class/input/%s/device/uniq", e->d_name);
                if (read_sys(p, v, sizeof v) < 0 || strcmp(v, uniq)) continue;
                int num = input_num(e->d_name);    // the newest, and newer than the create
                if (num > above && num > best) { best = num; snprintf(node, sizeof node, "%s", e->d_name); }
            }
            closedir(d);
        }
        if (best >= 0) {
            snprintf(clone_node, sizeof clone_node, "%s", node);
            // no kernel key repeat (HID keyboards get it; Android turns it off too when it opens the device)
            char path[48]; snprintf(path, sizeof path, "/dev/input/%s", clone_node);
            int fd = open(path, O_RDWR | O_CLOEXEC);
            if (fd >= 0) { unsigned int rep[2] = { 0, 0 }; ioctl(fd, EVIOCSREP, rep); close(fd); }
            return 0;
        }
        usleep(25000);
    }
    return -1;
}

/** Make the UHID copy of [from] (its plan already in HL). 0 = made and found. */
static int uhid_create(int from, char *why, int whyn) {
    (void)from;
    unsigned char desc[WFU_MAXDESC];
    int dl = wfu_descriptor(&HL, desc, sizeof desc);
    if (dl > (int)sizeof desc || dl > HID_MAX_DESCRIPTOR_SIZE) { snprintf(why, (size_t)whyn, "descriptor too long"); return -1; }
    int fd = open("/dev/uhid", O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) { snprintf(why, (size_t)whyn, "open /dev/uhid: %s", strerror(errno)); return -1; }
    struct uhid_event e; memset(&e, 0, sizeof e);
    e.type = UHID_CREATE2;
    if (named) snprintf((char *)e.u.create2.name, sizeof e.u.create2.name, "%.66s (Wayfinder)", src_name);
    else snprintf((char *)e.u.create2.name, sizeof e.u.create2.name, "%s", src_name);
    snprintf((char *)e.u.create2.phys, sizeof e.u.create2.phys, "%s", phys);
    snprintf((char *)e.u.create2.uniq, sizeof e.u.create2.uniq, "%s", uniq);
    e.u.create2.rd_size = (unsigned short)dl; memcpy(e.u.create2.rd_data, desc, (size_t)dl);
    e.u.create2.bus = src_id.bustype; e.u.create2.vendor = src_id.vendor; e.u.create2.product = src_id.product;
    e.u.create2.version = src_id.version; e.u.create2.country = 0;
    int above = max_input();
    if (write(fd, &e, sizeof e) != (ssize_t)sizeof e) { snprintf(why, (size_t)whyn, "create: %s", strerror(errno)); close(fd); return -1; }
    hfd = fd; hlast_ok = 0;
    memcpy(clone_name, src_name, sizeof clone_name); clone_id = src_id;
    if (test_uhid_fail || find_uhid_node(above) < 0) { snprintf(why, (size_t)whyn, test_uhid_fail ? "test" : "no input device"); drop_uhid(); return -1; }
    out_full = 1; copy_made_us = now_us();
    return 0;
}

/** The kernel asks things of a HID device (feature reports…): answered "no" at once (an unanswered GET_REPORT would
 *  block the asker ~5 s). The UHID fd failing = the copy is gone: back to uinput. */
static void uhid_service(short revents) {
    if (hfd < 0) return;
    if (revents & (POLLERR | POLLHUP | POLLNVAL)) {
        out("I copy uinput uhid-closed"); drop_uhid(); uhid_failed = 1;
        make_uinput_copy();
        return;
    }
    if (!(revents & POLLIN)) return;
    struct uhid_event in;
    while (read(hfd, &in, sizeof in) > 0) {
        struct uhid_event r; memset(&r, 0, sizeof r);
        if (in.type == UHID_GET_REPORT) {
            r.type = UHID_GET_REPORT_REPLY; r.u.get_report_reply.id = in.u.get_report.id; r.u.get_report_reply.err = EIO;
        } else if (in.type == UHID_SET_REPORT) {
            r.type = UHID_SET_REPORT_REPLY; r.u.set_report_reply.id = in.u.set_report.id; r.u.set_report_reply.err = EIO;
        } else continue;                          // START / STOP / OPEN / CLOSE / OUTPUT: nothing to do
        if (write(hfd, &r, sizeof r) < 0) { /* the asker times out */ }
    }
}

/** The uinput copy → the UHID one (back to back: it takes the number the uinput copy frees). On failure the uinput copy
 *  comes back and stays for this run. */
static void to_uhid(void) {
    if (!want_uhid || uhid_failed || hfd >= 0 || ufd < 0 || src < 0) return;
    char why[80] = "";
    if (uhid_plan(src, why, sizeof why) < 0) { uhid_failed = 1; out("I copy uinput %s", why); return; }   // nothing touched
    ioctl(ufd, UI_DEV_DESTROY); close(ufd); ufd = -1;
    if (uhid_create(src, why, sizeof why) == 0) {
        emit();
        out("R %s %s", clone_name, clone_node);
        out("I copy uhid %s", uniq);
        return;
    }
    uhid_failed = 1; out("I copy uinput %s", why);
    make_uinput_copy();
}

/** AYN's identity changed (Odin ↔ Xbox): the copy made again as it, back to back (it takes the number it frees). */
static int remake_copy(void) {
    if (hfd >= 0) {
        char why[80] = "";
        drop_uhid();
        if (uhid_plan(src, why, sizeof why) == 0 && uhid_create(src, why, sizeof why) == 0) {
            emit(); out("R %s %s", clone_name, clone_node); out("I copy uhid %s", uniq); return 0;
        }
        uhid_failed = 1; out("I copy uinput %s", why);
        return make_uinput_copy();
    }
    int u = prepare_clone(src);
    if (u < 0) return -1;
    drop_clone(); ufd = u;
    if (create_clone() < 0) return -1;
    emit(); return 0;
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
static wf_state PREV;             // b18n: the state before a resync, for its replay's order ([before], [replay_held])

/** b18n: the state till now, kept for a resync's replay (each resync resets S first). */
static const wf_state *before(void) { PREV = S; return &PREV; }

/** b18n: the keys held now ([ks]: a kernel's EVIOCGKEY bits) through the engine as presses — in the order they went down,
 *  as far as [old] (the state before the resync, NULL = none) tells it (fx/wfmap.h [wf_replay]). In code order Back came
 *  before Select: a Select + Back that went with the game gated on the replay and withheld its Select (b18h, WI-4). */
static void replay_held(const unsigned long *ks, const wf_state *old) {
    unsigned char held[WF_NK] = {0};
    for (int i = 0; i < S.nk; i++) if (TEST(S.keys[i], ks)) held[S.keys[i]] = 1;
    wf_replay(&S, held, old);
}

/** Capabilities + current state of a (new) source. [old]: the state before ([before]), or NULL. */
static void init_state(int fd, const wf_state *old) {
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
    wf_state_map(&S, &M);                         // its shift button, bg=1, what the copy shows of each key (b18h)
    ioctl(fd, EVIOCGKEY(sizeof ks), ks);
    replay_held(ks, old);
}

/** Events were dropped (SYN_DROPPED): re-read the pad's state, but keep what Wayfinder set —
 *  toggle latches, the gyro stick, its held virtual presses. A plain re-init wiped them: the gyro
 *  stayed dead until it moved, macro keys were silently released (review 2026-09-25). b18n review: also AYN's pad re-made
 *  with what it held kept ([find_source]). Returns the state before (for a fed pad's resync after it). */
static const wf_state *resync_state(int fd) {
    ext_release();
    unsigned char latch[WF_NK], vkey[WF_NK];
    memcpy(latch, S.latch, sizeof latch); memcpy(vkey, S.vkey, sizeof vkey);
    int gs = S.gyro_stick, gx = S.gyro_x, gy = S.gyro_y;
    const wf_state *was = before();
    init_state(fd, was);                          // b18n: the held keys in the order they went down
    memcpy(S.latch, latch, sizeof latch); memcpy(S.vkey, vkey, sizeof vkey);
    S.gyro_stick = gs; S.gyro_x = gx; S.gyro_y = gy;
    return was;
}

/** 1.3: before the source state is thrown away (pad rebuilt, events dropped): a release for every button
 *  and D-pad direction Wayfinder plays that is down now, so no keyboard key stays held. */
static void ext_release(void) {
    for (int i = 0; i < S.nk; i++) { int p = S.keys[i]; if (S.key[p] && M.keymap[p] == WF_EXT) out("X %d 0", p); }
    static const int DIRS[4] = { WF_HAT_UP, WF_HAT_DOWN, WF_HAT_LEFT, WF_HAT_RIGHT };
    for (int i = 0; i < 4; i++) if (M.keymap[DIRS[i]] == WF_EXT && wf_dir_on(S.abs[WF_HX], S.abs[WF_HY], DIRS[i])) out("X %d 0", DIRS[i]);
}

/* b18o (2026-10-11 — GitHub #81's turbo leftover): the copy's buttons said held for a hand that holds a turbo button
   ("TU", fx/wfmap.h [wf_turbo_held]) — by printed code. Never cleared on a resync or a profile: what no longer counts is said
   let go by the next emit (the helper says "TU 0 0 0" for all when this process ends). */
static unsigned char tsaid[WF_NK];
static void turbo_say(void) {
    int c[WF_APP_HI - WF_APP_LO + 1], v[WF_APP_HI - WF_APP_LO + 1];
    int n = wf_turbo_held(&S, &M, tsaid, c, v, WF_APP_HI - WF_APP_LO + 1);
    for (int i = 0; i < n; i++) out("TU %d %d %ld", c[i], v[i], S.now_ms);
}

/** Write the copy's new state: only what changed (everything after a fresh copy). b18l (GitHub #81, b18w B1): when Home or
 *  Back changes, in up to three reports — Home's change / Back's release first, then the rest (the gate letting go of what
 *  is held, or giving it back), Back's press last ([wf_frames]). In one report the UHID copy wrote the buttons before Home:
 *  R2 given back before Home's release made "Home + R2" in the app.
 *  b18o: first, what the hand holds as turbo buttons, said to Wayfinder ([turbo_say]) — before the copy's report, with or
 *  without a copy (it is the hand's). */
static void emit(void) {
    S.now_ms = now_us() / 1000;
    turbo_say();
    if (hfd >= 0) {                               // the UHID copy: whole reports, each when it changes something
        S.now_ms = now_us() / 1000;
        wf_out n; wf_compute(&S, &M, &n);
        wf_out mid[2];
        int nm = out_full || !hlast_ok ? 0 : wf_frames(&OUT, &n, mid);
        for (int s = 0; s <= nm; s++) {
            const wf_out *st = s < nm ? &mid[s] : &n;
            unsigned char rep[WFU_MAXREP];
            int len = wfu_report(&HL, st->key, WF_NK, st->abs, WF_NA, rep);
            if (out_full || !hlast_ok || memcmp(rep, hlast, (size_t)len)) {
                struct uhid_event e; memset(&e, 0, sizeof e);
                e.type = UHID_INPUT2; e.u.input2.size = (unsigned short)len; memcpy(e.u.input2.data, rep, (size_t)len);
                if (write(hfd, &e, sizeof e) < 0 && errno != EAGAIN) out("E write: %s", strerror(errno));
                memcpy(hlast, rep, (size_t)len); hlast_ok = 1;
            }
        }
        OUT = n; out_full = 0;
        return;
    }
    if (ufd < 0) return;
    S.now_ms = now_us() / 1000;
    wf_out n; wf_compute(&S, &M, &n);
    // the uinput copy: the same reports as events, each with its SYN_REPORT (one write; the kernel syncs at each)
    struct input_event ev[512];
    int c = wf_frame_events(&OUT, &n, S.keys, S.nk, S.axes, S.na, out_full, ev, (int)(sizeof ev / sizeof *ev));
    if (c && write(ufd, ev, (size_t)c * sizeof *ev) < 0 && errno != EAGAIN) out("E write: %s", strerror(errno));
    OUT = n; out_full = 0;
}

/** The source is gone: the game sees everything released (no stuck keys across AYN rebuilds). */
static void source_rest(void) { ext_release(); wf_state_rest(&S); emit(); }

/* b18n review: AYN's pad went with what it held KEPT (S and the copy as they were, [lose_source]) — until its next pad is
   read ([find_source]: a resync, in the order the keys went down) or until this time ([source_gone]). 0 = nothing kept. AYN
   re-makes its pad in about 3 ms (a controller-style switch, a per-game layout);
   the keep only bounds the case where no pad comes back (AYN's pad turned off): then everything is let go, as before. */
#define KEEP_US 1000000L
static long kept_until_us = 0;

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

/** sysfs says [ev] may be AYN's pad: its vendor, not the copy, not a pad Wayfinder publishes, not an external's copy. */
static int ayn_like(const char *ev) {
    if (!strcmp(ev, clone_node)) return 0;
    char p[160], v[64];
    snprintf(p, sizeof p, "/sys/class/input/%s/device/phys", ev);
    if (read_sys(p, v, sizeof v) == 0 && (!strcmp(v, phys) || !strcmp(v, "wayfinder-clone"))) return 0;   // a copy (ours, the layer's)
    // a pad Wayfinder publishes for an external controller (fx/wfhid.c, phys "wayfinder-ext-N"): AYN's ids, a name
    // no other device has — never AYN's pad (found 2026-10-02: the copy took a virtual external pad as its source)
    if (read_sys(p, v, sizeof v) == 0 && !strncmp(v, "wayfinder-ext-", 14)) return 0;
    snprintf(p, sizeof p, "/sys/class/input/%s/device/id/vendor", ev);
    if (read_sys(p, v, sizeof v) < 0 || strtol(v, NULL, 16) != clone_id.vendor) return 0;
    if (test_src_name) {                         // a test instance: only its fake pad
        snprintf(p, sizeof p, "/sys/class/input/%s/device/name", ev);
        if (read_sys(p, v, sizeof v) < 0 || strcmp(v, test_src_name)) return 0;
    }
    // an external pad's AYN copy (2020:0111 under a name a non-AYN device has): not AYN's pad
    char nm[128];
    snprintf(p, sizeof p, "/sys/class/input/%s/device/id/product", ev);
    long prod = read_sys(p, v, sizeof v) == 0 ? strtol(v, NULL, 16) : 0;
    snprintf(p, sizeof p, "/sys/class/input/%s/device/name", ev);
    int has_nm = read_sys(p, nm, sizeof nm) == 0;
    // dev build 15: AYN's copy of a pad of ours ("… (Wayfinder)", 2020:0111, no location) is never AYN's pad — no
    // non-AYN device has that name, so the rule below let it through and the copy could move onto it (newer_source)
    if (has_nm && wf_wayfinder_name(nm)) return 0;
    if (prod != 0x0112 && has_nm && foreign_name(nm)) return 0;
    return 1;
}

/** A pad, not "ODIN Station Virtual Mouse" (same vendor): sticks and a south button. */
static int is_pad_fd(int fd) {
    unsigned long ev[NBITS(EV_MAX + 1)] = {0}, keys[NBITS(KEY_MAX + 1)] = {0};
    ioctl(fd, EVIOCGBIT(0, sizeof ev), ev); ioctl(fd, EVIOCGBIT(EV_KEY, sizeof keys), keys);
    return TEST(EV_ABS, ev) && TEST(BTN_SOUTH, keys);
}

static char src_node[32];                // the current source's eventN

/** AYN's pads that sysfs lists now, NEWEST first (up to [max]). AYN can leave an OLD pad behind that it never writes
 *  to again (an orphan: seen 2026-10-06 after its pad was re-made from two threads at once) — the newest is the one
 *  it feeds, so the newest wins; picking the lowest eventN took the orphan: no Thor buttons at all. */
/** Exactly what AYN's own pad looks like: no location, AYN's pad ids (Odin 0111 / Xbox 0112). */
static int ayn_exact(const char *ev) {
    char p[160], v[64];
    snprintf(p, sizeof p, "/sys/class/input/%s/device/phys", ev);
    if (read_sys(p, v, sizeof v) < 0 || v[0]) return 0;
    snprintf(p, sizeof p, "/sys/class/input/%s/device/id/product", ev);
    if (read_sys(p, v, sizeof v) < 0) return 0;
    long prod = strtol(v, NULL, 16);
    return prod == 0x0111 || prod == 0x0112;
}

static int ayn_pads(char names[][32], int *nums, int max, int above, int exact) {
    DIR *d = opendir("/sys/class/input"); if (!d) return 0;
    struct dirent *e; int n = 0;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "event", 5) || strlen(e->d_name) >= 32) continue;
        int num = input_num(e->d_name);
        if (num <= above || !ayn_like(e->d_name) || (exact && !ayn_exact(e->d_name))) continue;
        if (n == max && nums[max - 1] >= num) continue;   // full: only a newer one gets in
        int i = n < max ? n++ : max - 1;
        // insertion, newest first
        while (i > 0 && nums[i - 1] < num) { nums[i] = nums[i - 1]; memcpy(names[i], names[i - 1], 32); i--; }
        nums[i] = num; snprintf(names[i], 32, "%s", e->d_name);
    }
    closedir(d);
    return n;
}

/** Look for AYN's (new) pad — the NEWEST one: same vendor as the copy, not the copy itself. Grab it. */
static int find_source(void) {
    char names[16][32]; int nums[16];
    // pads exactly like AYN's first; then anything of AYN's vendor (a firmware that gives its pad a location…)
    for (int exact = 1; exact >= 0; exact--) {
    int n = ayn_pads(names, nums, 16, -1, exact);
    for (int i = 0; i < n; i++) {
        char p[64];
        snprintf(p, sizeof p, "/dev/input/%s", names[i]);
        int fd = open_source(p);                  // may fail for a moment: node not made yet
        if (fd < 0) continue;
        if (!is_pad_fd(fd)) { close(fd); continue; }
        if (grab && ioctl(fd, EVIOCGRAB, 1) < 0) { close(fd); continue; }
        src = fd; src_since_us = now_us(); src_input = nums[i];
        snprintf(src_node, sizeof src_node, "%s", names[i]);
        // b18n: the copy's state till now, for the replays' order. b18n review: AYN's pad re-made with what it held kept
        // ([lose_source]) — read again as events dropped are ([resync_state]: Wayfinder's toggles, gyro stick and held
        // presses kept). The copy never let go of a key the hand still holds: Select + Back stays the game's, a held Home
        // makes no tap. Else (the first pad, docked, the keep run out): as before
        const wf_state *was;
        if (kept_until_us) { kept_until_us = 0; was = resync_state(fd); }
        else { was = before(); init_state(fd, was); }
        // fed (docked): AYN's held keys don't count, the external pad's do · merged (18f): both sources' count
        if (xsrc >= 0) { if (xmerge) merge_resync(was); else resync_from_x(was); }
        emit();
        out("S %s %s", src_name, names[i]);
        return 0;
    }
    }
    return -1;
}

/** A pad of AYN's NEWER than the source is there (AYN made a new one and kept the old: the old one is dead now).
 *  Exactly like AYN's (no location, its ids): no other device of AYN's vendor can pull the Thor's input away; and one
 *  that can be grabbed (find_source needs that too — else it took the old one again, round and round).
 *  0 = none · 1 = one, not yet there for a full second · 2 = the same one, there for ≥ 1 s (review 2026-10-06: an
 *  early re-check on a device appearing must not shorten that). */
static int newer_seen = -1;
static long newer_first_us = 0;
static int newer_source(void) {
    if (src < 0 || src_input < 0) { newer_seen = -1; return 0; }
    char names[4][32]; int nums[4];
    int n = ayn_pads(names, nums, 4, src_input, 1);   // only a pad exactly like AYN's takes over a working source
    for (int i = 0; i < n; i++) {
        char p[64];
        snprintf(p, sizeof p, "/dev/input/%s", names[i]);
        int fd = open(p, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) continue;
        int pad = is_pad_fd(fd);
        if (pad && grab) { if (ioctl(fd, EVIOCGRAB, 1) < 0) pad = 0; else ioctl(fd, EVIOCGRAB, 0); }
        close(fd);
        if (!pad) continue;
        long now = now_us();
        if (newer_seen != nums[i]) { newer_seen = nums[i]; newer_first_us = now; return 1; }
        if (now - newer_first_us < 1000000L) return 1;
        newer_seen = -1;
        out("I newer-source %s (was %s)", names[i], src_node);
        return 2;
    }
    newer_seen = -1;
    return 0;
}

/** Leaving the wait for AYN's next pad: forget the old source. b18n review: what it held is KEPT till its next pad is read
 *  ([kept_until_us], [find_source]) — undocked, and merged (18f: both pads' holds). Let go at once (before the review), the
 *  copy wrote Back / Select / Home UP and the next pad's replay found nothing held before: code order again — a Select + Back
 *  that went with the game gated and withheld Select (two Backs for one press over RetroArch), R2 + Home made a Home tap.
 *  Feeding (docked): the external pad drives the copy — what it holds stays held (AYN's pad is only drained). */
static void lose_source(long *waiting_since, long *mismatch_since) {
    drop_source();
    if (xsrc < 0 || xmerge) kept_until_us = now_us() + KEEP_US;
    *mismatch_since = 0; *waiting_since = now_us(); out("W");
}

/** b18n review: AYN's pad went and none came back within [KEEP_US]: what it held is let go now — undocked everything (the game
 *  sees it all released), merged AYN's holds (18f: the fed pad's stay). Docked (a feed started meanwhile): nothing. */
static void source_gone(void) {
    kept_until_us = 0;
    if (xsrc < 0) source_rest(); else if (xmerge) merge_drop(WFM_AYN);
}

static void emit(void);
static const wf_state *resync_state(int fd);

static int rescale(int a, int v);

/** Feeding: the copy's state from the EXTERNAL pad as the kernel has it now (its held buttons, its sticks rescaled),
 *  keeping what Wayfinder set (toggle latches, the gyro stick, held virtual presses) — as [resync_state] does for
 *  AYN's pad. At feed start, after events dropped on it, after AYN's pad was re-made (review 2026-10-03). [old]: the
 *  state before ([before]) — b18n: the held keys in the order they went down ([replay_held]). */
static void resync_from_x(const wf_state *old) {
    if (xsrc < 0) return;
    kept_until_us = 0;                            // b18n review: docked, AYN's holds don't count — none kept ([lose_source])
    ext_release();
    unsigned char latch[WF_NK], vkey[WF_NK];
    memcpy(latch, S.latch, sizeof latch); memcpy(vkey, S.vkey, sizeof vkey);
    int gs = S.gyro_stick, gx = S.gyro_x, gy = S.gyro_y;
    wf_state_rest(&S);
    memcpy(S.latch, latch, sizeof latch); memcpy(S.vkey, vkey, sizeof vkey);
    S.gyro_stick = gs; S.gyro_x = gx; S.gyro_y = gy;
    wf_state_map(&S, &M);                         // its shift button, bg=1, what the copy shows of each key (b18h)
    for (int i = 0; i < S.na; i++) {
        int a = S.axes[i]; struct input_absinfo ai;
        if (ioctl(xsrc, EVIOCGABS(a), &ai) == 0 && xinfo[a].maximum > xinfo[a].minimum) S.abs[a] = rescale(a, ai.value);
    }
    unsigned long ks[NBITS(KEY_MAX + 1)] = {0};
    ioctl(xsrc, EVIOCGKEY(sizeof ks), ks);
    S.now_ms = now_us() / 1000;
    replay_held(ks, old);
}

/** One event through the engine (AYN's pad, the fed pad — its axes already rescaled — or the merged stream): the gate's
 *  echo ("J"), a button / D-pad direction Wayfinder plays ("X"), a watched control ("V"), toggles and turbo. */
static void engine_event(int type, int code, int value) {
    int p;
    if (type == EV_KEY) S.now_ms = now_us() / 1000;   // before: the shift tap is timed from it
    int hat_old = type == EV_ABS && (code == WF_HX || code == WF_HY) ? S.abs[code] : 0;
    if (wf_event(&S, type, code, value, &p)) out("J %d %d %d", type, p, value);
    if (type == EV_KEY && wf_ext_event(&S, &M, p, value)) out("X %d %d", p, value);
    if (type == EV_ABS && (code == WF_HX || code == WF_HY)) {   // 1.3: D-pad directions Wayfinder plays
        int dc[4], dv[4], dn = wf_dir_ext(&S, &M, code, hat_old, value, dc, dv);
        for (int j = 0; j < dn; j++) out("X %d %d", dc[j], dv[j]);
    }
    if ((type == EV_KEY && p >= 0 && p < 0x300 && watch_key[p] && value != 2) ||
        (type == EV_ABS && code >= 0 && code < 0x40 && watch_abs[code])) out("V %d %d %d", type, p, value);
    if (type == EV_KEY) { S.now_ms = now_us() / 1000; wf_fire_edge(&S, &M, p, value); }
}
static void engine_cb(void *ctx, int type, int code, int value) { (void)ctx; engine_event(type, code, value); }

/** dev build 18f — the merged feed: the copy's state from BOTH pads as their kernels have them now (each one's held
 *  buttons; the fed pad's sticks rescaled), keeping what Wayfinder set (toggle latches, the gyro stick, held virtual
 *  presses) as [resync_from_x] does. A stick / trigger / hat axis held away from rest on the fed pad is its; the rest
 *  AYN's (fx/wfmerge.h wfm_decide). At a merged feed's start (a new node, or the in-place switch), after events dropped
 *  on either pad, after AYN's pad was re-made. [old]: the state before ([before]) — b18n: the held keys in the order they
 *  went down (fx/wfmap.h [wf_replay]). */
static void merge_resync(const wf_state *old) {
    if (xsrc < 0) return;
    ext_release();
    unsigned char latch[WF_NK], vkey[WF_NK];
    memcpy(latch, S.latch, sizeof latch); memcpy(vkey, S.vkey, sizeof vkey);
    int gs = S.gyro_stick, gx = S.gyro_x, gy = S.gyro_y;
    wf_state_rest(&S);
    memcpy(S.latch, latch, sizeof latch); memcpy(S.vkey, vkey, sizeof vkey);
    S.gyro_stick = gs; S.gyro_x = gx; S.gyro_y = gy;
    wf_state_map(&S, &M);                         // its shift button, bg=1, what the copy shows of each key (b18h)
    wfm_reset(&MG, &S); dropped2[0] = dropped2[1] = 0;
    for (int w = 0; w < 2; w++) {
        int fd = w == WFM_AYN ? src : xsrc;
        if (fd < 0) continue;                          // AYN's pad away for a moment: only the fed pad's
        for (int i = 0; i < S.na; i++) {
            int a = S.axes[i]; struct input_absinfo ai;
            if (ioctl(fd, EVIOCGABS(a), &ai) != 0) continue;
            if (w == WFM_EXT && xinfo[a].maximum <= xinfo[a].minimum) continue;
            MG.abs[w][a] = w == WFM_EXT ? rescale(a, ai.value) : wf_clamp(&S, a, ai.value);
        }
        unsigned long ks[NBITS(KEY_MAX + 1)] = {0};
        ioctl(fd, EVIOCGKEY(sizeof ks), ks);
        for (int i = 0; i < S.nk; i++) if (TEST(S.keys[i], ks)) MG.key[w][S.keys[i]] = 1;
    }
    wfm_decide(&MG, &S);
    for (int i = 0; i < S.na; i++) S.abs[S.axes[i]] = wfm_value(&MG, S.axes[i]);
    S.now_ms = now_us() / 1000;
    unsigned char held[WF_NK] = {0};
    for (int i = 0; i < S.nk; i++) { int k = S.keys[i]; held[k] = MG.key[WFM_AYN][k] || MG.key[WFM_EXT][k]; }
    wf_replay(&S, held, old);
}

/** dev build 18f — the merged feed lost one of its pads ([which]: AYN's gone / re-made, or the fed pad gone / "s -"):
 *  what it held is let go through the engine (an edge only where the other pad doesn't hold it — a held Thor Home keeps
 *  its gate), and the other pad owns every axis: after the drop the copy's state IS the other pad's. Then written. */
static void merge_drop(int which) {
    wfm_drop(&MG, &S, which, engine_cb, NULL);
    emit();
}

/** Back to AYN's pad (the helper asked, or the external controller's pad went away). */
static void feed_stop(void) {
    if (xsrc < 0) return;
    if (xmerge) {
        // merged (18f): the fed pad's holds go, the Thor's stay — after the drop the state IS AYN's (no resync)
        merge_drop(WFM_EXT);
        xmerge = 0;
        ioctl(xsrc, EVIOCGRAB, 0); close(xsrc); xsrc = -1; xnode[0] = 0;
        out("F off");
        return;
    }
    ioctl(xsrc, EVIOCGRAB, 0); close(xsrc); xsrc = -1; xnode[0] = 0;
    if (src >= 0) resync_state(src); else { ext_release(); wf_state_rest(&S); }
    emit();
    out("F off");
}

/** The answer to "s": the node fed and its mode. */
static void feed_said(void) { if (xmerge) out("F on %s m", xnode); else out("F on %s", xnode); }

/** Feed the copy from [node] (eventN): only a pad Wayfinder publishes for an external controller (its phys says so).
 *  Grabbed, so nothing else reads it; what AYN's pad holds now is let go first — or, [merge] (dev build 18f), AYN's pad
 *  keeps driving the copy too: the merged feed (fx/wfmerge.h). */
static void feed_start(const char *node, int merge) {
    if (strncmp(node, "event", 5) != 0 || strlen(node) > 12) { out("E feed bad node"); return; }
    if (xsrc >= 0 && !strcmp(node, xnode)) {      /* asked again: already (a 2nd grab = EBUSY) */
        if ((merge != 0) != (xmerge != 0)) {       /* 18f: the other mode, switched in place */
            if (merge) { xmerge = 1; merge_resync(before()); }
            else { xmerge = 0; resync_from_x(before()); }   /* drained (docking): AYN's holds let go, as a docked feed's start */
            emit();
        }
        feed_said(); return;
    }
    for (const char *c = node + 5; *c; c++) if (*c < '0' || *c > '9') { out("E feed bad node"); return; }
    char p[96], v[64];
    snprintf(p, sizeof p, "/sys/class/input/%s/device/phys", node);
    if (read_sys(p, v, sizeof v) < 0 || strncmp(v, "wayfinder-ext-", 14) != 0) { out("E feed not ours"); return; }
    snprintf(p, sizeof p, "/dev/input/%s", node);
    int fd = open(p, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) { out("E feed open: %s", strerror(errno)); return; }
    int clk = CLOCK_MONOTONIC; ioctl(fd, EVIOCSCLOCKID, &clk);
    if (ioctl(fd, EVIOCGRAB, 1) < 0) { close(fd); out("E feed grab: %s", strerror(errno)); return; }
    if (xsrc >= 0) { ioctl(xsrc, EVIOCGRAB, 0); close(xsrc); }
    xsrc = fd; snprintf(xnode, sizeof xnode, "%s", node);
    memset(xinfo, 0, sizeof xinfo);
    for (int a = 0; a <= ABS_MAX; a++) ioctl(fd, EVIOCGABS(a), &xinfo[a]);
    // what AYN's pad holds is let go; the copy takes what the external pad holds now (a button held while docking) —
    // merged (18f, the NEW request's mode): both pads' (never resync_from_x then: the Thor's holds would be let go)
    xmerge = merge != 0;
    if (xmerge) merge_resync(before()); else resync_from_x(before());
    emit();
    feed_said();
}

/** An external pad's axis value in AYN's pad's range (the copy has AYN's ranges). */
static int rescale(int a, int v) {
    if (a < 0 || a > ABS_MAX || a >= WF_NA) return v;
    const struct input_absinfo *x = &xinfo[a], *t = &S.info[a];
    long xs = (long)x->maximum - x->minimum, ts = (long)t->maximum - t->minimum;
    if (xs <= 0 || ts <= 0) return v;
    long r = t->minimum + ((long)v - x->minimum) * ts / xs;
    return (int)(r < t->minimum ? t->minimum : r > t->maximum ? t->maximum : r);
}

/** One command line from the helper. Returns 0 to quit. */
static int command(char *line) {
    if (line[0] == 'q') return 0;
    // tests only (started with "test-hooks"; the app never is): the UHID copy's device failing
    if (test_hooks && !strcmp(line, "!uhid-lost")) { uhid_service(POLLHUP); return 1; }
    if (line[0] == 's' && line[1] == ' ') {
        if (!strcmp(line + 2, "-")) { feed_stop(); return 1; }
        // dev build 18f: "s <eventN> m" = merged — the " m" split off first (feed_start checks a bare eventN)
        char *sp = strchr(line + 2, ' ');
        if (sp && strcmp(sp, " m") != 0) { out("E feed bad node"); return 1; }
        if (sp) *sp = 0;
        feed_start(line + 2, sp != NULL);
        return 1;
    }
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
            wf_state_map(&S, &M); S.shift_tap_until = 0;
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
    named = argc > 5 && !strcmp(argv[5], "named");
    for (int i = 6; i < argc; i++) {
        if (!strcmp(argv[i], "uhid")) want_uhid = 1;
        else if (!strcmp(argv[i], "test-hooks")) test_hooks = 1;
        else if (!strcmp(argv[i], "test-uhidfail")) test_uhid_fail = 1;
        else if (!strncmp(argv[i], "test-src=", 9) && argv[i][9]) test_src_name = argv[i] + 9;
        else if (!strncmp(argv[i], "uniq=", 5) && argv[i][5] && strlen(argv[i]) < 60) uniq = argv[i] + 5;
    }

    signal(SIGTERM, on_signal); signal(SIGINT, on_signal); signal(SIGPIPE, on_signal);
    wf_map_reset(&M);

    int ino = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
    if (ino >= 0) inotify_add_watch(ino, "/dev/input", IN_CREATE);
    src = open_source(argv[1]);
    if (src < 0) { out("E open %s: %s", argv[1], strerror(errno)); return 2; }
    init_state(src, NULL);
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
        src_since_us = now_us();
        snprintf(src_node, sizeof src_node, "%s", argv[1] + strlen("/dev/input/"));
        src_input = input_num(src_node);
        emit();
        out("S %s %s", src_name, src_node);
    }
    setpriority(PRIO_PROCESS, 0, -10);            // forwarding: ahead of ordinary app threads

    // added latency: source event time → our write, per SYN_REPORT frame
    enum { NS = 4096 };
    static long lat[NS]; int nl = 0; long last_report = now_us();
    long waiting_since = src < 0 ? now_us() : 0, mismatch_since = 0, both_since = 0;
    int dropped = 0, last_from = -1, skip_line = 0;
    long copy_retry_us = 0;
    long newer_at = 0, newer_check = now_us();   // look for a newer AYN pad then (a device appeared) / every 3 s
    if (src < 0) { out("W"); find_source(); if (src >= 0) waiting_since = 0; }
    struct input_event buf[64];
    char cmd[4096]; size_t cl = 0;
    for (;;) {
        struct pollfd pf[5] = { { .fd = 0, .events = POLLIN }, { .fd = ino, .events = POLLIN }, { .fd = src, .events = POLLIN },
                                { .fd = xsrc, .events = POLLIN }, { .fd = hfd, .events = POLLIN } };
        int turbo = (src >= 0 || xsrc >= 0) && (wf_turbo_active(&S, &M) || S.shift_tap_until);   // a shift tap needs its release on time
        int tmo = turbo ? 10 : app_pending ? 8 : src < 0 || mismatch_since || both_since ? 250 : 1000;
        // the external pad (docked) is polled in slot 3; a missing AYN pad leaves slot 2 at -1 (ignored by poll)
        // the swap to the UHID copy is due: wake for it
        if (want_uhid && !uhid_failed && hfd < 0 && ufd >= 0 && tmo > 250) tmo = 250;
        if (newer_at && tmo > 300) tmo = 300;
        // b18n review: AYN's pad gone with what it held kept: wake when the keep runs out ([source_gone])
        if (src < 0 && kept_until_us) { long w = (kept_until_us - now_us()) / 1000 + 1; if (w < tmo) tmo = w > 1 ? (int)w : 1; }
        int hfd_polled = hfd;
        int r = poll(pf, 5, tmo);   // a slot at -1 (no AYN pad, no external pad, no UHID copy) is ignored by poll
        if (r < 0) { if (errno == EINTR) continue; break; }
        if (pf[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t k = read(0, cmd + cl, sizeof cmd - 1 - cl);
            if (k <= 0) break;                         // EOF = the helper is gone
            cl += (size_t)k; cmd[cl] = 0;
            int quit = 0; char *nl_at;
            while (!quit && (nl_at = strchr(cmd, '\n'))) {
                *nl_at = 0;
                if (skip_line) skip_line = 0;          // the tail of an over-long line: not a command
                else if (!command(cmd)) quit = 1;
                size_t used = (size_t)(nl_at - cmd) + 1;
                memmove(cmd, cmd + used, cl - used + 1); cl -= used;
            }
            if (quit) break;
            if (cl >= sizeof cmd - 1) { cl = 0; skip_line = 1; }   // an over-long line: dropped, up to its end
        }
        if (pf[1].revents & POLLIN) { char b[1024]; while (read(ino, b, sizeof b) > 0) {} newer_at = now_us() + 300000L; }
        if (xsrc >= 0 && (pf[3].revents & (POLLERR | POLLHUP | POLLNVAL))) feed_stop();   // the external pad went away
        if (src >= 0 && (pf[2].revents & (POLLERR | POLLHUP | POLLNVAL))) {
            lose_source(&waiting_since, &mismatch_since);
        } else if (src >= 0 && xsrc >= 0 && !xmerge && (pf[2].revents & POLLIN)) {
            // docked: the Thor's own buttons drive nothing (still read: AYN's pad stays grabbed and its queue empty)
            ssize_t n = read(src, buf, sizeof buf);
            if (n <= 0 && !(n < 0 && errno == EAGAIN)) lose_source(&waiting_since, &mismatch_since);
        }
        // dev build 18f — the merged feed: AYN's pad AND the fed pad, both read in this pass, one engine on the merged
        // stream (fx/wfmerge.h). (Not merged: nothing here, the paths below as before.)
        for (int w = 0; xmerge && w < 2; w++) {
            int fd = w == WFM_AYN ? src : xsrc;
            short rv = w == WFM_AYN ? pf[2].revents : pf[3].revents;
            if (!xmerge || fd < 0 || !(rv & POLLIN) || (rv & (POLLERR | POLLHUP | POLLNVAL))) continue;
            ssize_t n = read(fd, buf, sizeof buf);
            if (n <= 0) {
                if (!(n < 0 && errno == EAGAIN)) { if (w == WFM_AYN) lose_source(&waiting_since, &mismatch_since); else feed_stop(); }
                continue;
            }
            size_t cnt = (size_t)n / sizeof(struct input_event);
            for (size_t i = 0; i < cnt && xmerge; i++) {
                struct input_event *e = &buf[i];
                if (e->type == EV_SYN && e->code == SYN_DROPPED) { dropped2[w] = 1; continue; }
                if (e->type == EV_SYN && e->code == SYN_REPORT) {
                    if (dropped2[w]) merge_resync(before());       // both kernels' states (clears both flags)
                    emit();
                    long src_us = e->input_event_sec * 1000000L + e->input_event_usec;
                    if (nl < NS) lat[nl++] = now_us() - src_us;
                    continue;
                }
                if (dropped2[w] || (e->type != EV_KEY && e->type != EV_ABS)) continue;
                int v = w == WFM_EXT && e->type == EV_ABS ? rescale(e->code, e->value) : e->value;
                wfm_event(&MG, &S, w, e->type, e->code, v, engine_cb, NULL);
            }
        }
        int from = -1;
        if (xsrc >= 0 && !xmerge && (pf[3].revents & POLLIN)) from = xsrc;
        else if (xsrc < 0 && src >= 0 && (pf[2].revents & POLLIN) && !(pf[2].revents & (POLLERR | POLLHUP | POLLNVAL))) from = src;
        if (from >= 0) {
            ssize_t n = read(from, buf, sizeof buf);
            if (n <= 0) {
                if (from == src && !(n < 0 && errno == EAGAIN)) lose_source(&waiting_since, &mismatch_since);
                if (from == xsrc && !(n < 0 && errno == EAGAIN)) feed_stop();
            } else {
                if (from != last_from) dropped = 0;
                last_from = from;
                size_t cnt = (size_t)n / sizeof(struct input_event);
                for (size_t i = 0; i < cnt; i++) {
                    struct input_event *e = &buf[i];
                    if (e->type == EV_SYN && e->code == SYN_DROPPED) { dropped = 1; continue; }
                    if (e->type == EV_SYN && e->code == SYN_REPORT) {
                        if (dropped) { if (from == src) resync_state(src); else resync_from_x(before()); dropped = 0; }   // the kernel's state
                        emit();
                        long src_us = e->input_event_sec * 1000000L + e->input_event_usec;
                        if (nl < NS) lat[nl++] = now_us() - src_us;
                        continue;
                    }
                    if (dropped || (e->type != EV_KEY && e->type != EV_ABS)) continue;
                    if (from == xsrc && e->type == EV_ABS) e->value = rescale(e->code, e->value);
                    engine_event(e->type, e->code, e->value);
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
        // AYN made a newer pad without removing the source: the source is dead — over to the newest (the same
        // way as when AYN's pad goes: everything released, then the new one's state)
        // Only once it's still there ~1 s later: AYN's copy of an external controller looks like its pad for a moment
        // when that controller goes (its original gone first) — never a reason to leave a working source.
        if (src >= 0 && ((newer_at && t >= newer_at) || t - newer_check > 3000000L)) {
            newer_at = 0; newer_check = t;
            int nw = newer_source();
            if (nw == 1 && !newer_at) newer_at = newer_first_us + 1000000L;
            else if (nw == 2) lose_source(&waiting_since, &mismatch_since);
        }
        if (src < 0) {
            if (find_source() == 0) { waiting_since = 0; if (!same_identity()) mismatch_since = t; }
            else if (xsrc >= 0) waiting_since = t;           // fed by the external pad: the copy lives on meanwhile
            else if (t - waiting_since > 10000000L) { out("E source lost"); cleanup(); return 2; }
        }
        // b18n review: no pad of AYN's came back within the keep — what the gone one held is let go now
        if (src < 0 && kept_until_us && t >= kept_until_us) source_gone();
        // AYN's pad now has another identity (Odin ↔ Xbox) and kept it: re-make the copy as
        // that identity, destroy + create back to back so it takes the number it frees.
        if (src >= 0 && mismatch_since && t - mismatch_since > 2000000L) {
            mismatch_since = 0;
            if (!same_identity() && remake_copy() < 0) { out("E create: %s", strerror(errno)); break; }
        }
        if (hfd == hfd_polled) uhid_service(pf[4].revents);   // (re-made this pass: the old fd's results)
        // a source but no copy (the UHID copy failed while AYN's pad was away, or a copy couldn't be made): uinput again
        // — never a running layer with no controller at all
        if (src >= 0 && ufd < 0 && hfd < 0 && t - copy_retry_us > 1000000L) {
            copy_retry_us = t;
            if (make_uinput_copy() == 0) out("I copy uinput re-made"); else out("E copy: %s", strerror(errno));
        }
        // dev build 13: the uinput copy → the UHID one, once AYN's pad has settled (its number taken: #2)
        if (want_uhid && !uhid_failed && hfd < 0 && ufd >= 0 && src >= 0 && !mismatch_since && same_identity()
            && t - copy_made_us > 1500000L && t - src_since_us > 500000L) to_uhid();
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
