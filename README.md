# Thor Wayfinder
**AI WAS USED IN MAKING THIS PROJECT**

**Move apps between screens on your AYN Thor — with one button.**

Thor Wayfinder is a lightweight utility for the AYN Thor dual-screen handheld. Swap or send apps between the top and bottom displays using simple back-button gestures. No root required.

suport me on [Ko-fi](https://ko-fi.com/thorwayfinder) ☕ or [gumroad](https://gumroad.com/products/vksksb) 

## Features

- **Long press Back (1s)** — Swap apps between screens, or send one to the other
- **Double tap Back** — Open recent apps / task manager
- **Single tap Back** — Normal back navigation
- **Shizuku integration** — Apps move without restarting (preserves state)
- **Works without Shizuku** — Basic functionality out of the box, less stable
- **Per-display tracking** — Real-time status of what's running on each screen
- **Built-in tutorial** — Quick start guide with setup steps and shortcuts

## Setup

1. Install and open Thor Wayfinder
2. Enable the Accessibility Service (Settings → Accessibility → Thor Wayfinder)
3. Disable battery optimization when prompted
4. *(Recommended)* Install [Shizuku](https://shizuku.rikka.app/) for reliable app switching

## Requirements

- AYN Thor (or any Android device with dual displays)
- Android 8.0+ (API 26)
- Shizuku (recommended, not required)

## Building from source

1. Open in Android Studio → Gradle sync (JDK 17)
2. Build & install on device
3. Follow setup steps above

## Disclaimer

Thor Wayfinder is experimental software. It is a workaround for Android's limited multi-display app management. Not all apps cooperate — heavy or single-task apps may crash, lose state, or refuse to move. This is a limitation of Android, not a bug in Wayfinder.

## License

This project is licensed under [CC BY-NC-ND 4.0](LICENSE).

The source code is published for **transparency and security auditing purposes**. You may view and verify the code to confirm the app is safe. You may **not** copy, modify, redistribute, or create derivative works for any purpose without explicit written permission.

## Support

If you find this useful, consider leaving a tip on [Ko-fi](https://ko-fi.com/thorwayfinder) ☕ or [gumroad](https://gumroad.com/products/vksksb) 
