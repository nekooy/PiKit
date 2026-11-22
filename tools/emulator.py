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

One AVD, one driver. The AVD name is machine state, not repository state: pass
it explicitly, set `pikit.avd` in local.properties, or set PIKIT_AVD. A start
claims `.tooling/emulator.state` for the calling session (`--owner`, defaulting
to DSH_SESSION_ID); another session gets a clear refusal instead of silently
driving the same screen. `stop --force` takes over an abandoned claim (the crash
case) after the process check has proven the owner gone.

Usage:
  python tools/emulator.py start --avd <name>
  python tools/emulator.py status
  python tools/emulator.py benchmark --apk app/build/outputs/apk/x64/debug/app-x64-debug.apk
  python tools/emulator.py stop

The SDK is found through ANDROID_HOME / ANDROID_SDK_ROOT or the ignored
`local.properties` file.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLING = ROOT / ".tooling"
STATE = TOOLING / "emulator.state"


def caller() -> str:
    return os.environ.get("DSH_SESSION_ID") or os.environ.get("USERNAME") or "local"


def process_alive(pid: int) -> bool:
    if not pid:
        return False
    try:
        os.kill(pid, 0)
    except (OSError, SystemError):
        return False
    return True


def read_state() -> dict:
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def claim_state(state: dict) -> bool:
    """Create the lease with O_EXCL, so two `start` calls cannot both win."""
    TOOLING.mkdir(exist_ok=True)
    try:
        descriptor = os.open(STATE, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        return False
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(state, stream, indent=2)
    return True


def write_state(state: dict) -> None:
    TOOLING.mkdir(exist_ok=True)
    temporary = STATE.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2), encoding="utf-8")
    temporary.replace(STATE)


def clear_state() -> None:
    STATE.unlink(missing_ok=True)


def held_by_another(sdk: Path, args: argparse.Namespace) -> str | None:
    """Return the owner when a live lease holds the device, clearing a dead one."""
    state = read_state()
    if not state:
        return None
    port = state.get("port", args.port)
    # pid 0 means `start` has claimed the lease but not spawned the emulator yet.
    starting = not state.get("pid") and (time.time() - float(state.get("started") or 0)) < 120
    alive = starting or process_alive(int(state.get("pid") or 0)) or booted(sdk, port)
    if alive:
        return state.get("owner") or "unknown"
    clear_state()
    return None


def local_property(key: str) -> str | None:
    properties = ROOT / "local.properties"
    if not properties.is_file():
        return None
    prefix = f"{key}="
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith(prefix):
            # Java properties escapes `:` and `\`; undo one level.
            return re.sub(r"\\(.)", r"\1", line[len(prefix):].strip())
    return None


def avd_default() -> str | None:
    return os.environ.get("PIKIT_AVD") or local_property("pikit.avd")


def sdk_root() -> Path:
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(name)
        if value:
            return Path(value)
    value = local_property("sdk.dir")
    if value:
        return Path(value)
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
    owner = args.owner or caller()
    holder = held_by_another(sdk, args)
    if holder:
        if holder == owner:
            print(f"emulator-{args.port} is already booted")
            return 0
        raise SystemExit(
            f"emulator-{args.port} is held by {holder}. "
            "Wait for that session to stop it, or use `stop --force` only after checking it is gone."
        )
    if booted(sdk, args.port):
        state = {"avd": args.avd, "port": args.port, "owner": owner, "pid": 0, "started": time.time()}
        if claim_state(state):
            print(f"emulator-{args.port} is already booted; lease claimed by {owner}")
            return 0
        holder = held_by_another(sdk, args)
        raise SystemExit(f"emulator-{args.port} is held by {holder or 'another session'}")

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

    state = {"avd": args.avd, "port": args.port, "owner": owner, "pid": 0, "started": time.time()}
    if not claim_state(state):
        holder = held_by_another(sdk, args)
        raise SystemExit(f"emulator-{args.port} is held by {holder or 'another session'}")
    print(" ".join(command))
    with log.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
    state["pid"] = process.pid
    write_state(state)
    try:
        elapsed = wait_boot(sdk, args.port, args.timeout)
    except (TimeoutError, OSError) as error:
        print(f"{error}\n--- {log} ---\n{tail(log)}", file=sys.stderr)
        if process.poll() is None:
            process.terminate()
        clear_state()
        return 1
    print(f"booted emulator-{args.port} in {elapsed:.1f}s (owner: {owner})")
    print(f"log: {log.relative_to(ROOT)}")
    return 0


def stop(args: argparse.Namespace) -> int:
    sdk = sdk_root()
    owner = args.owner or caller()
    holder = held_by_another(sdk, args)
    if holder and holder != owner and not args.force:
        raise SystemExit(
            f"emulator-{args.port} is held by {holder}; pass --force only after checking that session is gone."
        )
    # `emu kill` is a normal exit: the emulator writes the quickboot snapshot.
    adb(sdk, args.port, "emu", "kill")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        result = adb(sdk, args.port, "get-state")
        if result.returncode != 0:
            clear_state()
            print(f"emulator-{args.port} stopped")
            return 0
        time.sleep(1.0)
    print(f"emulator-{args.port} did not stop within 30s", file=sys.stderr)
    return 1


def status(args: argparse.Namespace) -> int:
    sdk = sdk_root()
    state = read_state()
    if not state:
        print("no emulator lease")
        return 0
    port = int(state.get("port") or args.port)
    print(
        f"{state.get('avd', '?')} on emulator-{port}: "
        f"owner={state.get('owner', '?')} pid={state.get('pid', 0)} "
        f"booted={str(booted(sdk, port)).lower()}"
    )
    return 0


def parse_total_time(output: str) -> int | None:
    match = re.search(r"TotalTime:\s*(\d+)", output)
    return int(match.group(1)) if match else None


def benchmark(args: argparse.Namespace) -> int:
    sdk = sdk_root()
    owner = args.owner or caller()
    state = read_state()
    if state and state.get("owner") != owner:
        raise SystemExit(
            f"emulator-{state.get('port', args.port)} is held by {state.get('owner')}; "
            "benchmark in that session or wait for it to stop."
        )
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

    def owner_argument(target: argparse.ArgumentParser) -> None:
        target.add_argument("--owner", default=None, help="lease owner; defaults to DSH_SESSION_ID")

    p_start = sub.add_parser("start", help="start the AVD and wait for boot")
    p_start.add_argument("--avd", default=avd_default())
    port_argument(p_start)
    owner_argument(p_start)
    p_start.add_argument("--gpu", default="host")
    p_start.add_argument("--timeout", type=float, default=180.0)
    p_start.add_argument("--cold", action="store_true", help="ignore and do not write the quickboot snapshot")
    p_start.add_argument("--headless", action="store_true", help="use -no-window; do not use this for UI/perf checks")
    p_start.add_argument("--visible", action="store_true", help="show the emulator window")
    p_start.set_defaults(func=start)

    p_stop = sub.add_parser("stop", help="stop the AVD; a normal exit saves its snapshot")
    port_argument(p_stop)
    owner_argument(p_stop)
    p_stop.add_argument("--force", action="store_true", help="stop despite another session's lease")
    p_stop.set_defaults(func=stop)

    p_status = sub.add_parser("status", help="print the current emulator lease")
    port_argument(p_status)
    p_status.set_defaults(func=status)

    p_bench = sub.add_parser("benchmark", help="install an APK and report launch/first-frame timings")
    p_bench.add_argument("--apk", required=True)
    port_argument(p_bench)
    owner_argument(p_bench)
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
