package app.wayfinder

/**
 * The set of actions Thor Wayfinder can perform. This is the shared vocabulary
 * that the UI (Hub buttons, quick menu) and the input layer (gestures, button
 * chords) both target, so every trigger routes through one execution path in
 * [ForegroundAppService.perform]. New features add an entry here and a branch there.
 *
 * UI-agnostic on purpose (no icons/Compose here) — the UI maps actions to icons.
 */
enum class ThorAction(val title: String, val description: String) {
    SWAP_OR_SEND("Move / swap apps", "Move the app to the other screen — or swap them if both screens have one"),
    CLEAR_BACKGROUND("Close background apps", "Closes the apps you're not using — keeps the ones on the screens"),
    /** 1.1: close the game without Android's multitask view (it broke the handheld feel). */
    CLOSE_APP("Close this app", "Closes the app on the screen that has the controller, and goes home there"),
    RECENTS("Recent apps", "Open the multitask view"),
    BACK("Back", "Android's normal Back"),
    // Wired in later phases; present now so bindings/UI can reference them.
    SCREENSHOT("Screenshot", "Capture the top, bottom or both screens (pick which in Screens & power)"),
    TOGGLE_SECOND_SCREEN("Bottom screen off / on", "Turn the bottom screen off, or back on"),
    TOGGLE_KEEP_AWAKE("Stay awake", "The screens don't turn off on their own (press again to stop)"),
    /** 1.3: sleep without the power button (Reddit request). */
    SLEEP("Sleep", "Both screens off, like a press on the power button"),
    /** 1.3 (GitHub #12): the game's guide page and notes — the other screen, or beside the game. */
    GUIDE("Guide & notes", "The game's guide page and your notes — on the other screen, or beside the game on one screen"),
    /** 1.3 (GitHub #17): AYN's virtual mouse on / off. */
    AYN_MOUSE("Mouse mode (AYN)", "AYN's virtual mouse on or off — then click a stick to make it the pointer"),
    /** 1.3: the game's gyro paused / back on (a combo, the AYN button, a button's long press). */
    GYRO_TOGGLE("Gyro on / off", "Pause the game's gyro, or turn it back on (set it up in Game controls → Gyro)"),
    FOCUS_SWITCH_UP("Controller to the top screen", "The controller now works the top screen"),
    FOCUS_SWITCH_DOWN("Controller to the bottom screen", "The controller now works the bottom screen"),
    FOCUS_LOCK_TOGGLE("Lock the controller", "Keep the controller on its screen — touching the other one won't move it"),
    KEYBOARD("Keyboard & mouse", "Keys and a trackpad for the game, on the other screen"),
    QUICK_MENU("Quick panel", "Brightness, volume, performance, fan and your shortcuts — on the bottom screen"),
    BRIGHTER("Brighter", "Both screens brighter, keeping their difference"),
    DIMMER("Dimmer", "Both screens dimmer, keeping their difference"),
    /** 1.3.1 (GitHub #44): one screen's brightness, and the volume — both screens or one. */
    TOP_BRIGHTER("Top screen brighter", "Only the top screen gets brighter"),
    TOP_DIMMER("Top screen dimmer", "Only the top screen gets dimmer"),
    BOTTOM_BRIGHTER("Bottom screen brighter", "Only the bottom screen gets brighter"),
    BOTTOM_DIMMER("Bottom screen dimmer", "Only the bottom screen gets dimmer"),
    LOUDER("Louder", "Both screens louder, keeping their difference"),
    QUIETER("Quieter", "Both screens quieter, keeping their difference"),
    TOP_LOUDER("Top screen louder", "Only the top screen's apps get louder"),
    TOP_QUIETER("Top screen quieter", "Only the top screen's apps get quieter"),
    BOTTOM_LOUDER("Bottom screen louder", "Only the bottom screen's apps get louder"),
    BOTTOM_QUIETER("Bottom screen quieter", "Only the bottom screen's apps get quieter"),
    /** 1.3.2 (GitHub #42, what Mjolnir does): a screen's home, whichever screen has the controller. */
    HOME_TOP("Home on the top screen", "The top screen goes to its home screen"),
    HOME_BOTTOM("Home on the bottom screen", "The bottom screen goes to its home screen"),
    HOME_BOTH("Home on both screens", "Both screens go to their home screens"),
    FPS_COUNTER("Frame rate (FPS)", "Show or hide the frame-rate counter (where: Screens & power)"),
    GAME_CONTROLS("Game controls", "The game's buttons, gyro and macros, while you play; the same combo or B goes back to the game"),
    /** Round 8: opens an app, an app pair or a Wayfinder page — the target is on the binding
     *  ([Binding.arg], see [OpenTargets]); several can exist, each on its own combo. */
    OPEN("Open an app, a pair or a page", "Your own combos that open something (Combos page, bottom)"),
    ;
}

/** Thin façade so callers don't reach into the service directly. */
object ActionRegistry {
    fun perform(action: ThorAction): Boolean = ForegroundAppService.perform(action)

    /** Actions that are fully implemented today (for enabling/greying UI). */
    fun isImplemented(action: ThorAction): Boolean = when (action) {
        ThorAction.SWAP_OR_SEND, ThorAction.CLEAR_BACKGROUND,
        ThorAction.RECENTS, ThorAction.BACK, ThorAction.TOGGLE_SECOND_SCREEN,
        ThorAction.TOGGLE_KEEP_AWAKE, ThorAction.FOCUS_SWITCH_UP, ThorAction.FOCUS_SWITCH_DOWN,
        ThorAction.FOCUS_LOCK_TOGGLE, ThorAction.SCREENSHOT, ThorAction.KEYBOARD,
        ThorAction.BRIGHTER, ThorAction.DIMMER, ThorAction.FPS_COUNTER, ThorAction.QUICK_MENU,
        ThorAction.GAME_CONTROLS, ThorAction.OPEN, ThorAction.CLOSE_APP, ThorAction.SLEEP, ThorAction.AYN_MOUSE, ThorAction.GUIDE, ThorAction.GYRO_TOGGLE,
        ThorAction.TOP_BRIGHTER, ThorAction.TOP_DIMMER, ThorAction.BOTTOM_BRIGHTER, ThorAction.BOTTOM_DIMMER,
        ThorAction.LOUDER, ThorAction.QUIETER, ThorAction.TOP_LOUDER, ThorAction.TOP_QUIETER,
        ThorAction.BOTTOM_LOUDER, ThorAction.BOTTOM_QUIETER,
        ThorAction.HOME_TOP, ThorAction.HOME_BOTTOM, ThorAction.HOME_BOTH -> true
        else -> false
    }
}
