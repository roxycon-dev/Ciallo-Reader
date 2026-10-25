package dev.liquidglass.core

import org.junit.Assert.*
import org.junit.Test

class GlassPackingReuseTest {
    @Test fun scratchPackingMatchesOriginalAndClearsRemovedShapes() {
        val shapes = listOf(PackedGlassShape(20f, 30f, 10f, 15f, 50f), PackedGlassShape(70f, 50f, 25f, 12f, 8f))
        val packed = FloatArray(GlassUniforms.MAX_SHAPES * GlassUniforms.SHAPE_FLOATS)
        val radii = FloatArray(GlassUniforms.MAX_SHAPES)
        for (count in listOf(2, 1, 0, 2)) {
            val active = shapes.take(count)
            GlassShapePacker.packShapesInto(active, packed, 4f)
            GlassShapePacker.packCornerRadiiInto(active, radii)
            assertArrayEquals(GlassShapePacker.packShapes(active.map { it.copy(centerX = it.centerX + 4f, centerY = it.centerY + 4f) }), packed, 0f)
            assertArrayEquals(GlassShapePacker.packCornerRadii(active), radii, 0f)
        }
    }
}
