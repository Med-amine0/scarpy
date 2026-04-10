package com.vaults.app.scraper

import kotlinx.coroutines.Dispatchers
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

data class PHResult(
    val webmUrl: String?,
    val mp4Url: String?,
    val gifUrl: String?
) {
    fun bestUrl(): String? = webmUrl ?: mp4Url ?: gifUrl
}

data class RedGifResult(
    val directUrl: String?,
    val embedUrl: String
) {
    fun bestUrl(): String? = directUrl ?: embedUrl
}

object PHScraper {
    private val embedRegex = Pattern.compile("""(fileWebm|fileMp4|fileGif)\s*=\s*'([^']+)'""")

    suspend fun getFreshUrl(gifId: String): PHResult? = withContext(Dispatchers.IO) {
        try {
            val request = HttpClient.buildRequest("https://www.pornhub.com/embedgif/$gifId")
            val response = HttpClient.client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            
            val body = response.body?.string() ?: return@withContext null
            val matcher = embedRegex.matcher(body)
            
            var webm: String? = null
            var mp4: String? = null
            var gif: String? = null
            
            while (matcher.find()) {
                val key = matcher.group(1)
                val url = matcher.group(2)
                when (key) {
                    "fileWebm" -> webm = url
                    "fileMp4" -> mp4 = url
                    "fileGif" -> gif = url
                }
            }
            
            if (webm != null || mp4 != null || gif != null) {
                PHResult(webm, mp4, gif)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getThumbnail(gifId: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = HttpClient.buildRequest("https://www.pornhub.com/embedgif/$gifId")
            val response = HttpClient.client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            
            val body = response.body?.string() ?: return@withContext null
            
            val thumbRegex = Pattern.compile("""mediaUrl\s*=\s*'([^']+)'""")
            val matcher = thumbRegex.matcher(body)
            
            if (matcher.find()) {
                val mediaUrl = matcher.group(1)
                val thumbRegex2 = Pattern.compile("""(\d+\.\d+\.\d+\.\d+/di)[^/]*?/\d+/[\w-]+(\.jpg|\.webp)""")
                val matcher2 = thumbRegex2.matcher(mediaUrl)
                if (matcher2.find()) {
                    matcher2.group(0).replace("/123/", "/80/").replace("/180/", "/80/").replace("/45/", "/80/")
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }
}

object RedGifScraper {
    suspend fun getDirectUrl(gifId: String): RedGifResult = withContext(Dispatchers.IO) {
        val cleanId = gifId.substringAfterLast("/").substringBefore("?")
        try {
            val request = HttpClient.buildRequest("https://api.redgifs.com/v2/gifs/$cleanId")
            val response = HttpClient.client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext RedGifResult(null, embedUrl(cleanId))
                
                var hdUrl: String? = null
                var sdUrl: String? = null
                
                val hdRegex = Pattern.compile(""""hd":\s*"([^"]+)""""")
                val sdRegex = Pattern.compile(""""sd":\s*"([^"]+)""""")
                
                hdRegex.find(body)?.let { hdUrl = it.group(1) }
                sdRegex.find(body)?.let { sdUrl = it.group(1) }
                
                val directUrl = hdUrl ?: sdUrl
                RedGifResult(directUrl, embedUrl(cleanId))
            } else {
                RedGifResult(null, embedUrl(cleanId))
            }
        } catch (e: Exception) {
            RedGifResult(null, embedUrl(cleanId))
        }
    }

    private fun embedUrl(id: String) = "https://redgifs.com/ifr/$id"
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