# Security Policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Report it privately through GitHub's
[**Report a vulnerability**](https://github.com/NOVA-X-PANEL/DARK-VVPN/security/advisories/new)
form (repository → *Security* → *Advisories* → *Report a vulnerability*).

Please include:

- what the issue is and where it lives (file, function, screen);
- the impact — what an attacker gains;
- steps to reproduce, or a proof of concept;
- the version and Android release you tested on.

You will get an acknowledgement within a few days. Please give us a reasonable
window to ship a fix before publishing anything publicly.

## Scope

DARK VVPN is a VPN client, so the highest-severity classes here are:

- **credential exposure** — connection secrets reaching logcat, backups, or
  crash reports;
- **traffic leakage** — packets leaving the device outside the tunnel, or
  failing open when the tunnel drops;
- **tun hijacking** — a second app or a malicious `Intent` starting, stopping or
  reconfiguring the tunnel.

## Engineering rules this project holds itself to

These are the properties a patch must not break:

1. **No secret ever reaches a log.** Use `Redact.secret` / `Redact.url`; never
   interpolate a credential, share link or subscription URL into a log line.
2. **Credentials are not backed up.** The backup and device-transfer rules
   exclude the secure store and the datastore directory. Keep new secret stores
   in that exclusion list.
3. **The service is not exported.** It is declared with
   `android:permission="android.permission.BIND_VPN_SERVICE"` and
   `android:exported="false"`, so only the system can bind to it.
4. **The service is not sticky.** `START_NOT_STICKY` is deliberate: the system
   must never resurrect a tunnel the user explicitly stopped.
5. **Failing to establish means failing closed.** A null fd from
   `establish()` surfaces an error and tears the service down; it never silently
   continues with traffic outside the tunnel.
6. **No secrets in the repository.** Signing material and `local.properties` are
   git-ignored. Rotate anything that was ever committed.
