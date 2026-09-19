# Security

## Reporting

Open a private report through GitHub's
[Security Advisories](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
("Report a vulnerability" on the repository's *Security* tab) rather than a public
issue. If the report is about a bundled component rather than PiKit's own code,
upstream is the right place and this project will still take the fix in the image
builder:

| Component | Where it comes from |
| --- | --- |
| `pi` and the bundled extension | [earendil-works/pi](https://github.com/earendil-works/pi) |
| Termux packages, the bootstrap | [termux/termux-packages](https://github.com/termux/termux-packages) |
| The vendored terminal emulator | [termux/termux-app](https://github.com/termux/termux-app) |

## What PiKit does and does not protect

- **The API key is plain text in the app's private storage** — SharedPreferences
  `pikit_settings` — which is readable by this app's uid and, on a rooted or
  debuggable device, by whoever has that access. It is not encrypted: there is no
  keystore-wrapped secret in this build. What protects it is Android's application
  sandbox plus `android:allowBackup="false"` (so it is not swept into a cloud backup
  or an `adb backup`) and nothing else.
- **A custom endpoint's key also reaches the agent process through `argv`.**
  Deliberately: pi does not expand `$VARIABLE` in `models.json` for a provider it
  does not know, so `--api-key` is the only mechanism that works ([ARCHITECTURE
  §6](docs/architecture/06-custom-endpoints.md)). `/proc/<pid>/cmdline`
  is readable by the same uid, so this widens the exposure from "the app's files" to
  "anything running as this app", and `models.json` itself holds `$PIKIT_API_KEY`
  rather than the key so that the secret exists in one place.
- **No telemetry, no account, no analytics, no crash reporter.** The network
  traffic the app originates is the agent's model calls, made on your behalf, and one
  request of its own: **Check for updates** on the About page asks
  `github.com` for this repository's newest release, and only when you tap it.
  There is no background check and no update notification, because both would mean
  traffic nobody asked for. Everything else that leaves the device does so through a
  process you started inside the runtime — `pi update`, `pkg upgrade`, a URL you
  fetch in the Terminal tab. The absence of the rest is checkable in the manifest and
  the dependency list.
- **The bundled runtime is a real Linux userland with network access**, and the
  agent inside it can run shell commands by design. `tools/pi-safety-guard.ts`
  refuses the calls that destroy data and `env/SafeDelete.kt` refuses a recursive
  delete outside the app's own directory — those are conveniences and guard rails,
  not a container. `docs/ARCHITECTURE.md` §5 says what each layer does and does not
  cover.
- **Shared storage is off until you grant it**, one folder at a time, and the
  "All files access" grant is the one permission that is not scoped — the app explains
  it once, on first launch, and offers the system page; the folder switches are the
  scoped grant and work without it.

## Supported versions

The tip of `main`, and nothing older: PiKit has no release branch. A release APK from
this repository carries the release certificate, whose key is the one
`docs/RELEASING.md` describes; a build made without the four `pikit.keystore.*`
properties falls back to the **debug** key, whose private half is in the Android SDK and
which is therefore a build artefact rather than a secret. Which key an APK carries is
checkable without trusting this file:

```bash
apksigner verify --print-certs app/build/outputs/apk/arm64/release/app-arm64-release.apk
```
