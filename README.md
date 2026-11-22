# Terminal Launcher

An Android launcher that is one terminal: it is the lock screen and the home screen.
You log in with a password, then type commands to use the phone.

<p>
  <img src="docs/lock.png" alt="Lock screen: a pixel clock and a password prompt" width="260">
  <img src="docs/home.png" alt="Home screen: ls output listing apps, with completion chips above the keyboard" width="260">
</p>

## Install

**With Obtainium:** add an app with the source URL `https://github.com/MarvynBailly/terminal-launcher`.

**By hand:** download the APK from [Releases](../../releases) and open it on the phone.

Then open "Terminal Launcher", run `setup`, and tap `home` to make it the default launcher.
`setup` also lists the other access Android has you grant by hand.

## Commands

| Command | What it does |
| --- | --- |
| `ls [-l] [text]` | list apps, optionally filtered |
| `open <app>` | launch an app; typing the app's name alone also works |
| `info <app>` | open the app's system page (uninstall, force stop) |
| `notifs [clear]` | show or clear notifications |
| `call <name>` | call a contact |
| `sms <name> <msg>` | open your messages app with the text filled in |
| `torch [on\|off]` | flashlight |
| `wifi`, `bt` | open Android's Wi-Fi and Bluetooth panels |
| `vol [0-100]`, `bright [0-100]` | media volume and screen brightness |
| `np [pause\|play\|next\|prev]` | what is playing, with progress and controls |
| `battery`, `date` | status |
| `cal [month] [year]` | a month calendar with today marked |
| `fetch` | a system card: Android version, device, uptime, apps, battery, storage |
| `theme [name]` | `default`, `green`, `amber`, `eink`, `nord`, `gruvbox`, `dracula`, `solarized`, `synthwave` or `paper` |
| `saver [name]` | the lock screen's screensaver: `rain`, `stars`, `life`, `clock`, `shuffle` or `off`; `saver show` previews it |
| `fx [on\|off]` | turn the animations on or off |
| `alias [name=cmd]` | define shortcuts (`ll` is `ls -l`) |
| `history` | recent commands |
| `passwd [-d]` | set, change or remove the password |
| `setup [name]` | list or grant the access the launcher needs |
| `lock`, `clear`, `help` | lock the terminal, clear the screen, list commands |

The chips above the keyboard complete what you are typing, and `↑` recalls earlier commands.
App names in `ls` output can be tapped. A mistyped command offers the nearest match; tapping it
puts it at the prompt.

## Motion

The terminal behaves like an old machine:

- **Waking** opens the screen from a bright line and prints a short boot log above the password
  prompt. `lock` collapses the screen to a dot.
- **A right password** resolves "access granted" out of noise while the clock breaks apart, then
  the login summary prints: how far through the day, week and year you are, what is waiting, and a tip.
- **Output** prints line by line, `ls` fills in as a wave, and opening an app grows its line until
  it fills the screen.
- **Notifications** that arrive while the terminal is open type themselves above the prompt, and
  the status line shows the track that is playing. Both need `setup notifs`.
- **Screensavers** start on the lock screen after 30 seconds without a touch, only while the phone
  is charging. They keep the screen on for up to 10 minutes, then let it sleep. Any touch ends one.

None of it blocks typing: what you type while something is still printing is kept. `fx off` turns
all of it off, and so does Android's own "remove animations" setting. `saver off` turns the
screensaver off.

## The lock

There is no password until you run `passwd`. After that the terminal locks on `lock`, when the
screen turns off, and when the phone restarts. Three wrong passwords start a timed lockout.

While it is locked, the terminal shows on top of Android's own lock screen, so its password page
is the first thing on screen when the phone wakes. Android's lock is still underneath:

- **Android lock set to PIN, pattern or fingerprint:** after the right terminal password, Android
  asks for its own unlock, and only then does the terminal open. Use the fingerprint sensor when
  Android asks, not before.
- **Android lock set to Swipe or None:** the terminal password alone opens the phone.

After a restart Android's lock comes first, because no app can run until the phone has been
unlocked once.

The terminal password is not a security boundary by itself. Android does not let an app replace
its lock screen, and the notification shade stays reachable while the terminal is locked. Keep
Android's screen lock on if the phone needs protecting. If you forget the terminal password,
clear the app's data in Android settings.

## Build

Requires JDK 17 and the Android SDK (platform 34).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # shell tests, no device needed
```

The command interpreter in `app/src/main/java/com/terminallauncher/shell/` is plain Kotlin and
talks to the phone through the `Phone` interface, which `AndroidPhone.kt` implements. The UI is
Jetpack Compose: `ui/TerminalScreen.kt` is the screen, `ui/Rows.kt` draws each kind of output line,
`ui/Effects.kt` holds the timing of the animations and `ui/Savers.kt` the screensavers.
`demo/index.html` is the original browser mock-up.

## Release

Push a version tag. The workflow in `.github/workflows/release.yml` runs the tests, builds a
signed APK and publishes it as a GitHub release, which Obtainium then offers as an update.

```bash
git tag v0.2.0
git push origin v0.2.0
```

The version name comes from the tag and the version code is derived from it (`0.2.0` is 200).

Releases are signed with `keystore/release.jks`, using the credentials in `keystore.properties`.
Both are git-ignored and mirrored as the repository secrets `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Android refuses updates signed with a
different key, so keep a backup of both files. With them in place, `./gradlew assembleRelease`
produces the same signed build locally.

## Credits

The typeface is IBM Plex Mono, under the SIL Open Font License (`licenses/IBM-Plex-Mono-OFL.txt`).
