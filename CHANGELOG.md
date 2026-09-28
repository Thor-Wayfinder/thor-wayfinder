# Changelog

## 1.3.1 — September 2026

- **The AYN button's tap and hold are on the Combos page** (they were only under Quick panel), at the top, and a hold
  shows on the "Your controls" sheet — e.g. hold the AYN button = bottom screen off / on.
- **New: volume and brightness per screen as actions** — Louder / Quieter (both screens, keeping their difference), Top
  screen louder / quieter, Bottom screen louder / quieter, and Top / Bottom screen brighter / dimmer. Put them on a combo,
  the AYN button or a button's long press.
- **New:** stick lights go down to 1 % (they stopped at 5 %).
- **Fixed:** Home on the bottom screen did nothing when Wayfinder hadn't seen a launcher there yet — it now opens the
  launcher Android has for a second screen, or says so.
- **Fixed (controller):** after picking the AYN button's action, the controller is back on its row.
- **New:** the version number at the bottom of Help & status.
- **New: "Update available"** — once a day Wayfinder asks GitHub for the latest version number (nothing else is sent);
  a newer one shows as a small orange-dot pill next to Ready, and opens the release page. Off in Help & status.
- **New: the AYN button can start a combo** — hold it and press a button (AYN + X…), like Home and Back. Record it on the
  Combos page. Its own tap and hold still work when no combo is made.

## 1.3 — September 2026

- **Fixed:** the quick panel no longer pauses the game under it (WatermelonDS and other emulators paused, and their sound
  drifted out of sync), and closing it no longer sends a frontend (iiSU…) back to its home. It opening over a video on
  the bottom screen (YouTube…) no longer sends it into picture-in-picture either.
- **New:** over dual-screen games (melonDS, Azahar, Cemu…) the quick panel now opens on the bottom screen, over the
  game's second screen, like everywhere else (it was a side sheet on the top screen).
- **New:** "The panel takes the controller" (Quick panel settings) — off: the panel is touch-only and the game keeps the
  controller while it's open.
- **Fixed:** swapping screens never restarts a game: RetroArch launched from a frontend came back at its main menu. The
  game stays where it was, and a frontend that jumps in front of it after the swap is sent back behind.
- **Fixed:** swapping onto a screen that shows another app's second screen (NeoStation, a dual-screen game) hid the app
  under it — Wayfinder now says so instead.
- **New:** stick lights "Screen colour" can come from the top screen, the bottom screen or the controller's screen
  (a DS game's bottom screen is menus — keep the colours on the top one).
- **Fixed:** RetroArch — the controller's Back reaches RetroArch again (held too), so Back hotkeys and Back as the menu
  button work. Per app: App profiles → "Back button: Automatic / Wayfinder / The game"; Automatic gives Back to RetroArch
  when its configuration uses Back for a hotkey or the menu.
- **Fixed:** Bluetooth and USB controllers are left alone — the input layer only takes over the Thor's own controls, even
  when a pad is already connected when Wayfinder starts (the Thor's own buttons stopped working then).
- **Fixed:** less battery used in sleep — the input layer's background check (every 3 s, even asleep) now rests while
  the screens are off.
- **Fixed:** apps moved to the bottom screen played quieter: Wayfinder kept AYN's untouched bottom-screen level. Both
  screens now start at the same level, and the Volume card shows when they differ, with "Same on both".
- **Fixed:** the quick panel over a dual-screen game sometimes didn't take the controller (B went to the game).
- **New:** Help & status → System access shows the input layer's state, and why it stopped if it did; the "couldn't
  start" message says why too.
- **New: Touch mode for the trackpad** (Keyboard & mouse → Trackpad → "Mouse / Touch"): a pointer on the game's screen and
  real finger touches there — tap, hold and drag, two-finger swipe — for games that ignore a mouse. Remembered.
- **New: "Gyro on / off"** — an action (a combo, the AYN button, a button's long press in Game controls) and a quick-panel
  tile: pauses the game's gyro and turns it back on.
- **Fixed:** the CPU temperature reads the CPU clusters' sensors (it showed the hottest single-core spot, often 10 °C more).
- **Fixed:** a screenshot of both screens skips a screen that's off.
- **Fixed:** Android's navigation bar popping back up although it's hidden: Wayfinder hides it again.
- **Fixed:** the app-profile window scrolls (its last rows were off the screen).
- **Fixed (controller):** in Game controls, B right after closing a choice now leaves the page (it did nothing once);
  the D-pad on the controller drawing moves straight across (Left on Y reaches the left stick); back on the Hub's home,
  the card you opened has the focus again; "Close this app" from the quick panel closes the game it's about.
- **Fixed:** a Custom fan (AYN's curve) is kept when you change performance; Custom is now in the fan tile and per-app
  profiles, and a "Fan curve" tile opens AYN's curve editor.
- **Fixed:** with AYN's Xbox controller style, the keyboard and Recents confirm with the bottom button.
- **Fixed:** the switches under Sleep actions update straight away; the keyboard has `<` and `>`.
- **Faster:** moving the controller to the top screen answers as quickly as moving it to the bottom.
- **New: remap each D-pad direction** — up, down, left, right on their own: a key, a click, the mouse wheel, another button,
  a Wayfinder action. The D-pad's page is laid out as a D-pad: press a direction to change it.
- **New: a stick as the mouse, the scroll wheel or four keys** (arrows or W A S D), with a dead zone so a resting or drifting
  stick never moves anything. Plus a **Web browsing** preset (mouse, scroll, clicks, Back).
- **New: AYN button — tap and hold** can each do an action (e.g. hold = bottom screen off). Default unchanged.
- **New: Sleep** — an action and a quick-panel tile.
- **New: button names on screen** — Automatic (follows AYN's controller style) / As printed / Xbox, for every hint and badge.
- **New: Guide & notes** — an action and a tile: the game's guide on the other screen (over a dual-screen game's second
  screen too), or beside the game on a single-screen device.
- **New: a simpler keyboard in Keyboard & mouse** (letters, numbers and the usual keys), and a D-pad option in Wayfinder's
  keyboard (the D-pad picks keys). Hold Space and slide to move the text cursor.
- **New: Black theme** for OLED screens (pure black, no aurora or background blur).
- **New: volume boost** — +3 to +12 dB with a limiter, on every output; in Screens & power and as a tile.
- **New: invert a stick left / right.**
- **New: per app and per game** — the frame-rate counter (usual / shown / hidden) and the bottom screen off; "Keep for
  <game>" in the quick panel now remembers the bottom screen too. The Frame rate tile steps through FPS, + battery,
  + temperatures, hidden.
- **New: Mouse mode (AYN)** — an action for any combo and a tile.
- **New: stick lights "each stick its own side"** of the screen's colours.
- **New: the Recents controller hint** can be compact or off, in any corner.
- While Wayfinder's keyboard is open, the controller types: the app's Game controls pause until it closes.

## 1.2 — September 2026

- **New: a Guide tab in Keyboard & mouse (Home + Y)** — the game's guide page and your notes on the other screen,
  one combo away, in any game — even dual-screen ones (melonDS, Azahar, Cemu…). Pin a page, go back, or open the
  full Guide page to edit your notes.
- **Guides and notes per game:** inside an emulator, each game now has its own pinned page and notes (before,
  all the games of one emulator shared them), and the guide search uses the game's title.
- **Pinned guides work offline:** pinning a page saves a copy of it (refreshed whenever it loads online); with no
  connection, or if the site fails, the pinned guide opens from that copy.
- A guide page that can't load now says so (with Retry) instead of staying blank — e.g. on a Wi-Fi that needs a
  login page first.

## 1.1 — September 2026

- **New: "Close this app"** — an action for any combo or game button: closes the app on the screen that has
  the controller and goes home there, without Android's multitask view. (No combo by default — pick one.)
- **Fixed:** in dual-screen games (melonDS, Azahar, Cemu…) the AYN button's quick panel opened hidden under the
  game's bottom screen. It now opens as the top screen's side sheet over the game; the Guide no longer opens
  hidden there either.
- **Fixed:** the quick panel's top bar (CPU, GPU, RAM, Battery) overflowed with the default text size or a
  12-hour clock — "Battery" wrapped letter by letter or was cut off. Its text now shrinks to fit your text
  size and clock, so all four readouts stay (RAM steps aside only with the very largest text sizes).

## 1.0 — September 2026

A new app: **Wayfinder — For the AYN Thor** (package `app.wayfinder`). It replaces the beta "Thor Wayfinder"
(`com.thorwayfinder.app`) — **uninstall the beta first**; its settings don't carry over.

### New
- **Nothing to install besides the app**: uses the Thor's own system service — no root, Shizuku or computer; the tour
  sets up the rest. Apps move live
  between the screens (no restart, no duplicate), verified and retried; a glass slide animation.
- **The controller follows you**: send it with Home + right stick; it stays where sent (or follows touch, always
  top, always bottom). Home goes home on the screen that has the controller.
- **Combos** on Home and Back, all changeable, per app too; hold Home for a cheat sheet. Combos can open an app,
  an app pair or a Wayfinder page.
- **Input layer**: games see a clean copy of the controller (Home / Back combos never reach them), the controller stays player 1,
  emergency off (hold Home + Back 5 s).
- **Game controls** (Home + X): per app and per game — remaps, keyboard keys, mouse, turbo, toggle, long / double
  press, macros, chords, hold-to-Shift layer, gyro aiming, stick deadzone / curve / full-at, trigger range, face
  buttons, emulator presets, per-game performance / fan / refresh rate / lights. Share and import mappings safely.
- **Quick panel** (AYN button): per-screen brightness and volume, screen modes, performance, fan, refresh rate,
  live stats, 39 shortcut tiles, Now playing + Keep for this game, Details and Media widgets, arrangeable.
- **Keyboard & mouse** (Home + Y) on the other screen and **Wayfinder Keyboard** (17 languages, controller typing).
- **Recent apps** driven with the controller.
- **App profiles**: opens on, bottom-screen rule, performance, fan, refresh rate, combos, lights, Guide & notes.
- **App pairs**, restore the screens after a restart.
- **Test the controller**: drift measurement and a one-press deadzone.
- **Sleep & standby** (lid guard, sleep actions, standby stats), **speaker sound fix**, **stick lights**, **Do not
  disturb while playing**, **FPS counter** (+ battery, + temperatures), screenshots of one or both screens, screen
  recording, **backup and restore**.
- An interactive **tour** that sets everything up and teaches the combos by doing them.
- A "Recent apps can't open" warning with a one-press fix when a system reset leaves Android thinking the
  Thor's setup isn't finished.

### Security
Dedicated security review passes (AI-assisted) before release; findings fixed and re-tested on a Thor. See [SECURITY.md](SECURITY.md).

## 0.1 — beta (April 2026)
- Hold Back to move / swap apps between screens, double Back for Recents; Shizuku for live moves.
