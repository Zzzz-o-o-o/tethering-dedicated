# Tethering Dedicated for Android 10 and 11

[![CI](https://github.com/Zzzz-o-o-o/tethering-dedicated/actions/workflows/ci.yml/badge.svg)](https://github.com/Zzzz-o-o-o/tethering-dedicated/actions/workflows/ci.yml)

Turn a spare Android 10 or 11 phone into a dedicated, long-running Wi-Fi hotspot — like a pocket Wi-Fi router.
The app keeps an eye on the hotspot, the mobile line, the internet connection, the battery and the temperature,
alerts you when something goes wrong, keeps a log, and helps you reduce power consumption.

- No root required. The app does not change carrier settings.
- It uses the phone's own hotspot as is. It never toggles airplane mode, mobile data or Data Saver, and never reboots the phone.
- It turns the hotspot on or off only if you enable one of the optional features **Turn on the hotspot at startup**, **quiet-hours schedule** and **monthly data cap**, tap **Turn on now** in the initial setup, or tap its **quick settings tile**.

**Languages:** English, French and Japanese. The app follows the phone's language (Settings → System → Languages & input → Languages); any other language shows English. The PC script follows the PC's display language.

---

## Requirements

| | |
|---|---|
| Phone | Android 10 or 11, with a SIM/plan that allows tethering. **That's all you need** |
| PC (optional) | Windows with **ADB** ([Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)) and a USB cable — only for the extra setup from a PC (see below) |

## Files

| File | Contents |
|---|---|
| `TetheringDedicated-v0.10.0_android10.apk` | The app for **Android 10** (also on the [Releases](https://github.com/Zzzz-o-o-o/tethering-dedicated/releases/latest) page) |
| `TetheringDedicated-v0.10.0_android11.apk` | The app for **Android 11** (see [Android 11](#android-11)) |
| `tools\adb-setup.bat` | Optional PC script: install, apply recommended phone settings, grant permissions, undo |
| `tools\backup\` | Phone settings saved by the script before it changes anything (one file per phone) |
| `developer-memo.md` | Developer notes in Japanese (design, test records, build) |
| `.github\` | GitHub Actions CI (unit tests, lint, debug build) and the bug report form |
| `LICENSE` | MIT License |

---

## Quick start (phone only)

1. **Download the app on the phone**: open https://github.com/Zzzz-o-o-o/tethering-dedicated/releases/latest in the phone's browser and tap the APK for your Android version — `TetheringDedicated-v0.10.0_android10.apk` for Android 10, `TetheringDedicated-v0.10.0_android11.apk` for Android 11 (Settings → About phone → Android version). The Android 11 one doesn't install on Android 10.
2. **Install it**: open the downloaded file. If Android asks, allow your browser (or Files app) to **install unknown apps**. If Google Play Protect warns that the app is unknown, choose to install it anyway (the app is not on Google Play).
3. **Open the app**. The **Initial setup** screen opens. Tap the buttons from the top; when a system screen opens, allow there and press Back:
   1. **Phone** permission — reads the network type and signal. Android calls it "make and manage phone calls", but the app never makes calls or sends SMS. On Android 11, also tap **Keep it** and turn off "Remove permissions if app isn't used" under *Permissions* — otherwise Android removes the permission when the app isn't opened for a few months.
   2. **Battery optimization** — exclude the app so that checks are not delayed while the screen is off.
   3. **Modify system settings** — lets the app turn the hotspot on and off (at startup, quiet-hours schedule, data cap).
   4. **Hotspot auto turn-off** — turn it off so the hotspot doesn't stop when nobody is connected. The hotspot name and password are on the same screen. On Android 11 the app can't check this setting: make sure "Turn off hotspot automatically" (under *Advanced*) is off.
   5. **Turn on the Wi-Fi hotspot at startup** — turns it back on automatically after every reboot.
   6. **Turn on the Wi-Fi hotspot** — turns it on now.
   7. *(optional)* **Usage access** — for the monthly data cap and the data use per app.
   8. *(optional)* **Monthly data cap** — if your plan has one.
4. Done. Monitoring runs from now on and restarts by itself after a reboot or an app update. You can open **Initial setup** again from the main screen at any time.

If the phone has a PIN, pattern or password lock, monitoring starts only after you unlock the phone once after a reboot (an Android restriction). Turning the hotspot on at startup works even before you unlock.

**Nothing is lost without a PC.** Every feature works with the APK alone, except the automatic Battery Saver/Doze switching on battery (its permission can only be granted with ADB). The power-saving system settings that the PC script changes in one go can also be changed one by one from the app's **Setup guide**.

### Optional: setup from a PC

The PC script applies the recommended system settings in one go, can undo them, and shows a few things the app can't read (the hotspot band and hardware acceleration support).

1. **Enable USB debugging** on the phone: Settings → About phone → tap *Build number* seven times, then Settings → System → Advanced → Developer options → *USB debugging*. Connect the phone to the PC and allow the "Allow USB debugging?" prompt on the phone.
2. From the project folder on the PC:
   ```
   tools\adb-setup.bat -Install TetheringDedicated-v0.10.0_android10.apk -Apply
   ```
   (on an Android 11 phone, use `TetheringDedicated-v0.10.0_android11.apk`)
   This installs the app, saves your current phone settings, applies the recommended settings, grants the app's permissions, and starts the app. If the app is already installed, leave out `-Install …`. See [The PC script](#the-pc-script-toolsadb-setupbat) for the other options.

---

## The main screen

| Status (chip at the top) | Meaning |
|---|---|
| OK | Hotspot, mobile line and internet are fine |
| Warning | Something needs attention: a problem being rechecked, a temperature/battery alert, Data Saver on, … |
| Error | A problem was confirmed (you were notified) |
| Maintenance | Automatic rechecks and alerts are paused |
| Stopped | Monitoring is off |
| Checking | The first check after starting is still running |

Below the status, the screen shows:

- **Tethering**: hotspot state, number of connected devices (an estimate), the hotspot's auto turn-off setting, the quiet-hours schedule, and the address of the local web page.
- **Mobile line**: SIM, network type, signal strength and quality, band.
- **Connectivity (step by step)**: the result of each check stage — ① registration on the network, ② IP connection, ③ DNS, ④ internet.
- **Power and temperature**: recent power consumption and estimated time left (on battery only), battery, charger, temperatures, smart plug.
- **Operating profile**: the check interval, and the mode — Auto (Normal while charging, Power saving on battery), Normal or Power saving.
- **Recent events** and the **Actions** buttons for all other screens.

**Check now** runs a check immediately.
**Start maintenance mode** pauses alerts for 30 minutes to 4 hours — useful while you update apps or change settings.

---

## How monitoring works

- A silent ongoing notification keeps the app running while the screen is off. Changes (hotspot on/off, signal lost, charger plugged…) are picked up immediately from system events.
- A full check runs every **15 minutes while charging** and every **60 minutes on battery**. On battery, if no device has been connected for 30 minutes or the battery is at 30 % or less, the interval becomes **180 minutes** ("idle"). One check uses about 1 KB of data.
- A problem is not reported right away: it is **rechecked after 1, 3 and 5 minutes**, and you are notified once if it persists (reminders every 6 hours). You are also told when it recovers.
- If the hotspot stops, the app rechecks after 1 minute and then notifies you, with a button to open the hotspot settings. The app does not turn the hotspot back on by itself.
- Alerts: battery temperature **45 °C** (high) and **50 °C** (very high), the phone's thermal status, battery **20 %** or less, estimated time left **60 minutes** or less (on battery).
- **After a reboot**, the app checks everything 2 minutes later and tells you about anything that needs attention (for example, hotspot off).
- Power consumption is measured **only on battery** (it can't be measured while charging). The app never wakes the phone just to measure.

All thresholds and intervals can be changed in **Settings**.

---

## Screens and features

| Screen | What it does |
|---|---|
| **Placement assistant** | Shows the signal every second (with a tone that rises as the signal improves) while you move the phone. **Record this spot**, compare spots, and mark where you put the phone with **Placed here**. You are notified later if the signal there gets worse |
| **Traffic by hour** | Mobile data per hour, with traffic that happened while no device was connected (the phone's own background traffic) shown separately. CSV export |
| **Client device guide** | How to set Windows, macOS, iPhone and Android to "metered" / Low Data Mode, so they don't download updates over your hotspot |
| **Power analysis** | Consumption by signal strength and band, standby power with no devices connected (with a suggestion), and mobile data per app, separating hotspot traffic from the phone's own (needs *Usage access*, which `-Apply` grants) |
| **Comparison test** | Measures the effect of one change (for example, turning off Bluetooth or switching the hotspot band): before (A) and after (B), alternately, on battery. Tells you whether to keep the change |
| **Daily report** | Average consumption, estimated runtime from a full charge, data used, uptime and alerts per day. CSV export |
| **Initial setup** | The permissions and settings needed to start, in order, each with its state and a button (opens by itself the first time) |
| **Hotspot details** (button in the main screen's Tethering card) | How long the hotspot has been on without a break (for example "On (since 10/20 08:12, 3 h 12 min)"), the last time it was off, its uptime over the last 24 hours, the hotspot interface and mode, and — if turned on in Settings — the list of connected devices, which you can name |
| **Setup guide** | Current value and recommended value of each phone setting that matters, with buttons to open the right settings screen. It never changes settings itself |
| **App list** (from the Setup guide) | Apps you installed, to review and uninstall unused ones |
| **Diagnostics** | What this phone can and cannot report. Copy or share it when asking for help. Contains no phone number, IMEI, SSID or MAC address, and nothing about connected devices |
| **Save log as CSV** | Saves the log as a CSV file (opens in Excel) |
| **Settings** | All settings, file backup/restore, **Undo last change**, **Reset to defaults** |

### Quick settings tile

Turn the hotspot on or off from the quick settings panel (swipe down from the top of the screen), without opening the app.

1. **Add the tile once**: open the quick settings panel fully (swipe down twice), tap the pencil (edit) icon, and drag **Tethering Dedicated** into the panel.
2. The tile shows whether the hotspot is on and how many devices are connected (for example "ON · 2 device(s)"). **Tap it** to turn the hotspot on or off.

- It needs **Modify system settings** (Initial setup step 3). Without it, the tile shows "Permission needed" and a tap opens Initial setup.
- After a restart, the tile works once you have unlocked the phone (if it has a screen lock).
- When the phone is locked, Android asks you to unlock it first, so nobody can switch the hotspot from the lock screen.
- Turning the hotspot off from the tile is not reported as a problem. It stays off until you turn it on again (with **Turn on the hotspot at startup**, it also comes back on at the next startup). Both actions are written in the log and **Recent events**.

### Local web page (read-only)

From a device connected to the hotspot, open `http://<address>:8080/` in a browser. The address is shown on the main screen under **Local web page**; it is the same as the connected device's default gateway, and it may change each time the hotspot is turned on.
The page shows status, battery, time left, today's traffic, today's log and the client device guide, in the phone's language. It cannot change anything. It is reachable only from the hotspot side, uses plain HTTP without a password, and can be turned off in Settings.
`/status.json` gives the same status for scripts; use its `overallKey` field (`OK`, `WARN`, `ERROR`, `MAINT`, `STOPPED`, `UNKNOWN`), which doesn't depend on the language.

---

## Optional features (off by default)

### Turn on the hotspot at startup

Android always starts with the hotspot off after a reboot. With this option, the app turns the Wi-Fi hotspot on right after the phone starts — even before a PIN, pattern or password lock is unlocked ("Direct Boot").

Settings → **Tethering**:

1. Allow **Modify system settings** with the button **Allow "Modify system settings"**, or from the PC with `tools\adb-setup.bat -Apply -AllowTetherControl`.
2. Tap **Check that switching works** to check that your phone accepts it (the hotspot is not turned off).
3. Turn on **Turn on the Wi-Fi hotspot at startup** and tap **Save**.

What it does:

- At startup, it asks Android to turn the hotspot on. If the hotspot doesn't come on, it tries again after 30 seconds, then 1, 2 and 5 minutes (5 attempts in about 10 minutes).
- If the hotspot is already on, it does nothing. Once the hotspot has come on, the app doesn't turn it on again until the next startup: if you turn it off by hand, it stays off.
- It works even while monitoring is stopped. Monitoring itself (checks, notifications, log) still starts only after you unlock the phone.
- After you unlock, the result is shown in the log (**Phone started**) and in the after-reboot check notification. Settings shows the result of the last startup.
- During the quiet hours, too, the hotspot is turned on at startup (the schedule can't run before you unlock). If the schedule had turned it off before the restart, it stays on until the next quiet period.
- It doesn't work on lines where the carrier must approve each hotspot use (Android refuses the request). The check button in step 2 tells you.

On the tested phone (UMIDIGI A9 Pro with a PIN lock), the hotspot came on about 20 seconds after power-on, before unlocking, and connected devices rejoined it.

### Connected device list

Settings → **Tethering** → **Show the list of connected devices**. The **Hotspot details** screen then lists the devices connected now (IP address, "Connected" / "Connected (idle)" / "Left", and the end of the MAC address). Tap **Name it** to give a device a name such as "Home PC".

- Names are remembered by MAC address, so a device keeps its name when its IP address changes. Most devices keep the same MAC address for the same Wi-Fi network; if a device changes it (for example with a "rotating" private address setting), name it again.
- The list is read only while the screen is open (no extra background work). Names stay on the phone: they are never written to the log, CSV files, external notifications, the local web page or settings backup files.

### Monthly data cap

For plans with a monthly data allowance. Settings → **Monthly data cap**:

1. Allow **Usage access** with the button below the section (the app reads this month's mobile data from the system's records — the same count as Settings → Network & internet → Data usage, hotspot traffic included).
2. Turn on **Use a monthly data cap**, enter the **Monthly cap** in GB (1 GB = 1024 MB) and the day your plan's count starts (**Count from this day each month**, 1 if it resets at the start of the month), and tap **Save**.
3. Optionally turn on **Turn off the hotspot when the cap is exceeded** (needs "Modify system settings").

What it does:

- You are notified once at **80 %** and once at **100 %** of the cap in each period (also sent to ntfy/Discord/Slack if external notifications are on). The main screen shows this month's data next to the cap.
- With **Turn off the hotspot when the cap is exceeded**, the hotspot is turned off at 100 % and turned back on when the next period starts — only if the app turned it off. If you turn it on by hand, it stays on for the rest of the period. The app doesn't turn it on at startup during that period either. Turning the option or the cap off, raising the cap above the usage, or stopping monitoring turns it back on.
- The count is the phone's own; your carrier's count may differ slightly. This is separate from Android's own "Data limit", which turns off mobile data itself.

### Quiet-hours schedule — turn the hotspot off when you don't use it

Settings → **Quiet hours (traffic alerts, hotspot off)**:

1. Enter the hours you don't use the hotspot in **Quiet hours**, for example `1-6`, `23-5` or `1:30-6:00` (24-hour clock; the end time is not included).
2. Allow **Modify system settings** with the button **Allow "Modify system settings"**, or from the PC with `tools\adb-setup.bat -Apply -AllowTetherControl`.
3. Tap **Check that switching works** to check that your phone accepts it (the hotspot is not turned off).
4. Turn on **Turn off the hotspot during quiet hours** and tap **Save**.

What it does:

- Inside the quiet hours, the hotspot is turned off **only after no device has been connected for 10 minutes**, and only once per quiet period. It is turned back on at the end of the quiet hours.
- It turns the hotspot back on only if the app turned it off. If you turn the hotspot on by hand during the quiet hours, it stays on.
- Turning the schedule off, or stopping monitoring, turns the hotspot back on immediately.
- If turning it back on fails, the app retries after 1, 5 and 15 minutes, then notifies you that the hotspot is off.
- It doesn't work on lines where the carrier must approve each hotspot use (Android refuses the request). The check button in step 3 tells you.

### Battery Saver and earlier Doze on battery

Settings → **Auto switching (on battery)**. On battery, the app can turn on **Battery Saver** (**Turn on Battery Saver automatically on battery**, optionally only below a battery level) and make the phone enter deep sleep sooner (**Enter Doze (deep sleep) sooner on battery**). Everything is put back when you plug in the charger.

- Needs a one-time permission from the PC: `tools\adb-setup.bat -Apply -AllowAutoSwitch`.
- **Restore everything changed automatically** restores the original values and turns the feature off.
- Battery Saver may slow the phone slightly. You can measure the effect with a comparison test.

### Notifications to your phone or PC (ntfy, Discord, Slack)

Settings → **External notifications**. Turn on **Send notifications externally (ntfy, Discord, Slack)** and enter the **Destination URL**:

| Service | URL |
|---|---|
| ntfy | `https://ntfy.sh/your-topic-name` (or your own server). With an access token: `https://:tk_xxxx@ntfy.example.com/topic` |
| Discord | the channel's webhook URL (`https://discord.com/api/webhooks/…`) |
| Slack | the incoming webhook URL (`https://hooks.slack.com/services/…`) |

- Sent: recoveries **with how long the line or hotspot was down**, reboots, temperature/battery/signal alerts, hotspot stopped, and failures of the schedule or the smart plug. You can choose recoveries and alerts separately. Messages are in the phone's language.
- Not sent: IP addresses, SSID, information about connected devices, phone number.
- Notifications that can't be sent while the line is down are kept (up to 20, for 24 hours) and sent after it recovers.
- Use **Send test** to try the URL before saving. With ntfy's public server anyone who knows the topic name can read it — choose a long, hard-to-guess name.
- The URL is not included in settings backup files or in Diagnostics.

### Smart plug — keep the battery between 40 % and 80 %

Phones without a charge limit wear out their battery when they are charged to 100 % all the time. With a smart plug on the charger, the app switches charging on and off:

- Charging starts at **40 %** and stops at **80 %** (adjustable).
- Charging also stops when the battery reaches **40 °C**, and resumes after it cools by 3 °C. At **50 °C** charging always stops. If the battery is very low (20 % or less), it charges up to 25 % even when warm, so that the hotspot doesn't die.
- After each command, the app checks that the phone really started or stopped charging. If not, it retries up to three times and then notifies you.

Settings → **Smart plug (charging control)**:

1. Choose the **Plug type**: **Shelly** Gen 2 or later (Plus, Pro, Gen3), **Shelly** Gen 1 (Plug S…), **Tasmota**, or **Custom URL** (for other plugs or Home Assistant webhooks).
2. Enter the **Plug address** (for example `192.168.43.50`), or for Custom URL the ON and OFF URLs (put `POST ` in front to send a POST request).
3. Try it with **Try ON** / **Try OFF**, then turn on **Control charging with a smart plug** and **Save**.

Notes:

- **Shelly**: when switching off, the app also sets the plug's own timer to switch back on after 240 minutes (adjustable) and keeps extending it while the app runs. If the phone or the app stops, the plug turns charging back on by itself. Turn off Shelly's login (Gen 2 login is not supported); for Gen 1 you can write `user:password@address`.
- If the plug is connected to this phone's own hotspot, it is not counted as a connected device. While the schedule has the hotspot off, the plug can't be reached and keeps its last state.
- Plugs that can be controlled only through a cloud service (such as TP-Link Tapo or SwitchBot) are not supported directly; use a Home Assistant webhook with Custom URL.
- Turning the feature off, or stopping monitoring, switches charging back on.

---

## The PC script (`tools\adb-setup.bat`)

Run it from the project folder with the phone connected by USB.

| Command | What it does |
|---|---|
| `tools\adb-setup.bat` | Shows the current values. Changes nothing |
| `… -Apply` | Saves the current values to `tools\backup\`, then applies the recommended settings and grants the app's basic permissions |
| `… -Restore` | Puts back everything saved by `-Apply` |
| `… -Install <apk>` | Installs or updates the app (with `-Apply`, grants permissions first and then starts the app) |
| `… -Apply -RestrictApps` | Also restricts background activity of your other installed apps. Exclude apps with `-KeepApps com.example.app,…` |
| `… -Apply -AllowAutoSwitch` | Also allows the Battery Saver / Doze switching |
| `… -Apply -AllowTetherControl` | Also allows turning the hotspot on at startup and the quiet-hours schedule ("Modify system settings") |
| `… -Apply -EnableOffload` | Also re-enables tethering hardware acceleration, only if the phone supports it |
| `… -Apply -IncludeOta` | Also turns off automatic system updates (update by hand regularly) |
| `… -Apply -KeepLocation` | Leaves Location as it is |
| `… -Apply -ScreenOffSec 30` | Sets the screen timeout (by default it is not changed on phones with a screen lock) |
| `-Serial <id>` / `-Adb <path>` | Choose the phone when several are connected / the path to adb.exe |
| `-Lang en` / `-Lang fr` / `-Lang ja` | Message language (by default, the PC's display language) |

`-Apply` changes only settings that save power and keep the hotspot running: hotspot auto turn-off, Wi-Fi/Bluetooth background scanning, "stay awake while charging", screen timeout (30 s, only on phones without a screen lock), screen wake-ups (ambient display, lift/tap to wake), Location, "mobile data always active", and the app's own permissions (battery optimization exemption, Phone, usage access). It never touches carrier tethering settings, airplane mode, Data Saver or system apps.
The script also shows the hotspot band (2.4/5 GHz) and whether the phone supports tethering hardware acceleration — the app itself cannot read these.
On Android 11, the script leaves the hotspot auto turn-off alone (it can't be changed with ADB any more; turn it off on the phone), and `-Apply` also turns off "Remove permissions if app isn't used" for this app (`-Restore` turns it back on). With `-Install`, it warns if the APK doesn't match the phone's Android version.

---

## Recommended phone setup

The **Setup guide** shows the current value of each item and opens the right settings screen.

| Setting | Recommended | Why |
|---|---|---|
| Hotspot auto turn-off | Off | Otherwise the hotspot turns itself off when nobody is connected |
| Data Saver | Off | Android blocks the hotspot while Data Saver is on |
| Battery optimization (this app) | Excluded | Checks are not delayed while the screen is off |
| Screen timeout | Short (30 s) once set up | The screen is the biggest power draw. With a PIN lock, keep it longer while you are still setting up |
| Screen lock | None, if the phone is in a safe place | With a lock, monitoring starts only after you unlock it following a reboot (turning the hotspot on at startup works before unlocking) |
| Ambient display, lift/tap to wake | Off | Avoids the screen turning on by itself |
| Location, Bluetooth, NFC | Off | Not used by the app |
| Account auto-sync, Play Store auto-update | Off / Wi-Fi only | Reduces the phone's own data use |
| Connected devices | Set to "metered" | Stops their OS updates and cloud sync over the hotspot (see the **Client device guide**) |

On the tested phone (UMIDIGI A9 Pro), the hotspot kept working with Battery Saver on, the screen off and the phone in deep sleep. If your phone's Battery Saver turns on Data Saver, the hotspot stops — the app warns you when Data Saver is on.

---

## Android 11

On Android 11, use `TetheringDedicated-v0.10.0_android11.apk`. It is built for Android 11 (target API 30), and everything works as on Android 10, with these differences:

| | On Android 11 |
|---|---|
| Hotspot auto turn-off | Apps (and the PC script) can no longer read or change it. The app shows "Apps can't read it". Turn off **Turn off hotspot automatically** yourself: Settings → Network & internet → Hotspot & tethering → Wi-Fi hotspot → *Advanced*. If it stays on, Android stops the hotspot after 10 minutes without devices |
| "Remove permissions if app isn't used" | Android 11 removes the Phone permission from apps that aren't opened for a few months. Turn it off for this app: Initial setup step 1 → **Keep it** (or the PC script's `-Apply`) |
| Connected devices | Counted with a method that works on Android 11 (shown as "netlink" instead of "ip neigh") |
| Installed apps | The app is allowed to see the list of installed apps (Android 11 hides it otherwise). It is used only for the **App list** and the data use per app, and stays on the phone |

- The `_android10.apk` also installs on Android 11, but it is made for Android 10. To switch, install the `_android11.apk` over it: settings, permissions and logs are kept.
- Android 11 support has been tested on the Android 11 emulator, not yet on a real Android 11 phone.

---

## Privacy

- The log stays on the phone (30 days, 10 MB). It contains no SSID, no information that identifies connected devices (only how many), no phone number and no IP address.
- The optional connected-device list is shown only on the phone's screen, and the names you give are stored only on the phone.
- On Android 11, the app reads the list of installed apps only to show the **App list** and the data use per app on the phone.
- The app's own network use: one small reachability request per check (`connectivitycheck.gstatic.com/generate_204`, about 1 KB). Optionally, notifications to the service you chose and commands to your smart plug.
- Settings backup files don't include the notification URL or the smart plug address/URLs.
- The local web page has no password; anyone connected to your hotspot can view it. Turn it off in Settings if you don't need it.

---

## Limitations

- Made for **Android 10 and 11** (one APK for each). Android 11 has been tested on the emulator only.
- On Android 11, the app can't read the hotspot auto turn-off setting (see [Android 11](#android-11)).
- Without the optional **Turn on the hotspot at startup**, the hotspot stays off after a reboot until you turn it on; the after-reboot check reminds you.
- Power consumption and time left are available **only on battery**.
- The number of connected devices is an estimate and may stay for a few minutes after a device disconnects.
- The hotspot band (2.4/5 GHz) can't be read by the app. Tethering hardware acceleration has an effect only on phones that support it.
- Turning the hotspot on at startup and the quiet-hours schedule use a system function that some carriers or manufacturers block. Use the check button to find out.
- Notifications to ntfy/Discord/Slack can't be sent while the mobile line is down; they are sent after it recovers.
- The monthly data cap needs "Usage access" and is checked at each scheduled check, so a notification can come up to one check interval after the cap is reached.
- If you change the phone's language, texts already written (log entries, notifications already shown) stay in the language they were written in; screens, event names and CSV headers switch to the new language.

---

## Troubleshooting

| Problem | What to do |
|---|---|
| Monitoring doesn't start after a reboot | Unlock the phone once. If it still doesn't start, open the app |
| The hotspot is off after a reboot | Turn on **Turn on the Wi-Fi hotspot at startup** in Settings → Tethering. If it is on, check that "Modify system settings" is allowed and read the last result below it. While the data cap has the hotspot off, it isn't turned on at startup |
| The monthly data shows "N/A" | Allow "Usage access" (Settings → Monthly data cap, or Initial setup step 7) |
| The APK won't install | Allow the browser or Files app to install unknown apps. If Google Play Protect blocks it, choose to install anyway |
| **Action needed** is shown | Follow the message and its button |
| "Data used with no devices connected" | The phone's own apps or system are using data (app updates, sync). See **Power analysis** for data per app |
| The schedule didn't turn the hotspot off | Check that "Modify system settings" is allowed and the check button succeeds. It waits until no device has been connected for 10 minutes |
| "Can't start/stop charging with the smart plug" | Check the plug's power and Wi-Fi, its address, and the charging cable |
| The test notification fails | Check the URL. "HTTP 4xx" usually means a wrong URL or token |
| The quick settings tile shows "Permission needed" | Allow "Modify system settings" (Initial setup step 3) |
| The hotspot stops after about 10 minutes with no devices | Turn off "Turn off hotspot automatically" in the hotspot settings (on Android 11 the app can't check it) |
| The quick settings tile is grayed out and does nothing | After a restart, unlock the phone once |
| Something else | Open **Diagnostics**, then copy or share it (see below) |

### Reporting a problem

Open a [bug report](https://github.com/Zzzz-o-o-o/tethering-dedicated/issues/new/choose) on GitHub (in English or Japanese). The form asks what happened, the app version, your phone, and the **Diagnostics** text: main screen → **Diagnostics** → **Copy**, then paste it into the form.
Don't paste phone numbers, IMEI, SSIDs, passwords or MAC addresses. Diagnostics contains none of these.

---

## Update and uninstall

- **Update:** download the new APK from [Releases](https://github.com/Zzzz-o-o-o/tethering-dedicated/releases/latest) on the phone and install it over the old one (or from a PC: `tools\adb-setup.bat -Install TetheringDedicated-vX.Y.Z_android10.apk` — `_android11` on Android 11 — or `adb install -r …`). Settings, permissions and logs are kept, and monitoring restarts. Updates must be signed with the same key.
- **Uninstall:**
  1. In Settings, tap **Restore everything changed automatically** if you used the Battery Saver / Doze switching, and turn off the schedule and the smart plug (this turns the hotspot and charging back on). Uninstalling also ends turning the hotspot on at startup.
  2. `adb uninstall local.tethering.dedicated`
  3. `tools\adb-setup.bat -Restore` to put back the phone settings changed by `-Apply`.

---

For build instructions, design notes and test records, see `developer-memo.md` (Japanese). Every push and pull request runs the unit tests, lint and a debug build on GitHub Actions ([CI](https://github.com/Zzzz-o-o-o/tethering-dedicated/actions/workflows/ci.yml)).

## License

MIT License — see [LICENSE](LICENSE).
