// wfpark.h — 1.5-beta2 (GitHub #102): AYN's pad set aside ("parked") while the input layer's copy runs.
//
// GameNative (and apps like it) list every game controller, hidden ones too, and give out their player slots again at
// every hot-plug: AYN's disabled pad, listed beside the copy, could take player 1 (a silent one). Parked = wfpad has
// grabbed AYN's pad and its /dev/input node is moved out of /dev/input: EventHub drops a device whose node is deleted at
// once (ExtNodes does the same to AYN's copies of external controllers since dev build 15), and wfpad's open fd keeps
// reading it. The node is not deleted: it is hard-linked into a folder of root's own on /dev's tmpfs first
// ([WFP_DIR]/eventN.inputM), so putting it back is one link() of the SAME inode — its owner, mode and SELinux label as
// ueventd made them, and IN_CREATE in /dev/input, which EventHub opens it on. The folder is the record: a node left there
// by a killed wfpad is put back by the next one, by the helper (PadPark.kt, the same rules) or at the latest by a reboot
// (/dev is made anew).
// Only syscalls on the restore path (no stdio, no malloc): wfpad's signal handler puts the node back too. 1.5-beta2 (GitHub
// #102, repair): that path builds its paths with [wfp_join] / [wfp_utoa], never snprintf (not async-signal-safe).
// A planned restart ("q k") leaves the node aside and the helper starts the next wfpad ON it ([wfp_adopt]): AYN's pad is
// never listed in between (GameNative gives out player slots ~320 ms after a hot-plug).
#ifndef WFPARK_H
#define WFPARK_H

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/xattr.h>
#include <unistd.h>

#define WFP_DIR "/dev/wayfinder-park"

/** The three folders the rules act on: the real ones, or a test's (fx/wfpark_test.c: plain files in /data/local/tmp). */
typedef struct { const char *dev, *stage, *sys; } wfp_dirs;
static const wfp_dirs WFP_REAL = { "/dev/input", WFP_DIR, "/sys/class/input" };

/** The node set aside now: its eventN, its sysfs input number, its name in the folder, its inode. */
typedef struct { int on; char ev[16]; int input; char stage[160]; dev_t st_dev; ino_t st_ino; } wfp_rec;

/** 1.5-beta2 (GitHub #102, repair): [a] + [b] + [c] into [out] ([b] / [c] may be NULL) — no stdio: the signal handler's
 *  restore path builds its paths with it. -1 = too long (nothing usable in [out]). */
static int wfp_join(char *out, size_t n, const char *a, const char *b, const char *c) {
    const char *part[3] = { a, b, c };
    size_t k = 0;
    for (int i = 0; i < 3; i++) for (const char *s = part[i]; s && *s; s++) { if (k + 1 >= n) { if (n) out[0] = 0; return -1; } out[k++] = *s; }
    if (!n) return -1;
    out[k] = 0;
    return 0;
}

/** [v] in decimal into [out] (no stdio, as [wfp_join]). -1 = no room. */
static int wfp_utoa(char *out, size_t n, unsigned v) {
    char t[12]; int k = 0;
    do { t[k++] = (char)('0' + v % 10); v /= 10; } while (v && k < 11);
    if ((size_t)k + 1 > n) { if (n) out[0] = 0; return -1; }
    for (int i = 0; i < k; i++) out[i] = t[k - 1 - i];
    out[k] = 0;
    return 0;
}

/** "event" + 1..4 digits, nothing else (never a path). */
static int wfp_event_ok(const char *ev) {
    if (strncmp(ev, "event", 5)) return 0;
    int n = 0;
    for (const char *c = ev + 5; *c; c++, n++) if (*c < '0' || *c > '9') return 0;
    return n >= 1 && n <= 4;
}

/** The name a node is set aside under: "eventN.inputM" (the input number tells a re-used eventN apart). -1 = bad. */
static int wfp_name(const char *ev, int input, char *out, size_t n) {
    if (!wfp_event_ok(ev) || input < 0 || input > 9999999) return -1;
    int k = snprintf(out, n, "%s.input%d", ev, input);
    return k > 0 && (size_t)k < n ? 0 : -1;
}

/** A folder name back to its eventN and input number. -1 = not one of ours. */
static int wfp_parse(const char *name, char *ev, size_t evn, int *input) {
    const char *dot = strchr(name, '.');
    if (!dot || (size_t)(dot - name) >= evn || (size_t)(dot - name) >= 16) return -1;
    char e[16]; memcpy(e, name, (size_t)(dot - name)); e[dot - name] = 0;
    if (!wfp_event_ok(e) || strncmp(dot + 1, "input", 5)) return -1;
    const char *d = dot + 6; int n = 0, v = 0;
    for (; *d; d++, n++) { if (*d < '0' || *d > '9' || n >= 7) return -1; v = v * 10 + (*d - '0'); }
    if (n == 0) return -1;
    if (wfp_join(ev, evn, e, NULL, NULL) < 0) return -1;    // (repair: no stdio, the signal handler parses names)
    *input = v;
    return 0;
}

/** A small sysfs file, no trailing newline (open / read: signal-safe). -1 = none. */
static int wfp_rd(const char *path, char *buf, size_t n) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t k = read(fd, buf, n - 1); close(fd);
    if (k < 0) return -1;
    buf[k] = 0;
    while (k && (buf[k - 1] == '\n' || buf[k - 1] == ' ')) buf[--k] = 0;
    return 0;
}

/** [ev]'s input number in sysfs (its "device" link: "../../input43" → 43). -1 = no such device. */
static int wfp_sys_input(const wfp_dirs *d, const char *ev) {
    char s[200], p[200], l[200];
    if (wfp_join(s, sizeof s, d->sys, "/", ev) < 0 || wfp_join(p, sizeof p, s, "/device", NULL) < 0) return -1;
    ssize_t k = readlink(p, l, sizeof l - 1);
    if (k <= 0) return -1;
    l[k] = 0;
    const char *b = strrchr(l, '/'); b = b ? b + 1 : l;
    if (strncmp(b, "input", 5) || !b[5]) return -1;
    int v = 0;
    for (const char *c = b + 5; *c; c++) { if (*c < '0' || *c > '9') return -1; v = v * 10 + (*c - '0'); }
    return v;
}

/** The device [ev] / [input] still lives: sysfs has that eventN on that input number, and (a device node) its MAJ:MIN is
 *  the node's ([st]). A device gone, or its eventN given to another one, is never put back. */
static int wfp_lives(const wfp_dirs *d, const char *ev, int input, const struct stat *st) {
    if (wfp_sys_input(d, ev) != input) return 0;
    char s[200], p[200], v[32], mj[12], mn[12], want[32];
    if (wfp_join(s, sizeof s, d->sys, "/", ev) < 0 || wfp_join(p, sizeof p, s, "/dev", NULL) < 0) return 0;
    if (wfp_rd(p, v, sizeof v) < 0) return 0;
    if (st && S_ISCHR(st->st_mode)) {             // "MAJ:MIN" (repair: no stdio on the restore path)
        if (wfp_utoa(mj, sizeof mj, major(st->st_rdev)) < 0 || wfp_utoa(mn, sizeof mn, minor(st->st_rdev)) < 0 ||
            wfp_join(want, sizeof want, mj, ":", mn) < 0) return 0;
        if (strcmp(v, want)) return 0;
    }
    return 1;
}

/** The folder is ours: a real folder (not a link), our uid, nothing for group or others, on the same filesystem as the
 *  device folder (link() can't cross filesystems). */
static int wfp_dir_ok(const wfp_dirs *d) {
    struct stat a, b;
    if (lstat(d->stage, &a) < 0 || stat(d->dev, &b) < 0) return 0;
    return S_ISDIR(a.st_mode) && a.st_uid == geteuid() && (a.st_mode & 077) == 0 && a.st_dev == b.st_dev;
}

/** Set [ev] aside: [fd] must be open on exactly that node (same inode), the device alive in sysfs on [input]. Hard-linked
 *  into the folder, then removed from the device folder. 0 = parked ([r] filled); else nothing changed and [why] says why. */
static int wfp_park(const wfp_dirs *d, int fd, const char *ev, int input, wfp_rec *r, char *why, int whyn) {
    char path[200], name[48];
    if (wfp_name(ev, input, name, sizeof name) < 0) { snprintf(why, (size_t)whyn, "bad node"); return -1; }
    snprintf(path, sizeof path, "%s/%s", d->dev, ev);
    struct stat a, b;
    if (fstat(fd, &a) < 0 || lstat(path, &b) < 0 || a.st_dev != b.st_dev || a.st_ino != b.st_ino) {
        snprintf(why, (size_t)whyn, "node changed"); return -1;
    }
    if (!wfp_lives(d, ev, input, &b)) { snprintf(why, (size_t)whyn, "not in sysfs"); return -1; }
    if (mkdir(d->stage, 0700) < 0 && errno != EEXIST) { snprintf(why, (size_t)whyn, "folder: %s", strerror(errno)); return -1; }
    if (!wfp_dir_ok(d)) { snprintf(why, (size_t)whyn, "folder not ours"); return -1; }
    char stage[160];
    snprintf(stage, sizeof stage, "%s/%s", d->stage, name);
    unlink(stage);                                    // a leftover of that very name: its device is this one or gone
    if (link(path, stage) < 0) { snprintf(why, (size_t)whyn, "link: %s", strerror(errno)); rmdir(d->stage); return -1; }
    if (unlink(path) < 0) { snprintf(why, (size_t)whyn, "unlink: %s", strerror(errno)); unlink(stage); rmdir(d->stage); return -1; }
    r->on = 1; snprintf(r->ev, sizeof r->ev, "%s", ev); r->input = input;
    snprintf(r->stage, sizeof r->stage, "%s", stage); r->st_dev = b.st_dev; r->st_ino = b.st_ino;
    return 0;
}

/** 1.5-beta2 (GitHub #102, repair): a node a planned restart ("q k") left aside taken as this run's, where it is: [fd] open
 *  on exactly the folder's [name] (same inode), the folder ours, the device alive in sysfs on that input number, nothing
 *  with its name in the device folder (else it could not go back). Nothing moves — AYN's pad was never listed in between.
 *  0 = parked ([r] filled, its eventN and input number in [ev] / [input]); else nothing changed and [why] says why. */
static int wfp_adopt(const wfp_dirs *d, int fd, const char *name, wfp_rec *r, char *ev, size_t evn, int *input, char *why, int whyn) {
    char e[16], stage[160], path[200]; int in;
    if (wfp_parse(name, e, sizeof e, &in) < 0) { snprintf(why, (size_t)whyn, "bad node"); return -1; }
    if (!wfp_dir_ok(d)) { snprintf(why, (size_t)whyn, "folder not ours"); return -1; }
    snprintf(stage, sizeof stage, "%s/%s", d->stage, name);
    snprintf(path, sizeof path, "%s/%s", d->dev, e);
    struct stat a, b, c;
    if (fstat(fd, &a) < 0 || lstat(stage, &b) < 0 || a.st_dev != b.st_dev || a.st_ino != b.st_ino) {
        snprintf(why, (size_t)whyn, "aside changed"); return -1;
    }
    if (!wfp_lives(d, e, in, &b)) { snprintf(why, (size_t)whyn, "not in sysfs"); return -1; }
    if (lstat(path, &c) == 0) { snprintf(why, (size_t)whyn, "node there"); return -1; }
    r->on = 1; snprintf(r->ev, sizeof r->ev, "%s", e); r->input = in;
    snprintf(r->stage, sizeof r->stage, "%s", stage); r->st_dev = b.st_dev; r->st_ino = b.st_ino;
    snprintf(ev, evn, "%s", e); *input = in;
    return 0;
}

/** The node [stage] back as [ev]: link() (the same inode: owner, mode, label as they were). Refused (not EEXIST): made
 *  again as that device with its mode at creation, then its owner and label from the set-aside node. 0 = back. */
static int wfp_put_back(const char *stage, const char *path, const struct stat *st) {
    if (link(stage, path) == 0 || errno == EEXIST) return 0;
    if (!S_ISCHR(st->st_mode)) return -1;
    mode_t old = umask(0);
    int ok = mknod(path, S_IFCHR | (st->st_mode & 07777), st->st_rdev) == 0;
    umask(old);
    if (!ok) return errno == EEXIST ? 0 : -1;
    if (chown(path, st->st_uid, st->st_gid) < 0) { /* root:input expected: left as made (0666 already) */ }
    char label[256];
    ssize_t k = getxattr(stage, "security.selinux", label, sizeof label);
    if (k > 0) setxattr(path, "security.selinux", label, (size_t)k, 0);
    return 0;
}

/** One name in the folder ([name]): its node back if the device lives and nothing has its name in the device folder; the
 *  folder name goes. 1 = put back · 0 = dropped (gone, re-used, or there already) · -1 = could not be put back (the name
 *  kept: a later sweep, or a reboot). */
static int wfp_restore_one(const wfp_dirs *d, const char *name) {
    char ev[16]; int input;
    if (wfp_parse(name, ev, sizeof ev, &input) < 0) return 0;
    char stage[160], path[200];
    if (wfp_join(stage, sizeof stage, d->stage, "/", name) < 0 || wfp_join(path, sizeof path, d->dev, "/", ev) < 0) return -1;
    struct stat st, there;
    if (lstat(stage, &st) < 0) return 0;
    if (!wfp_lives(d, ev, input, &st) || lstat(path, &there) == 0) { unlink(stage); return 0; }
    if (wfp_put_back(stage, path, &st) < 0) return -1;
    unlink(stage);
    return 1;
}

/** The parked node back (wfpad stopping). The folder goes too when empty. */
static int wfp_unpark(const wfp_dirs *d, wfp_rec *r) {
    if (!r->on) return 0;
    r->on = 0;
    const char *name = strrchr(r->stage, '/'); name = name ? name + 1 : r->stage;
    int k = wfp_restore_one(d, name);
    rmdir(d->stage);
    return k;
}

/** The source went (wfpad looks for AYN's next pad): its name goes if the device is gone; a pad still alive (an orphan AYN
 *  left behind) stays aside — a sweep puts it back when wfpad stops. */
static void wfp_forget(const wfp_dirs *d, wfp_rec *r) {
    if (!r->on) return;
    r->on = 0;
    struct stat st;
    if (lstat(r->stage, &st) == 0 && !wfp_lives(d, r->ev, r->input, &st)) unlink(r->stage);
    rmdir(d->stage);
}

/** Every name in the folder (wfpad's normal exit; the helper's PadPark.restoreAll does the same): live ones back, the rest
 *  dropped. Returns how many came back, -1 if one could not. A folder that isn't ours is never touched. */
static int wfp_sweep(const wfp_dirs *d) {
    if (!wfp_dir_ok(d)) return 0;
    DIR *dir = opendir(d->stage);
    if (!dir) return 0;
    int back = 0, bad = 0;
    struct dirent *e;
    while ((e = readdir(dir))) {
        if (e->d_name[0] == '.') continue;
        int k = wfp_restore_one(d, e->d_name);
        if (k > 0) back++; else if (k < 0) bad = 1;
    }
    closedir(dir);
    rmdir(d->stage);
    return bad ? -1 : back;
}

/** AYN making its pad again and again (each one parked at once): [max] parks within [window_us] = stop parking for this
 *  run (AYN's pad then stays listed, hidden, as before beta2). Records this park; 1 = over the cap. */
typedef struct { long at[8]; int n; } wfp_cap;
static int wfp_cap_hit(wfp_cap *c, long now_us, int max, long window_us) {
    if (max > 8) max = 8;
    int k = 0;
    for (int i = 0; i < c->n; i++) if (now_us - c->at[i] <= window_us) c->at[k++] = c->at[i];
    c->n = k;
    if (c->n >= max) return 1;
    c->at[c->n++] = now_us;
    return 0;
}

#endif
