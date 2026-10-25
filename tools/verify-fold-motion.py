"""Reproducible, local-only verification against frozen source and APK baselines.

prepare creates comparison fixtures under ignored artifacts/, not production sources.
device runs ten alternating APK pairs on an explicitly selected emulator.
"""
from pathlib import Path
import argparse
import json
import os
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "artifacts/fold-motion"
SDK = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"
PACKAGE = "com.aistudio.novelreader.kxmpzq.debug"


def prepare(baseline_ref=None):
    frozen = OUT / "baseline-source/app/src/main/java"
    OUT.mkdir(parents=True, exist_ok=True)
    if baseline_ref:
        revision = subprocess.run(["git", "rev-parse", "--verify", "--end-of-options", baseline_ref + "^{commit}"],
                                  cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
        existing = OUT / 'baseline-head.txt'
        if existing.exists() and existing.read_text(encoding='utf-8-sig').strip() != revision:
            raise RuntimeError('Existing frozen baseline differs from --baseline-ref; preserve it before preparing another baseline.')
        for name in ['com/swapnil/squishyswitch/presentation/SquishyToggle.kt',
                     'com/example/ui/components/LiquidSlider.kt', 'com/example/ui/components/AppButton.kt',
                     'com/example/ui/components/PullOutMotion.kt']:
            source = subprocess.run(['git', 'show', revision + ':app/src/main/java/' + name],
                                    cwd=ROOT, capture_output=True, check=True).stdout
            destination = frozen / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(source)
        existing.write_text(revision + '\n', encoding='utf-8')
    generated = OUT / "parity-src"
    generated.mkdir(parents=True, exist_ok=True)
    switch = (frozen / "com/swapnil/squishyswitch/presentation/SquishyToggle.kt").read_text(encoding="utf-8")
    (generated / "BaselineSwitch.kt").write_text(switch.replace("SquishyToggleSwitch", "BaselineSquishyToggleSwitch"), encoding="utf-8")
    slider = (frozen / "com/example/ui/components/LiquidSlider.kt").read_text(encoding="utf-8")
    imports = slider[:slider.index("/** One physical")]
    body = slider[slider.index("internal class LiquidSliderView"):]
    (generated / "BaselineSlider.kt").write_text(imports + body.replace("LiquidSliderView", "BaselineLiquidSliderView"), encoding="utf-8")
    button = (frozen / "com/example/ui/components/AppButton.kt").read_text(encoding="utf-8")
    imports = button[:button.index("/**")]
    body = button[button.index("@Composable\nfun AppActionButton"):]
    (generated / "BaselineButton.kt").write_text(imports + body.replace("AppActionButton", "BaselineAppActionButton").replace("primaryGlassStyle", "baselinePrimaryGlassStyle"), encoding="utf-8")
    paper = (frozen / "com/example/ui/components/PullOutMotion.kt").read_text(encoding="utf-8")
    start = paper.index('internal class PaperSpring {')
    body = paper[start:paper.index('\n}\n', start) + 3]
    (generated / 'BaselinePaperSpring.kt').write_text('package com.example.ui.components\nimport kotlin.math.*\n' + body.replace('PaperSpring', 'BaselinePaperSpring'), encoding='utf-8')
    init = r'''
gradle.projectsEvaluated {
    def app = rootProject.project(':app')
    app.android.sourceSets.test.java.srcDir(rootProject.file('artifacts/fold-motion/parity-src'))
    app.android.sourceSets.test.java.srcDir(rootProject.file('tools/fold-motion/parity'))
    app.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        filter { includeTestsMatching 'com.example.data.AdultSourceDefaultsTest'; includeTestsMatching 'com.example.ui.FoldMotion*'; includeTestsMatching 'com.example.ui.ChapterPageBufferTest'; includeTestsMatching 'com.example.ui.ReaderPaginationCacheTest'; includeTestsMatching 'com.example.ui.components.PaperAndSliderTest'; includeTestsMatching 'com.example.ui.components.LiquidSegment*'; includeTestsMatching 'com.example.ui.components.TabBarMotionTest'; includeTestsMatching 'com.example.ui.MotionPolishTest'; includeTestsMatching 'com.example.ui.ThemeMotionPolishTest'; includeTestsMatching 'me.trishiraj.shadowglow.Shadow*Test' }
        maxParallelForks = 1
        filter {
            includeTestsMatching 'com.example.ui.components.LiquidGlassShaderCompileTest'
            includeTestsMatching 'com.example.ui.ReaderTypography125Test'
            includeTestsMatching 'com.example.ui.ReaderGlassPolishTest'
            includeTestsMatching 'com.example.ui.ReaderProgressEffectTest'
            includeTestsMatching 'com.example.ui.comic.ComicChapterNavigationTest'
        }
        maxHeapSize = '2048m'
        jvmArgs('--add-exports=java.base/sun.nio.ch=ALL-UNNAMED', '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED')
        testLogging { exceptionFormat = 'full' }
    }
}
'''
    (OUT / "tests.gradle").write_text(init, encoding="utf-8")


def run_device(serial, pairs, scenario):
    adb = str(SDK / "platform-tools/adb.exe")

    def call(*args, check=True):
        result = subprocess.run([adb, "-s", serial, *map(str, args)], capture_output=True, text=True, errors="replace", check=check)
        return result.stdout

    results = []
    for iteration in range(pairs):
        # Alternate pair order to balance host/thermal/time trends.
        order = ["baseline", "optimized"] if iteration % 2 == 0 else ["optimized", "baseline"]
        for variant in order:
            apk = OUT / (variant + ".apk")
            call("install", "-r", "-t", apk)
            call("shell", "am", "force-stop", PACKAGE)
            call("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
            output = call("shell", "am", "instrument", "-w", "-r", "-e", "class",
                          "com.example.ui.FoldMotionDeviceTest#measureDenseControls", "-e", "variant", variant,
                          "-e", "iteration", iteration,
                          "-e", "scenario", scenario,
                          PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
            (OUT / f"device-{variant}-{iteration:02d}.log").write_text(output, encoding="utf-8")
            if "FAILURES" in output or "OK (1 test)" not in output:
                raise RuntimeError(output[-5000:])
            remote = f"/sdcard/Android/data/{PACKAGE}/files/fold-motion/{variant}-{iteration}.json"
            local = OUT / f"device-{variant}-{iteration:02d}.json"
            call("pull", remote, local)
            result = json.loads(local.read_text())
            results.append(result)
            print(json.dumps({k: v for k, v in result.items() if k != 'rawFrames'}, ensure_ascii=False), flush=True)
            (OUT / f"gfx-{variant}-{iteration:02d}.txt").write_text(call("shell", "dumpsys", "gfxinfo", PACKAGE), encoding="utf-8")
    summary = {"pairs": pairs, "scenario": scenario, "environment": "hardware-accelerated x86_64 emulator; relative evidence only", "runs": results}
    for variant in ["baseline", "optimized"]:
        group = [r for r in results if r["variant"] == variant]
        summary[variant] = {name: statistics.median(r[name] for r in group) for name in ["p50Ms", "p95Ms", "drawP95Ms", "gpuP95Ms", "deadlineRatio", "allocatedBytes", "coldElapsedMs", "coldP95Ms"]}
    (OUT / "device-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["prepare", "device"])
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--pairs", type=int, default=10)
    parser.add_argument("--scenario", choices=['mixed', 'primary'], default='mixed')
    parser.add_argument("--baseline-ref", help="Commit used to reconstruct comparison fixtures in a fresh checkout")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.baseline_ref)
    else:
        run_device(args.serial, args.pairs, args.scenario)
