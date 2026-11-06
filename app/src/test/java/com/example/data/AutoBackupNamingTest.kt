package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 自动备份的命名与节奏判定。文件真实落盘需要 SAF 授权，属于设备侧验证；
 * 这里只钉住可离线验证的部分：文件名可排序、间隔判定不会无限重试。
 */
class AutoBackupNamingTest {

    private fun stamp(text: String): Long =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(text)!!.time

    @Test
    fun `backup file name carries a sortable local timestamp`() {
        val name = AutoBackupStore.fileName(stamp("20261106-183000"))
        assertEquals("ciallo-auto-20261106-183000.zip", name)
        assertTrue(name.startsWith(AutoBackupStore.FILE_PREFIX))
        assertTrue(name.endsWith(AutoBackupStore.FILE_SUFFIX))
        // 同一秒重复调用也必须稳定，否则目录里会堆出同名副本
        assertEquals(name, AutoBackupStore.fileName(stamp("20261106-183000")))
    }

    @Test
    fun `later backup sorts after earlier one`() {
        val early = AutoBackupStore.fileName(stamp("20261106-090000"))
        val late = AutoBackupStore.fileName(stamp("20261106-183000"))
        assertTrue(late > early)
    }

    @Test
    fun `due check respects switch folder and interval`() {
        val now = stamp("20261106-183000")
        assertTrue(AutoBackupStore.isDue(true, true, now - AutoBackupStore.INTERVAL_MS - 1, now))
        assertFalse("刚备份过不应重复", AutoBackupStore.isDue(true, true, now - 1000L, now))
        assertFalse("开关关闭不备份", AutoBackupStore.isDue(false, true, 0L, now))
        assertFalse("没有目录不备份", AutoBackupStore.isDue(true, false, 0L, now))
        assertFalse("首次启用也要等满间隔", AutoBackupStore.isDue(true, true, now - 60_000L, now))
    }

    @Test
    fun `keeps a bounded number of copies`() {
        assertEquals(3, AutoBackupStore.MAX_KEPT)
    }
}
