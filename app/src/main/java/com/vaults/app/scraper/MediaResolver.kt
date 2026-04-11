package com.vaults.app.scraper

import com.vaults.app.db.GalleryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    val error: String? = null
)

object MediaResolver {
    private val semaphore = Semaphore(4)

    suspend fun resolve(galleryType: GalleryType, value: String): ResolvedMedia = semaphore.withPermit {
        withContext(Dispatchers.IO) {
            when (galleryType) {
                GalleryType.NORMAL -> resolveNormal(value)
                GalleryType.PORNHUB -> resolvePornhub(value)
                GalleryType.REDGIF -> resolveRedgif(value)
                GalleryType.FOLDER -> ResolvedMedia(null, error = "Invalid type")
            }
        }
    }

    private fun resolveNormal(value: String): ResolvedMedia {
        val isVideo = value.endsWith(".mp4", ignoreCase = true) ||
                value.endsWith(".webm", ignoreCase = true) ||
                value.contains(".mp4", ignoreCase = true) ||
                value.contains(".webm", ignoreCase = true)
        return ResolvedMedia(url = value, isVideo = isVideo)
    }

    private fun resolvePornhub(gifId: String): ResolvedMedia {
        return try {
            val request = HttpClient.buildRequest("https://www.pornhub.com/embedgif/$gifId")
            val response = HttpClient.client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return ResolvedMedia(error = "Failed to fetch")
            }
            
            val body = response.body?.string() ?: return ResolvedMedia(error = "Empty response")
            
            val webmRegex = Pattern.compile("""fileWebm\s*=\s*'([^']+)'""")
            val mp4Regex = Pattern.compile("""fileMp4\s*=\s*'([^']+)'""")
            
            val webmMatch = webmRegex.matcher(body)
            val mp4Match = mp4Regex.matcher(body)
            
            when {
                webmMatch.find() -> ResolvedMedia(url = webmMatch.group(1), isVideo = true)
                mp4Match.find() -> ResolvedMedia(url = mp4Match.group(1), isVideo = true)
                else -> ResolvedMedia(error = "No video found")
            }
        } catch (e: Exception) {
            ResolvedMedia(error = e.message)
        }
    }

    private fun resolveRedgif(gifId: String): ResolvedMedia {
        val cleanId = gifId.substringAfterLast("/").substringBefore("?")
        
        return try {
            val request = HttpClient.buildRequest("https://api.redgifs.com/v2/gifs/$cleanId")
            val response = HttpClient.client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val embedUrl = "https://redgifs.com/ifr/$cleanId"
                return ResolvedMedia(embedUrl = embedUrl)
            }
            
            val body = response.body?.string() ?: return ResolvedMedia(
                embedUrl = "https://redgifs.com/ifr/$cleanId"
            )
            
            val hdRegex = Pattern.compile(""""hd":\s*"([^"]+)"""").toRegex()
            val sdRegex = Pattern.compile(""""sd":\s*"([^"]+)"""").toRegex()
            
            val hdUrl = hdRegex.find(body)?.groupValues?.get(1)
            val sdUrl = sdRegex.find(body)?.groupValues?.get(1)
            
            when {
                hdUrl != null -> ResolvedMedia(url = hdUrl, isVideo = true)
                sdUrl != null -> ResolvedMedia(url = sdUrl, isVideo = true)
                else -> ResolvedMedia(embedUrl = "https://redgifs.com/ifr/$cleanId")
            }
        } catch (e: Exception) {
            ResolvedMedia(embedUrl = "https://redgifs.com/ifr/$cleanId")
        }
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