package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 密码箱存储回归：加解密读写用普通 SharedPreferences 注入（Robolectric 下没有
 * AndroidKeyStore），覆盖增删查、更新覆盖、排序、非法字段与损坏条目。
 */
@RunWith(RobolectricTestRunner::class)
class CredentialVaultTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CredentialVault.testPrefs = context.getSharedPreferences("vault_test", Context.MODE_PRIVATE)
        CredentialVault.testPrefs!!.edit().clear().commit()
    }

    @After
    fun tearDown() {
        CredentialVault.testPrefs?.edit()?.clear()?.commit()
        CredentialVault.testPrefs = null
    }

    @Test
    fun `save then get roundtrips`() {
        assertTrue(
            CredentialVault.save(
                context,
                VaultCredential("zlibrary", "Z-Library", "me@example.com", "s3cret")
            )
        )
        val loaded = CredentialVault.get(context, "zlibrary")
        assertEquals("me@example.com", loaded?.username)
        assertEquals("s3cret", loaded?.password)
        assertEquals("Z-Library", loaded?.sourceName)
    }

    @Test
    fun `save updates existing entry instead of duplicating`() {
        CredentialVault.save(context, VaultCredential("zlibrary", "Z-Library", "a@b.c", "old"))
        CredentialVault.save(context, VaultCredential("zlibrary", "Z-Library", "a@b.c", "new"))
        assertEquals(1, CredentialVault.list(context).size)
        assertEquals("new", CredentialVault.get(context, "zlibrary")?.password)
    }

    @Test
    fun `list sorts by newest and delete removes`() {
        CredentialVault.save(context, VaultCredential("a", "A", "u1", "p1", updatedAt = 1000L))
        CredentialVault.save(context, VaultCredential("b", "B", "u2", "p2", updatedAt = 2000L))
        assertEquals(listOf("b", "a"), CredentialVault.list(context).map { it.sourceId })
        assertTrue(CredentialVault.delete(context, "b"))
        assertNull(CredentialVault.get(context, "b"))
        assertEquals(1, CredentialVault.list(context).size)
    }

    @Test
    fun `blank fields are rejected`() {
        assertFalse(CredentialVault.save(context, VaultCredential("", "X", "u", "p")))
        assertFalse(CredentialVault.save(context, VaultCredential("x", "X", "", "p")))
        assertFalse(CredentialVault.save(context, VaultCredential("x", "X", "u", "")))
        assertTrue(CredentialVault.list(context).isEmpty())
    }

    @Test
    fun `corrupted entries are skipped`() {
        CredentialVault.save(context, VaultCredential("ok", "OK", "u", "p"))
        CredentialVault.testPrefs!!.edit().putString("cred:broken", "{not json").commit()
        assertEquals(listOf("ok"), CredentialVault.list(context).map { it.sourceId })
    }
}
