package com.vaults.app.scraper

import com.vaults.app.db.GalleryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object HttpClient {
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mobileUA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun buildRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", mobileUA)
        .build()
}

data class ResolvedMedia(
    val url: String? = null,
    val embedUrl: String? = null,
    val isVideo: Boolean = false,
    val error: String? = null,
    // Secondary/small rendition used by the grid. Fullscreen always uses `url`.
    val thumbUrl: String? = null
)

object MediaResolver {
    private val semaphore = Semaphore(4)
    private var redgifToken: String? = null

    private fun getRedgifToken(): String? {
        if (redgifToken != null) return redgifToken
        return try {
            val req = HttpClient.buildRequest("https://api.redgifs.com/v2/auth/temporary")
            val res = HttpClient.client.newCall(req).execute()
            if (res.isSuccessful) {
                val body = res.body?.string() ?: return null
                Regex(""""token"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1)?.also {
                    redgifToken = it
                }
            } else null
        } catch (e: Exception) { null }
    }

    suspend fun resolve(galleryType: GalleryType, value: String): ResolvedMedia = semaphore.withPermit {
        withContext(Dispatchers.IO) {
            when (galleryType) {
                GalleryType.NORMAL -> resolveNormal(value)
                GalleryType.CLIPS -> resolveNormal(value)
                GalleryType.REDGIF -> resolveRedgif(value)
                GalleryType.FOLDER -> ResolvedMedia(null, error = "Invalid type")
            }
        }
    }

    fun resolveSync(galleryType: GalleryType, value: String): ResolvedMedia {
        return runBlocking { resolve(galleryType, value) }
    }

    private fun resolveNormal(value: String): ResolvedMedia {
        val isVideo = value.endsWith(".mp4", ignoreCase = true) ||
                value.endsWith(".webm", ignoreCase = true) ||
                value.contains(".mp4", ignoreCase = true) ||
                value.contains(".webm", ignoreCase = true)
        return ResolvedMedia(url = value, isVideo = isVideo)
    }

    private fun resolveRedgif(input: String): ResolvedMedia {
        val cleanId = when {
            input.contains("redgifs.com") -> input.substringAfterLast("/").substringBefore("?")
            else -> input.substringAfterLast("/").substringBefore("?")
        }

        val embedUrl = "https://redgifs.com/ifr/$cleanId"

        return try {
            // Step 1: get a temporary token (required by RedGifs API)
            val token = getRedgifToken()

            // Step 2: fetch gif info with token if we got one
            val gifRequest = if (token != null) {
                Request.Builder()
                    .url("https://api.redgifs.com/v2/gifs/$cleanId")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                HttpClient.buildRequest("https://api.redgifs.com/v2/gifs/$cleanId")
            }

            val response = HttpClient.client.newCall(gifRequest).execute()
            if (!response.isSuccessful) return ResolvedMedia(embedUrl = embedUrl, isVideo = true)

            val body = response.body?.string() ?: return ResolvedMedia(embedUrl = embedUrl, isVideo = true)

            val hdUrl = Regex(""""hd"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1)
            val sdUrl = Regex(""""sd"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1)

            when {
                hdUrl != null -> ResolvedMedia(url = hdUrl, isVideo = true)
                sdUrl != null -> ResolvedMedia(url = sdUrl, isVideo = true)
                else -> ResolvedMedia(embedUrl = embedUrl, isVideo = true)
            }
        } catch (e: Exception) {
            ResolvedMedia(embedUrl = embedUrl, isVideo = true)
        }
    }

    /**
     * GoonBox resolution runs ONCE, when the user pastes — never on gallery open.
     * Shares the same semaphore as [resolve] so a 1000-item batch can't stampede.
     */
    suspend fun resolveGoonboxLink(input: String): ResolvedMedia = semaphore.withPermit {
        withContext(Dispatchers.IO) { resolveGoonbox(input) }
    }

    /**
     * GoonBox stores only a short ID (`tQl6wdw`). The CDN URLs carry random UUIDs
     * that cannot be derived from the ID, so they must be fetched from the API:
     *
     *   GET https://goonbox.cr/api/images/{id}   -> { image: { original_url, medium_url, thumb_url, mime } }
     *
     * No auth, no key, no rate limit observed. Returns 404 {"message":"Image not found"}
     * for an unknown ID.
     *
     * url      -> original_url (fullscreen / swipe)
     * thumbUrl -> medium_url   (grid)
     */
    fun resolveGoonbox(input: String): ResolvedMedia {
        // Already a direct CDN URL (…/images4/<uuid>.jpg)? There is no reverse lookup
        // from UUID → ID, but the URL itself is loadable, so use it as-is.
        if (input.contains("cuckcapital.cr/")) {
            val direct = input.trim().substringBefore("?")
            return ResolvedMedia(
                url = direct,
                thumbUrl = direct,
                isVideo = direct.endsWith(".mp4", true) || direct.endsWith(".webm", true)
            )
        }

        // A plain direct URL with nothing to resolve — pass through untouched.
        if (input.trim().startsWith("http") &&
            !input.contains("goonbox.cr") && !input.contains("cuckcapital.cr")
        ) {
            val direct = input.trim()
            return ResolvedMedia(
                url = direct,
                isVideo = direct.endsWith(".mp4", true) || direct.endsWith(".webm", true)
            )
        }

        val id = extractGoonboxId(input)
            ?: return ResolvedMedia(error = "Not a GoonBox link")

        return try {
            val response = HttpClient.client
                .newCall(HttpClient.buildRequest("https://goonbox.cr/api/images/$id"))
                .execute()

            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) {
                return ResolvedMedia(error = if (response.code == 404) "Image not found" else "GoonBox error ${response.code}")
            }

            val image = JSONObject(body).optJSONObject("image")
                ?: return ResolvedMedia(error = "GoonBox: bad response")

            val original = image.optString("original_url").takeIf { it.isNotBlank() }
            val medium = image.optString("medium_url").takeIf { it.isNotBlank() }
            val thumb = image.optString("thumb_url").takeIf { it.isNotBlank() }

            if (original == null) return ResolvedMedia(error = "GoonBox: no url")

            val mime = image.optString("mime")
            ResolvedMedia(
                url = original,
                thumbUrl = medium ?: thumb,
                isVideo = mime.startsWith("video/")
            )
        } catch (e: Exception) {
            ResolvedMedia(error = "GoonBox: ${e.message ?: "failed"}")
        }
    }

    /**
     * Pulls the ID out of anything the user might paste: the share URL, a bare ID,
     * a forum HTML snippet or BBCode — all of which embed the /img/{id} link.
     */
    fun extractGoonboxId(input: String): String? {
        Regex("""goonbox\.cr/img/([A-Za-z0-9]+)""").find(input)?.let {
            return it.groupValues[1]
        }
        // Bare ID pasted on its own (e.g. "tQl6wdw")
        val bare = input.trim().substringBefore("?").substringAfterLast("/")
        if (bare.matches(Regex("[A-Za-z0-9]{5,12}"))) return bare
        return null
    }

    /**
     * Pulls GoonBox tokens out of a pasted blob. The forum posts a whole
     * &lt;a&gt;&lt;img&gt;&lt;/a&gt; block (or BBCode) with no commas or newlines in it, so plain
     * splitting would store the markup verbatim. Hunt out every embedded /img/{id}
     * link first, then treat each line/comma chunk as a CDN URL, a plain direct URL
     * or a bare ID.
     */
    fun parseGoonboxInput(input: String): List<String> {
        val ids = Regex("""goonbox\.cr/img/([A-Za-z0-9]+)""")
            .findAll(input)
            .map { it.groupValues[1] }
            .toMutableList()

        // Chunks with no goonbox link: bare IDs, or direct CDN URLs (kept verbatim)
        input.replace("\"", "").split(",", "\n", "\r\n")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.contains("goonbox.cr/img/") }
            .forEach { chunk ->
                when {
                    chunk.contains("cuckcapital.cr/") -> ids.add(chunk.substringBefore("?"))
                    chunk.startsWith("http") -> ids.add(chunk)  // plain direct URL
                    else -> extractGoonboxId(chunk)?.let { ids.add(it) }
                }
            }

        return ids.distinct()
    }
}

object InputParser {
    fun parse(input: String): List<String> {
        return input
            .replace("\"", "")
            .split(",", "\n", "\r\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
}