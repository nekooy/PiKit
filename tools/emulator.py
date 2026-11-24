#!/usr/bin/env python3
"""Start a fresh Android emulator instance with the settings this project needs.

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

One AVD, one fresh instance per start. The AVD name is machine state, not
repository state: pass it explicitly, set `pikit.avd` in local.properties, or set
PIKIT_AVD. The AVD itself lives under the ignored `pikit.avdHome` (default
`.tooling/avd`), and this tool exports `ANDROID_AVD_HOME` only to the emulator it
launches — a bare `emulator -avd <name>` cannot find it.

`start` always launches a new instance and gives it the first free console port in
the emulator's own 5554-5584 range. It deliberately does not reuse an emulator that
is already booted: a running guest is probably another session's work, and its lease
belongs to that session. The tool imposes no count of its own: it takes the next free
console port until the emulator's own 16-port range runs out. Each guest is 2048 MB, so
the host's memory is the practical ceiling. Instances are started **read-only**, which
is what lets any number of them share the one AVD: nothing a guest installs survives
its own exit, and `benchmark` installs the APK it measures. Every instance is leased
by the calling session (`--owner`, defaulting to DSH_SESSION_ID); `status` lists all
live instances and any emulator without a lease.

The rule is use-and-close: `stop` stops every instance this session owns, and
`stop --port` stops one. Never stop another session's instance; `--force` is only
for a lease whose owning process is already gone.

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
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLING = ROOT / ".tooling"
PORT_MIN = 5554
PORT_MAX = 5584
PORT_STEP = 2


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
    """Create one instance's lease with O_EXCL, so two `start` calls cannot both win it."""
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


def state_ports() -> list[int]:
    """Every port that has a lease file, whether or not its process is still alive."""
    ports: list[int] = []
    if not TOOLING.is_dir():
        return ports
    for path in TOOLING.glob("emulator-*.state"):
        match = re.fullmatch(r"emulator-(\d+)\.state", path.name)
        if match:
            ports.append(int(match.group(1)))
    return sorted(ports)


def candidate_ports() -> list[int]:
    return list(range(PORT_MIN, PORT_MAX + 1, PORT_STEP))


def port_free(port: int) -> bool:
    """The console port and the ADB port it implies must both be bindable."""
    for candidate in (port, port + 1):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            try:
                sock.bind(("0.0.0.0", candidate))
            except OSError:
                return False
    return True


def instance_alive(sdk: Path, instance: dict) -> bool:
    # pid 0 means `start` has claimed the instance but not spawned the emulator yet.
    starting = not instance.get("pid") and (time.time() - float(instance.get("started") or 0)) < 120
    return starting or process_alive(int(instance.get("pid") or 0)) or booted(sdk, int(instance["port"]))


def live_instances(sdk: Path) -> dict[int, dict]:
    """The instances that are still alive, dropping state left by a dead process."""
    live: dict[int, dict] = {}
    for port in state_ports():
        instance = read_instance(port)
        if not instance:
            clear_instance(port)
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


def wait_boot(sdk: Path, port: int, timeout: float, process: subprocess.Popen[str] | None = None) -> float:
    started = time.monotonic()
    deadline = started + timeout
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError(f"emulator-{port} exited before booting (code {process.returncode})")
        if booted(sdk, port):
            return time.monotonic() - started
        time.sleep(1.0)
    raise TimeoutError(f"emulator-{port} did not report sys.boot_completed=1 within {timeout:.0f}s")


def tail(path: Path, lines: int = 30) -> str:
    if not path.is_file():
        return ""
    return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:])


def new_state(args: argparse.Namespace, port: int, owner: str) -> dict:
    return {
        "avd": args.avd,
        "port": port,
        "owner": owner,
        "pid": 0,
        "started": time.time(),
        "read_only": True,
    }


def start(args: argparse.Namespace) -> int:
    sdk = sdk_root()
    if not emulator_exe(sdk).is_file():
        raise SystemExit(f"emulator not found under {sdk}")
    owner = args.owner or caller()
    live = live_instances(sdk)
    adb_ports = adb_emulator_ports(sdk)

    if args.port is not None:
        port = args.port
        if port % PORT_STEP or not (PORT_MIN <= port <= PORT_MAX):
            raise SystemExit(f"--port must be an even number between {PORT_MIN} and {PORT_MAX}")
        if port in live:
            raise SystemExit(
                f"emulator-{port} is held by {live[port].get('owner', 'another session')}; "
                "choose another --port or stop that instance first."
            )
        if port in adb_ports or not port_free(port):
            raise SystemExit(f"port {port} is already in use on this host; choose another --port")
        state = new_state(args, port, owner)
        if not claim_instance(state):
            holder = read_instance(port).get("owner", "another session")
            raise SystemExit(f"emulator-{port} was just taken by {holder}")
    else:
        port = 0
        for candidate in candidate_ports():
            if candidate in live or candidate in adb_ports or not port_free(candidate):
                continue
            state = new_state(args, candidate, owner)
            if claim_instance(state):
                port = candidate
                break
        if not port:
            raise SystemExit(
                f"no free emulator port in {PORT_MIN}-{PORT_MAX}; stop an instance with "
                "`python tools/emulator.py status` then `stop --port <port>`"
            )

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
        elapsed = wait_boot(sdk, port, args.timeout, process)
    except (TimeoutError, OSError, RuntimeError) as error:
        print(f"{error}\n--- {log} ---\n{tail(log)}", file=sys.stderr)
        if process.poll() is None:
            process.terminate()
        clear_instance(port)
        return 1
    print(f"booted emulator-{port} in {elapsed:.1f}s (owner: {owner}, read-only)")
    print(f"log: {log.relative_to(ROOT)}")
    print(f"stop it when done: python tools/emulator.py stop --port {port}")
    return 0


def stop(args: argparse.Namespace) -> int:
    sdk = sdk_root()
    owner = args.owner or caller()
    live = live_instances(sdk)
    if args.port is not None:
        targets = [args.port]
    else:
        if args.all and args.force:
            targets = sorted(live)
        else:
            targets = sorted(port for port, instance in live.items() if instance.get("owner") == owner)
        if not targets:
            raise SystemExit("no emulator instance belongs to this session; `status` lists them")

    for port in targets:
        instance = read_instance(port)
        if not instance and not args.force:
            raise SystemExit(
                f"emulator-{port} has no lease in this checkout; it may belong to another session "
                "or to a bare emulator. Refusing to stop it without --force."
            )
        if instance and instance.get("owner") != owner and not args.force:
            raise SystemExit(
                f"emulator-{port} is held by {instance.get('owner')}; "
                "pass --force only after checking that session is gone."
            )
        # `emu kill` is a normal exit; read-only instances leave the base AVD unchanged.
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
        print("no leased emulator instances")
    for port, instance in sorted(live.items()):
        print(
            f"emulator-{port}: avd={instance.get('avd', '?')} owner={instance.get('owner', '?')} "
            f"pid={instance.get('pid', 0)} booted={str(booted(sdk, port)).lower()} "
            f"read_only={str(bool(instance.get('read_only'))).lower()}"
        )
    for port in sorted(unmanaged_ports(sdk, live)):
        print(f"unmanaged: emulator-{port} is booted without a lease (another session or a bare emulator)")
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
            raise SystemExit("no emulator instance belongs to this session; run `start` first")
        port = max(mine, key=lambda candidate: float(mine[candidate].get("started") or 0))
    else:
        instance = read_instance(port)
        if not instance:
            raise SystemExit(f"emulator-{port} has no lease in this checkout; start your own with `start`")
        if instance.get("owner") != owner:
            raise SystemExit(
                f"emulator-{port} is held by {instance.get('owner')}; "
                "benchmark in that session or choose another instance."
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
    print(f"`python tools/emulator.py stop --port {port}` stops this instance when you are done")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    def port_argument(target: argparse.ArgumentParser) -> None:
        target.add_argument(
            "--port",
            type=int,
            default=None,
            help="console port; defaults to the first free port from 5554",
        )

    def owner_argument(target: argparse.ArgumentParser) -> None:
        target.add_argument("--owner", default=None, help="lease owner; defaults to DSH_SESSION_ID")

    p_start = sub.add_parser("start", help="start a fresh read-only emulator instance")
    p_start.add_argument("--avd", default=avd_default())
    port_argument(p_start)
    owner_argument(p_start)
    p_start.add_argument("--gpu", default="host")
    p_start.add_argument("--timeout", type=float, default=180.0)
    p_start.add_argument("--cold", action="store_true", help="ignore and do not write the quickboot snapshot")
    p_start.add_argument("--headless", action="store_true", help="use -no-window; do not use this for UI/perf checks")
    p_start.add_argument("--visible", action="store_true", help="show the emulator window")
    p_start.set_defaults(func=start)

    p_stop = sub.add_parser("stop", help="stop this session's emulator instance(s)")
    port_argument(p_stop)
    owner_argument(p_stop)
    p_stop.add_argument(
        "--all",
        action="store_true",
        help="stop every instance owned by this session (the default); with --force, every live instance",
    )
    p_stop.add_argument("--force", action="store_true", help="stop despite another session's lease")
    p_stop.set_defaults(func=stop)

    p_status = sub.add_parser("status", help="list leased instances and unmanaged emulators")
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
