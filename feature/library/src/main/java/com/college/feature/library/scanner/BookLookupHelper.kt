package com.college.feature.library.scanner

import com.college.feature.library.network.BookLookupResponse
import com.college.feature.library.network.LibraryApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Multi-provider client lookup helper:
 * 1. Queries backend /api/v1/library/books/lookup/{isbn} (which uses Google Books, OpenLibrary, and ISBNdb).
 * 2. If backend is offline or returns error, performs direct on-device query to Google Books API & OpenLibrary.
 */
object BookLookupHelper {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun lookup(isbn: String): BookLookupResponse? = withContext(Dispatchers.IO) {
        val cleanIsbn = isbn.trim().replace("-", "").uppercase()
        if (cleanIsbn.length < 8) return@withContext null

        // 1. Try backend service first
        try {
            val response = LibraryApiClient.service.lookupBook(cleanIsbn)
            if (response.isSuccessful && response.body() != null) {
                return@withContext response.body()
            }
        } catch (_: Exception) {
            // Backend offline or unreachable — proceed to direct fallback APIs
        }

        // 2. Direct fallback: Google Books API
        val gbResult = queryGoogleBooks(cleanIsbn)
        if (gbResult != null) return@withContext gbResult

        // 3. Direct fallback: OpenLibrary API
        val olResult = queryOpenLibrary(cleanIsbn)
        if (olResult != null) return@withContext olResult

        null
    }

    private fun queryGoogleBooks(isbn: String): BookLookupResponse? {
        try {
            val url = "https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                val items = json.optJSONArray("items") ?: return null
                if (items.length() == 0) return null

                val volumeInfo = items.getJSONObject(0).optJSONObject("volumeInfo") ?: return null
                val title = volumeInfo.optString("title").ifBlank { "Unknown Title" }
                val authorsArray = volumeInfo.optJSONArray("authors")
                val authors = if (authorsArray != null && authorsArray.length() > 0) {
                    val list = mutableListOf<String>()
                    for (i in 0 until authorsArray.length()) list.add(authorsArray.getString(i))
                    list.joinToString(", ")
                } else "Unknown Author"

                val categoriesArray = volumeInfo.optJSONArray("categories")
                val category = if (categoriesArray != null && categoriesArray.length() > 0) {
                    categoriesArray.getString(0).uppercase().take(30)
                } else "GENERAL"

                val description = volumeInfo.optString("description").takeIf { it.isNotBlank() }
                val cleanOverview = description?.replace(Regex("<[^>]+>"), "")?.trim()
                val publishedDate = volumeInfo.optString("publishedDate").takeIf { it.isNotBlank() }
                val edition = publishedDate?.let { "Published $it" }

                val tocArray = volumeInfo.optJSONArray("tableOfContents")
                val tableOfContents = if (tocArray != null && tocArray.length() > 0) {
                    val lines = mutableListOf<String>()
                    for (i in 0 until tocArray.length()) {
                        val s = tocArray.optString(i).trim()
                        if (s.isNotBlank()) lines.add(s)
                    }
                    if (lines.isNotEmpty()) lines.joinToString("\n") else null
                } else {
                    volumeInfo.optString("tableOfContents").takeIf { it.isNotBlank() }
                }

                return BookLookupResponse(
                    title = title,
                    author = authors,
                    edition = edition,
                    index = "Main Hall",
                    isbn = isbn,
                    category = category,
                    overview = cleanOverview,
                    tableOfContents = tableOfContents,
                    source = "Google Books"
                )
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun queryOpenLibrary(isbn: String): BookLookupResponse? {
        try {
            val url = "https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BLE-Attendance-Library-App/1.0 (academic-project)")
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val root = JSONObject(bodyStr)
                val bookObj = root.optJSONObject("ISBN:$isbn") ?: return null

                val title = bookObj.optString("title").ifBlank { "Unknown Title" }
                val authorsArray = bookObj.optJSONArray("authors")
                val authors = if (authorsArray != null && authorsArray.length() > 0) {
                    val list = mutableListOf<String>()
                    for (i in 0 until authorsArray.length()) {
                        list.add(authorsArray.getJSONObject(i).optString("name"))
                    }
                    list.joinToString(", ")
                } else "Unknown Author"

                val subjectsArray = bookObj.optJSONArray("subjects")
                val category = if (subjectsArray != null && subjectsArray.length() > 0) {
                    subjectsArray.getJSONObject(0).optString("name").uppercase().take(30)
                } else "GENERAL"

                val publishDate = bookObj.optString("publish_date").takeIf { it.isNotBlank() }
                val classifications = bookObj.optJSONObject("classifications")
                val lcClass = classifications?.optJSONArray("lc_classifications")?.optString(0)
                val deweyClass = classifications?.optJSONArray("dewey_decimal_class")?.optString(0)
                val index = lcClass?.ifBlank { null } ?: deweyClass?.ifBlank { null } ?: "Main Hall"

                val notes = bookObj.opt("notes")
                val overview = when (notes) {
                    is JSONObject -> notes.optString("value")
                    is String -> notes
                    else -> null
                }?.replace(Regex("<[^>]+>"), "")?.trim()

                val tocArray = bookObj.optJSONArray("table_of_contents")
                val tableOfContents = if (tocArray != null && tocArray.length() > 0) {
                    val lines = mutableListOf<String>()
                    for (i in 0 until tocArray.length()) {
                        val item = tocArray.opt(i)
                        if (item is JSONObject) {
                            val label = item.optString("label").trim()
                            val t = item.optString("title").trim()
                            val page = item.optString("pagenum").trim()
                            val line = buildString {
                                if (label.isNotBlank()) append("$label ")
                                append(t)
                                if (page.isNotBlank()) append(" (p. $page)")
                            }.trim()
                            if (line.isNotBlank()) lines.add(line)
                        } else if (item is String && item.isNotBlank()) {
                            lines.add(item.trim())
                        }
                    }
                    if (lines.isNotEmpty()) lines.joinToString("\n") else null
                } else {
                    bookObj.optString("table_of_contents").takeIf { it.isNotBlank() }
                }

                return BookLookupResponse(
                    title = title,
                    author = authors,
                    edition = publishDate,
                    index = index,
                    isbn = isbn,
                    category = category,
                    overview = overview,
                    tableOfContents = tableOfContents,
                    source = "OpenLibrary"
                )
            }
        } catch (_: Exception) {
            return null
        }
    }
}
