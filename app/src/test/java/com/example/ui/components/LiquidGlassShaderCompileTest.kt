package com.example.ui.components

import android.graphics.RuntimeShader
import dev.liquidglass.core.LiquidGlassShaders
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Dialogs use this AGSL program on supported Android versions; compile the real program. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiquidGlassShaderCompileTest {
    // SDK <=34 uses Android 12's native Skia in Robolectric (upstream issue #9691).
    @Test @Config(sdk = [35]) fun fullGlassShaderCompilesWithAndroid15NativeRenderer() {
        RuntimeShader(LiquidGlassShaders.LIQUID_GLASS)
    }
}
