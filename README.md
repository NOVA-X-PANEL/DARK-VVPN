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

DARK VVPN is a working Android VPN client built on **Xray-core**. It imports
subscriptions and share links, renders any node the core can dial into an Xray
configuration, and runs it as a real system VPN: the traffic of every app on the
device goes through the node.

Node import, subscription refresh, config generation **and the tunnel itself**
are done. A link from any panel becomes a
[`XrayConfigBuilder`](app/src/main/java/com/darkvvpn/app/xray/XrayConfigBuilder.kt)
outbound, and the service hands the open tun descriptor to Xray-core, whose own
network stack terminates the flows behind it.

There is no tun2socks process and no packet pump in Kotlin: the core owns the
descriptor. That is why the service is a few hundred lines rather than several
thousand, and why the app supports every protocol the core does.

> **Status:** working client. It opens a tun interface, hands the descriptor to
> Xray-core, and carries traffic. See
> [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how the pieces fit.
>
> ⚠️ **The bundled node list uses `.invalid` hostnames** — they exist to
> demonstrate the UI, not to route traffic. Import a real subscription or share
> link on the **Subs** tab to connect to something.

## Features

| | |
|---|---|
| **A real tunnel** | Xray-core in-process, driven through the tun file descriptor. Traffic from every app on the device goes through the node. |
| **Every protocol** | VLESS, VMess, Trojan, Shadowsocks, WireGuard, SOCKS and HTTP, over TCP, WebSocket, gRPC, HTTP/2, HTTPUpgrade, SplitHTTP, XHTTP, QUIC and mKCP. |
| **Every security layer** | TLS, REALITY (publicKey / shortId / spiderX) and XTLS, with uTLS fingerprints, SNI, ALPN and `xtls-rprx-vision` flow. |
| **Subscription import** | Share links, base64 blobs, Clash YAML and sing-box JSON. A link can also be opened straight from another app. |
| **Subscription auto-update** | A configurable refresh interval, Wi-Fi-only option, per-subscription toggles, quota bar and last-error reporting. |
| **In-app updates** | Checks GitHub Releases, offers the new version, downloads it over HTTPS, **verifies the SHA-256** GitHub publishes, and hands it to the installer. |
| **Dark-first design** | A hand-tuned violet-on-ink palette, not a recoloured light theme. Material 3 throughout. |
| **One-tap connect** | An animated state-aware orb: pulsing ring while negotiating, green shield when up, red on failure. |
| **Full `VpnService` lifecycle** | Consent via `VpnService.prepare()`, foreground service with a live notification, correct teardown on revoke. |
| **Live session counters** | Download / upload throughput and session duration on the home screen. |
| **Credential hygiene** | A `Redact` helper, and backup rules that keep tokens out of logs and out of cloud backups. |
| **Tested** | 109 JVM unit tests covering the config builder, the subscription parser, version comparison and decoding edge cases. |

## Protocol and transport matrix

| Protocol | Xray outbound | Share link | Notes |
|---|---|---|---|
| VLESS | ✅ | `vless://` | REALITY, XTLS, `xtls-rprx-vision` |
| VMess | ✅ | `vmess://` | base64 JSON v2 and the legacy plain form |
| Trojan | ✅ | `trojan://` | TLS by definition |
| Shadowsocks | ✅ | `ss://` | SIP002 and the legacy whole-body base64 form |
| WireGuard | ✅ | `wireguard://` | native Xray outbound; keys, MTU, allowed IPs |
| SOCKS / HTTP | ✅ | `socks://`, `http://` | anonymous or user/pass |
| Hysteria2 | ❌ | `hysteria2://`, `hy2://` | QUIC-based; **needs a sing-box class core**. Parsed and stored, but reported as unsupported rather than mis-compiled |

Transport security: `none`, `tls`, `reality`, `xtls` · Transports: `tcp`, `raw`, `ws`,
`grpc`, `http`, `httpupgrade`, `splithttp`, `xhttp`, `quic`, `kcp`

A parsed node always becomes a `VpnServer`, and
[`XrayConfigBuilder`](app/src/main/java/com/darkvvpn/app/xray/XrayConfigBuilder.kt)
is the only place that knows Xray's schema — so a new input format never needs a
new config path, and a missing required field is reported by name instead of
surfacing as an opaque failure inside the core.


## Screens

<div align="center">

![DARK VVPN screens](docs/screenshots/00-overview.png)

</div>

| | |
|---|---|
| ![Splash](docs/screenshots/01-splash.png) | ![Home, disconnected](docs/screenshots/02-home-disconnected.png) |
| **Splash** — brand mark and tagline | **Home** — one-tap connect |
| ![Home, connected](docs/screenshots/03-home-connected.png) | ![Servers](docs/screenshots/04-servers.png) |
| **Home, connected** — live session counters | **Servers** — search, latency sort, protocol badges |
| ![Settings](docs/screenshots/05-settings.png) | |
| **Settings** — connection, appearance and about | |

> These previews are rendered from the same design tokens the app uses
> (`ui/theme/Color.kt`, the type scale in `ui/theme/Type.kt`, and each screen's
> layout constants), so they reflect the shipped UI rather than a redesign of it.

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
│       │   │   │   ├── model/                # VpnServer, VpnProtocol, Subscription…
│       │   │   │   ├── repository/           # ServerRepository, SettingsRepository
│       │   │   │   ├── subscription/         # parser, fetcher, GeoNaming, store
│       │   │   │   └── update/               # checker, downloader, installer, semver
│       │   │   ├── navigation/               # routes + NavHost
│       │   │   ├── ui/
│       │   │   │   ├── components/           # ConnectionOrb, ServerRow, UpdateDialog…
│       │   │   │   ├── screens/              # Splash, Home, Servers, Subs, Settings
│       │   │   │   └── theme/                # colour, type, MaterialTheme
│       │   │   ├── util/                     # Formatters, Redact, NetworkState
│       │   │   ├── viewmodel/                # four ViewModels + factories
│       │   │   ├── vpn/                      # DarkVvpnService, manager, notifications
│       │   │   └── xray/                     # XrayConfigBuilder
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

## How the tunnel fits together

```
  app traffic
       │  the system routes it here because of the tun routes
       ▼
  tun interface ──fd──► CoreController.startLoop(config, tunFd)
       │                        │
       │                        ▼
       │                Xray's network stack terminates the TCP/IP flows
       │                        │
       └────────────────  the protocol outbound dials the node
```

Three files matter:

| File | Responsibility |
|---|---|
| [`XrayConfigBuilder`](app/src/main/java/com/darkvvpn/app/xray/XrayConfigBuilder.kt) | Node → Xray document. The only place that knows Xray's schema. Also owns the tun address/gateway/MTU, because the config and the interface must agree. |
| [`XrayCore`](app/src/main/java/com/darkvvpn/app/xray/XrayCore.kt) | The process's handle on the core: one-time environment setup, start with the fd, stop, real traffic counters, status callback. |
| [`DarkVvpnService`](app/src/main/java/com/darkvvpn/app/vpn/DarkVvpnService.kt) | Opens the tun, hands over the descriptor, keeps the process alive, tears down in the right order. |

### The one line that makes it work

`builder.addDisallowedApplication(packageName)` in `establishTunInterface()`.
Without it the core's *own* outbound sockets are routed back into the tun, and
the tunnel connects while passing nothing at all. It is the single least obvious
requirement in the whole project.

### Swapping the core

The core is fetched by pinned version and SHA-256 in `app/build.gradle.kts`. To
use a different build, change `xrayCoreVersion`, `xrayCoreUrl` and
`xrayCoreSha256`. The API surface this app depends on is three calls —
`initCoreEnv`, `newCoreController` and `startLoop(config, tunFd)` — so a
different Xray build with the same gomobile bindings drops in unchanged.

## Roadmap

- [x] Xray config generation for every protocol and transport
- [x] Subscription import and scheduled refresh
- [x] In-app update with SHA-256 verification
- [x] Real packet forwarding through Xray-core
- [ ] Per-app split tunnelling (the plumbing is in place; the picker is not)
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
