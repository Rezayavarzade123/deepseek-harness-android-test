# `dsh` mobile client (Android APK)

English | [中文](README.zh.md)

A native Android shell for the [DeepSeek Harness](../../README.md) Web UI that carries the harness itself. The APK ships a Node runtime, the harness installation, and the libraries Node needs, so a fresh phone can run the agent straight from the launcher — no Termux, no package manager, no root. It still connects to a `dsh web` server on a computer when you want the workspace to live elsewhere.

## Everything ships in the APK

`dsh` is a Node.js application: it owns the agent loop, its tool processes, the filesystem, and the model credentials. Android has no system Node, and the runtimes on npm are either Node 18 (below the harness floor) or missing bionic builds of the harness's native addons. This project therefore vendors all of it.

```
 phone (this APK)                                    optional: a computer
┌──────────────────────────────────────┐   HTTP    ┌────────────────────────┐
│ WebView ── dsh Web UI                │ ────────▶ │ dsh web --host <lan-ip>│
│ native setup / runtime / error screens│          │ agent · tools · files  │
│                                      │           └────────────────────────┘
│ 127.0.0.1:308x  ◀── dsh web          │
│   ▲                                  │
│   └── Node 24 (bundled) + node_modules│
└──────────────────────────────────────┘
```

| Bundled piece | Size | Notes |
|---|---|---|
| Node 24.18.0 for Android arm64 | 43 MiB | Termux's bionic build, ELF runpaths rewritten to `$ORIGIN` |
| `node_modules` of `@deepseek-ai/dsh` 0.2.1-rc.2 | 52 MiB (zip) | Pruned of native/WASM packages the phone cannot run |
| Shared libraries | 44 MiB | ICU, OpenSSL, SQLite, c-ares, libc++ |
| Root certificates | 0.2 MiB | `SSL_CERT_FILE` for outbound HTTPS |

Total download: **84 MiB**. The app unpacks about 300 MiB into its private storage on first start.

## Run it on the phone

Open the app. The first launch shows a **This phone** card; tapping **Start harness on this phone** unpacks the runtime (a progress line and, on failure, the process log), spawns `dsh web` on loopback, and loads its Web UI. Later launches reuse the unpacked copy and go straight to the UI; a runtime the system killed in the background is restarted on resume.

The harness behaves like a local install, not a remote client:

* The workspace, session history, and credentials live in the app's private directory (`/data/data/dev.dsh.harness/files/runtime`). Uninstalling the app deletes them.
* Tools execute on the phone as the app's UID. Android's own sandbox is the boundary: `dsh`'s Landlock launcher is Linux-only, so the app sets `DSH_PERMISSION_MODE=danger-full-access` and the agent can touch anything the app can.
* The server binds `127.0.0.1` only, on a free port from 3080 up, and is reachable by nothing else on the network.
* Tool commands are mapped onto Android's `mksh`: the harness spawns `bash -c`, and a shim on `PATH` hands that to `/system/bin/sh`. POSIX shell syntax works; bash-only syntax (`shopt`, process substitution) and `git` do not, because neither is bundled.
* Model providers are configured in the Web UI as usual; API keys never leave the device except in requests to the provider.

Menu actions: **Restart harness**, **Runtime log** (last 400 lines of process output), **Run harness on this phone**, **Connect to a computer**.

Because the runtime is executed from app storage, the manifest targets Android 9 (API 28) — the same ceiling Termux lives with, since Android 10 blocks `exec` from writable app data for newer targets. On Android 13+ the installer may warn that the app was built for an older system; that warning is expected. The runtime is 16 KB-page aligned, so it also runs on newer devices with 16 KB pages.

## Download and install

The prebuilt APK is checked in at [`releases/dsh-mobile-0.2.1-rc.2-android.apk`](releases/dsh-mobile-0.2.1-rc.2-android.apk), so it downloads from the repository page without a build step.

```sh
adb install -r releases/dsh-mobile-0.2.1-rc.2-android.apk
```

Sideloading also works: copy the APK to the device and open it (allow installs from the file manager when asked). Requirements: **arm64** device, Android 8.0 (API 26) or newer, and roughly 400 MiB free while installing. The APK is signed with the development key the build creates; it is not a Play Store artifact.

## Connect to a computer

On the computer that should run the agent:

```sh
npx @deepseek-ai/dsh web --host 192.168.1.20 --no-open
```

* Replace `192.168.1.20` with that machine's LAN address (`hostname -I`, `ipconfig`, or the Wi-Fi settings screen).
* `--host` accepts one concrete address — `dsh` rejects wildcard binds by design.
* The command prints a URL such as `http://192.168.1.20:3080/?token=8f3…`. Keep that process running.

In the app:

1. Tap **Connect to a computer** (or the menu item of the same name).
2. Paste the printed URL into **Server address** (the app splits the token out for you), or type `192.168.1.20:3080` and paste the token separately.
3. Tap **Connect**. The launch token is exchanged for a session cookie, so the app reconnects without the token from then on.
4. If you do not know the address, tap **Scan my network**: the app probes port 3080 on the /24 around its own address and lists every host that answers.

Choosing a computer stops the embedded runtime, so the phone does not keep a second agent alive. Every `dsh` restart prints a new token; when the old one is rejected the app shows **Login token rejected** and offers to take the new URL.

## What the app does

| Feature | Detail |
|---|---|
| Embedded runtime | Unpacks the bundled Node + harness on first start, spawns `dsh web` on loopback, restarts it when the OS reclaims it, and exposes the process log |
| Session | WebView cookies, so the Web UI behaves exactly as in a browser; the token is only replayed after a 401 |
| Computer host | Any reachable `dsh web` server, with LAN scan and up to eight remembered servers (long-press to remove) |
| LAN discovery | Parallel TCP probe of loopback plus the local /24 on port 3080, results ordered numerically |
| Uploads | Native file picker for `<input type=file>`, including multi-select and camera/gallery providers |
| Downloads | Android `DownloadManager` with the WebView cookie attached, saved to `Downloads/` |
| Voice input | `RECORD_AUDIO` is requested only when the Web UI asks for the microphone |
| Navigation | Same-server links stay in the app; other links open in the browser; back walks the WebView history, then double-tap-to-exit |
| Errors | Timeout, refused, DNS, renderer crash, rejected-token, and runtime-startup screens with recovery actions |

The instrumentation is deliberately small: the harness Web UI is the product, and the shell only adds what a WebView lacks plus the runtime that makes it self-contained.

## Build

No Gradle and no Android SDK install are required. The scripts fetch the official Google binaries (aapt2, `android.jar`, D8, apksigner) from npm carriers and compile with ECJ, which is exactly how the APK in `dist/` is produced.

Requirements: Node.js, a JDK 17+, `zip`, and Python 3 (for the ELF runpath rewrite).

```sh
cd apps/android
scripts/fetch-toolchain.sh     # once: aapt2, android.jar, d8, apksigner, ECJ
scripts/prepare-runtime.sh     # Node tarball + npm install + prune + patch, ~10 min
JAVA=/path/to/java scripts/build-apk.sh
```

`prepare-runtime.sh` stages everything under `app/src/main/assets/runtime`: it downloads Node, installs `@deepseek-ai/dsh` from npm, prunes packages the phone cannot run (the WASM office engine, speech models, `sharp`, `node-pty`, telemetry SDKs) and native builds for other systems, replaces the two native addons the harness needs with JavaScript equivalents, packs the result into a stored-zip asset, and rewrites every ELF runpath to `$ORIGIN` so the dynamic linker finds the bundled libraries wherever the app is installed.

Output: `apps/android/dist/dsh-mobile-0.2.1-rc.2-android.apk`, signed and verified at the end of the run. Copy it to `releases/` under the same name to publish a new build for download.

Environment overrides:

| Variable | Default | Purpose |
|---|---|---|
| `JAVA` | `java` on `PATH` | JVM used to run ECJ, D8, and apksigner |
| `DSH_ANDROID_TOOLS` | `apps/android/.tools` | Toolchain and signing-key directory |
| `DSH_VERSION` | `0.2.1-rc.2` | Harness version installed into the payload |
| `NODE_DISTRO_URL` | pinned GitHub tarball | Android Node build to bundle |
| `WORK` | `apps/android/.runtime-build` | Payload staging directory |
| `VERSION_CODE` / `VERSION_NAME` | `23` / `0.2.1-rc.2-android` | APK version metadata |
| `DSH_KEYSTORE` | `.tools/signing/dsh-mobile.p12` | Signing keystore; created on first build |
| `DSH_STORE_PASS` | `android` | Keystore and key password |
| `DSH_KEY_ALIAS` | `dsh-mobile` | Signing key alias |

Pass your own `DSH_KEYSTORE`/`DSH_STORE_PASS`/`DSH_KEY_ALIAS` for a key you intend to keep: Android refuses to update an installed app whose signature changed.

### Tests

`ServerTarget` — the address/token parser — is plain `java.net` code, so it runs on the JVM, and the packaging rules are checked against the staged assets:

```sh
cd apps/android
tests/run-tests.sh
```

The parser suite covers bare hosts, default ports, tokenized startup URLs, mount prefixes, IPv6 literals, percent-encoding, and rejected schemes. The packaging suite checks that the bundled ELF files target arm64, that no runpath still points at Termux, that the payload carries the patched harness and none of the pruned native packages, and that the manifest keeps the target SDK the runtime needs.

## Layout

```
apps/android
├── app/src/main
│   ├── AndroidManifest.xml            targetSdk 28 so the bundled ELF can exec
│   ├── java/dev/dsh/harness
│   │   ├── MainActivity.java          WebView, setup/runtime/error screens, uploads, downloads
│   │   ├── EmbeddedHarness.java       unpack, spawn, watch, and stop the bundled runtime
│   │   ├── ServerTarget.java          address + launch-token parsing
│   │   ├── ServerStore.java           recent servers in SharedPreferences
│   │   └── LanScanner.java            /24 TCP probe for local dsh servers
│   ├── assets/runtime                 Node, shared libraries, payload.zip (staged, gitignored)
│   └── res                            layouts, dark theme, adaptive icon
├── scripts/fetch-toolchain.sh         aapt2 + platform jars via npm
├── scripts/prepare-runtime.sh         stage Node + harness into assets/runtime
├── scripts/patch-payload.mjs          prune packages, patch the two native addons
├── scripts/patch-elf-rpath.py         rewrite DT_RUNPATH to $ORIGIN
├── scripts/build-apk.sh               aapt2 → ECJ → D8 → apksigner
├── tests/run-tests.sh                 JVM parser tests + packaging checks
├── releases/                          published APK(s), downloadable from the repo page
└── dist/                              build output (gitignored)
```

## Security

* The embedded runtime listens on `127.0.0.1` only, and its launch token never leaves the device. The **Connect to a computer** path does send the token over the LAN, and the printed URL is a **process credential**: whoever opens it gets full agent access, including tool execution on that host.
* The bundled agent runs with `DSH_PERMISSION_MODE=danger-full-access` because the Landlock launcher has no Android build. Tool calls are bounded only by Android's per-app sandbox and by the permissions the user granted; treat the phone's storage as reachable by the agent.
* Credentials entered in the Web UI (provider keys, sessions) live in the app's private storage, not encrypted at rest, and are removed with the app.
* The manifest enables cleartext HTTP because `dsh` serves plain HTTP on a LAN bind. That is fine on a trusted network; for anything else terminate TLS on the server (`--tls-cert`/`--tls-key`) or reach it through a VPN such as Tailscale, then connect with the `https://` URL.
* Never expose `dsh` directly to the internet. Its remote-access story is a TLS-terminating reverse proxy in front of a loopback listener — see [public deployments](../../docs/user/guide/public-deployments.md).

## Limitations

* **arm64 only, Android 8.0+.** The bundled Node is an aarch64 bionic build; 32-bit and x86 devices fall back to the computer path.
* **84 MiB download, ~400 MiB on disk.** The runtime is unpacked into app storage on first start; there is no shared, system-wide copy.
* **No bash, git, or PTY.** Tool commands run under Android's `mksh`, so bash-only syntax and `git`-backed features (worktrees, some review flows) are unavailable. The terminal pane needs `node-pty`, which has no Android build, so it stays inert.
* **No Landlock sandbox.** Without the launcher, the agent's file access is bounded by the app sandbox rather than by a policy, so the app runs it in full-access mode.
* **Blob downloads** (`blob:` URLs created by the page) cannot be handed to `DownloadManager`; only real HTTP downloads are captured.
* **No background pushes.** The harness process is killed when Android reclaims the app; sessions resume when you reopen it.
* **WebView version matters.** The Web UI is a modern React application; keep Android System WebView up to date (Play Store) on older devices.
* **One signing key per install.** A rebuild with a different keystore requires uninstalling the previous app.
