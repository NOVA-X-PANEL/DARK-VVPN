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

## The tunnel, end to end

This is the part of the app that actually moves packets, so it is worth stating
in full.

```
  ┌─────────────────────────────────────────────────────────────┐
  │ VpnService.Builder                                          │
  │   addAddress(10.8.0.2/30)   addRoute(0.0.0.0/0)             │
  │   addDnsServer(1.1.1.1)     addDisallowedApplication(self)  │
  └──────────────────────────┬──────────────────────────────────┘
                             │ establish()
                             ▼
                    tun interface (fd)
                             │
                             │  the descriptor crosses the boundary as an int
                             ▼
  ┌─────────────────────────────────────────────────────────────┐
  │ CoreController.startLoop(configJson, tunFd)                 │
  │   └── Xray-core                                             │
  │         ├── tun inbound  → gVisor netstack terminates flows │
  │         ├── routing      → ads blocked, LAN direct, rest →  │
  │         └── outbound     → dials the node                   │
  └─────────────────────────────────────────────────────────────┘
```

### Why there is no tun2socks

The obvious design for an Android VPN client is a userspace bridge: read
packets from the tun fd, speak SOCKS to the core. That means shipping a second
native library and a JNI shim, or writing a TCP/IP stack by hand.

Xray-core already contains a `tun` inbound backed by gVisor's netstack, and the
Android build exposes it through one extra argument:
`startLoop(config, tunFd)`. The core takes the descriptor, terminates the flows
arriving on it, and dials each one through the configured outbound. So the app
carries **no** packet-forwarding code at all — the service opens an interface,
hands over a number, and cleans up.

That choice also decides the protocol story: because the core does the work, the
app supports every protocol the core does, and Hysteria2 is the one case where it
is not Xray's — which is why it is reported rather than mis-compiled.

### The failure that looks like success

`builder.addDisallowedApplication(packageName)`.

The core dials the node from inside the same process. Without that exclusion,
those outbound sockets are themselves captured by the tun routes, fed back into
the netstack, and dialled again. The interface comes up, the notification
appears, the state machine says **Connected**, and not one byte reaches the
internet. There is no error anywhere, because nothing failed — the packets are
just going in a circle.

It is the single least obvious requirement in this codebase, and it is why the
line carries a comment rather than appearing in a list of builder options.

### Why the config owns the tun constants

`TUN_CLIENT_ADDRESS`, `TUN_GATEWAY`, `TUN_PREFIX_LENGTH` and `TUN_MTU` live in
`XrayConfigBuilder` and are read by `DarkVvpnService`. They are two halves of one
agreement — the address Android assigns and the gateway the core answers on must
fall inside the same prefix — and they are wired together at runtime by an int,
so nothing at compile time forces them to match. `TunBridgeContractTest` asserts
the relationship instead.

### Shutdown order

Stop the core, then close the interface, then stop the service. Closing the tun
fd first leaves the core reading a dead descriptor; stopping the service first
can leave the core holding an fd with no owner. `shutdownTunnel()` is the only
place that tears anything down, and both `onRevoke` and `onDestroy` go through
it, so there is one order rather than three.

### Traffic counters

`queryAllOutboundTrafficStats()` returns the core's counters **and resets
them**, so each one-second sample is a true delta — which is what a throughput
figure is. Totals are accumulated in the service because the core deliberately
forgets. A real session timer falls out of the same loop.

## Where the tunnel core attaches

There is no pump loop to attach to any more. The core takes the descriptor
through `CoreController.startLoop(config, tunFd)` and owns everything behind it —
see "The tunnel, end to end" above. What remains the app's responsibility is the
lifecycle: opening the interface with the right routes, excluding the app from
its own tunnel, and tearing down in an order the core tolerates.

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

## Two ways a change can be invisible

The v1.2.0 launch crash is worth recording, because it beat every check the
project had: the build passed, 130 unit tests passed, and the APK was
structurally valid and contained the right native libraries. The app still died
the instant it opened.

The cause was one resource: the splash screen passed `R.mipmap.ic_launcher` to
Compose's `painterResource`. On API 26+ that id resolves to an
`<adaptive-icon>`, and `painterResource` can rasterize only a `<vector>` or a
bitmap. It threw during composition.

Two properties made it invisible, and both are worth designing against:

1. **A pure unit test never touches the resource system.** Every existing test
   was logic; none of them loaded a drawable.
2. **A structurally valid APK can still be broken.** Inspecting the archive
   proves the pieces are present, not that they work together.

The fix was a bitmap (`drawable-nodpi/splash_logo.png`), and the guard is
Robolectric: `AppStartupTest` builds the real `Application` and launches the real
`MainActivity`, so the splash screen is composed on the JVM. That test was
verified to have teeth the honest way — the fix was reverted on a throwaway
branch, the test failed with exactly the predicted stack trace
(`PainterResources_androidKt.loadVectorResource` → `SplashScreen`), and the
branch was deleted.

`ComposeResourceSafetyTest` covers the same ground statically, so the rule is
enforced rather than remembered. It checks the files AAPT compiles, not the
framework: the first version asked `BitmapFactory.decodeResource` whether a
drawable was rasterizable, and under Robolectric that call returns a synthetic
bitmap for *every* id — the assertion could never fail.

## Why a node would not connect: ALPN

Found by fetching a reporter's live subscription and measuring it. The panel
served this shape:

```
vless://…@www.speedtest.net:8443?type=ws&security=tls
      &alpn=h2%2Chttp%2F1.1%2Ch3&sni=panel…&path=%2F%40CHANNEL
```

Passing `alpn=h2,http/1.1,h3` straight into the config makes the server negotiate
**h2**, and a WebSocket node needs the HTTP/1.1 `Upgrade` handshake. The upgrade
request is answered with HTTP/2 binary frames instead of
`101 Switching Protocols`, and the tunnel never establishes. Measured over TLS
with the link's own SNI:

| ALPN offered | negotiated | `GET /@CHANNEL` upgrade |
|---|---|---|
| `h2,http/1.1,h3` | `h2` | binary frames, no upgrade |
| `http/1.1` | `http/1.1` | `101 Switching Protocols` |
| *(none)* | *(none)* | `101 Switching Protocols` |

`AlpnPolicy` therefore decides per transport rather than trusting the link. WS and
HTTPUpgrade drop `h2` — they run an HTTP/1.1 upgrade, and Xray's `ws` transport
does not implement RFC 8441 Extended CONNECT, so h2 is never correct for them.
HTTP/2 and gRPC keep it, and get it added when a panel omitted it. `h3` is dropped
everywhere, because it is a QUIC identifier with no meaning in a TCP handshake.
An empty result is left empty, since offering no ALPN was measured to work.

The parser still stores the panel's own ALPN. The decision belongs to the config
builder, which is the only component that knows what the transport can speak —
and keeping the parsed value intact means it stays inspectable.

### The same list also carried a node that could never work

```
vless://…@1.2.3.4.5:1234?type=tcp&security=none#Update+your+subscription+daily
```

Five octets: not an address, not a hostname, never resolvable. Providers inject
these to talk to their users, and imported faithfully they become rows that show
"—" and fail when picked — indistinguishable from a broken server. `HostValidator`
rejects them, while deliberately still allowing private LAN addresses, because a
self-hosted panel at home is a legitimate node.

## Why a subscription could import and still show nothing

Three independent faults produced one symptom, which is why it took a report from
a user to surface them: each is invisible from the others' vantage point.

**The fetch read compressed bytes as text.** Many panels sit behind Cloudflare or
nginx with gzip enabled, and `HttpURLConnection` does not decompress. The bytes
were converted to a string, the parser found no links in the noise, and the
message blamed the subscription. The fetcher now requests gzip, inflates it, and
sniffs the gzip magic bytes as well — a server may compress without saying so.
The body-size cap moved to *after* decompression, because a few kilobytes of gzip
can expand without bound; capping the wire size would have been the wrong guard.

**Fetched nodes were never persisted.** `load()` restored the subscription list
and nothing else, and the refresh only ran when the interval had elapsed, from
the Subscriptions screen. So a restart inside the window left an empty Servers
list. Nodes are now cached to disk alongside the subscriptions and restored on
launch, and the restore has somewhere to go: the ViewModel pushes them into the
server repository, and that ViewModel is created at app launch rather than on
first navigation to its tab.

The cache carries a schema version. `VpnServer` persists enum names, so renaming
`VpnSecurity.REALITY` would otherwise decode a stored node into one pointing at a
different security layer — failing in a way that looks like a server fault. A
version mismatch discards the cache instead of guessing.

**An `http → https` redirect was not followed.**
`HttpURLConnection.instanceFollowRedirects` does not follow a cross-protocol hop,
and plenty of panels redirect exactly that way, so the body arrived as a 301 page.
Redirects are now walked by hand, and the scheme is re-validated at every hop, so
a redirect cannot reach `file:` or `content:`.

### The message was hiding five problems

"No usable nodes" was true in every one of those cases and useful in none of
them. An HTML body is now classified as a document before parsing, and HTTP
statuses are mapped to what the user can act on — 403 means the link expired, 404
means a typo, 5xx means the provider is at fault. The classifier is a pure
function ([ResponseClassifier]) so it is tested directly, rather than
re-implemented in a test that would pass while the app did something else.

### Why the app ships no nodes

Earlier versions bundled demo entries on `*.invalid` hostnames. They could never
resolve, so they only ever produced failures — and they made a working import
look broken, because the list still showed unusable rows above the real ones. An
app that starts empty and explains itself is more useful than one that starts
full of things that cannot connect.

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
