package app.wayfinder

/**
 * The AYN Thor's raw (evdev) input map, captured on-device 2026-09-22 by pressing
 * every control. These are the codes the root
 * [InputMonitor] receives — used by gestures, chords, remaps,
 * screenshot combos, the AYN-button takeover and linked volume.
 */
object ThorInput {
    // evdev event types
    const val EV_SYN = 0
    const val EV_KEY = 1
    const val EV_ABS = 3

    // ── Devices, found by the root helper (event numbers + names can change) ──
    // Index = position in InputMonitor's launch list.
    const val DEV_BOTTOM_TOUCH = 0   // "fts_ts_3"        (bottom screen)
    const val DEV_TOP_TOUCH = 1      // "fts_ts"          (top screen)
    const val DEV_CONTROLLER = 2     // the AYN gamepad (+ Home, Back) — Odin OR Xbox layout
    const val DEV_GPIO_KEYS = 3      // "gpio-keys"       (AYN button, Vol+)
    const val DEV_PMIC_RESIN = 4     // "pmic_resin"      (Vol−)
    const val DEV_HALL = 5           // "hall_switch"     (the lid: EV_SW SW_LID, 1 = closed)
    // Matchers the root helper resolves itself (and re-resolves on hot-swap). The
    // controller changes identity with AYN's layout switch — "Odin Controller" (Odin /
    // Nintendo layout, product 0x0111) vs "Xbox Wireless Controller" (0x0112) — so
    // it's matched by type, not name.
    val DEVICE_MATCHERS = listOf("fts_ts_3", "fts_ts", "@controller", "gpio-keys", "pmic_resin", "hall_switch")

    // ── Keys (EV_KEY codes) ────────────────────────────────────────────────
    const val BTN_A = 304            // BTN_SOUTH
    const val BTN_B = 305            // BTN_EAST
    const val BTN_X = 307            // BTN_NORTH (physical X)
    const val BTN_Y = 308            // BTN_WEST  (physical Y)
    const val BTN_L1 = 310
    const val BTN_R1 = 311
    const val BTN_L2 = 312           // digital click; analog on ABS_BRAKE
    const val BTN_R2 = 313           // digital click; analog on ABS_GAS
    const val BTN_SELECT = 314
    const val BTN_START = 315
    const val BTN_L3 = 317
    const val BTN_R3 = 318
    const val KEY_HOME = 102         // controller device
    const val KEY_BACK = 158         // controller device
    const val KEY_AYN = 194          // KEY_F24 on gpio-keys — the AYN logo button
    const val KEY_VOL_UP = 115       // gpio-keys
    const val KEY_VOL_DOWN = 114     // pmic_resin

    // ── Axes (EV_ABS codes) ────────────────────────────────────────────────
    const val ABS_LX = 0; const val ABS_LY = 1          // left stick  ±32767
    const val ABS_RX = 2; const val ABS_RY = 5          // right stick ±32767 (ABS_Z / ABS_RZ)
    const val ABS_R2 = 9; const val ABS_L2 = 10         // triggers 0..32767 (GAS / BRAKE)
    const val ABS_HAT_X = 16; const val ABS_HAT_Y = 17  // D-pad −1/0/+1 (NO key events)
    const val STICK_MAX = 32767

    // Multi-touch (both touch panels)
    const val ABS_MT_SLOT = 47
    const val ABS_MT_POSITION_X = 53
    const val ABS_MT_POSITION_Y = 54
    const val ABS_MT_TRACKING_ID = 57
}
