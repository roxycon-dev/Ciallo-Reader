package com.example.library

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageBytesTest {
    @Test fun publicReaderEnvelopeDecodesLosslesslyOnlyForItsOrigin() {
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        val original=ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray() }
        bitmap.recycle()
        val encrypted=ByteArray(original.size) { (((original[it].toInt() and 255) xor 171)+47).toByte() }
        val decoded=ImageBytes.normalizeImage(encrypted,sourceUrl="https://webimg.iqtao.cn/page.webp")
        assertArrayEquals(original,decoded)
        assertTrue(ImageBytes.decodeOk(decoded))
        assertArrayEquals(original,ImageBytes.normalizeImage(encrypted,sourceUrl="https://f2.kkmh.com/page"))
        assertArrayEquals(encrypted,ImageBytes.normalizeImage(encrypted,sourceUrl="https://unrelated.example/page.webp"))
        assertArrayEquals(original,ImageBytes.normalizeImage(original,sourceUrl="https://webimg.iqtao.cn/page.webp"))
    }
}
