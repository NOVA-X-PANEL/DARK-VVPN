# Contributing to DARK VVPN

Thanks for taking the time to help. This document describes how the project is
put together and what a good patch looks like here.

## Getting set up

```bash
git clone https://github.com/NOVA-X-PANEL/DARK-VVPN.git
cd DARK-VVPN
./gradlew assembleDebug
```

You need JDK 17 and an Android SDK with `compileSdk 35`. Android Studio
Ladybug or newer will configure all of this on first sync.

## Before you open a pull request

Run both of these and make sure they pass:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Then:

- keep the change focused — one concern per pull request;
- add or update a test when you fix a bug (a test that would fail without your
  fix, not one that passes either way);
- describe *why* in the commit body, not just *what*.

## House style

### Kotlin

- Official Kotlin style (`kotlin.code.style=official` in `gradle.properties`).
- Four-space indent, trailing commas in multi-line argument lists.
- Prefer `val` over `var`, and immutable data classes over mutable state.
- Public declarations get a KDoc comment explaining **why** they exist, not what
  the signature already says. A comment that restates the code is noise.
- No wildcard imports.

### Compose

- Composables are **stateless**: take data in, emit events out. A composable that
  reads a repository directly is a bug.
- **Never pass a `mipmap` to `painterResource`.** On API 26+ a launcher icon
  resolves to an `<adaptive-icon>` XML, and `painterResource` can only rasterize
  a `<vector>` or a real bitmap. It throws
  `IllegalArgumentException: Only VectorDrawables and rasterized asset types are
  supported` — during composition, so the app dies on launch. This is not
  hypothetical: it is the v1.2.0 launch crash. Put UI images in
  `res/drawable*/` as a PNG/WebP or a `<vector>`, and let
  `ComposeResourceSafetyTest` keep you honest.
- `Modifier` is the first optional parameter and must be threaded through, so
  callers can position the component.
- Use `collectAsStateWithLifecycle()`, never `collectAsState()`, so collection
  stops when the screen is not visible.
- Pull colours and type from `MaterialTheme`; do not hard-code them in a
  component. The palette lives in `ui/theme/Color.kt` and nowhere else.

### Architecture

- The dependency direction is `ui → viewmodel → repository/service → model`.
  Never the other way round.
- ViewModels must not hold a `Context`, an `Activity` or a `View`. If you need
  one, publish an event and let the composable do the Activity work — see how
  the VPN consent flow does it in `VpnViewModel`.
- Everything Android-free belongs in `util` or `data/model` so it can be unit
  tested on the JVM.

## Adding a new screen

1. Add the route string to `Routes.kt`.
2. Add a `composable(...)` entry to `DarkVvpnNavHost.kt`.
3. Put the composable in `ui/screens/`, in one file named after the screen.
4. Reusable pieces go in `ui/components/`, not inline in the screen file.
5. If it needs state, add a ViewModel with a `Factory` that pulls
   `AppContainer` out of `APPLICATION_KEY`.

## Adding a new dependency

Add the version to `gradle/libs.versions.toml` and reference it through the
catalogue — do not put a raw coordinate and version in `app/build.gradle.kts`.
Prefer a library already in the project over a new one that does the same job.

## Reporting bugs

A useful bug report says: what you did, what you expected, what happened, and
the Android version and device. If it involves the tunnel, mention whether
another VPN app was active, since only one tunnel can exist at a time.

Security issues go through [`SECURITY.md`](SECURITY.md) instead.

## License

By contributing you agree that your work is released under the project's
GNU General Public License v3.0.
