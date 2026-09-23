# Architecture

## The shape of the app

```
                 ┌───────────────────────────────────────────┐
   Compose UI    │  Splash · Home · Servers · Settings       │
                 └───────────────────┬───────────────────────┘
                                     │ events ↑ / state ↓
                 ┌───────────────────▼───────────────────────┐
   ViewModels    │  VpnViewModel · ServersViewModel ·        │
                 │  SettingsViewModel                        │
                 └───────┬───────────────────────┬───────────┘
                         │                       │
        ┌────────────────▼──────────┐   ┌────────▼─────────────┐
        │  VpnConnectionManager     │   │  Repositories        │
        │  (process-wide singleton) │   │  Server · Settings   │
        └────────────────▲──────────┘   └────────┬─────────────┘
                         │ state                 │ DataStore
        ┌────────────────┴──────────┐            │
        │  DarkVvpnService          │      Preferences
        │  (VpnService, foreground) │
        └───────────────────────────┘
```

State moves in exactly one direction. The service owns reality; the manager
publishes it; the ViewModels shape it; the screens render it. Nothing in the UI
layer reaches past a ViewModel.

## Why a singleton state holder

The tunnel outlives any screen. If the connection state lived in
`VpnViewModel`, then:

- the foreground notification could disagree with the UI about whether the
  tunnel is up, and
- a configuration change could show "Disconnected" over a live tunnel.

`VpnConnectionManager` is an `object`, so both the service and every ViewModel
observe the same `StateFlow`. It holds no Android references, which also means
it can be unit-tested on a plain JVM.

## The connection state machine

```
                 connect()                establish() ok
  ┌──────────────┐  ───────►  ┌──────────┐  ────────►  ┌───────────┐
  │ Disconnected │            │Connecting│             │ Connected │
  └──────▲───────┘            └────┬─────┘             └─────┬─────┘
         │                         │ prepare() != null       │
         │                         ▼                         │ disconnect()
         │                 ┌────────────────┐                │
         │                 │AwaitingPermission│              │
         │                 └───────┬────────┘                │
         │           denied ───────┘  granted                │
         │                         │                         ▼
         │                         │              ┌─────────────────┐
         │  establish() == null    │              │  Disconnecting  │
         │◄────────────────────────┘              └────────┬────────┘
         │                                                 │
         └─────────────────────────────────────────────────┘  Error ──► Disconnected(lastError)
```

`VpnState.isBusy` gates the connect button so a double tap cannot launch two
consent flows or two service starts. `VpnConnectionManager.onConnected` resets
the counters, which is what makes a reconnect start the session timer at zero
rather than continuing the previous session's count.

## The consent handshake, and why the ViewModel does not do it

`VpnService.prepare()` returns an `Intent` that **only an Activity can launch**.
A ViewModel must not hold an Activity, so the split is:

1. `VpnViewModel.connect()` calls `VpnService.prepare()`.
2. If consent is already granted it starts the service immediately.
3. Otherwise it publishes the intent on `pendingPermissionIntent` and parks the
   chosen server.
4. `HomeScreen` observes that flow and launches it with
   `rememberLauncherForActivityResult`.
5. The result comes back as `onPermissionGranted()` / `onPermissionDenied()`.

The ViewModel owns the decision; the composable owns the Activity call. Neither
knows how the other does its job.

## Where the tunnel core attaches

`DarkVvpnService.startPumpLoop()` receives the open `ParcelFileDescriptor` and
runs on `Dispatchers.IO`. That is the integration point. A real core replaces
the parked `while` with:

```kotlin
val input  = FileInputStream(descriptor.fileDescriptor)
val output = FileOutputStream(descriptor.fileDescriptor)
// hand `input` to the core, write what the core produces to `output`
```

Two rules matter here and both are easy to get wrong:

- the loop must stop as soon as the job is cancelled, or a reconnect leaks a
  second pump on the same fd;
- nothing in the pump may touch the main thread, including any logging that
  could block.

`startStatsLoop()` is the second integration point: it currently fabricates
plausible numbers so the UI has something to animate. A real implementation
replaces it with the core's own accounting.

## Package responsibilities

| Package | Owns | Must not |
|---|---|---|
| `ui/theme` | Colour, typography, the `MaterialTheme` wrapper | Contain business logic |
| `ui/components` | Reusable, stateless composables | Read a ViewModel |
| `ui/screens` | Layout, ViewModel wiring, event handling | Touch repositories or the service |
| `navigation` | Route constants, `NavHost` | Know about business rules |
| `viewmodel` | Screen state, event handling, coroutine scoping | Hold a `Context` or an `Activity` |
| `data/model` | Immutable data classes and pure logic | Import Android |
| `data/repository` | Persistence and data sourcing | Know about the UI |
| `vpn` | Platform VPN lifecycle, connection state | Format anything for display |
| `util` | Pure helpers (`Formatters`, `Redact`) | Depend on other app packages |

## Dependency wiring

`AppContainer` is constructed once in `DarkVvpnApplication` and holds exactly
two objects. ViewModels get it through a `viewModelFactory { initializer { … } }`
that reads `APPLICATION_KEY` out of `CreationExtras`. Three objects and a factory
each is not worth a DI framework; if the graph grows past roughly a dozen
singletons, that trade-off flips and Hilt becomes the right answer.

## Import and update paths

Two pipelines sit on top of the state layer, and both are written so that
third-party input cannot escalate.

### Subscription → node → config

```
  URL ──HttpFetcher──► raw body ──SubscriptionParser──► [VpnServer] ──┐
  share link ─────────────────────────────────────────────────────────┤
  Clash YAML / sing-box JSON ─────────────────────────────────────────┤
  built-in catalogue ─────────────────────────────────────────────────┘
                                                                       │
                                        XrayConfigBuilder ◄────────────┘
                                                │
                                     complete Xray document
```

Every input becomes a `VpnServer` before anything else looks at it, so the
config builder is the only code that knows Xray's schema. `VpnProtocol` keeps
`uriScheme` and `xrayProtocol` separate for exactly this reason: Shadowsocks is
shared as `ss://` but must be emitted as `shadowsocks`, and conflating the two
produces a config the core silently refuses.

`HttpFetcher` refuses any scheme but `http`/`https` — including after a redirect,
because a redirect can change the scheme — caps the body at 4 MB, and never logs
the body, since a subscription URL usually carries an access token.

`SubscriptionParser` records a per-line failure and continues. A single
malformed node out of two hundred must not cost the user the other 199, so it
never throws.

### Update check → verified install

```
  GitHub Releases API (one hard-coded repo, HTTPS only)
        │
        ▼
  UpdateChecker ──VersionComparator──► is this newer than installed?
        │
        ▼  yes, and it has an .apk asset
  UpdateDownloader ── stream + SHA-256 over the bytes written ──┐
        │                                                        │
        │◄── mismatch? delete the file, report failure ──────────┘
        ▼  digest matches (or absent → reported as unverified)
  UpdateInstaller ──FileProvider content:// ──► package installer
```

The threat this closes: an app that installs an APK it downloaded is the highest
-risk surface in the project, because a substituted APK is a full device
compromise. Four things hold the line — the URL comes from the API response and
is never user-supplied, the digest covers exactly the bytes written to disk,
a mismatch deletes the file, and the file is written to a `.part` name and
renamed only after the hash passes, so a truncated download can never be
mistaken for a complete APK.

`VersionComparator` is hand-written because the rules are narrow and worth
stating: numeric identifiers compare numerically (`1.10.0 > 1.9.0`), a release
outranks its own pre-release (`1.0.0 > 1.0.0-rc1`, so a user is never offered a
downgrade), and a trailing number inside an alphanumeric identifier compares
numerically (`rc10 > rc9`) — a deliberate deviation from semver's lexical rule,
because the lexical order is the opposite of what a user reads.

## Testing strategy

- **JVM unit tests** cover everything pure: the Xray config builder (29 tests,
  one per protocol/security/transport branch), the subscription parser (24, one
  per input format), version comparison (15, one per semver rule that matters),
  link decoding and geo naming. No Android framework, so they run in
  milliseconds — which is why the parser and the config builder are kept free of
  it.
- **Instrumented tests** cover what only a device can answer: the packaged app
  identity, and (as the project grows) the consent flow and the service
  lifecycle.
- The connection state machine is deliberately pure enough to test without a
  device, which is the reason `VpnConnectionManager` holds no Android types.
