package org.draken.usagi.core.parser.tachiyomi

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.ResolvableSource
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria
import org.koitharu.kotatsu.parsers.model.search.SearchableField
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import java.net.URI
import java.security.MessageDigest
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TachiyomiMangaParser(
	override val source: MangaSource,
	private val tachiyomiSource: CatalogueSource,
	private val sourceConfig: MangaSourceConfig,
) : MangaParser {

	private val pageMap = ConcurrentHashMap<Long, Page>()
	private val pagingStates = ConcurrentHashMap<String, PagingState>()

	private val fallbackDomain = extractDomain(
		(tachiyomiSource as? HttpSource)?.baseUrl,
	).ifBlank { "localhost" }

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(fallbackDomain)

	override val config get() = sourceConfig

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.RELEVANCE,
	)

	@Suppress("DEPRECATION")
	override val searchQueryCapabilities: MangaSearchQueryCapabilities = MangaSearchQueryCapabilities()

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
	)

	override val domain: String
		get() = sourceConfig[configKeyDomain]

	override suspend fun getList(query: MangaSearchQuery): List<Manga> {
		val q = query.criteria.firstNotNullOfOrNull { criterion ->
			if (criterion.field != SearchableField.TITLE_NAME) return@firstNotNullOfOrNull null
			(criterion as? QueryCriteria.Match<*>)?.value?.toString()
		}
		return getList(
			offset = query.offset,
			order = query.order ?: SortOrder.POPULARITY,
			filter = MangaListFilter(query = q),
		)
	}

	override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val pagingKey = when {
			query.isNotEmpty() -> "search|$query"
			order in LATEST_SORT_ORDERS && tachiyomiSource.supportsLatest -> "latest"
			else -> "popular"
		}

		if (offset == 0) {
			pagingStates.remove(pagingKey)
		}
		val page = resolvePage(pagingKey, offset)
		val mangasPage = when (pagingKey) {
			"latest" -> tachiyomiSource.getLatestUpdates(page)
			"popular" -> tachiyomiSource.getPopularManga(page)
			else -> tachiyomiSource.getSearchManga(page, query, FilterList())
		}
		updatePaging(
			pagingKey = pagingKey,
			offset = offset,
			page = page,
			itemsCount = mangasPage.mangas.size,
			hasNextPage = mangasPage.hasNextPage,
		)
		return mangasPage.mangas.map { it.toManga(source) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val seed = manga.toSManga()
		val details = tachiyomiSource.getMangaDetails(seed)
		val chapters = getChapters(seed, details)
		return details.toManga(source, chapters)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val tChapter = chapter.toSChapter()
		val pages = tachiyomiSource.getPageList(tChapter)
		return pages.map { page ->
			val pageId = stableId("page|${source.name}|${chapter.url}|${page.index}|${page.url}|${page.imageUrl.orEmpty()}")
			pageMap[pageId] = page
			MangaPage(
				id = pageId,
				url = page.url.ifBlank { page.imageUrl.orEmpty() },
				preview = page.imageUrl?.toAbsoluteUrl((tachiyomiSource as? HttpSource)?.baseUrl),
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		val tPage = pageMap[page.id]
			?: Page(index = 0, url = page.url, imageUrl = page.preview)
		val imageUrl = tPage.imageUrl?.takeIf { it.isNotBlank() }
		if (imageUrl != null) return imageUrl
		val httpSource = tachiyomiSource as? HttpSource
		if (httpSource != null) {
			return httpSource.getImageUrl(tPage)
		}
		return tPage.url.ifBlank { page.preview.orEmpty() }
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions()
	}

	override suspend fun getFavicons(): Favicons {
		val baseUrl = (tachiyomiSource as? HttpSource)?.baseUrl ?: return Favicons.EMPTY
		val domain = extractDomain(baseUrl)
		if (domain.isBlank()) return Favicons.EMPTY
		return Favicons.single("https://$domain/favicon.ico")
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		keys += configKeyDomain
		keys += ConfigKey.UserAgent(DEFAULT_UA)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	override fun getRequestHeaders(): Headers {
		return (tachiyomiSource as? HttpSource)?.headers ?: Headers.Builder().build()
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		return chain.proceed(chain.request())
	}

	@InternalParsersApi
	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val resolvable = tachiyomiSource as? ResolvableSource ?: return null
		val uri = link.toString()
		if (!resolvable.canResolveUri(uri)) return null
		return resolvable.getManga(uri)?.toManga(source)
	}

	private fun SManga.toManga(
		targetSource: MangaSource,
		chapters: List<SChapter>? = null,
	): Manga {
		val mangaUrl = safeUrl()
		val mangaTitle = safeTitle()
		val publicUrl = resolvePublicUrl(this)
		val authors = linkedSetOf<String>().apply {
			author?.nullIfEmpty()?.let(::add)
			artist?.nullIfEmpty()?.let(::add)
		}
		val tags = genre.orEmpty()
			.split(',', ';')
			.mapNotNull { it.trim().nullIfEmpty() }
			.distinct()
			.mapTo(LinkedHashSet()) { title ->
				MangaTag(
					title = title,
					key = title.lowercase(Locale.ROOT),
					source = targetSource,
				)
			}
		return Manga(
			id = stableId("manga|${targetSource.name}|$mangaUrl"),
			title = mangaTitle.ifBlank { publicUrl.substringAfterLast('/').ifBlank { mangaUrl } },
			altTitles = emptySet(),
			url = mangaUrl,
			publicUrl = publicUrl,
			rating = 0f,
			contentRating = if (targetSource.contentType == org.koitharu.kotatsu.parsers.model.ContentType.HENTAI) {
				ContentRating.ADULT
			} else {
				null
			},
			coverUrl = thumbnail_url?.toAbsoluteUrl((tachiyomiSource as? HttpSource)?.baseUrl),
			tags = tags,
			state = status.toMangaState(),
			authors = authors,
			largeCoverUrl = thumbnail_url?.toAbsoluteUrl((tachiyomiSource as? HttpSource)?.baseUrl),
			description = description,
			chapters = chapters?.map { it.toMangaChapter(targetSource, mangaUrl) },
			source = targetSource,
		)
	}

	private fun SChapter.toMangaChapter(targetSource: MangaSource, mangaUrl: String): MangaChapter {
		val chapterUrl = safeUrl()
		val chapterTitle = safeName()
		return MangaChapter(
			id = stableId("chapter|${targetSource.name}|$mangaUrl|$chapterUrl|$chapterTitle|$chapter_number"),
			title = chapterTitle.nullIfEmpty(),
			number = chapter_number.takeIf { it > 0f } ?: parseChapterNumber(chapterTitle),
			volume = parseVolume(chapterTitle),
			url = chapterUrl,
			scanlator = scanlator?.nullIfEmpty(),
			uploadDate = date_upload,
			branch = scanlator?.nullIfEmpty(),
			source = targetSource,
		)
	}

	private fun Manga.toSManga(): SManga = SManga.create().apply {
		url = this@toSManga.url
		title = this@toSManga.title
		author = this@toSManga.authors.firstOrNull()
		artist = this@toSManga.authors.drop(1).firstOrNull()
		description = this@toSManga.description
		genre = this@toSManga.tags.joinToString(", ") { it.title }
		status = this@toSManga.state.toSMangaStatus()
		thumbnail_url = this@toSManga.coverUrl
		initialized = this@toSManga.chapters != null
	}

	private fun MangaChapter.toSChapter(): SChapter = SChapter.create().apply {
		url = this@toSChapter.url
		name = this@toSChapter.title ?: ""
		date_upload = this@toSChapter.uploadDate
		chapter_number = this@toSChapter.number
		scanlator = this@toSChapter.scanlator
	}

	private fun resolvePublicUrl(manga: SManga): String {
		val source = tachiyomiSource
		val fallback = manga.safeUrl().toAbsoluteUrl((source as? HttpSource)?.baseUrl)
		return if (source is HttpSource) {
			runCatching { source.getMangaUrl(manga) }.getOrElse { fallback }
		} else {
			fallback
		}
	}

	private fun SManga.safeUrl(): String = runCatching { url }.getOrDefault("")

	private fun SManga.safeTitle(): String = runCatching { title }.getOrDefault("")

	private fun SChapter.safeUrl(): String = runCatching { url }.getOrDefault("")

	private fun SChapter.safeName(): String = runCatching { name }.getOrDefault("")

	private fun parseChapterNumber(name: String): Float {
		return CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
	}

	private fun parseVolume(name: String): Int {
		return VOLUME_REGEX.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
	}

	private suspend fun getChapters(seed: SManga, details: SManga): List<SChapter> {
		var primaryError: Throwable? = null
		val primary = runCatching {
			tachiyomiSource.getChapterList(details)
		}.onFailure {
			primaryError = it
		}.getOrElse {
			emptyList()
		}
		if (primary.isNotEmpty()) {
			return primary
		}

		var fallbackError: Throwable? = null
		val fallback = runCatching {
			tachiyomiSource.getChapterList(seed)
		}.onFailure {
			fallbackError = it
		}.getOrElse {
			emptyList()
		}
		if (fallback.isNotEmpty()) {
			return fallback
		}

		if (primaryError != null || fallbackError != null) {
			throw (fallbackError ?: primaryError ?: IllegalStateException("Cannot load chapter list"))
		}
		return emptyList()
	}

	private fun resolvePage(pagingKey: String, offset: Int): Int {
		if (offset == 0) return 1
		val state = pagingStates.getOrPut(pagingKey) { PagingState() }
		state.nextByOffset[offset]?.let { return it }
		val basePage = offset / state.pageSize
		val remainder = offset % state.pageSize
		return basePage + 1 + if (remainder == 0) 0 else 1
	}

	private fun updatePaging(
		pagingKey: String,
		offset: Int,
		page: Int,
		itemsCount: Int,
		hasNextPage: Boolean,
	) {
		val state = pagingStates.getOrPut(pagingKey) { PagingState() }
		if (itemsCount > 0) {
			state.pageSize = itemsCount
		}
		state.nextByOffset[offset + itemsCount] = if (hasNextPage && itemsCount > 0) {
			page + 1
		} else {
			page
		}
	}

	private fun stableId(key: String): Long {
		val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
		return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
	}

	private fun String.toAbsoluteUrl(baseUrl: String?): String {
		if (startsWith("http://") || startsWith("https://")) return this
		if (baseUrl.isNullOrBlank()) return this
		return runCatching { URI(baseUrl).resolve(this).toString() }.getOrDefault(this)
	}

	private fun extractDomain(url: String?): String {
		if (url.isNullOrBlank()) return ""
		return runCatching { URI(url).host.orEmpty() }.getOrDefault("")
	}

	private fun Int.toMangaState(): MangaState? = when (this) {
		SManga.ONGOING -> MangaState.ONGOING
		SManga.COMPLETED,
		SManga.PUBLISHING_FINISHED,
		-> MangaState.FINISHED
		SManga.CANCELLED -> MangaState.ABANDONED
		SManga.ON_HIATUS -> MangaState.PAUSED
		SManga.LICENSED -> MangaState.RESTRICTED
		else -> null
	}

	private fun MangaState?.toSMangaStatus(): Int = when (this) {
		MangaState.ONGOING -> SManga.ONGOING
		MangaState.FINISHED -> SManga.COMPLETED
		MangaState.ABANDONED -> SManga.CANCELLED
		MangaState.PAUSED -> SManga.ON_HIATUS
		MangaState.RESTRICTED -> SManga.LICENSED
		else -> SManga.UNKNOWN
	}

	private class PagingState(
		var pageSize: Int = DEFAULT_PAGE_SIZE,
		val nextByOffset: MutableMap<Int, Int> = ConcurrentHashMap(),
	)

	companion object {
		private const val DEFAULT_PAGE_SIZE = 30
		private const val DEFAULT_UA =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
		private val LATEST_SORT_ORDERS = setOf(
			SortOrder.UPDATED,
			SortOrder.UPDATED_ASC,
			SortOrder.NEWEST,
			SortOrder.NEWEST_ASC,
			SortOrder.ADDED,
			SortOrder.ADDED_ASC,
		)
		private val CHAPTER_NUMBER_REGEX = Regex("""(?i)(?:ch(?:apter)?|ep(?:isode)?|#)?\s*(\d+(?:\.\d+)?)""")
		private val VOLUME_REGEX = Regex("""(?i)(?:vol(?:ume)?)\s*(\d+)""")
	}
}
