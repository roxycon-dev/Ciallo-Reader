package com.example.library

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.example.data.favorite.FavoriteEntity
import com.example.source.BookSource
import com.example.source.DetailLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray

/** Favorites share public work links, independently of downloaded shelf files. */
object FavoriteShareHelper {
    suspend fun shareFavorites(context: Context, favorites: List<FavoriteEntity>, source: (String) -> BookSource?): String? {
        return try {
            val intent = prepareShareIntent(context, favorites, source)
            withContext(Dispatchers.Main) {
                val chooser = Intent.createChooser(intent, "分享作品详情链接")
                if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
            null
        } catch (_: TimeoutCancellationException) {
            "获取详情链接超时，请检查网络后重试"
        } catch (e: CancellationException) { throw e }
        catch (_: ActivityNotFoundException) { "没有找到可分享的应用" }
        catch (e: Exception) { e.message ?: "无法获取作品详情链接" }
    }

    internal suspend fun prepareShareIntent(context: Context, favorites: List<FavoriteEntity>, source: (String) -> BookSource?): Intent =
        withContext(Dispatchers.IO) {
            require(favorites.isNotEmpty()) { "请先选择要分享的作品" }
            val prefs = context.getSharedPreferences("work_detail_share_links", Context.MODE_PRIVATE)
            val semaphore = Semaphore(3)
            val links = withTimeout(25_000) {
                coroutineScope {
                    favorites.distinctBy { it.sourceId to it.comicId }.map { favorite -> async {
                        semaphore.withPermit {
                            val key = JSONArray(listOf(favorite.sourceId, favorite.comicId)).toString()
                            val url = DetailLink.valid(favorite.comicId)
                                ?: DetailLink.valid(prefs.getString(key, null))
                                ?: try { withTimeout(12_000) { DetailLink.valid(source(favorite.sourceId)?.getShareUrl(favorite.comicId)) } }
                                   catch (_: TimeoutCancellationException) { throw IllegalStateException("《${favorite.title}》：获取详情链接超时，请重试") }
                                   catch (e: CancellationException) { throw e }
                                   catch (_: Exception) { throw IllegalStateException("《${favorite.title}》：无法获取详情链接，请检查书源与网络") }
                                ?: throw IllegalStateException("《${favorite.title}》：书源未提供可分享的详情链接，请先打开详情或恢复书源")
                            prefs.edit().putString(key, url).apply()
                            "《${favorite.title}》\n$url"
                        }
                    } }.awaitAll()
                }
            }
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, links.joinToString("\n\n"))
                putExtra(Intent.EXTRA_SUBJECT, favorites.joinToString("、") { it.title })
            }
        }
}
