package com.example.ui.components

import android.os.Build
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/** The common card and its draw lambdas must only reference types available on every OS. */
internal interface CardDentEffect {
    val available: Boolean
    fun effect(x: Float, y: Float, radius: Float, intensity: Float, rotationX: Float, rotationY: Float): RenderEffect?
}

internal fun createCardDentEffect(source: String, lightX: Float, lightY: Float, ambient: Float): CardDentEffect? {
    if (Build.VERSION.SDK_INT < 33) return null
    return try { CardDentApi33.create(source, lightX, lightY, ambient)
    } catch (_: RuntimeException) { null
    } catch (_: LinkageError) { null }
}

/** Isolate platform class verification and prevent R8 from moving it into the common path. */
@RequiresApi(33)
private object CardDentApi33 {
    @DoNotInline
    fun create(source: String, lightX: Float, lightY: Float, ambient: Float): CardDentEffect =
        Impl(source, lightX, lightY, ambient)

    private class Impl(source: String, lightX: Float, lightY: Float, ambient: Float) : CardDentEffect {
        private val shader = android.graphics.RuntimeShader(source).apply {
            setFloatUniform("lightDir", lightX, lightY)
            setFloatUniform("ambientStrength", ambient)
        }
        override var available = true
            private set

        override fun effect(x: Float, y: Float, radius: Float, intensity: Float, rotationX: Float, rotationY: Float): RenderEffect? {
            if (!available) return null
            return try {
                shader.setFloatUniform("pressCenter", x, y)
                shader.setFloatUniform("pressRadius", radius)
                shader.setFloatUniform("pressIntensity", intensity)
                shader.setFloatUniform("cardRotationX", rotationX)
                shader.setFloatUniform("cardRotationY", rotationY)
                android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "inputShader").asComposeRenderEffect()
            } catch (_: RuntimeException) { available = false; null
            } catch (_: LinkageError) { available = false; null }
        }
    }
}
