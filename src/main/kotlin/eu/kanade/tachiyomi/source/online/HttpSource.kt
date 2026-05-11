@file:Suppress("unused", "UNUSED_PARAMETER")

package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.*
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.security.MessageDigest

/**
 * A real implementation of HttpSource for JVM compatibility.
 * Tachiyomi extensions extend this class.
 */
public abstract class HttpSource : CatalogueSource {

    public val network: NetworkHelper by lazy { NetworkHelper.instance }

    public abstract val baseUrl: String

    public open val versionId: Int = 1

    override val id: Long by lazy {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val result = (0 until 8).fold(0L) { acc, i ->
            acc or ((bytes[i].toLong() and 0xff) shl (8 * (7 - i)))
        }
        result and Long.MAX_VALUE
    }

    public val headers: Headers by lazy { headersBuilder().build() }

    public open val client: OkHttpClient
        get() = network.client

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    override fun toString(): String = "$name ($lang)"

    // ============================== Popular ===============================

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return Observable.fromCallable {
            val request = popularMangaRequest(page)
            val response = client.newCall(request).execute()
            popularMangaParse(response)
        }
    }

    protected abstract fun popularMangaRequest(page: Int): Request
    protected abstract fun popularMangaParse(response: Response): MangasPage

    // ============================== Search ================================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return Observable.fromCallable {
            val request = searchMangaRequest(page, query, filters)
            val response = client.newCall(request).execute()
            searchMangaParse(response)
        }
    }

    protected abstract fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request
    protected abstract fun searchMangaParse(response: Response): MangasPage

    // ============================== Latest ================================

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return Observable.fromCallable {
            val request = latestUpdatesRequest(page)
            val response = client.newCall(request).execute()
            latestUpdatesParse(response)
        }
    }

    protected abstract fun latestUpdatesRequest(page: Int): Request
    protected abstract fun latestUpdatesParse(response: Response): MangasPage

    // ============================== Details ===============================

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.fromCallable {
            val request = mangaDetailsRequest(manga)
            val response = client.newCall(request).execute()
            mangaDetailsParse(response)
        }
    }

    public open fun mangaDetailsRequest(manga: SManga): Request {
        return Request.Builder()
            .url(baseUrl + manga.url)
            .headers(headers)
            .build()
    }

    protected abstract fun mangaDetailsParse(response: Response): SManga

    override val supportsRelatedMangas: Boolean get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        return relatedMangaListParse(client.newCall(relatedMangaListRequest(manga)).execute())
    }

    protected open fun relatedMangaListRequest(manga: SManga): Request {
        return mangaDetailsRequest(manga)
    }

    protected open fun relatedMangaListParse(response: Response): List<SManga> = emptyList()

    // ============================== Chapters ==============================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            val request = chapterListRequest(manga)
            val response = client.newCall(request).execute()
            chapterListParse(response)
        }
    }

    protected open fun chapterListRequest(manga: SManga): Request {
        return Request.Builder()
            .url(baseUrl + manga.url)
            .headers(headers)
            .build()
    }

    protected abstract fun chapterListParse(response: Response): List<SChapter>

    // ============================== Pages =================================

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            val request = pageListRequest(chapter)
            val response = client.newCall(request).execute()
            pageListParse(response)
        }
    }

    protected open fun pageListRequest(chapter: SChapter): Request {
        return Request.Builder()
            .url(baseUrl + chapter.url)
            .headers(headers)
            .build()
    }

    protected abstract fun pageListParse(response: Response): List<Page>

    // ============================== Image ==================================

    public open fun fetchImageUrl(page: Page): Observable<String> {
        return Observable.fromCallable {
            val request = imageUrlRequest(page)
            val response = client.newCall(request).execute()
            imageUrlParse(response)
        }
    }

    protected open fun imageUrlRequest(page: Page): Request {
        return Request.Builder()
            .url(page.url)
            .headers(headers)
            .build()
    }

    protected abstract fun imageUrlParse(response: Response): String

    public fun fetchImage(page: Page): Observable<Response> {
        return Observable.fromCallable {
            val request = imageRequest(page)
            client.newCall(request).execute()
        }
    }

    protected open fun imageRequest(page: Page): Request {
        val imgUrl = page.imageUrl ?: page.url
        return Request.Builder()
            .url(imgUrl)
            .headers(headers)
            .build()
    }

    // ============================== URL Helpers ============================

    public fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    public fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = java.net.URI(orig)
            var out = uri.path.orEmpty()
            if (uri.query != null) out += "?" + uri.query
            if (uri.fragment != null) out += "#" + uri.fragment
            out
        } catch (e: Exception) {
            orig
        }
    }

    public open fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    public open fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    public open fun prepareNewChapter(chapter: SChapter, manga: SManga) {}

    public override fun getFilterList(): FilterList = FilterList()
}
