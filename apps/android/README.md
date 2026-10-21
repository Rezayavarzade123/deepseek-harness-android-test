# `dsh` mobile client (Android APK)

English | [中文](README.zh.md)

A native Android shell for the [DeepSeek Harness](../../README.md) Web UI. It signs in to a `dsh web` server over HTTP(S) — on this phone through Termux, or on a computer across the LAN — keeps the browser session, and wraps the GUI in the affordances a phone needs: a connection screen with on-device and LAN discovery, an unreachable/expired-login screen, file uploads, downloads, and microphone access for voice input.

## Why the harness runs elsewhere

`dsh` is a Node.js application: it owns the agent loop, the tool processes, the filesystem, and the model credentials. Android has no Node runtime an app can ship without vendoring one, so this project does the next best thing — it turns the phone into the same client a desktop browser is, while the host keeps running on the machine that holds your workspace.

```
 phone (this APK)                         computer (or server)
┌───────────────────────┐   HTTP + WS   ┌────────────────────────────┐
│ WebView  ── dsh Web UI │ ───────────▶ │ dsh web --host <lan-ip>    │
│ native setup / errors  │              │ agent · tools · workspace  │
└───────────────────────┘               └────────────────────────────┘
```

## Run it on the phone

Android can host `dsh` itself. The APK does not bundle a runtime, because the harness needs Node.js `^22.19 || >=24` plus native modules built for its platform: the packaged Android runtime on npm (nodejs-mobile) is Node 18, and the harness's native addons have no bionic prebuilds. Termux supplies both.

```sh
pkg install nodejs-lts
npx @deepseek-ai/dsh web --no-open
```

Then, in the app:

1. Tap **Check again** in the **Run it on this phone** card. The app probes `127.0.0.1:3080` and fills in the address.
2. Paste the token from the URL dsh printed in Termux.
3. Keep Termux in the foreground while you work, and hold the wake lock (`termux-wake-lock`): Android 12+ kills long-lived background processes unless you also exempt Termux from battery optimization.

A server on loopback is a secure browser context, so clipboard, crypto, and worker APIs behave as they do over `https://`, unlike a plain-HTTP LAN address. The **Connect to a computer** path below is the alternative when the phone should stay a thin client.

## Install

```sh
adb install -r apps/android/dist/dsh-mobile-0.2.1-rc.2-android.apk
```

Sideloading also works: copy the APK to the device and open it (allow installs from the file manager when asked). The APK targets Android 8.0 (API 26) and is signed with the development key the build creates; it is not a Play Store artifact.

## Connect to a computer

On the computer that should run the agent:

```sh
npx @deepseek-ai/dsh web --host 192.168.1.20 --no-open
```

* Replace `192.168.1.20` with that machine's LAN address (`hostname -I`, `ipconfig`, or the Wi-Fi settings screen).
* `--host` accepts one concrete address — `dsh` rejects wildcard binds by design.
* The command prints a URL such as `http://192.168.1.20:3080/?token=8f3…`. Keep that process running.

In the app:

1. Paste the printed URL into **Server address** (the app splits the token out for you), or type `192.168.1.20:3080` and paste the token separately.
2. Tap **Connect**. The launch token is exchanged for a session cookie, so the app reconnects without the token from then on.
3. If you do not know the address, tap **Scan my network**: the app probes port 3080 on the /24 around its own address and lists every host that answers. Ports typed into the address field are honored.

Every `dsh` restart prints a new token. When the old one is rejected, the app shows **Login token rejected** and offers to take the new URL.

## What the app does

| Feature | Detail |
|---|---|
| Session | WebView cookies, so the Web UI behaves exactly as in a browser; the token is only replayed after a 401 |
| On-device host | `Run it on this phone` probes `127.0.0.1:3080`, reuses a saved token, and connects in one tap |
| LAN discovery | Parallel TCP probe of loopback plus the local /24 on port 3080, results ordered numerically |
| Recent servers | Up to eight entries with their tokens; long-press to remove |
| Uploads | Native file picker for `<input type=file>`, including multi-select and camera/gallery providers |
| Downloads | Android `DownloadManager` with the WebView cookie attached, saved to `Downloads/` |
| Voice input | `RECORD_AUDIO` is requested only when the Web UI asks for the microphone |
| Navigation | Same-server links stay in the app; other links open in the browser; back walks the WebView history, then double-tap-to-exit |
| Errors | Timeout, refused, DNS, renderer crash, and rejected-token screens with recovery actions |

The instrumentation is deliberately small: the harness Web UI is the product, and the shell only adds what a WebView lacks.

## Build

No Gradle and no Android SDK install are required. The scripts fetch the official Google binaries (aapt2, `android.jar`, D8, apksigner) from npm carriers and compile with ECJ, which is exactly how the APK in `dist/` is produced.

Requirements: Node.js (to fetch the tools) and a JDK 17+.

```sh
cd apps/android
scripts/fetch-toolchain.sh            # once: aapt2, android.jar, d8, apksigner, ECJ
JAVA=/path/to/java scripts/build-apk.sh
```

Output: `apps/android/dist/dsh-mobile-0.2.1-rc.2-android.apk`, signed and verified at the end of the run.

Environment overrides:

| Variable | Default | Purpose |
|---|---|---|
| `JAVA` | `java` on `PATH` | JVM used to run ECJ, D8, and apksigner |
| `DSH_ANDROID_TOOLS` | `apps/android/.tools` | Toolchain and signing-key directory |
| `VERSION_CODE` / `VERSION_NAME` | `21` / `0.2.1-rc.2-android` | APK version metadata |
| `DSH_KEYSTORE` | `.tools/signing/dsh-mobile.p12` | Signing keystore; created on first build |
| `DSH_STORE_PASS` | `android` | Keystore and key password |
| `DSH_KEY_ALIAS` | `dsh-mobile` | Signing key alias |

Pass your own `DSH_KEYSTORE`/`DSH_STORE_PASS`/`DSH_KEY_ALIAS` for a key you intend to keep: Android refuses to update an installed app whose signature changed.

### Tests

`ServerTarget` — the address/token parser — is plain `java.net` code, so it runs on the JVM:

```sh
cd apps/android
tests/run-tests.sh
```

The suite covers bare hosts, default ports, tokenized startup URLs, mount prefixes, IPv6 literals, percent-encoding, and rejected schemes.

## Layout

```
apps/android
├── app/src/main
│   ├── AndroidManifest.xml            cleartext HTTP is intentional (LAN servers)
│   ├── java/dev/dsh/harness
│   │   ├── MainActivity.java          WebView, setup/error screens, uploads, downloads
│   │   ├── ServerTarget.java          address + launch-token parsing
│   │   ├── ServerStore.java           recent servers in SharedPreferences
│   │   └── LanScanner.java            /24 TCP probe for local dsh servers
│   └── res                            layouts, dark theme, adaptive icon
├── scripts/fetch-toolchain.sh         aapt2 + platform jars via npm
├── scripts/build-apk.sh               aapt2 → ECJ → D8 → apksigner
├── tests/run-tests.sh         JVM tests for the parser
└── dist/                              build output (gitignored)
```

## Security

* The printed URL is a **process credential**: whoever opens it gets full agent access, including tool execution on the host. Share it only with the device you are pairing.
* The app stores the base URL and token in its private `SharedPreferences` (not encrypted at rest); the session cookie lives in the WebView cookie store.
* The manifest enables cleartext HTTP because `dsh` serves plain HTTP on a LAN bind by default. That is fine on a trusted network; for anything else terminate TLS on the server (`--tls-cert`/`--tls-key`) or reach it through a VPN such as Tailscale, then connect with the `https://` URL.
* Never expose `dsh` directly to the internet. Its remote-access story is a TLS-terminating reverse proxy in front of a loopback listener — see [public deployments](../../docs/user/guide/public-deployments.md).

## Limitations

* **The APK ships no Node runtime.** The harness itself runs either in Termux on the phone or on a computer; this app is the client for it. Bundling Node would mean cross-building the runtime and every native addon for Android.
* **Blob downloads** (`blob:` URLs created by the page) cannot be handed to `DownloadManager`; only real HTTP downloads are captured.
* **No background pushes.** Sessions keep running on the host, but the phone learns about new output while the app is open.
* **WebView version matters.** The Web UI is a modern React application; keep Android System WebView up to date (Play Store) on older devices.
* **One signing key per install.** A rebuild with a different keystore requires uninstalling the previous app.
