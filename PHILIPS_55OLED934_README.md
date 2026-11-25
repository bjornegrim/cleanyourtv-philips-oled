# CleanYourTV — Philips 55OLED934/12 Setup & Customization Guide

## 1. What Was Completed

- **Full RAM Optimization (+45 MB available headroom):** Disabled non-essential background processes (`org.droidtv.nettvbrowser`, `org.droidtv.contentexplorer`, `org.droidtv.oad`, `com.easy.oad`). Jellyfin remains fully intact and untouched.
- **Ambilight Bias Lighting (#1):**
  - Sends warm lounge glow (`red: 255, green: 220, blue: 160`) via `org.droidtv.settings/.LoungeMode` whenever the home screen is active.
  - Automatically shuts off when entering any app or game.
  - Configurable on/off switch in **Startskärmsinställningar** (`Ambilight-bakgrundsbelysning`).
- **OLED Panel Pixel Shift (#4):**
  - Subtle random vertical drift (±3dp) applied on each cold start / resume to prevent burn-in on the fixed dock boundary.
- **Enhanced Weather with "Feels Like" (#6):**
  - Open-Meteo integration now includes apparent temperature: `12°C · Klart · Känns som 9°C` on both Home and screensaver.
- **Fast Screensaver Gesture (#7):**
  - Pressing Back within 1.8 seconds of returning to Home immediately starts the clock screensaver.
  - Configurable on/off switch in **Startskärmsinställningar** (`Snabbstart av skärmsläckare`).
- **Adaptive Brightness Toggle (#8):**
  - Protects OLED by matching ambient room lighting.
  - Configurable on/off switch in **Startskärmsinställningar** (`Adaptiv ljusstyrka (OLED)`).
- **Direct HDMI / TV Inputs in Quick Settings (#9):**
  - Added dedicated **Ingångar** row in the top-right Quick Settings panel for direct switching.
- **Pure 9/10-App Dock Setup (Jellyfin preserved):**
  1. **SmartTube beta** (`org.smarttube.beta`)
  2. **Netflix** (`com.netflix.ninja`)
  3. **SVT Play** (`se.svt.android.svtplay`)
  4. **TV4 Play** (`se.tv4.tv4playtab`)
  5. **Spotify** (`com.spotify.tv.android`)
  6. **SOund** (`com.s0und.s0undtv`)
  7. **YummyTV** (`su.afk.yummy.tv`)
  8. **AniLibria** (`ru.radiationx.anilibria.app.tv`)
  9. **VLC** (`org.videolan.vlc`)
- **No Clutter Underneath Dock:** All non-dock Leanback activities (HDMI tiles, Live TV tile, Media, TV Guide, browser, Play Store, etc.) are hidden via `hidden_apps`. Your home screen displays only your chosen 9 dock apps.
- **Ultra-Minimalist Top Bar:**
  - Removed Inputs and Wi-Fi buttons (`show_inputs_pill=false`, `show_network_pill=false`).
  - Settings button is icon-only (`icon_only_buttons=true`), positioned directly in the top right corner.
- **Quick Settings Glass Panel:**
  - Clicking the top-right Settings icon opens a glass panel on the side with:
    - **TV-inställningar** (TV Settings)
    - **Skärm** (Display Settings)
    - **Nätverk** (Network Settings)
    - **Appar** (Manage Applications)
    - **Startskärmsinställningar** (CleanYourTV Launcher settings)
- **Bluetooth Connect Add-on (`com.gathin.btconnect`):**
  - Built from source and installed on the TV.
  - Grants quick 1-click access to connect and pair Bluetooth speakers, headphones, or controllers.
- **Double-Tap "Back" Screensaver:**
  - Double-tapping the remote's **Back** button triggers the drifting OLED clock screensaver immediately.
  - The screensaver displays the time, Swedish date, and live weather for the configured location (e.g. `12°C · Klart`) centered beneath the date.
  - Pressing any key exits back to your clean home screen.
- **Dedicated "Options" Key for Context Menu:**
  - Pressing the **Options** key on your remote opens the tile context menu ("Ersätt med en annan app", "Flytta", "Appinfo") directly on the focused tile.
- **Subtle Weather Information:**
  - Weather for the configured location (`latitude`/`longitude` in `config.xml`) is displayed right underneath the date: e.g. `8°C · Klart`.
  - Automatically refreshes every 20–30 minutes via Open-Meteo with no background battery drain or API keys needed.
- **Native AirPlay Receiver (`io.github.jqssun.airplay`):**
  - Fully installed and configured with auto-start at boot and Doze exemption.
  - Advertised on your local Wi-Fi network as **"Philips OLED TV"**.
  - Open Screen Mirroring or AirPlay Audio on any iPhone, iPad, or Mac on your Wi-Fi to mirror directly to the TV.
- **OLED Protection:** 5-minute drifting idle clock screensaver configured.
- **Ad-blocking Private DNS:** Configured to `dns.adguard-dns.com`.

---

## 2. AirPlay Usage

1. Make sure your iPhone, iPad, or Mac is on the same Wi-Fi network as the TV.
2. Open **Control Center** on your Apple device and tap **Screen Mirroring** (or the AirPlay icon in music/video apps).
3. Select **"Philips OLED TV"**.
4. The receiver will open full screen on the TV. When you stop mirroring, it returns to Home.

---

## 3. Restoring Everything

If you ever want to revert back to factory stock:

### Option A: Windows Batch Script (1-Click)
Run:
```cmd
backup\restore.bat
```

### Option B: PowerShell
Run:
```powershell
powershell -ExecutionPolicy Bypass -File backup\restore.ps1
```

---

## 4. Cast Fix Assessment (Why You Don't Need It)

We evaluated [Cast Fix (thayford/castfix)](https://github.com/thayford/castfix):
- **Why Cast Fix exists:** Google TV sets (specifically Chromecast with Google TV / Android 12 & 14) break casting when `setupwraith` is disabled to remove the Google TV launcher, because `setupwraith` hosts `CastInfoProvider`. Cast Fix runs an adb loopback pairing background service to spoof this provider.
- **Your Philips TV:** Your TV is a Philips Android TV 11 device (`TPM191E`). `setupwraith` is enabled, system credentials are intact, and Chromecast built-in (`com.google.android.apps.mediashell`) operates cleanly without Cast Fix. Installing Cast Fix would only introduce unnecessary battery/CPU background overhead and local adb loopback pairing. The APK (`castfix-1.0.apk`) is kept outside the repo in case it is ever needed.

---

## 5. Rebuilding & Reinstalling

If you ever want to modify `config.xml` again:
1. Edit `launcher/app/src/main/res/values/config.xml`.
2. Run `build-and-install.bat`.

---

## 6. Speed pass (2026-10-31)

- **11 more background packages disabled** (all tested on other TPM191E units, all undone by `backup\restore.bat`):
  `org.droidtv.tv.frameworklogger` (~31 MB), `org.droidtv.sticker`, `org.droidtv.nettvapps`,
  `org.droidtv.nettv_launcherapp`, `org.droidtv.dropboxprovider`, `org.droidtv.dukservice`,
  `com.google.android.marvin.talkback`, `com.google.android.sss.authbridge`, `org.droidtv.dlna`,
  `com.android.printspooler`, `com.google.android.backdrop`.
  Tuner, HDMI, Ambilight, Chromecast, AirPlay and firmware updaters are deliberately left alone.
- **Animations at 0.5x** (window, transition, animator).
- **Dock apps + Jellyfin + launcher compiled with `cmd package compile -m speed -f`** for faster starts.
  A Play Store update of an app drops it back to the default; re-run the command for that app if wanted.
- **System screensaver** pointed at `com.gathin.tvhome/.ClockDream` (it pointed at an uninstalled Aerial app).
- **Launcher:** weather moved into one shared `Weather.java` (cached, one request at a time, 2-min back-off when
  offline) instead of two copies that started a new thread on every minute tick while offline.
- **Signing key:** the launcher is now signed with a fixed local debug keystore (outside the repo)
  (`build-and-install.bat` sets `HOST_KEY_DIR`). Keep that file: without it the TV refuses launcher updates.

Note: the Ambilight bias lighting (#1) and adaptive brightness (#8) toggles listed in section 1 are not
present in the launcher source; only the fast screensaver, pixel shift, weather and Quick Settings parts are.

Checked after a reboot (2026-10-31): all disabled packages stayed disabled (except `com.phorus.playfi.tv`, which the
firmware re-enables at every boot, a known quirk on this board), Home, the HDMI inputs, 0.5x animations, Private DNS,
the screensaver setting and the launcher's compiled state all survived, and all 10 apps cold-start fine.

### Extra tweaks (2026-10-31, from reviewing projectivy-installer / omae-wa-mou-debloated)

- `heads_up_notifications_enabled 0`: no notification pop-ups over video.
- Play Store limited in the background: `appops RUN_IN_BACKGROUND/RUN_ANY_IN_BACKGROUND ignore` and
  `cmd netpolicy add restrict-background-blacklist 10016` (Play Store's uid). Survives a reboot on this TV.
  Apps no longer auto-update: open the Play Store now and then to update. Google Play Services still starts the Play
  Store's processes at boot through bound services, so the memory saving is smaller than hoped.
- Undo for both is in `backup\restore.bat`.
- `wifi_scan_always_enabled 0` and `wifi_networks_available_notification_on 0`: no Wi-Fi scanning for location,
  no "open network" scans. Undo in `backup\restore.bat`. AirPlay stays stopped on purpose (started by hand when needed).
- Launcher frame timing baseline (`dumpsys gfxinfo com.gathin.tvhome`, 20 D-pad presses): median 6 ms, 90th 17 ms,
  99th 20 ms, 16.7% frames over budget, slow UI thread 0; the GPU (90th 15 ms) is the limit, not the code.

### Quick Settings without Bluetooth (2026-11-25)

- The Bluetooth row is gone from the Settings panel on Home (row, icon and strings removed from the launcher).
  The Bluetooth Connect add-on (`com.gathin.btconnect`) is still installed; open it from Apps, or pair from
  TV Settings > Remotes & Accessories.

### Upstream merge (2026-11-25)

- Launcher brought up to Gathin23/cleanyourtv `main` of 2026-11-24 (from the 2026-10-30 base): Home no longer
  keeps earlier activities alive across recreate(), complete Swedish strings, tabular clock digits, sRGB wallpaper
  decode, dock and tile fixes, settings icon aligned with the clock. All local changes kept.
- Left out on purpose: upstream's `back_opens_clock` (the local double-Back does this) and the Picture row in the
  Settings panel. The APK is 590025 B, 201 B over upstream's size gate; that gate is for upstream CI only.
- Only `launcher/` was merged; repo tests outside it (themes README rows, locale table) now lag upstream.

### Centered spotlight Home (2026-11-25)

- New Home look, picked by the owner from mockups in `work/design-forslag/` (11-kombination): greeting, clock, date
  and weather centred; the focused app steps forward (1.12x) with a glow in its colour while the others dim; the second
  row is smaller and dimmed until focus moves there; the app's name and "Senast öppnad" sit centred under the rows;
  a faint aura in the app's colour behind the clock; a short press-down on OK before the app opens; after 30 s without
  a key everything except the clock fades to 25 % (first key wakes, Back and volume still work).
- New `config.xml` keys, all on for this TV: `centered_home`, `spotlight_focus`, `peek_row`, `clock_aura`,
  `press_feedback`, `rest_dim_seconds` (30, 0 = off). The design also set `dock_band`, `dock_title`,
  `focus_overshoot` and `ambient_glow` to false and `glow_snap`, `app_glow` to true; the old values are noted beside them.
- Measured on the TV: janky frames 16.7 % -> about 8.5 % and GPU p90 15 ms -> 9 ms over 20 D-pad presses; no frames
  drawn while Home rests; `bench.py` PSS 32 -> 28 MB, cold start 637 -> 658 ms, idle launcher CPU 0.1 -> 0.3 %.
- Not yet tried with the real remote: hold OK, Options, volume and double Back from rest mode.

### Aurora screensaver (2026-11-25)

- The clock screensaver (Double Back, and the system screensaver `ClockDream`) shows a slow aurora behind the clock:
  OpenGL ES 2.0 in four passes at 960x540, 15 fps, with curtains, rays, a soft bloom at the foot and drifting stars.
  Its colours follow the screensaver's palette, which changes every minute with an 8 s fade.
- New `config.xml` key `clock_aurora` (true here). It only runs with `clock_glow` on and no `clock_wallpaper`
  picture; a GPU without a usable ES 2 config keeps the plain glow instead.
- Measured on the TV (Mali-G51): GPU about 6 ms per frame (p50), launcher CPU 5-6 % while the screensaver shows,
  0 % and no GL thread once it closes. 30 fps cost 10-12 % CPU, so the rate is 15 fps.
