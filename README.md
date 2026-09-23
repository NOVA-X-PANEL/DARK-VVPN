<div align="center">

<img src="assets/logo.png" alt="DARK VVPN" width="130" />

# DARK VVPN

**A dark-first Android VPN client — Kotlin · Jetpack Compose · Material 3**

[![Android CI](https://github.com/NOVA-X-PANEL/DARK-VVPN/actions/workflows/android.yml/badge.svg)](https://github.com/NOVA-X-PANEL/DARK-VVPN/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/NOVA-X-PANEL/DARK-VVPN?include_prereleases&label=download&color=7C4DFF)](https://github.com/NOVA-X-PANEL/DARK-VVPN/releases/latest)
![Platform](https://img.shields.io/badge/platform-Android%2024%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.12-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

**[⬇ Download the latest APK](https://github.com/NOVA-X-PANEL/DARK-VVPN/releases/latest)** — debug build, ready to sideload.

</div>

---

## What this is

DARK VVPN is a **production-shaped client shell**: every part of the app that is
*not* the packet tunnel is finished. Navigation, theming, state management,
preference persistence, the `VpnService` lifecycle, the consent handshake and
the foreground notification all work as they would in a shipped app.

The tunnel data plane is deliberately left as a well-marked integration point so
you can drop in the core you actually use — Xray, sing-box, WireGuard, or
anything else — without rewriting the UI around it.

> **Status:** skeleton / reference implementation. It builds, runs and connects
> to a tun interface, but it does not yet forward packets. See
> [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for exactly where the core
> attaches.

## Features

| | |
|---|---|
| **Dark-first design** | A hand-tuned violet-on-ink palette, not a recoloured light theme. Material 3 throughout. |
| **One-tap connect** | An animated state-aware orb: pulsing ring while negotiating, green shield when up, red on failure. |
| **Full `VpnService` lifecycle** | Consent via `VpnService.prepare()`, foreground service with a live notification, correct teardown on revoke. |
| **Server catalogue** | Searchable, latency-sorted node list with per-node protocol and quality badges. |
| **Live session counters** | Download / upload throughput and session duration on the home screen. |
| **Persistent preferences** | Auto-connect, kill switch, ad blocking, sort order and theming, stored with Preferences DataStore. |
| **Credential hygiene** | A `Redact` utility plus backup rules that keep tokens out of logs and out of cloud backups. |
| **Tested** | JVM unit tests for the pure logic; an instrumented smoke test for the packaged app. |

## Screens

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│   Splash    │  │    Home     │  │   Servers   │
│             │→ │             │  │             │
│   logo +    │  │  connect/   │  │  search +   │
│   tagline   │  │  disconnect │  │  node list  │
│             │  │    orb      │  │             │
│             │  │  + stats    │  │  + latency  │
└─────────────┘  └─────────────┘  └─────────────┘
                        │
                 ┌──────┴──────┐
                 │  Settings   │
                 │ connection/ │
                 │ appearance/ │
                 │   about     │
                 └─────────────┘
```

## Getting started

### Requirements

- Android Studio **Ladybug** (2024.2) or newer
- JDK **17**
- Android SDK with **compileSdk 35**
- A device or emulator running **Android 7.0 (API 24)** or newer

### Build from the command line

```bash
git clone https://github.com/NOVA-X-PANEL/DARK-VVPN.git
cd DARK-VVPN

# Debug build
./gradlew assembleDebug

# Install straight onto a connected device
./gradlew installDebug

# Unit tests
./gradlew testDebugUnitTest
```

The debug APK lands in `app/build/outputs/apk/debug/`.

### Build from Android Studio

`File → Open…` → select the repository root → let Gradle sync → press **Run**.

## Project structure

```
DARK-VVPN/
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/com/darkvvpn/app/
│       │   │   ├── DarkVvpnApplication.kt    # service locator (AppContainer)
│       │   │   ├── MainActivity.kt           # edge-to-edge host + bottom nav
│       │   │   ├── data/
│       │   │   │   ├── model/                # VpnServer, VpnState, AppSettings…
│       │   │   │   └── repository/           # ServerRepository, SettingsRepository
│       │   │   ├── navigation/               # routes + NavHost
│       │   │   ├── ui/
│       │   │   │   ├── components/           # ConnectionOrb, ServerRow, badges…
│       │   │   │   ├── screens/              # Splash, Home, Servers, Settings
│       │   │   │   └── theme/                # colour, type, MaterialTheme
│       │   │   ├── util/                     # Formatters, Redact
│       │   │   ├── viewmodel/                # three ViewModels + factories
│       │   │   └── vpn/                      # DarkVvpnService, manager, notifications
│       │   └── res/                          # strings, themes, icons, backup rules
│       ├── test/                             # JVM unit tests
│       └── androidTest/                      # instrumented tests
├── docs/ARCHITECTURE.md
├── gradle/libs.versions.toml                 # version catalogue
└── .github/workflows/android.yml             # CI: build + test + artifact
```

## Architecture in one paragraph

State flows in one direction: `VpnConnectionManager` (a process-wide singleton)
owns the tunnel state and counters, `DarkVvpnService` drives them, and the
ViewModels expose them to Compose as `StateFlow`s. Screens are stateless
functions that read from a ViewModel and emit events back. Repositories are the
only things that touch persistence, and the whole dependency graph is wired by
hand in `AppContainer` — no DI framework, because the graph is three objects
deep. The full write-up, including the state machine and the exact place your
tunnel core plugs in, is in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Adding your tunnel core

1. Implement the packet pump in `DarkVvpnService.startPumpLoop()` — it is
   already off the main thread, cancellable and passed the open tun fd.
2. Replace the synthetic numbers in `startStatsLoop()` with counters from the
   core's own accounting.
3. Point `ServerRepository.seedCatalogue()` at your subscription parser instead
   of the built-in list.
4. Keep credentials out of plain `SharedPreferences`; use the Keystore-backed
   storage of your choice and log only through `Redact`.

## Roadmap

- [ ] Real packet forwarding behind a pluggable core interface
- [ ] Subscription import (`vless://`, `vmess://`, `trojan://`, base64 lists)
- [ ] Per-app split tunnelling
- [ ] Real latency probes instead of the simulated ones
- [ ] Quick Settings tile and home-screen shortcuts
- [ ] Localisation (Persian, Arabic, Russian, Turkish, Chinese)
- [ ] Baseline-profile and macrobenchmark suites

## Contributing

Issues and pull requests are welcome. Please keep changes focused, run
`./gradlew testDebugUnitTest` before opening a PR, and match the existing
Compose/Material 3 conventions rather than introducing a second way to do
something the project already does.

## Security

Please do not open a public issue for a security problem. See
[`SECURITY.md`](SECURITY.md) for the private reporting channel.

## License

Released under the **GNU General Public License v3.0**. See [`LICENSE`](LICENSE).

---

<div dir="rtl">

## دربارهی این پروژه (فارسی)

**DARK VVPN** یک اپلیکیشن اندروید برای اتصال به VPN است که با **Kotlin** و
**Jetpack Compose** و بر پایهی **Material 3** نوشته شده. این پروژه یک
«اسکلت کامل و آمادهی توسعه» است: تمام بخشهای اپ — رابط کاربری، ناوبری،
تم تاریک اختصاصی، مدیریت وضعیت، ذخیرهی تنظیمات، چرخهی حیات سرویس VPN و
اعلان سیستمی — پیادهسازی شدهاند.

تنها بخشی که بهصورت عمدی ناتمام گذاشته شده، «انتقال واقعی بستههای شبکه»
است؛ محلی که باید هستهی تونل دلخواه شما (Xray، sing-box، WireGuard و …)
به آن وصل شود. محل دقیق اتصال در فایل `docs/ARCHITECTURE.md` مشخص شده است.

### ساختار کلی

- `ui/` — صفحهها و کامپوننتهای Compose
- `data/` — مدلها و مخزنهای داده
- `vpn/` — سرویس VPN و مدیر اتصال
- `viewmodel/` — مدیریت وضعیت هر صفحه
- `navigation/` — مسیرها و NavHost

### اجرای پروژه

```bash
git clone https://github.com/NOVA-X-PANEL/DARK-VVPN.git
cd DARK-VVPN
./gradlew assembleDebug
```

حداقل نیازمندی: Android 7.0 (API 24) و JDK 17.

</div>
