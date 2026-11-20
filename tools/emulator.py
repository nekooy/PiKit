#!/usr/bin/env python3
"""Start the Android emulator with the settings this project needs.

The emulator has two traps that make PiKit look much worse than it is:

* `-no-window` removes the display surface. Every frame is then scheduled by a
  headless path and a 320 ms drawer swipe was measured at four frames (80-100%
  janky). `-qt-hide-window` keeps the real renderer while leaving the desktop
  alone; the same swipe measured 26 frames and 11.5% janky.
* `-no-snapshot-save` throws the session away. The AVD's snapshot is a disk
  image, so the next start returns to whatever APK and runtime it held when the
  snapshot was written. That is how a fresh APK kept looking like the previous
  UI. The default here is quickboot, and `stop` saves the snapshot; `--cold`
  is only for testing the boot path itself.

Usage:
  python tools/emulator.py start --avd Medium_Phone
  python tools/emulator.py benchmark --apk app/build/outputs/apk/x64/debug/app-x64-debug.apk
  python tools/emulator.py stop

The AVD name is machine state, not repository state: pass it explicitly or set
PIKIT_AVD. The SDK is found through ANDROID_HOME / ANDROID_SDK_ROOT or the
ignored `local.properties` file.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLING = ROOT / ".tooling"


def sdk_root() -> Path:
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(name)
        if value:
            return Path(value)
    properties = ROOT / "local.properties"
    if properties.is_file():
        for line in properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                # Java properties escapes `:` and `\`; undo one level.
                return Path(re.sub(r"\\(.)", r"\1", line.split("=", 1)[1].strip()))
    raise SystemExit("Android SDK not found: set ANDROID_HOME or write sdk.dir in local.properties")


def emulator_exe(sdk: Path) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    return sdk / "emulator" / f"emulator{suffix}"


def adb_exe(sdk: Path) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    return sdk / "platform-tools" / f"adb{suffix}"


def adb(sdk: Path, port: int, *args: str, check: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(adb_exe(sdk)), "-s", f"emulator-{port}", *args],
        text=True,
        capture_output=True,
        check=check,
    )


def booted(sdk: Path, port: int) -> bool:
    try:
        result = adb(sdk, port, "shell", "getprop", "sys.boot_completed")
    except OSError:
        return False
    return result.returncode == 0 and result.stdout.strip() == "1"


def wait_boot(sdk: Path, port: int, timeout: float) -> float:
    started = time.monotonic()
    deadline = started + timeout
    while time.monotonic() < deadline:
        if booted(sdk, port):
            return time.monotonic() - started
        time.sleep(1.0)
    raise TimeoutError(f"emulator-{port} did not report sys.boot_completed=1 within {timeout:.0f}s")


def tail(path: Path, lines: int = 30) -> str:
    if not path.is_file():
        return ""
    return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:])


def start(args: argparse.Namespace) -> int:
    sdk = sdk_root()
    if not emulator_exe(sdk).is_file():
        raise SystemExit(f"emulator not found under {sdk}")
    if booted(sdk, args.port):
        print(f"emulator-{args.port} is already booted")
        return 0

    TOOLING.mkdir(exist_ok=True)
    log = TOOLING / f"emulator-{args.port}.log"
    command = [
        str(emulator_exe(sdk)),
        "-avd",
        args.avd,
        "-port",
        str(args.port),
        "-gpu",
        args.gpu,
        "-no-audio",
        "-no-boot-anim",
    ]
    if args.cold:
        command += ["-no-snapshot-load", "-no-snapshot-save"]
    elif args.headless:
        # ADB-only: no display surface, and therefore no honest frame timing.
        command += ["-no-window"]
    elif not args.visible:
        command += ["-qt-hide-window"]

    print(" ".join(command))
    with log.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
    try:
        elapsed = wait_boot(sdk, args.port, args.timeout)
    except (TimeoutError, OSError) as error:
        print(f"{error}\n--- {log} ---\n{tail(log)}", file=sys.stderr)
        if process.poll() is None:
            process.terminate()
        return 1
    print(f"booted emulator-{args.port} in {elapsed:.1f}s")
    print(f"log: {log.relative_to(ROOT)}")
    return 0


def stop(args: argparse.Namespace) -> int:
    sdk = sdk_root()
    # `emu kill` is a normal exit: the emulator writes the quickboot snapshot.
    adb(sdk, args.port, "emu", "kill")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        result = adb(sdk, args.port, "get-state")
        if result.returncode != 0:
            print(f"emulator-{args.port} stopped")
            return 0
        time.sleep(1.0)
    print(f"emulator-{args.port} did not stop within 30s", file=sys.stderr)
    return 1


def parse_total_time(output: str) -> int | None:
    match = re.search(r"TotalTime:\s*(\d+)", output)
    return int(match.group(1)) if match else None


def benchmark(args: argparse.Namespace) -> int:
    sdk = sdk_root()
    apk = Path(args.apk)
    if not apk.is_absolute():
        apk = ROOT / apk
    if not apk.is_file():
        raise SystemExit(f"APK not found: {apk}")
    if not booted(sdk, args.port):
        raise SystemExit(f"emulator-{args.port} is not booted; run `start` first")

    install = adb(sdk, args.port, "install", "-r", "-d", str(apk), check=False)
    print(install.stdout.strip())
    if install.returncode != 0:
        raise SystemExit(
            "install refused. A different signing key needs `adb uninstall pi.kit.mob` first; "
            "that also removes the unpacked runtime, so do it deliberately."
        )

    adb(sdk, args.port, "shell", "am", "force-stop", "pi.kit.mob")
    adb(sdk, args.port, "logcat", "-c")
    started = time.monotonic()
    launch = adb(sdk, args.port, "shell", "am", "start", "-W", "-n", args.activity, check=False)
    total = parse_total_time(launch.stdout)
    if launch.returncode != 0:
        raise SystemExit(launch.stderr.strip() or "am start failed")

    ready = None
    deadline = started + args.ready_timeout
    while time.monotonic() < deadline:
        dump = adb(sdk, args.port, "shell", "uiautomator", "dump", "--compressed", "/sdcard/pikit-emulator.xml")
        if dump.returncode != 0:
            time.sleep(0.5)
            continue
        xml = adb(sdk, args.port, "exec-out", "cat", "/sdcard/pikit-emulator.xml")
        if re.search(args.ready_text, xml.stdout):
            ready = time.monotonic() - started
            break
        time.sleep(0.5)

    print(f"first frame: {total} ms" if total is not None else "first frame: unknown")
    if ready is None:
        print(f"ready text not found within {args.ready_timeout:.0f}s", file=sys.stderr)
        return 1
    print(f"ready: {ready:.1f}s (includes uiautomator's own dump latency)")
    print("run `python tools/emulator.py stop` to persist this APK in the snapshot")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    def port_argument(target: argparse.ArgumentParser) -> None:
        target.add_argument("--port", type=int, default=5554)

    p_start = sub.add_parser("start", help="start the AVD and wait for boot")
    p_start.add_argument("--avd", default=os.environ.get("PIKIT_AVD"))
    port_argument(p_start)
    p_start.add_argument("--gpu", default="host")
    p_start.add_argument("--timeout", type=float, default=180.0)
    p_start.add_argument("--cold", action="store_true", help="ignore and do not write the quickboot snapshot")
    p_start.add_argument("--headless", action="store_true", help="use -no-window; do not use this for UI/perf checks")
    p_start.add_argument("--visible", action="store_true", help="show the emulator window")
    p_start.set_defaults(func=start)

    p_stop = sub.add_parser("stop", help="stop the AVD; a normal exit saves its snapshot")
    port_argument(p_stop)
    p_stop.set_defaults(func=stop)

    p_bench = sub.add_parser("benchmark", help="install an APK and report launch/first-frame timings")
    p_bench.add_argument("--apk", required=True)
    port_argument(p_bench)
    p_bench.add_argument("--activity", default="pi.kit.mob/.ui.MainActivity")
    p_bench.add_argument("--ready-text", default=r"新对话|New conversation")
    p_bench.add_argument("--ready-timeout", type=float, default=90.0)
    p_bench.set_defaults(func=benchmark)

    args = parser.parse_args()
    if args.command == "start" and not args.avd:
        raise SystemExit("--avd is required (or set PIKIT_AVD)")
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
