package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/** 密码箱里的一条账号：书源 + 账号 + 密码。 */
data class VaultCredential(
    val sourceId: String,
    val sourceName: String,
    val username: String,
    val password: String,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 密码箱：集中保存需要登录的书源账号密码（Z-Library 等）。
 *
 * - 用 EncryptedSharedPreferences（Android Keystore 主密钥 + AES256 加密）落盘，
 *   明文不进 SharedPreferences；
 * - 存在应用私有目录，**同签名覆盖安装/升级自动保留**（这就是“随版本保留”）；
 * - 有意不进入 `.ciallo` 备份与设备互传（和账号 Cookie、隐私 PIN 同一保密级别），
 *   换设备需要重新录入；
 * - 缓存清理只删 cacheDir，不碰 shared_prefs，密码箱不受影响。
 */
object CredentialVault {

    private const val PREFS_NAME = "credential_vault"
    private const val KEY_PREFIX = "cred:"
    private const val MAX_SOURCES = 64

    /** 单测注入用（Robolectric 下用普通 SharedPreferences 绕开 AndroidKeyStore）。 */
    @Volatile
    internal var testPrefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences? {
        testPrefs?.let { return it }
        return runCatching {
            val master = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()
    }

    /** 保存/更新一个书源的账号；系统安全存储不可用或字段为空时返回 false。 */
    fun save(context: Context, credential: VaultCredential): Boolean {
        if (credential.sourceId.isBlank() || credential.username.isBlank() || credential.password.isBlank()) return false
        val store = prefs(context) ?: return false
        return runCatching {
            val keys = store.all.keys.filter { it.startsWith(KEY_PREFIX) }
            if (keys.size >= MAX_SOURCES && KEY_PREFIX + credential.sourceId !in keys) return false
            store.edit().putString(KEY_PREFIX + credential.sourceId, encode(credential)).apply()
            true
        }.getOrDefault(false)
    }

    fun get(context: Context, sourceId: String): VaultCredential? {
        if (sourceId.isBlank()) return null
        val store = prefs(context) ?: return null
        return runCatching { store.getString(KEY_PREFIX + sourceId, null)?.let(::decode) }.getOrNull()
    }

    fun list(context: Context): List<VaultCredential> {
        val store = prefs(context) ?: return emptyList()
        return runCatching {
            store.all.entries
                .filter { it.key.startsWith(KEY_PREFIX) }
                .mapNotNull { (it.value as? String)?.let(::decode) }
                .sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun delete(context: Context, sourceId: String): Boolean {
        if (sourceId.isBlank()) return false
        val store = prefs(context) ?: return false
        return runCatching { store.edit().remove(KEY_PREFIX + sourceId).commit() }.getOrDefault(false)
    }

    /** 系统安全存储当前是否可用（Keystore 损坏时 UI 给出明确提示）。 */
    fun isAvailable(context: Context): Boolean = prefs(context) != null

    private fun encode(c: VaultCredential): String = JSONObject()
        .put("sourceId", c.sourceId)
        .put("sourceName", c.sourceName)
        .put("username", c.username)
        .put("password", c.password)
        .put("updatedAt", c.updatedAt)
        .toString()

    private fun decode(raw: String): VaultCredential? = runCatching {
        val o = JSONObject(raw)
        val sourceId = o.optString("sourceId")
        if (sourceId.isBlank()) return@runCatching null
        VaultCredential(
            sourceId = sourceId,
            sourceName = o.optString("sourceName", sourceId),
            username = o.optString("username"),
            password = o.optString("password"),
            updatedAt = o.optLong("updatedAt", 0L)
        )
    }.getOrNull()?.takeIf { it.username.isNotBlank() }
}
