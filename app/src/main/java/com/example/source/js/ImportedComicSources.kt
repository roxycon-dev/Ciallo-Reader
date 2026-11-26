package com.example.source.js

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.dokar.quickjs.QuickJs
import com.example.data.readImportBytes
import com.example.i18n.tr
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Local sources use a separate install directory and runtime identity from repository sources. */
internal object ImportedComicSources {
    private const val MAX_SCRIPT_BYTES = 512 * 1024
    const val MAX_BATCH = 20
    private const val MAX_SOURCES = 200
    private val keyPattern = Regex("[A-Za-z0-9_.-]{1,100}")
    private val classPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    const val MEDIA_SCOPE_HEADER = "X-Ciallo-Imported-Source"
    private val runtimeKeyPattern = Regex("import_[a-f0-9]{32}")
    private val parserLock = Mutex()
    private var parser: QuickJs? = null

    data class Definition(
        val key: String,
        val name: String,
        val version: String,
        val className: String,
        val script: String,
        val hasAccount: Boolean,
        val hasSearch: Boolean,
    ) {
        val runtimeKey: String get() {
            val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            return "import_" + hash.take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val id: String get() = "js_$runtimeKey"

        fun record() = JSONObject().put("key", key).put("name", name).put("version", version)
            .put("className", className).put("hasAccount", hasAccount).put("hasSearch", hasSearch)
            .put("script", script).put("userImported", true)

        fun source(context: Context) = JsComicSource(
            context.applicationContext, runtimeKey, name, version, script,
            importedClassName = className, importedAccount = hasAccount, importedSearch = hasSearch,
        )
    }

    data class Candidate(
        val fileName: String,
        val definition: Definition? = null,
        val error: String? = null,
        val installedVersion: String? = null,
    )

    suspend fun inspect(context: Context, files: List<Uri>): List<Candidate> = withContext(Dispatchers.IO) {
        files.distinct().take(MAX_BATCH).map { uri ->
            val resolver = context.contentResolver
            var label = uri.lastPathSegment?.substringAfterLast('/')?.takeLast(140) ?: "JavaScript"
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) label = it.getString(0)?.take(140) ?: label
                }
                val bytes = resolver.openInputStream(uri)?.use { it.readImportBytes(MAX_SCRIPT_BYTES) }
                    ?: error(tr("无法读取此文件"))
                val script = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                val definition = inspectScript(context, script)
                Candidate(label, definition, installedVersion = find(context, definition.id)?.version)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Candidate(label, error = if (e is java.nio.charset.CharacterCodingException) tr("请选择 UTF-8 编码的 JS 文件")
                    else e.message?.take(160) ?: tr("无法读取此文件"))
            }
        }
    }

    /** Parse the AST without evaluating source code or attaching the app's JS bridge. */
    suspend fun inspectScript(context: Context, script: String): Definition = parserLock.withLock {
        require(script.isNotBlank() && script.toByteArray().size <= MAX_SCRIPT_BYTES) { tr("JS 文件为空或超过 512 KB") }
        val runtime = parser ?: QuickJs.create(Dispatchers.IO).apply {
            memoryLimit = 32L * 1024 * 1024
            maxStackSize = 512L * 1024
            evaluate<Any?>(context.assets.open("js_safety/acorn.js").bufferedReader().use { it.readText() }, filename = "acorn.js")
        }.also { parser = it }
        val raw = try {
            runtime.evaluate<String>("$INSPECT(${JSONObject.quote(script)})", filename = "source_metadata.js")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error(tr("JS 文件语法无效或内容过于复杂")) }
        finally { withContext(kotlinx.coroutines.NonCancellable) { QuickJsAsyncLifecycle.settle(runtime) } }
        requireNotNull(raw) { tr("JS 文件语法无效或内容过于复杂") }
        val fields = JSONObject(raw)
        require(fields.optInt("count") == 1) { tr("每个文件须声明一个继承 ComicSource 的类") }
        val version = fields.optString("version").trim().ifBlank { "1.0.0" }
        val data = fields.put("version", version).put("script", script)
        decode(data)
    }

    private fun decode(record: JSONObject): Definition {
        val key = record.optString("key").trim()
        val name = record.optString("name").trim()
        val version = record.optString("version").trim()
        val className = record.optString("className")
        val script = record.getString("script")
        require(keyPattern.matches(key) && name.length in 1..120 && name.none(Char::isISOControl)) {
            tr("漫画源须声明有效的 name 和 key")
        }
        require(classPattern.matches(className) && version.length in 1..64 && script.isNotBlank() &&
            script.toByteArray().size <= MAX_SCRIPT_BYTES) { tr("书源脚本无效") }
        return Definition(key, name, version, className, script, record.optBoolean("hasAccount"), record.optBoolean("hasSearch", true))
    }

    private fun dir(context: Context) = File(context.filesDir, "imported_comic_sources")
    private fun file(context: Context, id: String) = AtomicFile(File(dir(context), "$id.json"))
    private val installLock = Any()

    fun load(context: Context): List<JsComicSource> = records(context).map { it.source(context) }

    fun isImportedId(id: String) = id.startsWith("js_") && runtimeKeyPattern.matches(id.removePrefix("js_"))

    fun mediaScope(value: String?) = value?.takeIf(runtimeKeyPattern::matches)

    fun mediaUrl(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        return try {
            com.example.source.SourceNetworkPolicy.check(parsed, null)
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) null else parsed.toString()
        } catch (_: IllegalArgumentException) { null }
    }

    fun find(context: Context, id: String): JsComicSource? = synchronized(installLock) {
        if (!isImportedId(id)) return@synchronized null
        read(file(context, id)).takeIf { it?.id == id }?.source(context)
    }

    fun records(context: Context): List<Definition> = synchronized(installLock) {
        dir(context).listFiles().orEmpty().filter { it.extension == "json" }.sortedBy(File::getName)
            .take(MAX_SOURCES).mapNotNull { file ->
                read(AtomicFile(file))?.takeIf { file.name == "${it.id}.json" }
            }
    }

    private fun read(file: AtomicFile): Definition? = runCatching {
        file.openRead().use {
            decode(JSONObject(it.readImportBytes(MAX_SCRIPT_BYTES * 6 + 4096).toString(Charsets.UTF_8)))
        }
    }.getOrNull()

    fun install(context: Context, definition: Definition, replace: Boolean): JsComicSource? = synchronized(installLock) {
        dir(context).mkdirs()
        val file = file(context, definition.id)
        if (file.baseFile.exists()) {
            if (!replace) return@synchronized null
        } else require(dir(context).listFiles().orEmpty().count { it.extension == "json" } < MAX_SOURCES) {
            tr("已达到本地漫画源数量上限")
        }
        val output = file.startWrite()
        try {
            output.write(definition.record().toString().toByteArray())
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
        definition.source(context)
    }

    fun remove(context: Context, id: String) = synchronized(installLock) {
        require(isImportedId(id))
        file(context, id).delete()
        check(!file(context, id).baseFile.exists()) { tr("删除漫画源失败") }
    }

    // Only literal metadata is needed during preview. The script's body runs when the installed source is used.
    private const val INSPECT = """((text) => {
      const tree = acorn.parse(text, {ecmaVersion: "latest"});
      const classes = tree.body.filter(node => node.type === "ClassDeclaration" &&
          node.superClass?.type === "Identifier" && node.superClass.name === "ComicSource");
      const fields = {count: classes.length};
      if (classes.length !== 1) return JSON.stringify(fields);
      const source = classes[0];
      fields.className = source.id.name;
      const members = source.body.body.filter(item => !item.static && !item.computed);
      for (const member of members) {
          const key = member.key?.name ?? member.key?.value;
          if (["name", "key", "version"].includes(key) &&
              member.value?.type === "Literal" && typeof member.value.value === "string")
              fields[key] = member.value.value;
          if (key === "account" && member.value?.value !== null) fields.hasAccount = true;
          if (key === "search" && member.value?.value !== null) fields.hasSearch = true;
      }
      fields.hasSearch = !!fields.hasSearch;
      return JSON.stringify(fields);
    })"""
}

internal fun okhttp3.OkHttpClient.Builder.restrictImportedComicNetwork() = addInterceptor { chain ->
    val request = chain.request()
    require(ImportedComicSources.mediaUrl(request.url.toString()) != null)
    chain.proceed(request)
}.addNetworkInterceptor { chain ->
    val request = chain.request()
    val url = request.url
    com.example.source.SourceNetworkPolicy.check(url, null)
    chain.connection()?.route()?.socketAddress?.address?.let {
        com.example.source.SourceNetworkPolicy.checkAddress(url.host, it, null)
    }
    val outgoing = request.newBuilder().removeHeader(ImportedComicSources.MEDIA_SCOPE_HEADER)
    if (url.host != chain.call().request().url.host) {
        outgoing.removeHeader("Cookie").removeHeader("Authorization").removeHeader("Proxy-Authorization")
    }
    chain.proceed(outgoing.build())
}.dns(object : okhttp3.Dns {
    override fun lookup(hostname: String) = SourceDns.dns().lookup(hostname)
        .onEach { com.example.source.SourceNetworkPolicy.checkAddress(hostname, it, null) }
}).proxy(java.net.Proxy.NO_PROXY)
