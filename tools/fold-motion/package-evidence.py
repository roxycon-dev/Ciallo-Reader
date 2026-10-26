"""Record the local release APK and its actual production source inputs."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile
import re
import argparse

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "artifacts/fold-motion"
BUILD = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
VERSION = re.search(r'versionName\s*=\s*"([^"]+)"', BUILD).group(1)
VERSION_CODE = int(re.search(r'versionCode\s*=\s*(\d+)', BUILD).group(1))
APK = OUT / f"Ciallo-Reader-v{VERSION}.apk"
SDK = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"
BUILD_TOOLS = SDK / "build-tools/36.1.0"


def run(*args):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, capture_output=True, text=True, check=True)
    return result.stdout


def source_snapshot():
    roots = [ROOT / module / "src" / source for module in ("app", "backdrop", "liquidglass-core", "liquidglass-compose")
             for source in ("main", "commonMain", "androidMain", "release")]
    files = {p for path in roots if path.is_dir() for p in path.rglob("*") if p.is_file() and not any(part.startswith(".") for part in p.relative_to(path).parts)}
    for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
                 "app/build.gradle.kts", "app/proguard-rules.pro", "backdrop/build.gradle.kts", "liquidglass-core/build.gradle.kts", "liquidglass-compose/build.gradle.kts"):
        if (ROOT / name).is_file():
            files.add(ROOT / name)
    return {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", action="store_true")
    parser.add_argument("--output-dir", type=Path, default=OUT)
    parser.add_argument("--apk", type=Path, help="Local validation APK; does not modify a published release")
    args = parser.parse_args()
    OUT = args.output_dir.resolve()
    OUT.mkdir(parents=True, exist_ok=True)
    APK = args.apk.resolve() if args.apk else OUT / f"Ciallo-Reader-v{VERSION}.apk"
    if args.snapshot:
        snapshot = source_snapshot()
        (OUT / "production-snapshot.json").write_text(json.dumps(snapshot, indent=2))
        print(f"Frozen {len(snapshot)} release inputs (excluding Debug, tests and editor metadata)")
    else:
        snapshot = json.loads((OUT / "production-snapshot.json").read_text())
        assert source_snapshot() == snapshot, "Production inputs changed since snapshot"
        with zipfile.ZipFile(APK) as archive:
            assert archive.testzip() is None
            entries = archive.namelist()
            abis = sorted({name.split("/")[1] for name in entries if name.startswith("lib/")})
            assert abis == ["arm64-v8a"]
            for name in entries:
                if name == "AndroidManifest.xml" or (name.startswith("classes") and name.endswith(".dex")):
                    payload = archive.read(name)
                    for probe in ("MotionResourceProbeActivity", "ShadowKernelProbeActivity"):
                        assert probe.encode("utf-8") not in payload
                        assert probe.encode("utf-16-le") not in payload
        signer = run(BUILD_TOOLS / "apksigner.bat", "verify", "--verbose", "--print-certs", APK)
        expected = (ROOT / "signing/release-cert.sha256").read_text().strip().lower()
        assert expected in signer.lower()
        (OUT / "final-signature.txt").write_text(signer)
        run(BUILD_TOOLS / "zipalign.exe", "-c", "-P", "16", "4", APK)
        badging = run(BUILD_TOOLS / "aapt.exe", "dump", "badging", APK)
        assert "name='com.aistudio.novelreader.kxmpzq'" in badging
        assert f"versionCode='{VERSION_CODE}'" in badging and f"versionName='{VERSION}'" in badging
        assert "application-debuggable" not in badging
        (OUT / "final-badging.txt").write_text(badging)
        identity = {"apk": APK.name, "bytes": APK.stat().st_size, "sha256": hashlib.sha256(APK.read_bytes()).hexdigest(),
                    "certificateSha256": expected, "abis": abis, "sourceInputs": len(snapshot), "sourceSnapshotMatches": True,
                    "versionName": VERSION, "versionCode": VERSION_CODE, "package": "com.aistudio.novelreader.kxmpzq",
                    "zipCrcVerified": True, "zipAlignment16KiBVerified": True, "debuggable": False,
                    "resourceProbeAbsent": True}
        (OUT / "release-identity.json").write_text(json.dumps(identity, indent=2))
        print(json.dumps(identity, indent=2))
