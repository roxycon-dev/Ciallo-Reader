"""Verify and archive the local candidate. Never pushes or publishes."""
from pathlib import Path
import hashlib
import json
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
out = root / 'artifacts/release-1.3.7'
out.mkdir(parents=True, exist_ok=True)
assert 'BUILD SUCCESSFUL' in (root / 'artifacts/discover-final-repair/final-build-and-tests.log').read_text(encoding='utf-8')
snapshot = json.loads((root / 'artifacts/discover-final-repair/source-snapshot-final.json').read_text(encoding='utf-8'))
changed = [p for p, value in snapshot.items()
           if hashlib.sha256((root / p).read_bytes()).hexdigest() != value]
# Only the device fixture path/report hash changed after the Release build.
# Its final test APK was rebuilt and executed; all production inputs stay exact.
assert changed == ['app/src/androidTest/java/com/example/source/js/MyComicVerificationDeviceTest.kt'], changed
chain = json.loads((root / 'artifacts/discover-final-repair/mycomic-verification-chain.json').read_text(encoding='utf-8'))
assert chain['source'] == 'mycomic' and chain['complete'] and chain['stage'] == 'passed'
assert chain['verificationPassed'] and chain['catalogueCount'] > 0 and chain['chapters'] > 0 and chain['pages'] > 1
assert len(chain['decodedImages']) == 2 and all(i['width'] > 0 and i['height'] > 0 for i in chain['decodedImages'])
assert chain['scriptSha256'] == hashlib.sha256((root / 'artifacts/discover-live/patched/mycomic.js').read_bytes()).hexdigest()
assert 'OK (1 test)' in (root / 'artifacts/discover-final-repair/mycomic-device-run-2.log').read_text(encoding='utf-8')
assert 'BUILD SUCCESSFUL' in (root / 'artifacts/discover-final-repair/mycomic-device-build-2.log').read_text(encoding='utf-8')
tests = [ET.parse(p).getroot().attrib for p in (root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
assert sum(int(t['tests']) for t in tests) == 96
assert all(int(t[k]) == 0 for t in tests for k in ['failures', 'errors', 'skipped'])
apk = root / 'app/build/outputs/apk/release/app-release.apk'
assets = {}
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    assert not any('.mimosa' in n for n in archive.namelist())
    asset_root = root / 'app/src/main/assets'
    for p in asset_root.rglob('*'):
        if p.is_file() and not any(part.startswith('.') for part in p.relative_to(asset_root).parts):
            name = p.relative_to(asset_root).as_posix()
            assert archive.read('assets/' + name) == p.read_bytes(), name
            assets[name] = hashlib.sha256(p.read_bytes()).hexdigest()
    abis = sorted({n.split('/')[1] for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')})
    assert abis == ['arm64-v8a']
    dex = b''.join(archive.read(n) for n in archive.namelist() if n.endswith('.dex'))
    assert b'__cialloChapterJson' in dex and b'__cialloApiFailover' in dex
    for name in ['lib/arm64-v8a/libncnn.so', 'lib/arm64-v8a/libmangajanai.so']:
        data = archive.read(name)
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        for index in range(count):
            header = offset + index * size
            if struct.unpack_from('<I', data, header)[0] == 1:
                assert struct.unpack_from('<Q', data, header + 48)[0] >= 16384
destination = out / 'Ciallo-Reader-v1.3.7.apk'
shutil.copy2(apk, destination)
build_tools = Path('C:/Users/GuanXingRen/AppData/Local/Android/Sdk/build-tools/36.1.0')
for tool, arguments, filename in [
    ('aapt.exe', ['dump', 'badging'], 'apk-package.txt'),
    ('apksigner.bat', ['verify', '--verbose', '--print-certs'], 'apk-signature.txt'),
    ('zipalign.exe', ['-c', '-P', '16', '-v', '4'], 'apk-alignment.txt'),
]:
    result = subprocess.run([str(build_tools / tool), *arguments, str(destination)], capture_output=True, text=True, check=True)
    (out / filename).write_text(result.stdout + result.stderr, encoding='utf-8')
assert "versionCode='218' versionName='1.3.7'" in (out / 'apk-package.txt').read_text(encoding='utf-8')
certificate = 'd2115e3cc5880b210a120558aaab03eb30ee5de2952a80f3376dda7e45501146'
assert certificate in (out / 'apk-signature.txt').read_text(encoding='utf-8')
assert 'Verification successful' in (out / 'apk-alignment.txt').read_text(encoding='utf-8')
report = {'version': '1.3.7', 'versionCode': 218, 'bytes': destination.stat().st_size,
          'sha256': hashlib.sha256(destination.read_bytes()).hexdigest(), 'abis': abis,
          'certificateSha256': certificate, 'zipCrc': True, 'zipAlignment16KB': True,
          'nativeElfAlignment16KB': True, 'packagedAssets': assets, 'testsPassed': 96,
          'sourceSnapshotCount': len(snapshot), 'sourceInputsStable': True,
          'postBuildDeviceTestChanges': changed,
          'deviceTestCompiled': True, 'deviceTestExecuted': True,
          'mycomicActualChainPassed': True, 'mycomicChain': chain,
          'taskComplete': False, 'publication': 'validated; awaiting source push and Release upload'}
(out / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(out / 'SHA256SUMS.txt').write_text(report['sha256'] + '  ' + destination.name + '\n', encoding='utf-8')
print(json.dumps({k: report[k] for k in ['version', 'bytes', 'sha256', 'testsPassed', 'sourceSnapshotCount', 'publication']}))
