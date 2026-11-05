package com.example.ui.components

import androidx.compose.ui.geometry.Rect
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** The reference was captured from released 1.5.12, before the CPU-only rewrite. */
class PaperMotionTimingTest {
    private val cases=listOf(Rect(140f,270f,260f,310f),Rect(120f,-140f,280f,-100f),Rect(120f,720f,280f,760f),Rect(30f,30f,380f,150f))
    @Test fun warpKeepsTheReleasedSilhouetteWithinASubpixel() {
        val record=System.getProperty("paper.motion.record")=="true"
        val generated=mutableListOf<String>(); val shape=PaperWarpGeometry()
        for(source in cases) for(step in 0..20) {
            val progress=step/20f;shape.update(400f,600f,source,progress,1f)
            val values=listOf(shape.frame.left,shape.frame.top,shape.frame.right,shape.frame.bottom)+listOf(0,15,31,47,63).flatMap { row -> (0..3).map { shape.rows[row*4+it] } }
            generated+=values.joinToString(",")
        }
        if(record) File("src/test/resources/paper-warp-1.5.12.csv").apply { parentFile.mkdirs();writeText(generated.joinToString("\n")+"\n") }
        else {
            val reference=javaClass.getResourceAsStream("/paper-warp-1.5.12.csv")!!.bufferedReader().readLines()
            assertEquals(reference.size,generated.size)
            reference.zip(generated).forEach { (before,after) ->
                before.split(',').zip(after.split(',')).forEachIndexed { index,(a,b) ->
                    // Normalized row coordinates are bounded by 600 px; never trade mesh precision for speed.
                    val pixelError=abs(a.toFloat()-b.toFloat())*(if(index<4) 1f else 600f)
                    assertTrue("Silhouette changed by $pixelError px",pixelError<.01f)
                }
            }
        }
    }

    @Test fun reportGeometryCpuCost() {
        val geometry=PaperWarpGeometry();val source=cases[0]
        repeat(2000) { geometry.update(400f,600f,source,(it%99+1)/100f,1f) }
        val samples=LongArray(7)
        var checksum=0f
        for(round in samples.indices) {
            val start=System.nanoTime()
            repeat(5000) { geometry.update(400f,600f,source,(it%99+1)/100f,1f);checksum+=geometry.rows[(it%64)*4] }
            samples[round]=System.nanoTime()-start
        }
        samples.sort()
        val dir=File("../artifacts/paper-motion").apply { mkdirs() }
        val variant=System.getProperty("paper.motion.variant")
        File(dir,"geometry-$variant.json").writeText("""{"iterationsPerRound":5000,"medianNanosPerUpdate":${samples[3]/5000.0},"checksum":$checksum}""")
        assertTrue(checksum.isFinite())
    }

    @Test fun slowFramesDoNotStretchOpeningOrTheReturnToTheButton() {
        fun duration(hz:Int,opening:Boolean,lightweight:Boolean):Float {
            val spring=PaperSpring().apply { position=if(opening) 0f else 1f }
            var seconds=0f
            do { seconds+=1f/hz;val moving=spring.advance(if(opening) 1f else 0f,opening,1f/hz,lightweight) }
            while(moving && seconds<10f)
            assertEquals(if(opening) 1f else 0f,spring.position,0f)
            return seconds
        }
        for(opening in listOf(false,true)) for(lightweight in listOf(false,true)) {
            val baseline=duration(120,opening,lightweight)
            for(hz in listOf(8,10,15,20,30,60)) {
                val seconds=duration(hz,opening,lightweight)
                assertTrue("$hz Hz stretched the motion from $baseline to $seconds",abs(seconds-baseline)<=1f/hz+.01f)
            }
        }
    }
    @Test fun oneLongFrameConsumesTheElapsedTimeWithoutACatchUpLoop() {
        val close=PaperSpring().apply { position=1f }
        assertFalse(close.advance(0f,false,2f));assertEquals(0f,close.position,0f)
        val open=PaperSpring()
        assertFalse(open.advance(1f,true,2f));assertEquals(1f,open.position,0f)
        val whole=PaperSpring().apply { position=1f }
        val divided=PaperSpring().apply { position=1f }
        whole.advance(0f,false,.3f)
        repeat(30) { divided.advance(0f,false,.01f) }
        assertEquals(divided.position,whole.position,.00001f)
        assertEquals(divided.velocity,whole.velocity,.0001f)
    }
    @Test fun theBackdropSharesAnEndpointSafeMonotonicCurve() {
        var before=0f
        for(step in 0..1000) {
            val progress=paperBackdropProgress(step/1000f)
            assertTrue(progress>=before && progress in 0f..1f);before=progress
        }
        assertEquals(0f,paperBackdropProgress(-1f),0f)
        assertEquals(1f,paperBackdropProgress(2f),0f)
        assertTrue(paperBackdropProgress(.001f)<.00001f)
        assertTrue(paperBackdropProgress(.999f)>.99999f)
    }
}
