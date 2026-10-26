"""Freeze the pre-round-2 MAX glow and complete button for opt-in Native pixel tests."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
REFERENCE = "10d1f206404ac49cc77bc7867baf1fefed22dfa2"
OUT = ROOT / "artifacts/motion-round2/glow-parity-src"


def source(path):
    return subprocess.check_output(["git", "show", f"{REFERENCE}:{path}"], cwd=ROOT, text=True, encoding="utf-8")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    glow = source("app/src/main/java/me/trishiraj/shadowglow/ShadowGlow.kt")
    glow = glow.replace("Modifier.shadowGlow(", "Modifier.baselineRound2ShadowGlow(")
    glow = glow.replace("Modifier.consistentShadow(", "Modifier.baselineRound2ConsistentShadow(")
    glow = glow.replace("drawGlowTrailAlongShape(", "baselineRound2DrawGlowTrailAlongShape(")
    (OUT / "Round2BaselineGlow.kt").write_text(glow, encoding="utf-8")
    drawing = source("app/src/main/java/me/trishiraj/shadowglow/Drawing.kt")
    drawing = drawing[drawing.index("internal fun DrawScope.drawGlowTrailAlongShape("):]
    drawing = """package me.trishiraj.shadowglow
import android.graphics.Path
import android.graphics.PathMeasure
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
""" + drawing.replace("drawGlowTrailAlongShape(", "baselineRound2DrawGlowTrailAlongShape(")
    (OUT / "Round2BaselineDrawing.kt").write_text(drawing, encoding="utf-8")
    button = source("app/src/main/java/com/example/ui/components/AppButton.kt")
    button = button[:button.index("enum class AppButtonVariant")] + button[button.index("@Composable\nfun AppActionButton("):]
    button = button.replace("fun AppActionButton(", "fun Round2BaselineAppActionButton(")
    button = button.replace("import me.trishiraj.shadowglow.shadowGlow", "import me.trishiraj.shadowglow.baselineRound2ShadowGlow as shadowGlow")
    (OUT / "Round2BaselineButton.kt").write_text(button, encoding="utf-8")
    print(f"Frozen {REFERENCE}: {OUT}")


if __name__ == "__main__":
    main()
