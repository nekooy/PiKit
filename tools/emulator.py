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

One AVD, several independent slots. The AVD name is machine state, not repository
state: pass it explicitly, set `pikit.avd` in local.properties, or set PIKIT_AVD. The
AVD itself lives under the ignored `pikit.avdHome` (default `.tooling/avd`), and this
tool exports `ANDROID_AVD_HOME` only to the emulator it launches — a bare
`emulator -avd <name>` cannot find it. `start` takes the first free of two slots
(emulator-5554, emulator-5556) and runs that slot **read-only** from the one AVD, so a
second session gets its own process, its own ADB serial and its own screen instead of a
refusal. The read-only flag is what lets several instances of one AVD coexist; nothing a
slot installs survives its own exit, and `benchmark` installs the APK it measures. Each
slot is leased by the calling session (`--owner`, defaulting to DSH_SESSION_ID); `status`
lists the two slots and any unmanaged emulator, and `stop --port` releases one. `stop
--force` takes over an abandoned slot only after the process check has proven the owner
gone.

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
SLOT_PORTS = (5554, 5556)


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


def state_path(port: int) -> Path:
    return TOOLING / f"emulator-{port}.state"


def read_instance(port: int) -> dict:
    try:
        return json.loads(state_path(port).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def claim_instance(instance: dict) -> bool:
    """Create one slot's lease with O_EXCL, so two `start` calls cannot both win it."""
    TOOLING.mkdir(exist_ok=True)
    try:
        descriptor = os.open(state_path(instance["port"]), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        return False
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(instance, stream, indent=2)
    return True


def write_instance(instance: dict) -> None:
    TOOLING.mkdir(exist_ok=True)
    target = state_path(instance["port"])
    temporary = target.with_suffix(".tmp")
    temporary.write_text(json.dumps(instance, indent=2), encoding="utf-8")
    temporary.replace(target)


def clear_instance(port: int) -> None:
    state_path(port).unlink(missing_ok=True)


def instance_alive(sdk: Path, instance: dict) -> bool:
    # pid 0 means `start` has claimed the slot but not spawned the emulator yet.
    starting = not instance.get("pid") and (time.time() - float(instance.get("started") or 0)) < 120
    return starting or process_alive(int(instance.get("pid") or 0)) or booted(sdk, int(instance["port"]))


def live_instances(sdk: Path) -> dict[int, dict]:
    """The slots that are still alive, dropping state left by a dead process."""
    live: dict[int, dict] = {}
    for port in SLOT_PORTS:
        instance = read_instance(port)
        if not instance:
            continue
        if instance_alive(sdk, instance):
            live[port] = instance
        else:
            clear_instance(port)
    return live


def owner_instances(sdk: Path, owner: str) -> dict[int, dict]:
    return {port: instance for port, instance in live_instances(sdk).items() if instance.get("owner") == owner}


def adb_emulator_ports(sdk: Path) -> set[int]:
    result = subprocess.run([str(adb_exe(sdk)), "devices"], text=True, capture_output=True)
    ports: set[int] = set()
    for line in result.stdout.splitlines():
        serial = line.split("\t", 1)[0].strip()
        if serial.startswith("emulator-") and serial[len("emulator-"):].isdigit():
            ports.add(int(serial[len("emulator-"):]))
    return ports


def unmanaged_ports(sdk: Path, live: dict[int, dict]) -> set[int]:
    return adb_emulator_ports(sdk) - set(live)


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


def avd_home() -> Path:
    value = os.environ.get("ANDROID_AVD_HOME") or local_property("pikit.avdHome")
    return Path(value) if value else TOOLING / "avd"


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
    mine = owner_instances(sdk, owner)
    if mine:
        print(f"emulator-{next(iter(mine))} is already booted for this session")
        return 0

    live = live_instances(sdk)
    unmanaged = unmanaged_ports(sdk, live)
    if unmanaged:
        listed = ", ".join(f"emulator-{port}" for port in sorted(unmanaged))
        raise SystemExit(
            f"unmanaged emulator(s) already booted without a slot lease: {listed}. "
            "Run `python tools/emulator.py status`, stop them, then start through this tool."
        )
    if args.port is not None:
        port = args.port
        if port in live:
            raise SystemExit(
                f"emulator-{port} is held by {live[port].get('owner', 'another session')}; "
                "choose another --port or stop that slot first."
            )
    else:
        if len(live) >= len(SLOT_PORTS):
            holders = ", ".join(f"emulator-{p} ({i.get('owner', '?')})" for p, i in sorted(live.items()))
            raise SystemExit(
                f"all {len(SLOT_PORTS)} emulator slots are in use: {holders}. "
                "Stop one with `stop --port <port>`; the 16 GB profile deliberately keeps two."
            )
        port = next(p for p in SLOT_PORTS if p not in live)

    state = {"avd": args.avd, "port": port, "owner": owner, "pid": 0, "started": time.time(), "read_only": True}
    if not claim_instance(state):
        holder = read_instance(port).get("owner", "another session")
        raise SystemExit(f"emulator-{port} was just taken by {holder}")

    TOOLING.mkdir(exist_ok=True)
    log = TOOLING / f"emulator-{port}.log"
    command = [
        str(emulator_exe(sdk)),
        "-avd",
        args.avd,
        "-port",
        str(port),
        "-read-only",
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
    environment = os.environ.copy()
    environment["ANDROID_AVD_HOME"] = str(avd_home())
    with log.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, env=environment)
    state["pid"] = process.pid
    write_instance(state)
    try:
        elapsed = wait_boot(sdk, port, args.timeout)
    except (TimeoutError, OSError) as error:
        print(f"{error}\n--- {log} ---\n{tail(log)}", file=sys.stderr)
        if process.poll() is None:
            process.terminate()
        clear_instance(port)
        return 1
    print(f"booted emulator-{port} in {elapsed:.1f}s (owner: {owner}, read-only)")
    print(f"log: {log.relative_to(ROOT)}")
    return 0


def stop(args: argparse.Namespace) -> int:
    sdk = sdk_root()
    owner = args.owner or caller()
    live = live_instances(sdk)
    if args.all:
        targets = sorted(port for port, instance in live.items() if args.force or instance.get("owner") == owner)
    elif args.port is not None:
        targets = [args.port]
    else:
        mine = sorted(port for port, instance in live.items() if instance.get("owner") == owner)
        if not mine:
            raise SystemExit("no emulator slot belongs to this session; `status` lists the slots")
        if len(mine) > 1:
            raise SystemExit(f"this session owns several slots ({', '.join(map(str, mine))}); pass --port or --all")
        targets = mine

    for port in targets:
        instance = read_instance(port)
        if instance and instance.get("owner") != owner and not args.force:
            raise SystemExit(
                f"emulator-{port} is held by {instance.get('owner')}; pass --force only after checking that session is gone."
            )
        # `emu kill` is a normal exit; read-only slots leave the base AVD unchanged.
        adb(sdk, port, "emu", "kill")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            result = adb(sdk, port, "get-state")
            if result.returncode != 0:
                clear_instance(port)
                print(f"emulator-{port} stopped")
                break
            time.sleep(1.0)
        else:
            print(f"emulator-{port} did not stop within 30s", file=sys.stderr)
            return 1
    return 0


def status(args: argparse.Namespace) -> int:
    sdk = sdk_root()
    live = live_instances(sdk)
    if not live:
        print("no emulator slots")
    for port, instance in sorted(live.items()):
        print(
            f"emulator-{port}: avd={instance.get('avd', '?')} owner={instance.get('owner', '?')} "
            f"pid={instance.get('pid', 0)} booted={str(booted(sdk, port)).lower()} "
            f"read_only={str(bool(instance.get('read_only'))).lower()}"
        )
    for port in sorted(unmanaged_ports(sdk, live)):
        print(f"unmanaged: emulator-{port} is booted without a slot lease")
    return 0


def parse_total_time(output: str) -> int | None:
    match = re.search(r"TotalTime:\s*(\d+)", output)
    return int(match.group(1)) if match else None


def benchmark(args: argparse.Namespace) -> int:
    sdk = sdk_root()
    owner = args.owner or caller()
    port = args.port
    if port is None:
        mine = owner_instances(sdk, owner)
        if not mine:
            raise SystemExit("no emulator slot belongs to this session; run `start` first")
        port = next(iter(mine))
    else:
        instance = read_instance(port)
        if instance and instance.get("owner") != owner:
            raise SystemExit(
                f"emulator-{port} is held by {instance.get('owner')}; benchmark in that session or choose another slot."
            )
    apk = Path(args.apk)
    if not apk.is_absolute():
        apk = ROOT / apk
    if not apk.is_file():
        raise SystemExit(f"APK not found: {apk}")
    if not booted(sdk, port):
        raise SystemExit(f"emulator-{port} is not booted; run `start` first")

    install = adb(sdk, port, "install", "-r", "-d", str(apk), check=False)
    print(install.stdout.strip())
    if install.returncode != 0:
        raise SystemExit(
            "install refused. A different signing key needs `adb uninstall pi.kit.mob` first; "
            "that also removes the unpacked runtime, so do it deliberately."
        )

    adb(sdk, port, "shell", "am", "force-stop", "pi.kit.mob")
    adb(sdk, port, "logcat", "-c")
    started = time.monotonic()
    launch = adb(sdk, port, "shell", "am", "start", "-W", "-n", args.activity, check=False)
    total = parse_total_time(launch.stdout)
    if launch.returncode != 0:
        raise SystemExit(launch.stderr.strip() or "am start failed")

    ready = None
    deadline = started + args.ready_timeout
    while time.monotonic() < deadline:
        dump = adb(sdk, port, "shell", "uiautomator", "dump", "--compressed", "/sdcard/pikit-emulator.xml")
        if dump.returncode != 0:
            time.sleep(0.5)
            continue
        xml = adb(sdk, port, "exec-out", "cat", "/sdcard/pikit-emulator.xml")
        if re.search(args.ready_text, xml.stdout):
            ready = time.monotonic() - started
            break
        time.sleep(0.5)

    print(f"first frame: {total} ms" if total is not None else "first frame: unknown")
    if ready is None:
        print(f"ready text not found within {args.ready_timeout:.0f}s", file=sys.stderr)
        return 1
    print(f"ready: {ready:.1f}s (includes uiautomator's own dump latency)")
    print(f"`python tools/emulator.py stop --port {port}` releases this read-only slot")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    def port_argument(target: argparse.ArgumentParser) -> None:
        target.add_argument("--port", type=int, default=None, help="slot port; auto-picks 5554/5556 when omitted")

    def owner_argument(target: argparse.ArgumentParser) -> None:
        target.add_argument("--owner", default=None, help="lease owner; defaults to DSH_SESSION_ID")

    p_start = sub.add_parser("start", help="start a read-only emulator slot")
    p_start.add_argument("--avd", default=avd_default())
    port_argument(p_start)
    owner_argument(p_start)
    p_start.add_argument("--gpu", default="host")
    p_start.add_argument("--timeout", type=float, default=180.0)
    p_start.add_argument("--cold", action="store_true", help="ignore and do not write the quickboot snapshot")
    p_start.add_argument("--headless", action="store_true", help="use -no-window; do not use this for UI/perf checks")
    p_start.add_argument("--visible", action="store_true", help="show the emulator window")
    p_start.set_defaults(func=start)

    p_stop = sub.add_parser("stop", help="stop one emulator slot")
    port_argument(p_stop)
    owner_argument(p_stop)
    p_stop.add_argument("--all", action="store_true", help="stop every slot owned by this session")
    p_stop.add_argument("--force", action="store_true", help="stop despite another session's lease")
    p_stop.set_defaults(func=stop)

    p_status = sub.add_parser("status", help="list emulator slots and unmanaged emulators")
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
