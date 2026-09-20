# On a physical device

*[Verification](../VERIFICATION.md): what the `arm64-v8a` phone pass settled — the things an
emulator cannot show.*

Xiaomi 15, Android 17 / API 37, `arm64-v8a`, installed alongside the official
`com.termux`:

First-run unpacking with the staging directory cleaned up; `node`, `bash`, `rg`,
`fd` and `pi 0.85.1` running from the relocated prefix; `pi --mode rpc` driven
through a real model-backed turn with tool calls and rendered Markdown; the
terminal's PTY, keyboard handling and extra-keys row; several concurrent terminal
sessions surviving a tab switch with their scrollback; renaming (both while a
conversation is open and while it is closed), pinning and multi-select delete;
model discovery against a live provider; saving a profile and the agent restarting
with the new `--model`; `pi update` reaching pi.dev from inside the runtime;
switching the interface language with the agent running; attaching images;
previewing a file from the Files tab; granting shared storage and writing through
it (`shared storage: symlinks=true writable=true`, a real write-and-read-back
probe); and coexistence with the real Termux installation, which is the point of
shipping under a separate application id.

What the phone pass does *not* cover is listed in [known-gaps.md](known-gaps.md),
together with the checks that need a person rather than an instrument.
