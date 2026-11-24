// wfpark_test — 1.5-beta2 (GitHub #102): the rules that set AYN's pad aside while the copy runs (fx/wfpark.h), run ON the Thor.
//   tools/build_wfpad.sh test   → pushes and runs /data/local/tmp/wfpark_test (after wfuhid_test): plain files in
//                                 /data/local/tmp/wft_park_test stand for /dev/input, the folder and sysfs (no root needed)
//   wfpark_test probe           → ROOT only (a device check): the same calls on a real input device of its own ("wft park
//                                 probe", one key, no gamepad: nobody takes it) — Android stops listing it when it is set
//                                 aside, lists it again when it is put back with the same inode, owner, mode and label;
//                                 a device gone while aside is never made again; a node left by a killed process comes
//                                 back with a sweep. Folder /dev/wft-park-probe (its own), removed at the end.
//   wfpark_test fakepad         → ROOT only: a fake 2020:0111 pad "wft fake pad" (phys wft-fake-pad) for a wfpad test
//                                 instance (test-src=wft fake pad). stdin: "c" make (again), "d" destroy, "p" press A, "q".
//                                 Prints "node <eventN> <inputN>" when made.
//   wfpark_test sweep <folder>  → ROOT only: every node set aside in <folder> back (PadPark.restoreAll's rules). 1.5-beta2
//                                 (GitHub #102, repair): a test's own folder only (a device test: /dev/wft-park-t2);
//                                 /dev/wayfinder-park is the running layer's and is refused (its pad would be listed again).
#include <linux/uinput.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <time.h>
#include "wfpark.h"

static int fails = 0, checks = 0;
#define CHECK(cond, ...) do { checks++; if (!(cond)) { fails++; printf("FAIL %s:%d ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static char B[128], DEV[160], SYS[160], STG[160];
static wfp_dirs D;

static void sh(const char *cmd) { if (system(cmd) != 0) { /* rm of a folder that isn't there */ } }
static void put(const char *path, const char *text) { FILE *f = fopen(path, "w"); if (f) { fputs(text, f); fclose(f); } }
static int exists(const char *path) { struct stat st; return lstat(path, &st) == 0; }
static ino_t ino(const char *path) { struct stat st; return lstat(path, &st) == 0 ? st.st_ino : 0; }

/** A fake device [ev] on sysfs input number [input]: its "node" (a plain file) and its sysfs entry. */
static void fake(const char *ev, int input, const char *dev) {
    char p[256], l[128];
    snprintf(p, sizeof p, "%s/%s", DEV, ev); put(p, "node");
    snprintf(p, sizeof p, "%s/%s", SYS, ev); mkdir(p, 0755);
    snprintf(p, sizeof p, "%s/%s/dev", SYS, ev); put(p, dev);
    snprintf(p, sizeof p, "%s/%s/device", SYS, ev); unlink(p);
    snprintf(l, sizeof l, "../../devices/virtual/input/input%d", input); if (symlink(l, p) < 0) printf("symlink failed\n");
}
static void sys_gone(const char *ev) { char c[300]; snprintf(c, sizeof c, "rm -rf %s/%s", SYS, ev); sh(c); }
static void sys_input(const char *ev, int input) {
    char p[256], l[128]; snprintf(p, sizeof p, "%s/%s/device", SYS, ev); unlink(p);
    snprintf(l, sizeof l, "../../devices/virtual/input/input%d", input); if (symlink(l, p) < 0) printf("symlink failed\n");
}
static void fresh(void) {
    char c[300]; snprintf(c, sizeof c, "chmod -R 700 %s 2>/dev/null; rm -rf %s", B, B); sh(c);
    mkdir(B, 0700); mkdir(DEV, 0755); mkdir(SYS, 0755);
}
static int open_node(const char *ev) { char p[256]; snprintf(p, sizeof p, "%s/%s", DEV, ev); return open(p, O_RDONLY | O_CLOEXEC); }
static void pth(char *out, size_t n, const char *dir, const char *name) { snprintf(out, n, "%s/%s", dir, name); }

static void unit(void) {
    snprintf(B, sizeof B, "/data/local/tmp/wft_park_test");
    snprintf(DEV, sizeof DEV, "%s/dev", B); snprintf(SYS, sizeof SYS, "%s/sys", B); snprintf(STG, sizeof STG, "%s/stage", B);
    D.dev = DEV; D.sys = SYS; D.stage = STG;
    char name[48], ev[16], p[256], q[256], why[96]; int input = -1;

    // names: "eventN.inputM", never a path
    CHECK(wfp_name("event11", 43, name, sizeof name) == 0 && !strcmp(name, "event11.input43"), "name %s", name);
    CHECK(wfp_name("event", 1, name, sizeof name) < 0, "no number");
    CHECK(wfp_name("event12345", 1, name, sizeof name) < 0, "5 digits");
    CHECK(wfp_name("../event1", 1, name, sizeof name) < 0, "a path");
    CHECK(wfp_name("event1/x", 1, name, sizeof name) < 0, "a path 2");
    CHECK(wfp_name("event1", -1, name, sizeof name) < 0, "no input");
    CHECK(wfp_parse("event11.input43", ev, sizeof ev, &input) == 0 && !strcmp(ev, "event11") && input == 43, "parse");
    const char *bad[] = { "event1.input", "event1.inputx", "input3.event1", "event1.input12345678", ".", "..", "event1input2",
                          "event1.input2.x", "eventa.input2", "x.input2", "event1.Input2", "" };
    for (unsigned i = 0; i < sizeof bad / sizeof *bad; i++) CHECK(wfp_parse(bad[i], ev, sizeof ev, &input) < 0, "parse refuses '%s'", bad[i]);

    // park and put back: the same inode, the folder gone after
    fresh(); fake("event11", 43, "13:75");
    pth(p, sizeof p, DEV, "event11"); ino_t i0 = ino(p);
    int fd = open_node("event11"); wfp_rec r; memset(&r, 0, sizeof r);
    CHECK(wfp_park(&D, fd, "event11", 43, &r, why, sizeof why) == 0, "park: %s", why);
    CHECK(!exists(p), "node out of the device folder");
    pth(q, sizeof q, STG, "event11.input43");
    CHECK(exists(q) && ino(q) == i0, "set aside under its name, same inode");
    CHECK(r.on && !strcmp(r.ev, "event11") && r.input == 43, "record");
    struct stat st; CHECK(lstat(STG, &st) == 0 && (st.st_mode & 0777) == 0700, "folder 0700");
    CHECK(wfp_unpark(&D, &r) == 1, "put back");
    CHECK(exists(p) && ino(p) == i0, "the same inode back");
    CHECK(!exists(q) && !exists(STG), "folder name and folder gone");
    CHECK(!r.on, "record cleared");
    CHECK(wfp_unpark(&D, &r) == 0, "a second put back does nothing");
    close(fd);

    // refused: the fd isn't that node / the device re-used / the folder not ours (no change at all)
    fresh(); fake("event11", 43, "13:75"); fake("event12", 44, "13:76");
    fd = open_node("event12"); memset(&r, 0, sizeof r);
    CHECK(wfp_park(&D, fd, "event11", 43, &r, why, sizeof why) < 0 && !strcmp(why, "node changed"), "another node: %s", why);
    close(fd);
    pth(p, sizeof p, DEV, "event11"); CHECK(exists(p) && !r.on, "untouched");
    fd = open_node("event11");
    CHECK(wfp_park(&D, fd, "event11", 42, &r, why, sizeof why) < 0 && !strcmp(why, "not in sysfs"), "re-used number: %s", why);
    CHECK(exists(p), "untouched 2");
    mkdir(STG, 0755);
    CHECK(wfp_park(&D, fd, "event11", 43, &r, why, sizeof why) < 0 && !strcmp(why, "folder not ours"), "open folder: %s", why);
    CHECK(exists(p), "untouched 3");
    rmdir(STG);
    char other[200]; snprintf(other, sizeof other, "%s/other", B); mkdir(other, 0700);
    if (symlink(other, STG) < 0) printf("symlink failed\n");
    CHECK(wfp_park(&D, fd, "event11", 43, &r, why, sizeof why) < 0, "a folder link is refused");
    CHECK(exists(p), "untouched 4");
    unlink(STG);
    CHECK(wfp_park(&D, fd, "event11", 43, &r, why, sizeof why) == 0, "then parks: %s", why);
    close(fd);

    // the device went while aside: never made again, its name goes
    sys_gone("event11");
    CHECK(wfp_unpark(&D, &r) == 0, "gone: dropped");
    CHECK(!exists(p), "no node made for a gone device");
    pth(q, sizeof q, STG, "event11.input43"); CHECK(!exists(q), "its name gone");

    // its eventN given to another device while aside: never made again
    fresh(); fake("event11", 43, "13:75");
    fd = open_node("event11"); memset(&r, 0, sizeof r);
    CHECK(wfp_park(&D, fd, "event11", 43, &r, why, sizeof why) == 0, "park 2");
    close(fd);
    sys_input("event11", 44);
    CHECK(wfp_unpark(&D, &r) == 0, "re-used: dropped");
    pth(p, sizeof p, DEV, "event11"); CHECK(!exists(p), "no node for a re-used number");

    // its name already in the device folder (made again meanwhile): left as it is, the aside name goes
    fresh(); fake("event11", 43, "13:75");
    fd = open_node("event11"); memset(&r, 0, sizeof r);
    CHECK(wfp_park(&D, fd, "event11", 43, &r, why, sizeof why) == 0, "park 3");
    close(fd);
    pth(p, sizeof p, DEV, "event11"); put(p, "new");
    ino_t inew = ino(p);
    CHECK(wfp_unpark(&D, &r) == 0, "there already: dropped");
    CHECK(ino(p) == inew, "the node there is untouched");
    pth(q, sizeof q, STG, "event11.input43"); CHECK(!exists(q), "aside name gone");

    // forget: a live one stays aside (the exit's sweep), a gone one's name goes
    fresh(); fake("event11", 43, "13:75"); fake("event12", 44, "13:76");
    fd = open_node("event11"); memset(&r, 0, sizeof r);
    CHECK(wfp_park(&D, fd, "event11", 43, &r, why, sizeof why) == 0, "park 4");
    close(fd);
    wfp_forget(&D, &r);
    pth(q, sizeof q, STG, "event11.input43"); CHECK(exists(q) && !r.on, "live orphan kept aside");
    fd = open_node("event12"); wfp_rec r2; memset(&r2, 0, sizeof r2);
    CHECK(wfp_park(&D, fd, "event12", 44, &r2, why, sizeof why) == 0, "park 5");
    close(fd);
    sys_gone("event12");
    wfp_forget(&D, &r2);
    pth(q, sizeof q, STG, "event12.input44"); CHECK(!exists(q), "gone one dropped");

    // the sweep: live ones back, gone ones dropped, other names left alone; a failure keeps the name
    fake("event13", 45, "13:77");
    fd = open_node("event13"); memset(&r2, 0, sizeof r2);
    CHECK(wfp_park(&D, fd, "event13", 45, &r2, why, sizeof why) == 0, "park 6");
    close(fd);
    sys_gone("event13");
    pth(q, sizeof q, STG, "notes.txt"); put(q, "x");
    CHECK(wfp_sweep(&D) == 1, "sweep: one back");
    pth(p, sizeof p, DEV, "event11"); CHECK(exists(p), "event11 back");
    pth(p, sizeof p, DEV, "event13"); CHECK(!exists(p), "event13 (gone) not made");
    CHECK(exists(q), "a name that isn't ours is left alone");
    unlink(q); rmdir(STG);

    fresh(); fake("event11", 43, "13:75");
    fd = open_node("event11"); memset(&r, 0, sizeof r);
    CHECK(wfp_park(&D, fd, "event11", 43, &r, why, sizeof why) == 0, "park 7");
    close(fd);
    chmod(DEV, 0555);                                  // the device folder refuses the link: the name must stay
    CHECK(wfp_sweep(&D) == -1, "sweep says it failed");
    pth(q, sizeof q, STG, "event11.input43"); CHECK(exists(q), "name kept on a failed put back");
    chmod(DEV, 0755);
    CHECK(wfp_sweep(&D) == 1, "a later sweep puts it back");
    pth(p, sizeof p, DEV, "event11"); CHECK(exists(p) && !exists(STG), "back, folder gone");

    // a folder that isn't ours: the sweep never touches it
    fresh(); fake("event11", 43, "13:75");
    pth(p, sizeof p, DEV, "event11"); mkdir(STG, 0755); pth(q, sizeof q, STG, "event11.input43");
    if (link(p, q) < 0) printf("link failed\n");
    unlink(p);
    CHECK(wfp_sweep(&D) == 0 && exists(q) && !exists(p), "folder not ours: left alone");

    // 1.5-beta2 (GitHub #102, repair): the restore path's own string building (no snprintf in the signal handler)
    char j[16];
    CHECK(wfp_join(j, sizeof j, "/a", "/", "b") == 0 && !strcmp(j, "/a/b"), "join %s", j);
    CHECK(wfp_join(j, sizeof j, "abc", NULL, NULL) == 0 && !strcmp(j, "abc"), "join one");
    CHECK(wfp_join(j, 4, "abc", "d", NULL) < 0 && j[0] == 0, "join too long");
    CHECK(wfp_join(j, 4, "abc", NULL, NULL) == 0, "join exactly fits");
    CHECK(wfp_utoa(j, sizeof j, 0) == 0 && !strcmp(j, "0"), "utoa 0");
    CHECK(wfp_utoa(j, sizeof j, 4294967295u) == 0 && !strcmp(j, "4294967295"), "utoa max %s", j);
    CHECK(wfp_utoa(j, 3, 123) < 0, "utoa no room");
    CHECK(wfp_utoa(j, 4, 123) == 0 && !strcmp(j, "123"), "utoa fits");

    // 1.5-beta2 (GitHub #102, repair): a planned restart's node taken where it is (wfp_adopt) — nothing moves
    fresh(); fake("event11", 43, "13:75");
    fd = open_node("event11"); memset(&r, 0, sizeof r);
    CHECK(wfp_park(&D, fd, "event11", 43, &r, why, sizeof why) == 0, "park 8");
    close(fd);                                         // "q k": that wfpad gone, the node left aside
    pth(q, sizeof q, STG, "event11.input43"); i0 = ino(q);
    fd = open(q, O_RDONLY | O_CLOEXEC);                // the next wfpad, started on the folder's node
    wfp_rec ra; memset(&ra, 0, sizeof ra); char aev[16] = ""; int ain = -1;
    CHECK(wfp_adopt(&D, fd, "event11.input43", &ra, aev, sizeof aev, &ain, why, sizeof why) == 0, "adopt: %s", why);
    CHECK(ra.on && !strcmp(ra.ev, "event11") && ra.input == 43 && !strcmp(aev, "event11") && ain == 43, "adopt: record");
    pth(p, sizeof p, DEV, "event11");
    CHECK(!exists(p) && exists(q) && ino(q) == i0, "adopt: nothing moved");
    CHECK(wfp_unpark(&D, &ra) == 1 && exists(p) && ino(p) == i0 && !exists(STG), "adopted: put back on exit");
    close(fd);
    // refused: another node, a name not there, a bad name, its name back in the device folder, a re-used number, a folder
    // not ours — nothing changes
    fresh(); fake("event11", 43, "13:75"); fake("event12", 44, "13:76");
    fd = open_node("event11"); memset(&r, 0, sizeof r);
    CHECK(wfp_park(&D, fd, "event11", 43, &r, why, sizeof why) == 0, "park 9");
    close(fd);
    int f12 = open_node("event12"); memset(&ra, 0, sizeof ra);
    CHECK(wfp_adopt(&D, f12, "event11.input43", &ra, aev, sizeof aev, &ain, why, sizeof why) < 0 && !strcmp(why, "aside changed") && !ra.on,
          "adopt another node: %s", why);
    close(f12);
    pth(q, sizeof q, STG, "event11.input43"); fd = open(q, O_RDONLY | O_CLOEXEC);
    CHECK(wfp_adopt(&D, fd, "event11.input42", &ra, aev, sizeof aev, &ain, why, sizeof why) < 0 && !ra.on, "adopt a name not there: %s", why);
    CHECK(wfp_adopt(&D, fd, "../x", &ra, aev, sizeof aev, &ain, why, sizeof why) < 0 && !strcmp(why, "bad node"), "adopt a bad name: %s", why);
    pth(p, sizeof p, DEV, "event11"); put(p, "made again");
    CHECK(wfp_adopt(&D, fd, "event11.input43", &ra, aev, sizeof aev, &ain, why, sizeof why) < 0 && !strcmp(why, "node there"), "adopt, name taken: %s", why);
    unlink(p);
    sys_input("event11", 50);
    CHECK(wfp_adopt(&D, fd, "event11.input43", &ra, aev, sizeof aev, &ain, why, sizeof why) < 0 && !strcmp(why, "not in sysfs"), "adopt re-used: %s", why);
    sys_input("event11", 43);
    chmod(STG, 0755);
    CHECK(wfp_adopt(&D, fd, "event11.input43", &ra, aev, sizeof aev, &ain, why, sizeof why) < 0 && !strcmp(why, "folder not ours"), "adopt, folder open: %s", why);
    chmod(STG, 0700);
    CHECK(exists(q) && !ra.on, "refusals changed nothing");
    close(fd);
    CHECK(wfp_sweep(&D) == 1 && exists(p) && !exists(STG), "then the sweep puts it back");

    // the cap: 4 parks in 30 s, the 5th refused; old ones age out
    wfp_cap c; memset(&c, 0, sizeof c);
    long t = 1000000L;
    for (int i = 0; i < 4; i++) CHECK(!wfp_cap_hit(&c, t + i * 1000000L, 4, 30000000L), "park %d allowed", i + 1);
    CHECK(wfp_cap_hit(&c, t + 5000000L, 4, 30000000L), "5th within 30 s refused");
    CHECK(!wfp_cap_hit(&c, t + 40000000L, 4, 30000000L), "allowed again 30 s later");

    char cmd[300]; snprintf(cmd, sizeof cmd, "chmod -R 700 %s 2>/dev/null; rm -rf %s", B, B); sh(cmd);
}

// ---- root only: a real device of our own ----

static long ms(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec * 1000L + ts.tv_nsec / 1000000; }

/** Android lists a device named [name] (dumpsys input's Event Hub part). */
static int listed(const char *name) {
    FILE *f = popen("dumpsys input", "r"); if (!f) return -1;
    char line[512]; int hit = 0, hub = 1;
    while (fgets(line, sizeof line, f)) {
        if (!strncmp(line, "Input Reader State", 18)) hub = 0;
        if (hub && strstr(line, name)) hit = 1;
    }
    pclose(f);
    return hit;
}
static int wait_listed(const char *name, int want, int max_ms) {
    long end = ms() + max_ms;
    while (ms() < end) { if (listed(name) == want) return 1; usleep(100000); }
    return listed(name) == want;
}

/** A uinput device: [pad] = a 2020:0111 pad (south button, two sticks), else one key (F24), no gamepad. */
static int make_dev(const char *name, const char *phys, int pad, char *ev, size_t evn, int *input) {
    int u = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (u < 0) return -1;
    ioctl(u, UI_SET_EVBIT, EV_KEY);
    if (pad) {
        ioctl(u, UI_SET_KEYBIT, BTN_SOUTH); ioctl(u, UI_SET_KEYBIT, BTN_EAST); ioctl(u, UI_SET_EVBIT, EV_ABS);
        int ax[] = { ABS_X, ABS_Y, ABS_RX, ABS_RY };
        for (int i = 0; i < 4; i++) {
            struct uinput_abs_setup s; memset(&s, 0, sizeof s); s.code = ax[i];
            s.absinfo.minimum = -32767; s.absinfo.maximum = 32767;
            ioctl(u, UI_SET_ABSBIT, ax[i]); ioctl(u, UI_ABS_SETUP, &s);
        }
    } else ioctl(u, UI_SET_KEYBIT, KEY_F24);
    ioctl(u, UI_SET_PHYS, phys);
    struct uinput_setup us; memset(&us, 0, sizeof us);
    us.id.bustype = BUS_VIRTUAL; us.id.vendor = pad ? 0x2020 : 0x1209; us.id.product = pad ? 0x0111 : 0x0102; us.id.version = 1;
    snprintf(us.name, sizeof us.name, "%s", name);
    if (ioctl(u, UI_DEV_SETUP, &us) < 0 || ioctl(u, UI_DEV_CREATE) < 0) { close(u); return -1; }
    char sys[64] = "", dir[128];
    ev[0] = 0; *input = -1;
    if (ioctl(u, UI_GET_SYSNAME(sizeof sys), sys) >= 0) {
        *input = atoi(sys + 5);
        snprintf(dir, sizeof dir, "/sys/devices/virtual/input/%s", sys);
        for (int t = 0; t < 40 && !ev[0]; t++) {
            DIR *d = opendir(dir);
            if (d) { struct dirent *e; while ((e = readdir(d))) if (!strncmp(e->d_name, "event", 5)) snprintf(ev, evn, "%s", e->d_name); closedir(d); }
            if (!ev[0]) usleep(25000);
        }
    }
    // its node made by ueventd
    char p[64]; snprintf(p, sizeof p, "/dev/input/%s", ev);
    for (int t = 0; t < 40 && !exists(p); t++) usleep(25000);
    return u;
}
static void kill_dev(int u) { if (u >= 0) { ioctl(u, UI_DEV_DESTROY); close(u); } }

static int label(const char *p, char *out, size_t n) { ssize_t k = getxattr(p, "security.selinux", out, n - 1); if (k < 0) k = 0; out[k] = 0; return (int)k; }

static void probe(void) {
    wfp_dirs R = { "/dev/input", "/dev/wft-park-probe", "/sys/class/input" };
    const char *NM = "wft park probe";
    char ev[32], p[64], why[96], l0[256], l1[256]; int input;
    int u = make_dev(NM, "wft-park-probe", 0, ev, sizeof ev, &input);
    CHECK(u >= 0 && ev[0] && input >= 0, "probe device made (%s input%d)", ev, input);
    if (u < 0 || !ev[0]) return;
    snprintf(p, sizeof p, "/dev/input/%s", ev);
    CHECK(wait_listed(NM, 1, 3000), "Android lists the probe");
    struct stat a; lstat(p, &a); label(p, l0, sizeof l0);
    printf("probe %s input%d: mode %o uid %d gid %d label %s\n", ev, input, a.st_mode & 07777, a.st_uid, a.st_gid, l0);

    int fd = open(p, O_RDONLY | O_CLOEXEC); wfp_rec r; memset(&r, 0, sizeof r);
    long t0 = ms();
    CHECK(wfp_park(&R, fd, ev, input, &r, why, sizeof why) == 0, "park: %s", why);
    CHECK(!exists(p), "node out of /dev/input");
    CHECK(wait_listed(NM, 0, 2000), "Android stops listing it");
    printf("unlisted after %ld ms\n", ms() - t0);
    t0 = ms();
    CHECK(wfp_unpark(&R, &r) == 1, "put back");
    struct stat b; lstat(p, &b); label(p, l1, sizeof l1);
    CHECK(b.st_ino == a.st_ino && b.st_mode == a.st_mode && b.st_uid == a.st_uid && b.st_gid == a.st_gid && b.st_rdev == a.st_rdev,
          "same inode, mode, owner, device");
    CHECK(!strcmp(l0, l1), "same label (%s / %s)", l0, l1);
    CHECK(wait_listed(NM, 1, 3000), "Android lists it again");
    printf("listed again after %ld ms\n", ms() - t0);
    CHECK(!exists("/dev/wft-park-probe"), "folder gone");

    // gone while aside: no node made
    CHECK(wfp_park(&R, fd, ev, input, &r, why, sizeof why) == 0, "park again: %s", why);
    close(fd); kill_dev(u); u = -1;
    usleep(300000);
    CHECK(wfp_unpark(&R, &r) == 0, "gone: dropped");
    CHECK(!exists(p), "no node for the gone device");
    CHECK(!exists("/dev/wft-park-probe"), "folder gone 2");

    // a process killed while its node is aside: a sweep puts it back
    u = make_dev(NM, "wft-park-probe", 0, ev, sizeof ev, &input);
    snprintf(p, sizeof p, "/dev/input/%s", ev);
    CHECK(u >= 0 && wait_listed(NM, 1, 3000), "probe made again");
    pid_t c = fork();
    if (c == 0) {
        int f2 = open(p, O_RDONLY | O_CLOEXEC); wfp_rec r3; memset(&r3, 0, sizeof r3); char w3[96];
        if (wfp_park(&R, f2, ev, input, &r3, w3, sizeof w3) == 0) { pause(); }
        _exit(1);
    }
    for (int t = 0; t < 40 && exists(p); t++) usleep(25000);
    kill(c, SIGKILL); waitpid(c, NULL, 0);
    CHECK(!exists(p), "killed: its node still aside");
    CHECK(wait_listed(NM, 0, 2000), "and not listed");
    CHECK(wfp_sweep(&R) == 1, "the sweep puts it back");
    CHECK(exists(p) && wait_listed(NM, 1, 3000), "listed again after the sweep");
    kill_dev(u);
    rmdir("/dev/wft-park-probe");
}

static int fake_u = -1;
static void fakepad(void) {
    char line[64], ev[32]; int input;
    setvbuf(stdout, NULL, _IOLBF, 0);
    for (;;) {
        if (!fgets(line, sizeof line, stdin)) break;
        if (line[0] == 'c') {
            kill_dev(fake_u);
            fake_u = make_dev("wft fake pad", "wft-fake-pad", 1, ev, sizeof ev, &input);
            printf(fake_u >= 0 ? "node %s input%d\n" : "E make\n", ev, input);
        } else if (line[0] == 'd') { kill_dev(fake_u); fake_u = -1; printf("gone\n"); }
        else if (line[0] == 'p' && fake_u >= 0) {
            struct input_event e[4]; memset(e, 0, sizeof e);
            e[0].type = EV_KEY; e[0].code = BTN_SOUTH; e[0].value = 1; e[1].type = EV_SYN;
            e[2].type = EV_KEY; e[2].code = BTN_SOUTH; e[2].value = 0; e[3].type = EV_SYN;
            if (write(fake_u, e, 2 * sizeof *e) < 0) printf("E write\n");
            usleep(80000);
            if (write(fake_u, e + 2, 2 * sizeof *e) < 0) printf("E write\n");
            printf("pressed\n");
        } else if (line[0] == 'q') break;
    }
    kill_dev(fake_u);
}

int main(int argc, char **argv) {
    if (argc > 1 && !strcmp(argv[1], "fakepad")) { fakepad(); return 0; }
    // root, device tests: what the helper's PadPark.restoreAll does (the same rules), on the real folder
    // 1.5-beta2 (GitHub #102, repair): a test's own folder on /dev only — never the running layer's (its pad listed again)
    if (argc > 1 && !strcmp(argv[1], "sweep")) {
        if (argc < 3 || strncmp(argv[2], "/dev/", 5) || !strncmp(argv[2], WFP_DIR, strlen(WFP_DIR)) || !strncmp(argv[2], "/dev/input", 10) ||
            strstr(argv[2], "..")) { printf("sweep: give a test folder on /dev (never %s)\n", WFP_DIR); return 2; }
        wfp_dirs T = { "/dev/input", argv[2], "/sys/class/input" };
        int k = wfp_sweep(&T); printf("sweep: %d\n", k); return k < 0 ? 1 : 0;
    }
    if (argc > 1 && !strcmp(argv[1], "probe")) {
        if (geteuid() != 0) { printf("probe: root only\n"); return 2; }
        probe();
        printf("wfpark probe: %d checks, %d failed\n", checks, fails);
        return fails ? 1 : 0;
    }
    unit();
    printf("wfpark: %d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
