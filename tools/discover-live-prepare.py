"""Fetch all configurations and export the exact production Kotlin patchScript, without Gradle or Android.

Usage: python tools/discover-live-prepare.py [--offline]
Requires requests, Java (JAVA_HOME), and the project's Kotlin 2.0.21 Gradle cache.
--offline reuses downloaded scripts; subsequent discover-live-audit.cjs HTTP is always live.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import requests

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts/discover-live'
RAW, PATCHED = OUT / 'raw', OUT / 'patched'
UPSTREAM = 'https://raw.githubusercontent.com/venera-app/venera-configs/main/'


def fetch(name):
    for attempt in range(3):
        try:
            response = requests.get(UPSTREAM + name, timeout=(10, 25))
            response.raise_for_status()
            (RAW / name).write_bytes(response.content)
            return
        except requests.RequestException:
            if attempt == 2:
                raise
            time.sleep(1 + attempt)


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--offline', action='store_true')
    offline = args.parse_args().offline
    RAW.mkdir(parents=True, exist_ok=True)
    PATCHED.mkdir(parents=True, exist_ok=True)
    if not offline:
        fetch('index.json')
    profiles = json.loads((RAW / 'index.json').read_text(encoding='utf-8'))
    if not offline:
        with ThreadPoolExecutor(max_workers=3) as pool:
            list(pool.map(fetch, [p['fileName'] for p in profiles]))
    repo_file = ROOT / 'app/src/main/java/com/example/source/js/JsSourceRepo.kt'
    source = repo_file.read_text(encoding='utf-8')
    excluded = set(re.findall(r'"([^"]+)"', source.split('private val EXCLUDED_KEYS = setOf(', 1)[1].split(')', 1)[0]))
    seen = set()
    for profile in profiles:
        profile['offered'] = profile['key'] not in excluded and profile['key'] not in seen
        seen.add(profile['key'])
    locals_text = source.split('private val LOCAL_EXTRA_SOURCES = listOf(', 1)[1].split('\n    )', 1)[0]
    for key, name, filename in re.findall(r'Triple\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)"\)', locals_text):
        profiles.append(dict(key=key, name=name, fileName=filename, offered=key not in excluded, local=True))
    functions = source[source.index('    private fun patchWnacgSearch'):source.index('    private fun fetch(context: Context, url: String)')]
    exporter = 'import java.io.File\nobject LiveProductionPatcher {\n' + functions + '''
    fun export(key: String, input: String, output: String) {
        File(output).writeText(patchScript(key, File(input).readText()))
    }
}
fun main(args: Array<String>) {
    File(args[0]).readLines().forEach { row ->
        val a = row.split("\\t")
        LiveProductionPatcher.export(a[0], a[1], a[2])
    }
}
'''
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
    dependencies = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.0.21',
        'org.jetbrains.kotlin/kotlin-reflect/2.0.21', 'org.jetbrains.intellij.deps/trove4j',
        'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0', 'org.jetbrains/annotations/23.0.0']
    jars = [str(jar) for dep in dependencies for jar in (cache / dep).rglob('*.jar')]
    if not any('compiler-embeddable' in jar for jar in jars):
        raise RuntimeError('Kotlin 2.0.21 compiler missing from Gradle cache')
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe') if os.environ.get('JAVA_HOME') else shutil.which('java')
    if not java:
        raise RuntimeError('Set JAVA_HOME to the Android Studio JBR')
    classpath = os.pathsep.join(jars)
    with tempfile.TemporaryDirectory(prefix='discover-live-') as temp:
        folder = Path(temp)
        kotlin = folder / 'LiveProductionPatcher.kt'
        kotlin.write_text(exporter, encoding='utf-8')
        manifest = folder / 'export.tsv'
        manifest.write_text('\n'.join('\t'.join([p['key'], str((ROOT / 'app/src/main/assets/js_extra' if p.get('local') else RAW) / p['fileName']), str(PATCHED / p['fileName'])]) for p in profiles), encoding='utf-8')
        jar = folder / 'patcher.jar'
        with (OUT / 'standalone-export.log').open('w', encoding='utf-8') as log:
            subprocess.run([java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                '-classpath', classpath, '-d', str(jar), str(kotlin)], check=True, stdout=log, stderr=log)
            subprocess.run([java, '-cp', str(jar) + os.pathsep + classpath, 'LiveProductionPatcherKt', str(manifest)], check=True, stdout=log, stderr=log)
    for profile in profiles:
        profile['patched'] = True
        profile['sha256'] = hashlib.sha256((PATCHED / profile['fileName']).read_bytes()).hexdigest()
    shutil.copyfile(ROOT / 'tools/discover-live-mangadex.js', PATCHED / 'native-mangadex-audit.js')
    profiles.append(dict(key='mangadex', name='MangaDex 原生源 API 对应探测', fileName='native-mangadex-audit.js', offered=True, nativeEquivalent=True))
    (PATCHED / 'index.json').write_text(json.dumps(profiles, ensure_ascii=False, indent=2), encoding='utf-8')
    (OUT / 'preparation.json').write_text(json.dumps(dict(preparedAt=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), upstream=UPSTREAM,
        upstreamRefreshed=not offline, profiles=len(profiles), productionPatchSha256=hashlib.sha256(functions.encode()).hexdigest()), indent=2), encoding='utf-8')
    print(f'Exported {len(profiles)} profiles using actual production Kotlin patchScript')


if __name__ == '__main__':
    main()
