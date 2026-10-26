"""Actual Android lifecycle/window checks in a verified read-only existing AVD."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import psutil

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "artifacts/performance-stability-2026-10-26"
ADB = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5582"
PACKAGE = "com.aistudio.novelreader.kxmpzq.debug"


def call(*args, timeout=300):
    r = subprocess.run([str(ADB), "-s", SERIAL, *map(str, args)], capture_output=True,
                       text=True, encoding="utf-8", errors="replace", timeout=timeout, check=True)
    return r.stdout


def main():
    assert call("emu", "avd", "name").splitlines()[0] == "codex_paper_1413"
    assert any("qemu-system" in (p.info["name"] or "").lower()
               and "codex_paper_1413" in (p.info["cmdline"] or [])
               and "-read-only" in (p.info["cmdline"] or [])
               for p in psutil.process_iter(["name", "cmdline"]))
    app = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    test = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    call("install", "-r", "-t", "-d", app)
    call("install", "-r", "-t", test)
    targets = ["com.example.ui.PaperPolishDeviceTest#" + method for method in (
        "denseGlassSurvivesFastFlicksRapidTapsAndRepeatedWindows",
        "interruptingAndReversingNeverStrandsALiveSurface",
        "livePaperUsesOneShaderForOpeningClosingAndRealSourceSheet",
        "chooserHandsOffToPageWithoutRetractingTheCallerOrItsTabs",
        "realManagementUsesItsOwnGlassBackgroundAndDeduplicatesNavigation",
        "cacheEntryAndDetailsUseTheirActualButtonsAndOnePaperClock",
        "novelFooterTextIsNotClippedByTheSheetOrItsButton",
        "commonActionButtonsKeepLargeTextWholeInBothRenderingModes",
        "hiddenBackdropCannotPaintWhiteIntoTheWindow",
    )]
    targets += [
        "com.example.ui.FoldMotionDeviceTest#dynamicBackdropAndResizingKeepRealGlassLive",
        "com.example.ui.TouchZoneInteractionDeviceTest#ordinaryButtonClicksNeverCaptureTexturesOrAllocatePaperSnapshots",
        "com.example.ui.TouchZoneInteractionDeviceTest#rangeAndDiscreteLensesRemainUsableWithARealAndroidCanvas",
    ]
    targets += [
        "com.example.ui.components.SliderLayerParityDeviceTest#identityLayerPreservesNativeSliderPixelsThroughRealTouchFrames",
        "com.example.ui.comic.ComicRegionDeviceTest",
        "com.example.ui.LiquidSliderLifecycleDeviceTest",
        "com.example.ui.PreBlurredSourceLifecycleDeviceTest",
        "com.example.ui.WindowResizeStabilityDeviceTest",
    ]
    call("shell", "wm", "size", "1768x2208")
    call("shell", "wm", "density", "420")
    call("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    call("shell", "wm", "dismiss-keyguard")
    try:
        output = call("shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(targets),
                      PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=600)
        (OUT / "android-regression.log").write_text(output, encoding="utf-8")
        completed = re.search(r"OK \((\d+) tests?\)", output)
        passed = completed is not None and int(completed[1]) == 19 and "FAILURES" not in output and "INSTRUMENTATION_FAILED" not in output
        evidence = {"passed": passed, "tests": int(completed[1]) if completed else None, "targets": targets,
                    "serial": SERIAL, "avd": "codex_paper_1413", "readOnlyVerified": True,
                    "windowAndDensitySimulationOnly": True, "samsungPhysicalDevice": False,
                    "appSha256": hashlib.sha256(app.read_bytes()).hexdigest(),
                    "testSha256": hashlib.sha256(test.read_bytes()).hexdigest()}
        (OUT / "android-regression.json").write_text(json.dumps(evidence, indent=2))
        print(json.dumps(evidence), flush=True)
        if not passed:
            raise RuntimeError(output[-7000:])
        call("pull", f"/sdcard/Android/data/{PACKAGE}/files/motion-round2/window-density.json", OUT / "window-density.json")
    finally:
        call("shell", "wm", "size", "reset")
        call("shell", "wm", "density", "reset")


if __name__ == "__main__":
    main()
