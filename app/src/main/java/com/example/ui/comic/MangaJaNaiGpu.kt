package com.example.ui.comic

import java.nio.ByteBuffer

/** JNI entry points stay named across R8. All calls use the shared JaNai page lock. */
internal object MangaJaNaiGpu {
    init { System.loadLibrary("mangajanai") }
    external fun create(param: String, weights: String, cache: String): Long
    external fun run(handle: Long, input: ByteBuffer, output: ByteBuffer, side: Int)
    external fun save(handle: Long)
    external fun destroy(handle: Long)
}
