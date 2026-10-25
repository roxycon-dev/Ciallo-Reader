"""Analyze only the app's marked hot interval, excluding startup/JIT/test setup."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "artifacts/fold-motion/profiling/pythonlibs"))
from perfetto.prebuilts.perfetto_prebuilts import get_perfetto_prebuilt
from perfetto.prebuilts.manifests.trace_processor_shell import TRACE_PROCESSOR_SHELL_MANIFEST
from perfetto.trace_processor import TraceProcessor
from perfetto.trace_processor.api import TraceProcessorConfig

binary = get_perfetto_prebuilt(TRACE_PROCESSOR_SHELL_MANIFEST, arch="windows-amd64")
scene = sys.argv[sys.argv.index("--scene") + 1] if "--scene" in sys.argv else "mixed"
out = ROOT / "artifacts/fold-motion/resource-probe" / ("debug" if "--debug" in sys.argv else "release") / ("max" if "--max" in sys.argv else "high") / scene
results = {}
for variant in ("baseline", "optimized"):
    with TraceProcessor(trace=str(out / f"{variant}.perfetto-trace"), config=TraceProcessorConfig(bin_path=binary)) as tp:
        def query(sql):
            return [vars(row) for row in tp.query(sql)]
        span = query("SELECT ts, dur FROM slice WHERE name='motion_cpu_gpu_measured' AND dur>0 ORDER BY ts LIMIT 1")
        if not span:
            raise RuntimeError(f"{variant}: missing measurement span; trace is not valid performance evidence")
        start = span[0]["ts"]
        end = start + span[0]["dur"]
        cpu = query(f"""
            SELECT t.name thread, ROUND(SUM(MIN(s.ts+s.dur,{end})-MAX(s.ts,{start}))/1e6,3) cpu_ms
            FROM sched s JOIN thread t USING(utid) JOIN process p USING(upid)
            WHERE p.name='com.aistudio.novelreader.kxmpzq.debug' AND s.dur>0 AND s.ts<{end} AND s.ts+s.dur>{start}
            GROUP BY t.utid ORDER BY cpu_ms DESC
        """)
        slices = query(f"""
            SELECT t.name thread,s.name,COUNT(*) count,ROUND(SUM(MIN(s.ts+s.dur,{end})-MAX(s.ts,{start}))/1e6,3) inclusive_ms
            FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
            WHERE p.name='com.aistudio.novelreader.kxmpzq.debug' AND s.dur>0 AND s.ts<{end} AND s.ts+s.dur>{start}
            GROUP BY t.utid,s.name ORDER BY inclusive_ms DESC LIMIT 50
        """)
        if not cpu:
            raise RuntimeError(f"{variant}: span exists but app sched data missing")
        phases = query(f"""
            SELECT t.name thread,s.name,COUNT(*) count,
                   ROUND(SUM(MIN(s.ts+s.dur,{end})-MAX(s.ts,{start}))/1e6,3) inclusive_ms
            FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
            WHERE p.name='com.aistudio.novelreader.kxmpzq.debug' AND s.dur>0 AND s.ts<{end} AND s.ts+s.dur>{start}
              AND (s.name LIKE '%recompose%' OR s.name LIKE '%applyChanges%' OR s.name LIKE '%measureAndLayout%'
                   OR s.name='Record View#draw()' OR s.name LIKE 'drawLayer [%')
            GROUP BY t.utid,s.name ORDER BY inclusive_ms DESC
        """)
        results[variant] = {"span": span[0], "cpu": cpu, "slices": slices,
                            "phases": phases,
                            "note": "slice durations are nested and inclusive; do not sum as CPU"}
(out / "trace-analysis.json").write_text(json.dumps(results, indent=2))
for variant, result in results.items():
    print(variant, json.dumps(result["cpu"][:8]))
