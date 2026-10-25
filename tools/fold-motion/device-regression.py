"""Run final HWUI regressions and frozen, release-independent visual comparisons."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "artifacts/fold-motion"
ADB = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
PACKAGE = "com.aistudio.novelreader.kxmpzq.debug"


def call(*args, timeout=180):
    r = subprocess.run([str(ADB), "-s", "emulator-5580", *map(str, args)], capture_output=True,
                       text=True, encoding="utf-8", errors="replace", timeout=timeout, check=True)
    return r.stdout


def instrument(targets, name, *extra):
    result = call("shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(targets),
                  *extra, PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
    (OUT / name).write_text(result, encoding="utf-8")
    assert f"OK ({len(targets)} test" in result and "FAILURES" not in result, result[-6000:]


if __name__ == "__main__":
    assert "codex_fold_motion_20261025" in call("emu", "avd", "name")
    original = OUT / "baseline.apk"
    final = OUT / "optimized.apk"
    test = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    call("install", "-r", "-t", "-d", final)
    call("install", "-r", "-t", test)
    old = json.loads((OUT / "hardware-results.json").read_text())
    targets = list(dict.fromkeys(old["targets"] + [
        "com.example.ui.components.SliderLayerParityDeviceTest#identityLayerPreservesNativeSliderPixelsThroughRealTouchFrames",
    ]))
    instrument(targets, f"delivery-hardware-{len(targets)}.log")
    (OUT / "delivery-hardware-results.json").write_text(json.dumps({
        "tests": len(targets), "targets": targets, "passed": True,
        "apkSha256": hashlib.sha256(final.read_bytes()).hexdigest(),
        "knownBaselineFailure": old["known_baseline_failure"],
    }, indent=2))
    print(f"HWUI: {len(targets)} passed", flush=True)

    # Fix only SystemUI decoration on the owned AVD, never product pixels.
    call("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    for variant, apk in [("baseline", original), ("optimized", final)]:
        call("install", "-r", "-t", "-d", apk)
        call("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "--es", "command", "enter")
        call("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "--es", "command", "clock", "--es", "hhmm", "0800")
        instrument(["com.example.ui.FoldMotionDeviceTest#measureDenseControls"],
                   f"delivery-visual-{variant}.log", "-e", "variant", variant, "-e", "iteration", "900", "-e", "scenario", "mixed")
        remote = f"/sdcard/Android/data/{PACKAGE}/files/fold-motion/{variant}-900-before.png"
        call("pull", remote, OUT / f"final-{variant}-mixed-controls.png")
    call("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "--es", "command", "exit")
    from PIL import Image
    import numpy as np
    a = np.asarray(Image.open(OUT / "final-baseline-mixed-controls.png"))
    b = np.asarray(Image.open(OUT / "final-optimized-mixed-controls.png"))
    assert a.shape == b.shape
    changed = int(np.any(a != b, axis=2).sum())
    evidence = {"width": a.shape[1], "height": a.shape[0], "changedPixels": changed,
                "baselineApkSha256": hashlib.sha256(original.read_bytes()).hexdigest(),
                "finalApkSha256": hashlib.sha256(final.read_bytes()).hexdigest()}
    (OUT / "delivery-visual-parity.json").write_text(json.dumps(evidence, indent=2))
    print(json.dumps(evidence), flush=True)
    assert changed == 0, "Inspect the saved originals; never update a baseline to conceal differences"
