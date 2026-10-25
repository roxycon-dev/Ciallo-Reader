package me.trishiraj.shadowglow

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ShadowVerticalBlurTest {
    // Frozen column-major implementation, including its exact floating-point rounding.
    private fun reference(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        val last = h - 1
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += src[(if (i < 0) 0 else if (i > last) last else i) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = ((sum * inv) + .5f).toInt()
                val add = y + r + 1; val sub = y - r
                sum += (if (add > last) src[last * w + x] else src[add * w + x]) -
                    (if (sub < 0) src[x] else src[sub * w + x])
            }
        }
    }

    @Test fun allEdgeCasesAndRandomMasksRemainBitExact() {
        val random = Random(42)
        val sizes = listOf(1 to 1, 1 to 97, 127 to 1, 2 to 3, 3 to 2, 97 to 41, 384 to 128)
        for ((w, h) in sizes) for (r in listOf(1, 2, 7, 31, 128, 256)) {
            val src = IntArray(w * h + 17) { random.nextInt(256) }
            val old = IntArray(src.size) { -1 }; val next = old.copyOf()
            reference(src, old, w, h, r)
            boxBlurVertical(src, next, w, h, r)
            assertArrayEquals("$w x $h radius $r", old, next)
        }
    }

    @Test fun tenAlternatingCpuPairs() {
        val w = 384; val h = 160; val r = 24
        val src = IntArray(w * h) { (it * 131 + 17) and 255 }
        val dst = IntArray(src.size)
        fun sample(old: Boolean): Long {
            val start = System.nanoTime()
            repeat(50) { if (old) reference(src, dst, w, h, r) else boxBlurVertical(src, dst, w, h, r) }
            return System.nanoTime() - start
        }
        repeat(4) { sample(true); sample(false) }
        val before = mutableListOf<Long>(); val after = mutableListOf<Long>()
        repeat(10) { if (it % 2 == 0) { before.add(sample(true)); after.add(sample(false)) }
            else { after.add(sample(false)); before.add(sample(true)) } }
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "gradlew.bat").exists() }
        root.resolve("artifacts/fold-motion/shadow-cpu.json").apply { parentFile.mkdirs() }
            .writeText("{\"passesPerSample\":50,\"baselineNs\":$before,\"optimizedNs\":$after}")
    }
}
