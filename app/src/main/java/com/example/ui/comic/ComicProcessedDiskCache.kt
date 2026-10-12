package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.sync.withPermit

/**
 * 漫画增强结果的磁盘持久化缓存（第七轮第 1 条子问题 B）。
 *
 * 设计约束：
 * - 只服务重管线档（ANIME4K / WAIFU2X / SUPER_RES）：这些档单页处理是秒级推理，
 *   命中后"翻回同一页 / 重启 App"都免重算；轻档（CAS / 纯调色）全页 <0.5s，
 *   不值得付出磁盘 IO + 无损编码的代价。
 * - key = SHA-256(内存 cacheKey)。内存 cacheKey 本身已含
 *   `页id|半页|管线指纹(含增强开关+档位+强度)|旋转`——切档/开关天然落入不同
 *   key 空间，磁盘与内存两级缓存对"档位维度"的语义严格一致（子问题 A 同源根治）。
 * - 无损编码：API 30+ 用 WEBP_LOSSLESS，低版本退 PNG——处理质量不因缓存打折。
 * - 容量上限 [MAX_BYTES]，超限按 lastModified 从最旧开始删（简单 LRU）。
 * - 所有 IO 由调用方在 Dispatchers.IO 上执行；任何异常静默降级——缓存只是
 *   加速器，坏盘/满盘不允许影响阅读链路。
 */
internal class ComicProcessedDiskCache(context: Context) {

    companion object {
        private const val DIR_NAME = "comic_processed_v1"
        private const val MAX_BYTES = 192L shl 20   // 192MB

        /** 重管线档才启用磁盘缓存（纯函数，单测钉死档位集合） */
        fun eligible(mode: ComicEnhanceMode): Boolean =
            mode.isJaNai ||
                mode == ComicEnhanceMode.ANIME4K ||
                mode == ComicEnhanceMode.WAIFU2X ||
                mode == ComicEnhanceMode.SUPER_RES
    }

    private val dir = File(context.cacheDir, DIR_NAME)

    /** 串行写闸：并发预载多页同时编码会瞬时吃满 CPU/内存带宽 */
    private val writeGate = kotlinx.coroutines.sync.Semaphore(1)

    private val hex = { b: Byte -> "%02x".format(b) }

    internal fun fileFor(cacheKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("", postfix = ext()) { hex(it) })
    }

    private fun ext() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ".webp" else ".png"

    /** 读取处理结果；null = 未命中/损坏（损坏文件顺手清除） */
    fun read(cacheKey: String): Bitmap? = runCatching {
        val f = fileFor(cacheKey)
        if (!f.isFile) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeFile(f.absolutePath, opts)
        if (bmp == null || bmp.width <= 0 || bmp.height <= 0) {
            f.delete()
            null
        } else {
            f.setLastModified(System.currentTimeMillis())   // LRU 近期性
            bmp
        }
    }.getOrNull()

    /**
     * 写入处理结果（调用方保证在 IO 线程；建议经 [withWriteGate] 串行）。
     * 已存在的 key 仅刷新时间戳。失败静默返回 false。
     */
    suspend fun write(cacheKey: String, bitmap: Bitmap): Boolean = withWriteGate {
        runCatching {
            val f = fileFor(cacheKey)
            if (f.isFile) {
                f.setLastModified(System.currentTimeMillis())
                return@runCatching true
            }
            if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
            val tmp = File(dir, f.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (!ok) return@runCatching false
            }
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return@runCatching false
            }
            trimLocked()
            true
        }.getOrDefault(false)
    }

    suspend fun <T> withWriteGate(block: () -> T): T = writeGate.withPermit { block() }

    /** 容量整理：超限从最旧删除。仅在写闸内调用（单写者，免并发删除竞态） */
    private fun trimLocked() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        val victims = files.sortedBy { it.lastModified() }.iterator()
        while (total > MAX_BYTES && victims.hasNext()) {
            val f = victims.next()
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /** 当前占用字节数（诊断/测试用） */
    fun usedBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
}
