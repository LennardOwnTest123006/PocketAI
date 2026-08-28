package com.pocketai.app.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.pocketai.app.data.repo.SearchProvider
import com.pocketai.app.data.repo.WebSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed interface SearchOutcome {
    data class Success(val query: String, val sources: List<WebSource>) : SearchOutcome
    data object Offline : SearchOutcome
    data class Failed(val message: String) : SearchOutcome
    data object NoResults : SearchOutcome
}

/**
 * Optional web retrieval.
 *
 * Only ever called when the user has switched Web Search on. No API key is
 * required or stored - both providers expose keyless public endpoints.
 */
class WebSearchClient(private val context: Context) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    suspend fun search(
        query: String,
        provider: SearchProvider,
        maxResults: Int
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext SearchOutcome.NoResults
        if (!isOnline()) return@withContext SearchOutcome.Offline
        try {
            val results = when (provider) {
                SearchProvider.DUCKDUCKGO -> duckDuckGo(query, maxResults)
                SearchProvider.WIKIPEDIA -> wikipedia(query, maxResults)
            }
            if (results.isEmpty()) SearchOutcome.NoResults
            else SearchOutcome.Success(query, results)
        } catch (t: Throwable) {
            SearchOutcome.Failed(t.message ?: "The search request failed.")
        }
    }

    private fun duckDuckGo(query: String, maxResults: Int): List<WebSource> {
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Search returned HTTP ${response.code}")
            val html = response.body?.string().orEmpty()
            val document = Jsoup.parse(html)
            return document.select("div.result, div.web-result")
                .asSequence()
                .mapNotNull { element ->
                    val link = element.selectFirst("a.result__a") ?: return@mapNotNull null
                    val title = link.text().trim()
                    val href = resolveRedirect(link.attr("href"))
                    val snippet = element.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
                    if (title.isBlank() || href.isBlank()) null
                    else WebSource(title = title, url = href, snippet = snippet)
                }
                .distinctBy { it.url }
                .take(maxResults)
                .toList()
        }
    }

    /** DuckDuckGo wraps outbound links in /l/?uddg=<encoded>. */
    private fun resolveRedirect(href: String): String {
        if (!href.contains("uddg=")) {
            return if (href.startsWith("//")) "https:$href" else href
        }
        return runCatching {
            val encoded = href.substringAfter("uddg=").substringBefore("&")
            java.net.URLDecoder.decode(encoded, "UTF-8")
        }.getOrDefault(href)
    }

    private fun wikipedia(query: String, maxResults: Int): List<WebSource> {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json" +
            "&srlimit=$maxResults&srsearch=" + URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Wikipedia returned HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val search: JSONArray = org.json.JSONObject(body)
                .optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
            return (0 until search.length()).mapNotNull { i ->
                val item = search.optJSONObject(i) ?: return@mapNotNull null
                val title = item.optString("title")
                if (title.isBlank()) return@mapNotNull null
                val snippet = Jsoup.parse(item.optString("snippet")).text()
                WebSource(
                    title = title,
                    url = "https://en.wikipedia.org/wiki/" + title.replace(' ', '_'),
                    snippet = snippet
                )
            }.take(maxResults)
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) PocketAI/1.0 (local AI assistant)"

        private val TRIGGERS = listOf(
            "search the internet", "search the web", "look this up", "look it up",
            "google ", "what happened today", "latest news", "current news",
            "today's weather", "weather today", "current price", "stock price",
            "latest version", "current version", "right now", "as of today",
            "this week", "this year", "recent"
        )

        /**
         * Heuristic used to surface the "this looks like it needs the web" hint.
         * It never turns search on by itself - the user's toggle always decides.
         */
        fun looksTimeSensitive(text: String): Boolean {
            val lower = text.lowercase()
            return TRIGGERS.any { lower.contains(it) }
        }

        /** Strips conversational filler so the query sent out is minimal. */
        fun toQuery(text: String): String {
            var q = text.trim().removeSuffix("?")
            listOf(
                "search the internet for", "search the web for", "look up",
                "can you tell me", "please tell me", "tell me", "search for"
            ).forEach { prefix ->
                if (q.lowercase().startsWith(prefix)) q = q.substring(prefix.length).trim()
            }
            return q.take(200)
        }
    }
}
