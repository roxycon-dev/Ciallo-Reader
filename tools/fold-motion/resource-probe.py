"""Paired CPU/GPU measurements without an instrumentation test in the timed app.

Both APKs must contain the identical debug-only MotionResourceProbeActivity.
Traces are separate diagnostic runs and never enter the performance summary.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import time
import random
import psutil

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "artifacts/fold-motion/resource-probe"
PACKAGE = "com.aistudio.novelreader.kxmpzq.debug"
ACTIVITY = PACKAGE + "/com.example.ui.perf.MotionResourceProbeActivity"
METRICS = ("processCpuMs", "mainCpuMs", "renderCpuMs", "allocatedBytes", "pssKb", "p50Ms", "p95Ms", "gpuP95Ms", "gpuTotalMs", "deadlineRatio", "frames", "rootRecords")


def main():
    global OUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--pairs", type=int, default=10)
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument("--trace", action="store_true")
    parser.add_argument("--build-mode", choices=("release", "debug"), default="release")
    parser.add_argument("--quality", choices=("HIGH", "MAX", "LOW"), default="HIGH")
    parser.add_argument("--scene", choices=("mixed", "primary", "scroll"), default="mixed")
    parser.add_argument("--baseline-apk", type=Path)
    parser.add_argument("--optimized-apk", type=Path)
    parser.add_argument("--comparison", default="original-vs-optimized")
    parser.add_argument("--output-dir", type=Path, help="Separate evidence directory; never overwrite another exercise")
    parser.add_argument("--expected-avd", help="Check the AVD name before installing test packages")
    parser.add_argument("--require-read-only", action="store_true", help="Require a matching emulator process launched with -read-only")
    parser.add_argument("--restart-every", type=int, default=0,
                        help="Restart only the task-owned AVD every N pairs, including before the first pair")
    parser.add_argument("--reuse-first-boot", action="store_true",
                        help="Use an already booted and settled owned AVD for the first batch")
    parser.add_argument("--disable-vulkan", action="store_true",
                        help="Keep host GLES acceleration while disabling the emulator Vulkan feature")
    parser.add_argument("--resume", action="store_true",
                        help="Reuse only complete validated pairs with matching APK hashes and scene")
    args = parser.parse_args()
    if args.output_dir:
        OUT = args.output_dir.resolve()
    OUT = OUT / args.build_mode / args.quality.lower() / args.scene
    OUT.mkdir(parents=True, exist_ok=True)
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"

    def command(*tail, timeout=90, **kwargs):
        result = subprocess.run([str(adb), "-s", args.serial, *tail], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout, **kwargs)
        if result.returncode:
            raise RuntimeError(f"adb {tail}: {result.stdout}\n{result.stderr}")
        return result.stdout.strip()

    avd = command("emu", "avd", "name").splitlines()[0]
    if args.expected_avd and avd != args.expected_avd:
        raise RuntimeError(f"Unexpected AVD {avd!r}; expected {args.expected_avd!r}")
    if args.require_read_only:
        if args.restart_every:
            raise RuntimeError("Read-only shared AVDs cannot use automatic restart")
        matches = [p for p in psutil.process_iter(["name", "cmdline"])
                   if "qemu-system" in (p.info["name"] or "").lower()
                   and avd in (p.info["cmdline"] or [])
                   and "-read-only" in (p.info["cmdline"] or [])]
        if not matches:
            raise RuntimeError("No matching read-only emulator process; refusing to touch the shared AVD")

    rows = []
    def restart_owned_emulator(pair):
        if args.serial != "emulator-5580":
            raise RuntimeError("Automatic restart is restricted to the task-owned emulator-5580")
        existing = subprocess.run([str(adb), "-s", args.serial, "emu", "avd", "name"], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10)
        owned = [p for p in psutil.process_iter(["name", "cmdline"])
                 if "qemu-system" in (p.info["name"] or "").lower()
                 and "codex_fold_motion_20261025" in (p.info["cmdline"] or [])]
        if existing.returncode == 0:
            if "codex_fold_motion_20261025" not in existing.stdout:
                raise RuntimeError("Refusing to restart a different AVD")
            command("emu", "kill")
            _, alive = psutil.wait_procs(owned, timeout=30)
            if alive:
                raise RuntimeError("Previous task-owned emulator has not finished shutdown")
        elif owned:
            raise RuntimeError("Owned emulator process exists but its console is unavailable")
        print("restart task-owned AVD for batch " + str(pair), flush=True)
        launcher = adb.parents[1] / "emulator/emulator.exe"
        log = (OUT / f"emulator-batch-{pair}.log").open("w")
        subprocess.Popen([str(launcher), "-avd", "codex_fold_motion_20261025", "-no-window", "-no-audio",
                          "-no-boot-anim", "-no-snapshot", "-gpu", "host", "-memory", "4096", "-cores", "4", "-port", "5580"]
                         + (["-feature", "-Vulkan"] if args.disable_vulkan else []),
                         stdout=log, stderr=log, creationflags=subprocess.CREATE_NO_WINDOW)
        log.close()
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            boot = subprocess.run([str(adb), "-s", args.serial, "shell", "getprop", "sys.boot_completed"], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10)
            if boot.returncode == 0 and boot.stdout.strip() == "1":
                if "codex_fold_motion_20261025" not in command("emu", "avd", "name"):
                    raise RuntimeError("Unexpected AVD after boot")
                time.sleep(15)
                return
            time.sleep(1)
        raise RuntimeError("Task-owned AVD did not finish booting")
    suffix = "-release" if args.build_mode == "release" else ""
    apks = {name: ROOT / f"artifacts/fold-motion/resource-{name}{suffix}.apk" for name in ("baseline", "optimized")}
    if args.baseline_apk: apks["baseline"] = args.baseline_apk.resolve()
    if args.optimized_apk: apks["optimized"] = args.optimized_apk.resolve()
    identity = {name: {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size} for name, path in apks.items()}
    if args.build_mode == "release":
        aapt = adb.parents[1] / "build-tools/36.1.0/aapt.exe"
        for path in apks.values():
            badging = subprocess.run([str(aapt), "dump", "badging", str(path)], capture_output=True, text=True, encoding="utf-8", errors="replace", check=True).stdout
            if "application-debuggable" in badging or f"name='{PACKAGE}'" not in badging:
                raise RuntimeError("Expected non-debuggable isolated probe APK: " + str(path))
    for pair in range(args.offset, args.offset + (1 if args.trace else args.pairs)):
        if args.resume and not args.trace:
            retained = []
            for variant in ("baseline", "optimized"):
                path = OUT / f"{variant}-{pair}.json"
                if not path.exists(): break
                saved = json.loads(path.read_text(encoding="utf-8"))
                if (saved.get("apk") != identity[variant] or saved.get("probeRevision") != 3 or
                    saved.get("variant") != variant or saved.get("iteration") != pair or
                    saved.get("quality") != args.quality or saved.get("scene") != args.scene or
                    "Success" not in saved.get("aot", "") or saved.get("actions") != 12 or
                    saved["coldUiSequence"]["actions"] != 12 or saved["frames"] < 30):
                    raise RuntimeError("Existing pair does not match this exercise: " + str(path))
                retained.append(saved)
            if len(retained) == 2:
                rows.extend(retained)
                print(f"reuse validated pair {pair + 1}", flush=True)
                continue
        if args.restart_every and (pair - args.offset) % args.restart_every == 0 and not (args.reuse_first_boot and pair == args.offset):
            restart_owned_emulator(pair)
        order = ("baseline", "optimized") if pair % 2 == 0 else ("optimized", "baseline")
        for variant in order:
            print(f"pair {pair + 1}: {variant}" + (" (trace)" if args.trace else ""), flush=True)
            command("shell", "am", "force-stop", PACKAGE)
            command("install", "-r", "-t", "-d", str(apks[variant]))
            compiled = command("shell", "cmd", "package", "compile", "-m", "speed", "-f", PACKAGE, timeout=180)
            if "Success" not in compiled:
                raise RuntimeError("AOT compilation failed: " + compiled)
            external = f"/sdcard/Android/data/{PACKAGE}/files/motion-resource/{variant}-{pair}.json"
            command("shell", "rm", "-f", external)
            trace_remote = f"/data/misc/perfetto-traces/resource-{variant}.perfetto-trace"
            server_pid = command("shell", "pidof", "system_server")
            launch = command("shell", "am", "start", "-W", "-n", ACTIVITY, "--es", "variant", variant, "--ei", "iteration", str(pair), "--es", "quality", args.quality, "--es", "scene", args.scene)
            if "Error" in launch or "Exception" in launch:
                raise RuntimeError(launch)
            if args.trace:
                # Start after the first window, during the fixed warm-up interval.
                config = (ROOT / "tools/fold-motion/trace.pbtxt").read_text().replace("duration_ms: 10000", "duration_ms: 20000")
                (OUT / "trace.pbtxt").write_text(config)
                command("shell", "perfetto", "--background-wait", "--txt", "-c", "-", "-o", trace_remote, input=config)
            deadline = time.monotonic() + 45
            row = None
            while time.monotonic() < deadline:
                result = subprocess.run([str(adb), "-s", args.serial, "shell", "cat", external], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10)
                if result.returncode == 0:
                    row = json.loads(result.stdout)
                    break
                time.sleep(.5)
            if row is None:
                raise RuntimeError("No resource result: " + command("logcat", "-d", "-t", "80"))
            stable_server = server_pid == command("shell", "pidof", "system_server")
            if (not stable_server or row.get("quality") != args.quality or row.get("probeRevision") != 3 or row.get("scene") != args.scene or
                row["actions"] != 12 or row["coldUiSequence"]["actions"] != 12 or row["frames"] < (1 if args.trace else 30) or
                row["mainCpuMs"] <= 0 or row["renderCpuMs"] <= 0 or row["gpuP95Ms"] <= 0):
                (OUT / f"rejected-{variant}-{pair}.json").write_text(json.dumps(row, indent=2))
                raise RuntimeError("Invalid exercise or metrics: " + json.dumps(row))
            row["aot"] = compiled
            row["apk"] = identity[variant]
            prefix = "trace-" if args.trace else ""
            (OUT / f"{prefix}{variant}-{pair}.json").write_text(json.dumps(row, indent=2))
            rows.append(row)
            print({key: round(row[key], 3) for key in ("mainCpuMs", "renderCpuMs", "gpuP95Ms", "p95Ms", "allocatedBytes")}, flush=True)
            if args.trace:
                time.sleep(22)  # outside timed work; allow trace flush before pulling
                command("pull", trace_remote, str(OUT / f"{variant}.perfetto-trace"))
    command("shell", "am", "force-stop", PACKAGE)
    if not args.trace:
        summary = {"serial": args.serial, "pairs": args.pairs, "apk": identity, "buildMode": args.build_mode, "quality": args.quality, "scene": args.scene, "comparison": args.comparison,
                   "avd": avd, "readOnlyAvdVerified": args.require_read_only,
                   "restartEveryPairs": args.restart_every,
                   "bootSettleSeconds": 15,
                   "reusedFirstBoot": args.reuse_first_boot,
                   "emulatorVulkanDisabled": args.disable_vulkan,
                   "resumed": args.resume,
                   "sdk": command("shell", "getprop", "ro.build.version.sdk"),
                   "display": command("shell", "wm", "size"), "density": command("shell", "wm", "density"),
                   "probeSourceSha256": hashlib.sha256((ROOT / "app/src/debug/java/com/example/ui/perf/MotionResourceProbeActivity.kt").read_bytes()).hexdigest(),
                   "cpuCores": command("shell", "cat", "/sys/devices/system/cpu/online"),
                   "method": "identical test activity, AOT speed, warm controls, 4.3s measured real-input sequence; frame listener on separate thread; alternating order"}
        for key in METRICS:
            old = statistics.median(row[key] for row in rows if row["variant"] == "baseline")
            new = statistics.median(row[key] for row in rows if row["variant"] == "optimized")
            summary[key] = {"baselineMedian": old, "optimizedMedian": new, "ratio": new / old if old else None}
            paired = []
            for pair in range(args.offset, args.offset + args.pairs):
                values = {row["variant"]: row[key] for row in rows if row["iteration"] == pair}
                if values["baseline"]:
                    paired.append((values["optimized"] / values["baseline"] - 1) * 100)
            if paired:
                rng = random.Random(42)
                bootstraps = sorted(statistics.median(rng.choices(paired, k=len(paired))) for _ in range(2000))
                summary[key].update({"pairedPercentChanges": paired,
                                     "pairedMedianPercentChange": statistics.median(paired),
                                     "pairedMedianBootstrap95Percent": [bootstraps[49], bootstraps[1949]]})
        summary["coldUiSequence"] = {}
        for key in ("mainCpuMs", "processCpuMs", "elapsedMs", "allocatedBytes", "p95Ms", "frames", "rootRecords"):
            old = statistics.median(row["coldUiSequence"][key] for row in rows if row["variant"] == "baseline")
            new = statistics.median(row["coldUiSequence"][key] for row in rows if row["variant"] == "optimized")
            summary["coldUiSequence"][key] = {"baselineMedian": old, "optimizedMedian": new, "ratio": new / old if old else None}
        (OUT / f"summary-{args.offset}-{args.pairs}.json").write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary, indent=2), flush=True)


if __name__ == "__main__":
    main()
