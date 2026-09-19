---
name: Bug report
about: Something in the app, the runtime image or a build behaves wrongly
title: ""
labels: bug
assignees: ""
---

## What happens

<!-- And what you expected instead. -->

## Steps

1.
2.

## The build

- **PiKit version** and **runtime revision**: both are on **Settings → About PiKit**
  (the revision looks like `aarch64-a47a6435ae5411d9`, and it says which runtime the
  APK actually carries).
- **Which APK**: `arm64` or `x64`, debug or release, and whether you built it
  yourself or installed a published one.
- **Signing**: if you installed a release APK from this repository before a release
  keystore existed, say so — it is signed with the debug key, which changes what can
  be reinstalled over it.

## The device

- **Model and Android version** (for example: Xiaomi 15, Android 17 / API 37).
- **ABI**: `arm64-v8a` on a phone, `x86_64` on the usual emulator. The app reports a
  missing runtime image rather than falling back when these disagree, so this is
  usually the first question.
- **Emulator?** Say which system image. A Play-Store image has no working DNS and no
  `adb root`, which is a known trap and not this bug (`AGENTS.md`).

## Logs

<!--
`adb logcat` output around the failure, and — if the agent or the shell is involved —
what the Terminal tab printed. A runtime image is unpacked on first launch
(~110 MB from the APK) and messages from the installer name the file it is on.
-->
