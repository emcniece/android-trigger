# Android Trigger

This native Android app allows for custom actions to run when Android OS events happen. For example: when an SMS message is received with a particular string, make an HTTP request.

This is a simplified version of the [MacroDroid](https://www.macrodroid.com/) app, and is free for everyone, forever.

**[Download the latest APK](https://github.com/emcniece/android-trigger/releases)** · **[Request a feature](https://github.com/emcniece/android-trigger/issues/new?labels=enhancement)** · **[Report a bug](https://github.com/emcniece/android-trigger/issues/new?labels=bug)**

## Screenshots

| Macros | Editor | HTTP request | Activity log |
|---|---|---|---|
| <img src="docs/screenshots/macroList.png" width="200" alt="Macro list"> | <img src="docs/screenshots/macroEditor.png" width="200" alt="Macro editor: SMS trigger with sender and text filters"> | <img src="docs/screenshots/macroEditorHttp.png" width="200" alt="HTTP request action with headers and a templated JSON body"> | <img src="docs/screenshots/activityLog.png" width="200" alt="Activity log"> |

| Conditions | Variables | Dark theme | About |
|---|---|---|---|
| <img src="docs/screenshots/constraints.png" width="200" alt="Only-if conditions: time window, weekdays and Wi-Fi, with live status"> | <img src="docs/screenshots/variables.png" width="200" alt="Unknown variable warning and the insert-variable picker"> | <img src="docs/screenshots/macroListDark.png" width="200" alt="Macro list in dark theme"> | <img src="docs/screenshots/about.png" width="200" alt="About page"> |

*Screenshots are rendered from sample data with [Paparazzi](https://github.com/cashapp/paparazzi); regenerate them with `./gradlew updateScreenshots`.*

## Concepts

Like MacroDroid, the unit of automation is a **macro**: one **trigger** (with optional filters) and an ordered list of **actions**. MacroDroid offers 70+ triggers, 100+ actions and ~50 constraint types; Trigger deliberately covers the useful core.

### Triggers

| Trigger | Filters | Variables |
|---|---|---|
| SMS received | sender, message text | `sms_sender`, `sms_body` |
| Notification posted | app package, title/text | `notif_package`, `notif_title`, `notif_text` |
| Charger connected / disconnected | direction | `power_state` |
| Battery level | drops to / rises to N% | `battery_level`, `battery_previous` |
| Network connected / lost | any / Wi-Fi / mobile data | `network_state`, `network_type` |
| Device boot | – | – |
| Time of day | daily HH:MM | `time` |
| Phone call | ringing / answered / ended / missed, caller number | `call_state`, `call_number` |
| Headphones | connected / disconnected; any, wired, Bluetooth or USB | `headphones_state`, `headphones_type`, `headphones_name` |
| Bluetooth device | connected / disconnected, device name | `bt_state`, `bt_name`, `bt_address` |
| Screen | turned on / turned off / phone unlocked | `screen_state` |

Text filters support *contains*, *equals*, *starts with*, *ends with* and *regex*, optionally case-insensitive.

### Conditions ("Only if")

Optionally limit when a macro runs. Conditions are checked when the trigger fires; if they aren't met, the run is skipped and noted in the activity log. With several conditions, choose whether **all** or **any** must hold.

| Condition | Options |
|---|---|
| Time of day | from / until (overnight windows like 22:00–06:00 work) |
| Day of week | any combination of Mon–Sun |
| Charging | charging / not charging |
| Battery level | at least / at most N% |
| Network | on Wi-Fi / on mobile data / online / offline |
| Screen | on / off |
| Wi-Fi network | connected / not connected to a network by name (SSID). Needs precise location, "Allow all the time" and location services on: Android hides the name otherwise, and the condition then counts as not met. |

The editor shows whether each condition is met right now. The ▶ test button runs actions regardless of conditions.

### Actions

| Action | Notes |
|---|---|
| HTTP request | GET/POST/PUT/PATCH/DELETE with custom headers and body. Network errors and 408/429/5xx responses are retried with exponential backoff once the network is available. |
| Show notification | |
| Send SMS | |
| Vibrate | |
| Open app | Pick from installed apps. Needs **Display over other apps** to open from the background. |
| Open URL | Any link: `https://`, `geo:`, `tel:`, … Same permission as Open app. |
| Set volume | Media, ring, notification or alarm, as a percentage. Ring/notification need Do Not Disturb access while DND is on. |
| Do Not Disturb | Off, priority only, alarms only or total silence. Needs Do Not Disturb access. |
| Send intent | Broadcast an intent with an action, optional target package and text extras, e.g. for Tasker or Home Assistant. |
| Run another macro | Runs another macro's actions, optionally only if its conditions are met. Trigger variables are passed along; nesting stops at 5 levels. |

### Variables

Any action field can use `{variable}` placeholders. Tap the **{ }** button on a field to pick one, and the editor flags placeholders that won't be filled in, such as a typo or a variable from a different trigger. Every macro gets `{macro_name}`, `{trigger}`, `{timestamp}` (ISO-8601), `{epoch_ms}` and `{device}`, plus the trigger's own variables above. Add `:json` to escape for a JSON string or `:url` to URL-encode, e.g.:

```json
{"from": "{sms_sender:json}", "text": "{sms_body:json}", "at": "{timestamp}"}
```

The ▶ button in the editor test-runs a macro with sample data, and every run is recorded in the activity log.

### Import and export

Use **⋮ → Export macros** to save all macros to a JSON file, and **⋮ → Import macros** to load them on another phone or after reinstalling. If an imported macro already exists, you can replace it or keep both. Exports include everything in your macros, including header values such as webhook secrets, so store them privately.

## Architecture

```
OS event ──► receiver / service ──► TriggerEvent ──► MacroEngine ──► ActionRunner
             SmsReceiver                              (match enabled     HTTP / notify / SMS / vibrate
             NotificationListener                      macros)           └─ HTTP failure ─► HttpRetryWorker (WorkManager)
             BootReceiver
             AlarmReceiver (AlarmManager)
             BluetoothReceiver
             MonitorService (foreground; battery, charger, network, calls, screen, headphones)
```

- `model/`: serializable `Macro`, `Trigger`, `Action` sealed types
- `data/`: JSON-file persistence for macros and the activity log
- `engine/`: dispatch, templating, action execution, HTTP retry worker
- `triggers/`: Android entry points that turn OS events into `TriggerEvent`s
- `ui/`: Jetpack Compose screens (macro list, editor, log) and permission handling

The foreground `MonitorService` only runs while an enabled macro needs battery, charger, network, call, screen or headphone events. Since Android 8, those broadcasts can't be received by manifest-declared receivers.

## Building

Requires JDK 17–21 and the Android SDK (platform 35).

```sh
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
./gradlew updateScreenshots    # re-render docs/screenshots from ReadmeScreenshots.kt
adb install app/build/outputs/apk/debug/app-debug.apk
```

Debug builds install as a separate app, **Trigger Debug** (`com.emcniece.trigger.debug`), so they can sit alongside a release install. Both apps receive every SMS, so use different keywords for test macros. Debug builds can also simulate trigger events over adb without a real SMS or charger:

```sh
adb shell am broadcast -n com.emcniece.trigger.debug/com.emcniece.trigger.DebugEventReceiver \
  --es type sms --es sms_sender +15555550123 --es sms_body "'hello world'"
```

## Releases

Publishing a GitHub release tagged `vMAJOR.MINOR.PATCH` builds a signed APK and attaches it to the release, along with a SHA-256 checksum. Pull requests run unit tests, lint and a debug build. Dependabot patch and minor updates merge automatically once tests pass.

## Notes and limitations

- SMS and battery-optimization permissions are restricted on Google Play. This app is meant for sideloading or F-Droid.
- Cleartext `http://` is allowed so you can call LAN services such as Home Assistant.
- For reliable background runs, grant "Unrestricted battery use" when the editor asks for it. Some vendors (Xiaomi, Samsung, etc.) also need per-app auto-start settings.

## Roadmap

- More triggers: geofence (location), app launched
