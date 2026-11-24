# Wayfinder — security

Wayfinder needs more access than most apps: it's an accessibility service and a keyboard, and it runs
system commands through the AYN Thor's own system service (the same one AYN's "Run script as root"
uses). So before 1.0 its code went through repeated, dedicated security review passes (September 2026;
AI-assisted, like the code — no outside firm), each finding fixed and re-tested on a real Thor. This is
what was checked and what was found. This page describes 1.5-beta2 (November 2026). The Bluetooth / USB
controller support added in 1.5-beta1, and what 1.5-beta2 changes, went through the same kind of review.

## What was reviewed
- **Every exported component** (anything another app can call): only the main screen can be opened by
  other apps, and it accepts nothing but a fixed list of page names (plus per-app pages, only for a valid
  package name, and a one-time number that only counts when it matches the one Wayfinder made itself a
  moment before, so another app can't change what Back does on a page). The accessibility service and the
  keyboard can only be bound by Android itself; the two library components (Shizuku's provider, AndroidX's
  profile installer) need system-level permissions.
- **Every string that reaches a system (root) command**: package names, game titles, media titles,
  files, settings values — validated and quoted.
- **The channel between the app and its system helper** (a local socket): both ends check the other's
  identity (user id) and a handshake, so an ordinary app can't impersonate either side or read what goes
  through it (controller events and the Keyboard & mouse keys; Wayfinder Keyboard's typing never uses it).
  On a stock Thor any app can already run commands as root through AYN's service (see Known limits), and
  such a process could pass this check — it gains nothing it didn't already have.
- **Files Wayfinder imports** (shared controller mappings, backups): size caps, strict formats, every
  value range-checked; a shared file can't carry Wayfinder actions, system keys or system shortcuts —
  including shortcuts split across two buttons (if the file holds Ctrl or Alt anywhere, Tab, Space and Esc
  are removed everywhere) — and macros arrive switched off. A full restore only works with a backup made
  by the same installation (signed with a key that never leaves the device); anything else restores
  game controls only (the Thor's and each controller's own), through the same filter.
- **The native input layer** (the controller copy games see): its profile parser rejects any
  out-of-range value. Its tests and the controller reader's (below) — about 4,000 automated checks,
  `tools/build_wfpad.sh test` and `tools/build_wfhid.sh test` — run on a Thor.
- **Bluetooth and USB controllers** (new in 1.5): a small native reader (`wfhid`, built from `fx/wfhid.c`)
  runs as root, one per connected controller, started by the system helper only from the app's own copy
  of the binary (owned by the app, writable by nobody else). It reads the controller's raw HID reports and
  makes one game controller from them; its descriptor and report parsing is bounds-checked, and what the
  app sends it (controller keys, button maps, profiles) is checked against strict patterns on both sides.
  So that games don't see a controller twice, the helper removes the duplicate input node Android or AYN
  made for it, and puts it back (same device, owner and mode) when Wayfinder lets that controller go; the
  list of removed nodes lives in the app's private files and every field is checked when it's read back.
  While Game controls run, the input layer also sets AYN's own controller aside (new in 1.5-beta2), so apps
  that list every controller can't give it a player slot: its /dev/input node is moved into a folder of
  root's own on /dev (`/dev/wayfinder-park`) and moved back, the same node with its owner, mode and label,
  when Game controls stop, when Wayfinder is uninstalled, or at the latest when the Thor restarts.
  The names of Wayfinder's own controllers (only names ending in "(Wayfinder)") go on the list of devices
  AYN's mapping service leaves alone.
- **Logging and debug code**: the release build is not debuggable, has no test receivers, and writes no
  debug/info logs from the app (the release script checks the built app for any); its own warnings don't
  name apps or keys (logs could reveal which apps you use; an Android error message quoted in a rare
  failure may still contain one). A few root-only entry points used to drive the screens (focus, input
  tools) are part of the app itself, not test code. The input layer's binary keeps five command-line
  switches used by its on-device tests; Wayfinder never passes them. Five system properties, each set to
  0, are there for troubleshooting: `debug.wayfinder.aynhold` (AYN's list isn't used),
  `debug.wayfinder.unlink` (duplicate input nodes are switched off instead of removed),
  `debug.wayfinder.uhidcopy` (the Thor's controller copy is made without a serial),
  `debug.wayfinder.nodestage` (a controller's input node is given back the older way, straight into
  place) and `debug.wayfinder.park` (AYN's controller isn't set aside, only switched off as before). Each one makes Wayfinder do what it already falls back to by itself when the Thor refuses the
  usual way. On a stock Thor any app could set them (see Known limits), and they reset when the Thor
  restarts.

## What was found and fixed (highlights)
- A command-injection path through an app-supplied package name (a malicious app could have named
  itself so that a system command ran extra code) — every package name is now validated and quoted.
- The app ↔ helper socket accepted any peer — now identity-checked on both ends.
- An activity-name injection in a system `am start` call — now validated.
- A crafted media title could fake a whole playback session (the Media card then showed, and its "Open"
  button opened, another app) — the separator is now removed from titles before parsing, and only exact,
  validated lines are accepted.
- A shared mapping could assemble a system shortcut from two buttons (Alt on one, Tab on another) — now
  filtered across the whole file.
- Restoring a backup could turn off your own Do not disturb — runtime state is no longer backed up.
- Release warnings that named apps, keys or whole commands — removed.
- Plus many reliability fixes found in the same passes (stuck buttons, overlays, sensors left running).

## Known limits (said plainly)
- The Thor's system service is open to every app on the device — that's AYN's design, not Wayfinder's,
  and Wayfinder doesn't make it wider.
- Wayfinder doesn't need root or Shizuku, but uses them as fallbacks when present: on a rooted Thor your
  root manager may ask once, and if Shizuku runs it asks for its permission (saying no is fine to both).
- The helper's socket has a fixed name. An app that grabs that name first can't read or inject anything
  (the helper refuses it), but it can stop Wayfinder's combos and input features from working — a
  denial of service only. (A per-launch random name is planned.)
- Screenshots and screen recordings capture everything shown, including content apps hide from
  screenshots (passwords, banking), and are saved to your Pictures / Movies folders.
- When Wayfinder gives a controller back, its input node comes back with the same permissions as the
  Thor's own input nodes, as it was before Wayfinder took it. On a stock Thor every input node, AYN's own
  controller included, is readable and writable by every app all the time; that's AYN's design, and
  Wayfinder doesn't make it wider.
- Turning "Wayfinder handles controllers" off takes Wayfinder's controller names off AYN's list (above).
  Uninstalling Wayfinder without doing so leaves them there; they name only Wayfinder's own controllers,
  so they do nothing.
- For each Bluetooth / USB controller it handles, Wayfinder writes a small buttons layout file to
  /data/system/devices/keylayout: a copy of AYN's own controller layout, readable by every app like the
  system's own layouts. The files name no controller and apply only to Wayfinder's own controllers.
  Turning "Wayfinder handles controllers" off removes them, and so does uninstalling Wayfinder while it's
  turned on; otherwise they stay there and do nothing. Also left after an uninstall: the
  `persist.wayfinder.wide` system property, a number only Wayfinder's Speaker fix reads.
- The Guide page is a small web browser: a DuckDuckGo search for your game, or a page you pinned — when
  you open it, or automatically for apps where you turned the Guide companion on (off by default). It
  runs inside Wayfinder's app (with JavaScript on, and no bridge from web pages into the app). Like any
  browser it keeps what sites store (their own cookies, site data and cache; third-party cookies are
  off), and an offline copy of each pinned page. They stay in Wayfinder's own storage, never backed up
  or exported. Help & status › Clear the Guide's site data removes what sites stored, and Remove the
  offline copies removes the offline copies; your pinned pages and your notes stay (each copy is saved
  again the next time its page loads online).

## Reporting a vulnerability
Please don't open a public issue: use **Report a vulnerability** on this repository's Security tab
(private). Other questions: open an issue.
