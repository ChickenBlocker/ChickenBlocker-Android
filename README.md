# ChickenBlocker Android

ChickenBlocker Android is a personal, non-root app blocker. It currently runs as an ordinary install and enforces through its Accessibility service. The project uses Android framework APIs directly and builds without Gradle. It does not require a hosted MDM service.

## Native Ordinary-Install Blocker

ChickenBlocker enforces these rules:

- **Browser access:** 40 active minutes grants 30 minutes.
- **No no apps:** 30 active minutes grants 10 minutes.
- **No return apps:** always blocked with no temporary access path.
- **Telegram channels:** 60 active minutes grants 10 minutes. Channel screens inside Telegram are recognized by content — a subscriber count in the header or the channel action bar — and closed with Back, while simple chats and groups stay open.
- **No no sites:** always blocked in detected browsers through Accessibility URL-bar inspection.

Default app and site targets are loaded from the private `.block-config.json` build input. The tracked `.block-config.example.json` contains a non-sensitive starter configuration.

Website blocking does not use a VPN. It discovers installed apps that handle web links and inspects their exposed address-bar text through Accessibility. A browser that does not expose its current URL to Accessibility cannot be covered by this ordinary-install approach.

The app uses a dark theme with a fixed bottom bar that switches between **Blocks** and **Settings**. Blocks shows each list's apps as an icon grid; uninstalled package IDs show a default placeholder icon. It supports built-in and custom named lists with 30/30, 10/5, or always-blocked behavior. The app picker stages selections, supports search, and applies them on save. Additions apply immediately after saving; removing an installed app or deleting a custom list remains pending until the next local `05:00` and can be canceled before then, while removing an uninstalled package ID is immediate. Uninstalled package IDs remain stored and become active when installed. Pausing is initiated only from ChickenBlocker and uses a compact slider in five-minute increments up to the rule's configured maximum. Pausing uses the same timer duration as unlocking that rule. The remaining pause time is shown and notifications are sent with five minutes and one minute left. Resuming protection is immediate. The **Settings** screen shows system protection, Accessibility health, and the last blocked page.

Intercepted apps show a blocked screen with only an **OK** action. Pausing can be started only from ChickenBlocker: choose a duration with the rule's **Pause** action, then complete its timer. Leaving the timer, opening another app, locking the phone, turning the display off, restarting the process, or rebooting restarts the timer. The app does not keep the screen awake.

## Current Prototype

The current prototype:

- directly intercepts packages in the three editable package rules and the Telegram channel content rule through Accessibility;
- requires a 40-minute foreground, screen-on wait for 30 minutes of browser access;
- requires a 30-minute foreground, screen-on wait for 10 minutes of No no apps access;
- requires a 60-minute foreground, screen-on wait for 10 minutes of Telegram channel access;
- permanently blocks confirmed No return apps packages;
- requires a 30-minute unlocking timer for a 10-minute protected-Settings maintenance window;
- includes an Accessibility guard that guards ChickenBlocker's own Accessibility detail, recognized App info, and uninstall pages, redirecting them to a blocked screen;
- exposes the last-match diagnostic without an in-app guard bypass.

Do not treat ordinary-install mode as tamper-resistant.

## Accessibility Guard

Enable the service manually from **Settings > Accessibility**. While healthy, it observes Settings window changes and closes only configured policy-management pages, such as ChickenBlocker's own Accessibility detail page. It identifies both the page and target package using multiple semantic signals rather than blocking the shared Settings container.

To test native blocking and the guard:

1. Install or update the debug APK and open ChickenBlocker.
2. Select **Accessibility settings**, open **ChickenBlocker blocker and settings guard**, and enable it.
3. Open a browser such as Chrome, Firefox, Brave, Opera, DuckDuckGo, or Vivaldi. ChickenBlocker should leave the browser and display a blocked screen with an **OK** action.
4. In ChickenBlocker, press **Pause** for Browser access, select a pause duration, and keep the pausing timer visible for 40 minutes. The rule remains paused for the selected duration afterward.
5. Open a Telegram channel. ChickenBlocker backs out of the channel and displays its blocked screen; chats and groups are not affected.
6. Open ChickenBlocker's Accessibility service detail or App info page. ChickenBlocker closes the page and displays its blocked screen. Start protected-Settings unlocking from ChickenBlocker; completion grants a 10-minute maintenance window.

This design improves self-control resistance but is not a claim that an Accessibility service is unkillable. Android may kill its process under memory pressure and OEM Settings layouts may change.

## Build Without Gradle

Copy the tool-path template and configure paths for the local JDK and Android SDK:

```bash
cp .env.example .env
cp .block-config.example.json .block-config.json
```

Configure the private target arrays in `.block-config.json`, then set the required `.env` entries:

```dotenv
JAVA_HOME=/path/to/jdk-21
ANDROID_PLATFORM_JAR=/path/to/android.jar
AAPT2=/path/to/aapt2
D8=/path/to/d8
ZIPALIGN=/path/to/zipalign
APKSIGNER=/path/to/apksigner
```

Build the APK:

```bash
./build.sh
```

The script compiles resources with `aapt2`, compiles Java with `javac`, creates DEX with `d8`, aligns the APK, signs it with a generated local debug key, and verifies the signature. Output:

```text
build/chickenblocker-debug.apk
```

`.env`, build output, and the generated debug keystore are excluded from Git.

An existing ChickenBlocker installation can be updated in place when the APK uses the same application ID and signing key. This repository keeps the local `debug.keystore`, so the development update command is:

```bash
adb install -r build/chickenblocker-debug.apk
```

Removing the app first is necessary only if the installed APK was signed with a different key, Android reports an incompatible update, or application data intentionally needs to be cleared. Removing first also removes ChickenBlocker's stored allowances and configuration.

## Future Improvements: Device Owner Hardening

ChickenBlocker is not used as Device Owner at the moment. Hardened deployment remains planned and would combine several layers:

- Provision ChickenBlocker as Device Owner on a clean emulator or test device: `adb shell dpm set-device-owner io.github.chickenblocker.android/.AdminReceiver`. Assignment normally fails after the device has completed setup, added accounts, or otherwise become provisioned, so hardened deployment requires backup, factory reset, and DPC enrollment during initial setup.
- Device Owner policy becomes the primary enforcement layer: `setUserControlDisabledPackages()` protects selected packages from force-stop and data clearing, `setUninstallBlocked()` prevents removal, `setPackagesSuspended()` blocks configured distracting applications, and user restrictions close secondary-user, app-control, and time-change bypasses. Package suspension then remains enforced by Android even when the application process is not running, and Accessibility is no longer the primary app blocker.
- A `DeviceAdminService` keeps the DPC process system-bound while the user is running and causes Android to rebind it after process death.
- Guard-health fail-closed suspension: if the Accessibility guard is disabled, unbound beyond its startup grace period, or stops reporting health, the DPC suspends configured distracting applications. Re-enabling the service does not automatically relax that state; recovery requires an authorized maintenance permit or reprovisioning.
- Signed, scoped, expiring maintenance permits authorize temporary policy relaxation, transferred by QR or equivalent offline transfer. Permits are device-bound, replay-resistant, and capped by local maximum durations; expiration restores enforcement automatically, including after reboot.
- Device Owner cannot directly lock special-access/AppOps toggles such as display over other apps, Accessibility enablement, Usage Access, notification-listener access, or exact-alarm authorization; the Settings guard keeps protecting those UI routes without modifying AppOps through root, hidden APIs, Shizuku, or ADB.
- Safe mode, Settings factory reset, recovery, bootloader reset, and device reprovisioning remain intentional escape paths. ChickenBlocker does not apply `DISALLOW_SAFE_BOOT` or `DISALLOW_FACTORY_RESET`, and it must not obstruct emergency calling, required authentication, system updates, or recovery mechanisms.
- The prototype's development-only Device Owner removal action must be removed, and a release signing key must be stored outside the managed phone.
