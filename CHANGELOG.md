# Changelog

## 1.5-beta1 — October 2026 (pre-release)

A test version of 1.5: everything below is new since 1.4.1. 1.4.1 stays the current release — the Download link and
Wayfinder's update notice still point to it.

**Before you install**
- **Don't go back to an older version after installing this one** unless you have to: Android installs 1.4.1 (or an
  older beta) only after an uninstall, and Wayfinder's settings go with it. Save a backup in 1.4.1 first (More › Help &
  status › Back up now): restored after a reinstall, it brings back your game controls — combos and other settings are
  set up again.
- **Wayfinder's update notice only tells you about full releases.** Testing the beta? Watch the
  [Releases](https://github.com/Thor-Wayfinder/thor-wayfinder/releases) page for the next one.
- **Map your Bluetooth / USB controllers again in your emulators, once** (Eden, Dolphin…): games now see each one as
  Wayfinder's controller ("… (Wayfinder)"). After that, a controller turned off for less than 30 minutes, or
  reconnected over Bluetooth or USB, keeps its place.
- Update with no game running: an update while a game runs can number the controllers differently until that game's
  next start.

**New: Bluetooth and USB controllers** (on by default — Controller › Settings for every controller › "Wayfinder handles
controllers")
- **Wayfinder reads each controller itself** — PlayStation (DualSense, DualShock 4), Xbox (over Bluetooth), Switch Pro,
  Joy-Cons and other gamepads — so games get every button and stick, and AYN's incomplete copy is taken away. Rumble
  too, and aim with motion on Nintendo controllers.
- **Every controller gets the Thor's buttons**: its Home is the Thor's Home (your combos), and it has a Back and an AYN
  button — a PlayStation controller's touchpad click and Mute, an Xbox Series pad's Share, a Switch Pro's or a Joy-Con
  pair's Capture; a controller without spare buttons uses Home + Select and Home + Start. "Back and AYN buttons" on its
  page changes them.
- **Controller is a list**: the Thor first, then each controller with its own page — face buttons, Test it (its own
  deadzone), its buttons in games, Home and combos, and a setup wizard for a button read wrong.
- **Face buttons**: PlayStation and Xbox controllers confirm with their bottom button (✕ / A), Nintendo ones with A on
  the right — in games too. "Like the Thor" on its page keeps the Thor's layout.
- **Player order** for every game, for an app or for one game. A change waits for the game's next start ("Apply now"
  does it at once — the game may lose its controllers).
- **"Plays as the Thor's controller"** (its page, off by default): it drives the Thor's controller, so games set up for
  the Thor (Eden's player 1) need no change, and the Thor's own buttons keep working alongside. **"New controllers play
  as Thor's"** (Settings for every controller, off by default) does it for every controller you connect for the first
  time.
- **PlayStation touchpad moves the pointer** (on by default, on its page): a light tap clicks, two fingers scroll;
  pressing the touchpad stays its Back.
- **On a TV or monitor** (on by default): docked, the controller you connect plays as the Thor's, as player 1.
- **Joy-Cons**: hold L on the left one and R on the right one for a second to make them one controller ("Combine
  Joy-Cons"); a single one can be held sideways (hold SL + SR).
- Holding a controller's Home + Back for 5 seconds turns its Wayfinder controls off (its plain buttons); its page turns
  them back on. Xbox LT / RT press L2 / R2, so Home + LT / RT and trigger changes work on them.

**Game controls for every controller**
- **Home + X on any controller** (PS + △ on a PlayStation one) opens Game controls on that controller's tab, with its
  own drawing and button names. The first change makes its own controls (the Thor's stay as they are); "Use the Thor's
  again" switches them off without deleting them; Remove asks twice; "Bring back" (Game settings › These controls)
  undoes it.
- A Games tile opens the game's page — Game controls first, its settings below; Home + X opens the same page. Game
  controls' "More ›" is now "Game settings ›".
- Controller › **"Open on the other screen"** (off by default): Game controls opens on the screen the game isn't on, so
  Eden and GameNative keep running. When a game paused itself under Game controls, a message says to resume it there.
- Eden: a game started from Eden's own list is recognised (its Game controls, performance and lights apply); a game an
  emulator names late (GameNative) still switches the page to it.

**Clearer, and easier with any controller**
- One focus highlight, clearly visible everywhere and in every theme; a chosen option is filled, with a check.
- Each controller's buttons are drawn and named as on it (PS, Create, touchpad click, Mute); page headers show what its
  buttons do on the focused control.
- Messages show on the controller's screen, follow the theme and never end up in a screenshot.
- Search: the D-pad reaches the field, every result opens its setting, and Back keeps your search.
- Appearance: one theme choice with Your colours, a warning when text gets hard to read, and a reset. Help & status
  shows the status first.
- **Asks before acting**: Recent apps' close all (Select, then Select again), Close background apps, Arrange's resets,
  deleting a change, a macro step, a chord or an app pair; Test it's Start / Select act only when held for a second.

**Fixed (GitHub issues)**
- Wayfinder Keyboard: ↵ and Start run the search in boxes like the Play Store's (#94). Keyboard › "Keep a thin bar" (on
  by default): turn it off to lose Android's black keyboard bar on the typing screen (#99).
- Home combos: Home and another button pressed at the same moment no longer go home, and holding a button (accelerate,
  say) then tapping Home goes home instead of making a combo (#81). New combo action "Nothing", for example on Home's
  tap (#81).
- New combo actions "Top screen off / on" (#91) and "Turn Wayfinder off", for games that refuse to run beside an
  accessibility app — one press in Wayfinder turns it back on (#83).
- Stick lights: after the lid or the power button, Off and Colour come back in about a tenth of a second, and the colour
  wheel follows the stick (#64); Spectrum can show the same colour on both sticks (#82).
- Keyboard & mouse: a "No pointer" tab in Direct (#65); Touch and Direct reach the whole top screen (#49); Pointer speed
  1 to 10 (#49, #99).
- Quick panel: the Details card shows the power drawn, the fan and the memory (#89, #87); the Screenshot shortcut asks
  which screen (#71).
- The frame-rate counter comes in three sizes and a compact style (#86). Screen recordings can be saved with your
  screenshots (#98).
- Back over a game: a game never gets one Back twice; locking or sending the controller no longer takes the game's
  focus; Recent apps opened right after a swap stays open (#102). A second swap pressed during the first is done right
  after it (#99).
- Home on both screens sends both screens home, wherever the controller is, and Home on the top screen no longer sends
  the bottom one home when the controller is down there (#97).
- A quick-panel tile, a combo that opens an app on "The controller's screen" and the Media card's Open follow the
  app's own "Opens on" (Top or Bottom), as Android's launcher does; if that app is already showing on a screen, it
  stays there (#107).
- Wayfinder Keyboard: typing in a Guide page (Guide & notes) no longer freezes Wayfinder for seconds. A pinned guide
  whose site answers with an error or a check page ("Just a moment…") keeps its offline copy (#108).
- Stick lights on a system without the Thor's own light controls: Wayfinder's light effects stay off and Stick lights
  says "No stick lights found". On LineageOS (or another system whose sticks have standard LED controls), Wayfinder
  drives the lights if your system lets Wayfinder change them — directly, without root; if it doesn't, Stick lights
  says "Can't change the stick lights". Not yet tried on such a system (#109).

**Also fixed**
- Select + Back (RetroArch's hotkey): Select stays held while Back is pressed. Turbo: holding a turbo button and tapping
  Home goes home; a combo with a turbo button fires once.
- After Recent apps and back, the game is still recognised (its performance, lights and Game controls).
- Appearance › Hub screen › Bottom no longer leaves Wayfinder hidden behind the launcher.
- The screen-record tile stops saying "Recording" once the file is saved.

## 1.4.1 — October 2026

**Fixed**
- **Wayfinder closed when you pressed ⌫ at the start of a text field** with the Wayfinder Keyboard (for example the hex
  code of your own colours). The same could close other apps' text fields: ⌫ with nothing before the cursor now does
  nothing.
- **Typing in a filled-in field started at the beginning** (a hex code typed before the old one): editing now starts at
  the end.
- **Colour saturation went back to 110 % after a restart** on some Thors: Wayfinder now puts your own value back once it
  has started.

## 1.4 — October 2026

**Simpler**
- **New: choose what Wayfinder is for** — "Just switching screens" (move and swap apps, Recents, Home on each screen;
  the AYN button stays AYN's, no game controls) or "Everything". Asked in the tour, and once on the Hub after the
  update. Change it any time in More → Features, part by part (combos, quick panel, game controls, keyboard & mouse,
  stick lights). Nothing is deleted, and updating changes none of your settings.
- **New Hub** — Screens · Controller · Games · Battery · Quick panel · Keyboard · More (sound, stick lights, appearance,
  hints & pop-ups, features, help); the Quick panel card only while the AYN button opens it. "App profiles" is now "Games".
- **Shorter pages** — each page shows its main settings; the rest are behind one "More options" row (search opens it
  for you). The keyboard shows your languages, the others behind "Add a language".
- **Combos page** — only the combos you have, in order of their first button; "+ Add a combo" lists the actions in groups. Pressing
  a combo shows it with Change and Remove.
- **Hints & pop-ups** (More) — turn off the "Your controls" sheet, the combo list you see while holding a button (or show it
  later), and the Recent apps hint.
- **One short line per setting** — the rest of the explanation is behind a small "?" (and shows while the controller is
  on the setting). Choices with many options are one row showing the current one; press it to pick another.
- **Game controls** — the bottom bar is "Change a button" and "More" (chords, presets, sharing, the Shift button, the
  input layer for this game, reset), plus your changes when there are some.
- **Back puts you where you were** — going back from a page selects the row you opened it from.

**New**
- **Swipes from the controller** — Swipe up / down / left / right on the controller's screen, for a combo or a game's
  button (YouTube Shorts, web pages, photos).
- **Battery** (a new Hub page) — what the Thor draws right now, the time left and the CPU's top speed in force, the
  charging switches (they moved here from Screens) and sleep & standby.
- **With ClusterTune or Pulse installed, Wayfinder steps aside**: no per-game performance from Wayfinder (with Pulse,
  no per-game fan and refresh rate either), and the quick panel's Performance tile shows the tuner's name and changes
  nothing — the two never fight over the CPU. The pages say so. (Lower CPU limits of Wayfinder's own were built and
  measured on a Thor: they saved too little to keep — 0 to 10 % — so ClusterTune is the app for that.)
- **Warnings for apps that do the same job** — Mjolnir (the Home button), BiFrost (the stick lights) or OdinTools
  (per-app performance and fan) active next to Wayfinder: the Hub says what can conflict and what to do, once. Help &
  status lists every such app found, handled or not.
- **Input layer: where it runs** — Everywhere, Only games I set up (new installs), or Nowhere; and per app
  Automatic / On / Off, for games that don't like the copied controller.
- **Home + X opens the game you're playing** (its own controls, a copy of the emulator's until you change something).
- **Two-button combos can be tapped twice, three times or held** — Home + B twice, Home + B held… each its own action.
- **New actions:** record the screen, close the other screen's app, AYN's own drawer (keep it on the AYN button's hold
  with the quick panel on its tap), Home on the controller's screen.
- **PlayStation button names** — ✕ ○ □ △ in every hint (Controller → More options → Button names).
- **Your own colours** — a flat look: background, cards, text and accent, on a colour wheel or as a hex code
  (More → Appearance).
- **Wake the bottom screen with a double tap** — a new option (Screens): a thumb brushing it doesn't wake it.
- **Stop charging at 80 % and direct power** — now on the new Battery page (they were only quick-panel tiles).
- **The controller goes with a game you move** — move or swap a game (hold Back) and the controller follows it to its
  new screen. Controller → "Goes with a game you move" (on for new installs), and per game: Games → the game, or
  Home + X → More ("Usual / Goes with it / Stays"). "Always top / bottom" keep the controller where they say.
- **Quick panel tiles that open an app, an app pair or a Wayfinder page** — Quick panel → Shortcuts → "Add — a tile
  that opens…". An app opens on the screen with the controller.
- **Headphones & Bluetooth EQ** (More → Sound) — wired headphones, USB audio and Bluetooth each get their own sound:
  a few presets, or shape the curve yourself (drag the points, or A then the D-pad). It follows what you plug in; the
  speaker fix still only plays on the speakers.
- **Stick lights can follow the screens' brightness** — the rings dim and brighten with the screens. A colour can also
  be typed as a hex code. "Screen colour" now reads the screen less often while the colour holds (it cost about
  0.4 W — measured), and its page says what it costs.
- **Fewer ads in the web guide** — well-known ad and tracking networks aren't loaded.
- **Gyro "While touching a screen"** — the gyro aims only while a finger rests on either screen.
- **Trackpad "Direct" mode** — Keyboard & mouse: the pad is the game's screen; touch, drag and swipe with one finger
  (the mode button goes Mouse → Touch → Direct).

**Fixed**
- RetroArch (and any game) with the input layer on: a short Back and Back with another button held (Select + Back =
  RetroArch's menu) reach the game as the controller's own Back, and hold Back still moves / swaps it.
- The on-screen keyboard typed the neighbouring key when you pressed A and moved quickly.
- Games that check the keyboard is open (Tomodachi Life in Eden) took an empty name at once and asked again for ever:
  a thin keyboard strip now stays on the game's screen while you type on the other one.
- Recent apps froze the second time it was opened from the same app.
- The quick panel's RAM reading disappeared after a second.
- The navigation bar flashed in apps: Wayfinder's "keep it hidden" check only looks at the top screen, confirms first
  and gives up if it doesn't help (and can be turned off: Screens → More options).
- "Input layer couldn't start (exit 4)" on a Thor whose controller style was never changed; Wayfinder moved to an SD card.
- AYN's stick mouse stayed on after a crash or an update (the pointer stuck on one screen).
- Home + X in an emulator that keeps several games open picked the wrong game.
- The bottom screen turned off at once when an app appeared on it (the timer counted from the last touch).
- The controller stayed on the bottom screen when it went dark — it moves to the top.
- Wayfinder could stay off after a restart: Android sometimes stops it while the Thor starts. Wayfinder now checks a
  minute later and turns itself back on.
- "Input layer couldn't start (exit 4)" on Thors where the controller doesn't restart: after two tries the layer starts
  another way, automatically (Help & status says "compatibility mode").
- RetroArch set to use Back held (hotkey enable, hold fast-forward) keeps Back as before with the input layer.
- Stick lights went back to AYN's colours after closing and opening the lid.
- With a larger font size, the combo list and the quick panel cut their text; the list now uses as many columns as fit
  (one on the bottom screen when needed).
- Screenshots no longer include the quick panel or Wayfinder's hints.
- "Controller to the top screen" sometimes did nothing while the top app showed its status bar.
- After sleeping with the quick panel open, the controller stayed on the bottom screen for a few seconds.
- A combo's own message ("Controller unlocked"…) vanished as soon as you let go of Home.
- The Recent apps controller hint sometimes didn't show.
- Two-button combos with the D-pad or a stick ignored "twice / three times / held".
- With the combo list turned off, a slow press of Home did nothing.
- Sending an app to the top screen could leave the controller on the bottom one (the home screen that opened there
  took it).
- **Everything reachable with the controller**: the "?" of a setting (it opens while the controller is on the card), the
  frame-rate card and every switch with settings under it, "+ Add a combo", "Open Game controls", "Import controls",
  the text fields (the D-pad passes over them; A types, B stops), your own Keyboard & mouse buttons, and text under a
  page's last button (Sleep & standby's log). Left and right no longer jump out of a page's settings; a row of choices
  is entered on the current one.
- "Stop charging at 80 %" said "the battery stops at 80 %" while it sat at 100 %: the limit stops charging, it doesn't
  bring a fuller battery down. The card and the Hub now say what the charger does right now ("80 % limit", "full",
  "on the charger"), and Wayfinder checks AYN applied the limit.
- "Test the controller": its title was under the status bar.
- With a large font, quick panel tiles cut their name ("Keyboard &"): it now shrinks to fit.
- A bottom screen left black by something else (another app, AYN's own bottom-off, a brightness at 0): "Bottom screen
  off / on" now brings it back instead of blanking it, and Help & status has "The bottom screen stays black (touch
  still works) → Fix now".
- The tour: the controller starts on the "What do you want Wayfinder for?" choices (A on Next skipped the question),
  and goes on to Next once the quick setup is all done (it stayed on a "Done ✓" that did nothing).

## 1.3.2 — September 2026

- **New: emoji in Wayfinder's keyboard** — a 🙂 key in the bottom row: five groups of emoji (the group key steps
  through them), ABC goes back.
- **Fixed:** the trackpad's Touch mode pointer stopped about 60 % across the game's screen.
- **Fixed:** the deck's Clipboard types the text when it can (so it works in games that ignore paste) and puts your own
  clipboard back after pasting; the clipboard list only keeps text (up to 20,000 characters), forgets clips after an
  hour, when Android clears its clipboard, or when you switch to another keyboard.
- **Changed:** holding the AYN button to read its combos now also says what keeps holding does (its hold action), and
  the list closes when that action runs.
- **Changed:** the Recent apps controller hint is one line of words along the top of the screen (it covered the
  middle card). "Symbols" keeps the shortest version.
- **Fixed:** the quick panel sometimes lost its see-through background on the bottom screen (it showed its own
  purple backdrop): Android handed it the top screen's size, so it drew itself as the top screen's side sheet.
- **Fixed:** "Input layer couldn't start (binary-not-trusted)" when Wayfinder runs in a second Android user or
  profile: its helper looked for its file in the first user's folder. If the layer still can't start, the message now
  says exactly why (file missing, owner, permissions).
- **Fixed:** Firefox (and Firefox-based browsers) crashed when you selected a word or opened a menu after being moved
  to the other screen. They now move by reopening there (your tabs come back). Per app: App profiles → "When it moves
  to the other screen: Automatic / Keep running / Reopen".
- **New: Home on the top screen / on the bottom screen / on both screens** — actions for a combo (Home tap, double,
  triple or hold…) or the AYN button, like a dual-screen launcher manager.
- **New: "Opens on"** for "Open an app" combos — the controller's screen, the top screen or the bottom screen.
- **New: a clipboard** — your last 5 copied texts: a clipboard key in Wayfinder's keyboard, and a Clipboard button in
  Keyboard & mouse (Home + Y) that pastes into the game's screen (accents and emoji included). Kept in memory only,
  never saved or sent; passwords are skipped. Needs Wayfinder Keyboard as your keyboard.

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
