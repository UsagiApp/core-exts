package org.draken.usagi.core.parser.tachiyomi

import eu.kanade.tachiyomi.RuntimeContext
import eu.kanade.tachiyomi.source.model.Filter
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
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
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
import kotlinx.coroutines.CancellationException

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

	override val availableSortOrders: Set<SortOrder> = buildSet {
		add(SortOrder.POPULARITY)
		if (tachiyomiSource.supportsLatest) add(SortOrder.UPDATED)
		add(SortOrder.RELEVANCE)
		val info = try { extractFilterInfo() } catch (_: Exception) { null }
		if (info != null) {
			addAll(info.detectedSortOrders)
		}
	}

	@Suppress("DEPRECATION")
	override val searchQueryCapabilities: MangaSearchQueryCapabilities = MangaSearchQueryCapabilities()

	private val cachedFilterInfo by lazy { extractFilterInfo() }

	override val filterCapabilities: MangaListFilterCapabilities by lazy {
		val info = cachedFilterInfo
		MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = info.genreGroups.isNotEmpty(),
			isTagsExclusionSupported = info.hasTriStateGenres,
			isSearchWithFiltersSupported = info.stateFilterIndex >= 0 || info.typeFilterIndex >= 0
				|| info.ratingFilterIndex >= 0 || info.demographicFilterIndex >= 0,
			isYearSupported = info.yearFilterIndex >= 0,
			isYearRangeSupported = info.yearFromFilterIndex >= 0 || info.yearToFilterIndex >= 0,
			isOriginalLocaleSupported = info.localeFilterIndex >= 0,
			isAuthorSearchSupported = info.authorFilterIndex >= 0,
		)
	}

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
		val httpSource = tachiyomiSource as? HttpSource
		val hasFilters = filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty()
				|| filter.states.isNotEmpty() || filter.types.isNotEmpty()
				|| filter.contentRating.isNotEmpty() || filter.demographics.isNotEmpty()
				|| filter.year > 0 || filter.yearFrom > 0 || filter.yearTo > 0
				|| !filter.author.isNullOrBlank() || filter.originalLocale != null
		val hasSortFilter = cachedFilterInfo.sortFilterIndex >= 0
		val pagingKey = when {
			query.isNotEmpty() -> "search|$query"
			hasFilters -> "search|filters|${filter.hashCode()}"
			hasSortFilter -> "search|sort|${order.name}"
			order in LATEST_SORT_ORDERS && tachiyomiSource.supportsLatest -> "latest"
			else -> "popular"
		}
		syncWebSession(httpSource?.baseUrl)

		if (offset == 0) {
			pagingStates.remove(pagingKey)
		}
		val page = resolvePage(pagingKey, offset)
		val mangasPage = when (pagingKey) {
            "latest" -> {
                syncWebSession(httpSource?.invokeRequestUrl("latestUpdatesRequest", page))
                tachiyomiSource.getLatestUpdates(page)
            }
            "popular" -> {
                syncWebSession(httpSource?.invokeRequestUrl("popularMangaRequest", page))
                tachiyomiSource.getPopularManga(page)
            }
            else -> {
                val searchFilters = buildTachiyomiFilterList(filter, order)
                syncWebSession(httpSource?.invokeRequestUrl("searchMangaRequest", page, query, searchFilters))
                tachiyomiSource.getSearchManga(page, query, searchFilters)
            }
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
		val httpSource = tachiyomiSource as? HttpSource
		val seed = manga.toSManga()
		syncWebSession(seed.safeUrl().toAbsoluteUrl(httpSource?.baseUrl))
		syncWebSession(httpSource?.invokeRequestUrl("mangaDetailsRequest", seed))
		val details = try {
			tachiyomiSource.getMangaDetails(seed)
		} catch (e: Throwable) {
			throw mapCaptchaException(
				cause = e,
				url = resolveMangaUrl(seed, httpSource),
			)
		}
		val chapters = getChapters(seed, details)
		return details.toManga(source, chapters)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val httpSource = tachiyomiSource as? HttpSource
		val tChapter = chapter.toSChapter()
		syncWebSession(tChapter.safeUrl().toAbsoluteUrl(httpSource?.baseUrl))
		syncWebSession(httpSource?.invokeRequestUrl("pageListRequest", tChapter))
		val pages = try {
			tachiyomiSource.getPageList(tChapter)
		} catch (e: Throwable) {
			throw mapCaptchaException(
				cause = e,
				url = resolveChapterUrl(tChapter, httpSource),
			)
		}
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
			syncWebSession(tPage.url.ifBlank { page.url })
			syncWebSession(httpSource.invokeRequestUrl("imageUrlRequest", tPage))
			return httpSource.getImageUrl(tPage)
		}
		return tPage.url.ifBlank { page.preview.orEmpty() }
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val info = cachedFilterInfo
		return MangaListFilterOptions(
			availableTags = info.tags,
			availableStates = info.stateValues.map { it.second }.toSet(),
			availableContentRating = info.ratingMappedValues.map { it.second }.toSet(),
			availableContentTypes = info.typeMappedValues.map { it.second }.toSet(),
			availableDemographics = info.demographicValues.map { it.second }.toSet(),
			availableLocales = info.localeValues.map { it.second }.toSet(),
		)
	}

	override suspend fun getFavicons(): Favicons {
		val baseUrl = (tachiyomiSource as? HttpSource)?.baseUrl ?: return Favicons.EMPTY
		val domain = extractDomain(baseUrl)
		if (domain.isBlank()) return Favicons.EMPTY
		return Favicons.single("https://$domain/favicon.ico")
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		keys += configKeyDomain
		val ua = RuntimeContext.defaultUserAgent().ifBlank { DEFAULT_UA }
		keys += ConfigKey.UserAgent(ua)
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

	private suspend fun syncWebSession(url: String?) {
		val baseUrl = (tachiyomiSource as? HttpSource)?.baseUrl
		val targets = LinkedHashSet<String>(2).apply {
			baseUrl?.takeIf { it.isNotBlank() }?.let(::add)
			url?.takeIf { it.isNotBlank() }?.let(::add)
		}
		if (targets.isEmpty()) return
		for (target in targets) {
			try {
				RuntimeContext.syncWebSession(target)
			} catch (e: Exception) {
				if (e is CancellationException) throw e
			}
		}
	}

	private fun parseChapterNumber(name: String): Float {
		return CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
	}

	private fun parseVolume(name: String): Int {
		return VOLUME_REGEX.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
	}

	private suspend fun getChapters(seed: SManga, details: SManga): List<SChapter> {
		val httpSource = tachiyomiSource as? HttpSource
		var primaryError: Throwable? = null
		val primary = runCatching {
			syncWebSession(httpSource?.invokeRequestUrl("chapterListRequest", details))
			tachiyomiSource.getChapterList(details)
		}.onFailure {
			primaryError = mapCaptchaException(it, resolveMangaUrl(details, httpSource))
		}.getOrElse {
			emptyList()
		}
		if (primary.isNotEmpty()) {
			return normalizeChapterOrder(primary)
		}

		var fallbackError: Throwable? = null
		val fallback = runCatching {
			syncWebSession(httpSource?.invokeRequestUrl("chapterListRequest", seed))
			tachiyomiSource.getChapterList(seed)
		}.onFailure {
			fallbackError = mapCaptchaException(it, resolveMangaUrl(seed, httpSource))
		}.getOrElse {
			emptyList()
		}
		if (fallback.isNotEmpty()) {
			return normalizeChapterOrder(fallback)
		}

		if (primaryError != null || fallbackError != null) {
			throw (fallbackError ?: primaryError ?: IllegalStateException("Cannot load chapter list"))
		}
		return emptyList()
	}

	private fun normalizeChapterOrder(chapters: List<SChapter>): List<SChapter> {
		if (chapters.size < 2) return chapters
		val byNumber = detectChapterNumberOrder(chapters)
		val byDate = detectChapterDateOrder(chapters)
		val order = when {
			byNumber != SortDirection.UNKNOWN -> byNumber
			byDate != SortDirection.UNKNOWN -> byDate
			else -> SortDirection.UNKNOWN
		}
		return if (order == SortDirection.DESCENDING) chapters.asReversed() else chapters
	}

	private fun mapCaptchaException(cause: Throwable, url: String): Throwable {
		if (cause is ParseException) return cause
		val signal = generateSequence(cause) { it.cause }
			.mapNotNull { it.message }
			.firstOrNull { message -> isCaptchaMessage(message) }
			?: return cause
		return ParseException(
			shortMessage = signal,
			url = url,
			cause = cause,
		)
	}

	private fun isCaptchaMessage(message: String): Boolean {
		val lower = message.lowercase(Locale.ROOT)
		return CAPTCHA_KEYWORDS.any { it in lower }
	}

	private fun resolveMangaUrl(manga: SManga, httpSource: HttpSource?): String {
		val resolved = runCatching { httpSource?.getMangaUrl(manga) }
			.getOrNull()
			.orEmpty()
		if (resolved.isNotBlank()) return resolved
		return manga.safeUrl().toAbsoluteUrl(httpSource?.baseUrl)
	}

	private fun resolveChapterUrl(chapter: SChapter, httpSource: HttpSource?): String {
		val resolved = runCatching { httpSource?.getChapterUrl(chapter) }
			.getOrNull()
			.orEmpty()
		if (resolved.isNotBlank()) return resolved
		return chapter.safeUrl().toAbsoluteUrl(httpSource?.baseUrl)
	}

	private fun detectChapterNumberOrder(chapters: List<SChapter>): SortDirection {
		var asc = 0
		var desc = 0
		var previous: Float? = null
		for (chapter in chapters) {
			val number = chapter.chapter_number
				.takeIf { it > 0f }
				?: parseChapterNumber(chapter.safeName()).takeIf { it > 0f }
				?: continue
			val prev = previous
			if (prev != null) {
				when {
					number > prev + CHAPTER_NUMBER_EPSILON -> asc++
					number < prev - CHAPTER_NUMBER_EPSILON -> desc++
				}
			}
			previous = number
		}
		return resolveOrder(asc, desc)
	}

	private fun detectChapterDateOrder(chapters: List<SChapter>): SortDirection {
		var asc = 0
		var desc = 0
		var previous: Long? = null
		for (chapter in chapters) {
			val date = chapter.date_upload.takeIf { it > 0L } ?: continue
			val prev = previous
			if (prev != null) {
				when {
					date > prev -> asc++
					date < prev -> desc++
				}
			}
			previous = date
		}
		return resolveOrder(asc, desc)
	}

	private fun resolveOrder(asc: Int, desc: Int): SortDirection {
		val comparisons = asc + desc
		if (comparisons < MIN_ORDER_COMPARISONS) {
			return SortDirection.UNKNOWN
		}
		val ascValue = asc.toFloat()
		val descValue = desc.toFloat()
		return when {
			ascValue >= (descValue * ORDER_DOMINANCE_FACTOR) -> SortDirection.ASCENDING
			descValue >= (ascValue * ORDER_DOMINANCE_FACTOR) -> SortDirection.DESCENDING
			else -> SortDirection.UNKNOWN
		}
	}

	private fun HttpSource.invokeRequestUrl(methodName: String, vararg args: Any?): String? {
		val method = findDeclaredMethod(javaClass, methodName, args) ?: return null
		return runCatching {
			method.isAccessible = true
			(method.invoke(this, *args) as? Request)?.url?.toString()
		}.getOrNull()
	}

	private fun findDeclaredMethod(targetClass: Class<*>, name: String, args: Array<out Any?>): java.lang.reflect.Method? {
		var current: Class<*>? = targetClass
		while (current != null) {
			val method = current.declaredMethods.firstOrNull { method ->
				method.name == name &&
					method.parameterTypes.size == args.size &&
					method.parameterTypes.indices.all { index ->
						isArgumentCompatible(method.parameterTypes[index], args[index])
					}
			}
			if (method != null) {
				return method
			}
			current = current.superclass
		}
		return null
	}

	private fun isArgumentCompatible(parameterType: Class<*>, argument: Any?): Boolean {
		if (argument == null) return !parameterType.isPrimitive
		return boxedClass(parameterType).isInstance(argument)
	}

	private fun boxedClass(type: Class<*>): Class<*> = when (type) {
		java.lang.Integer.TYPE -> java.lang.Integer::class.java
		java.lang.Long.TYPE -> java.lang.Long::class.java
		java.lang.Float.TYPE -> java.lang.Float::class.java
		java.lang.Double.TYPE -> java.lang.Double::class.java
		java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
		java.lang.Byte.TYPE -> java.lang.Byte::class.java
		java.lang.Short.TYPE -> java.lang.Short::class.java
		java.lang.Character.TYPE -> java.lang.Character::class.java
		else -> type
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

	private enum class SortDirection {
		ASCENDING,
		DESCENDING,
		UNKNOWN,
	}

	private fun buildTachiyomiFilterList(filter: MangaListFilter, order: SortOrder? = null): FilterList {
		val info = cachedFilterInfo
		val hasTags = filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty()
		val hasState = filter.states.isNotEmpty() && info.stateFilterIndex >= 0
		val hasType = filter.types.isNotEmpty() && info.typeFilterIndex >= 0
		val hasRating = filter.contentRating.isNotEmpty() && info.ratingFilterIndex >= 0
		val hasDemo = filter.demographics.isNotEmpty() && info.demographicFilterIndex >= 0
		val hasYear = filter.year > 0 && info.yearFilterIndex >= 0
		val hasYearFrom = filter.yearFrom > 0 && info.yearFromFilterIndex >= 0
		val hasYearTo = filter.yearTo > 0 && info.yearToFilterIndex >= 0
		val hasAuthor = !filter.author.isNullOrBlank() && info.authorFilterIndex >= 0
		val hasLocale = filter.originalLocale != null && info.localeFilterIndex >= 0
		val hasSort = order != null && info.sortFilterIndex >= 0
		if (!hasTags && !hasState && !hasType && !hasRating && !hasDemo
			&& !hasYear && !hasYearFrom && !hasYearTo && !hasAuthor && !hasLocale && !hasSort) {
			return FilterList()
		}
		val baseFilters = try {
			tachiyomiSource.getFilterList()
		} catch (_: Exception) {
			return FilterList()
		}
		// Map tags to genre groups
		if (hasTags) {
			val includeKeys = filter.tags.mapTo(HashSet()) { it.key }
			val excludeKeys = filter.tagsExclude.mapTo(HashSet()) { it.key }
			for (f in baseFilters) {
				if (f !is Filter.Group<*>) continue
				for (child in f.state) {
					when (child) {
						is Filter.CheckBox -> {
							val key = child.name.lowercase(Locale.ROOT)
							if (key in includeKeys) child.state = true
						}
						is Filter.TriState -> {
							val key = child.name.lowercase(Locale.ROOT)
							when {
								key in includeKeys -> child.state = Filter.TriState.STATE_INCLUDE
								key in excludeKeys -> child.state = Filter.TriState.STATE_EXCLUDE
							}
						}
					}
				}
			}
		}
		// Map state filter
		if (hasState) {
			val target = baseFilters.getOrNull(info.stateFilterIndex)
			if (target is Filter.Select<*>) {
				val requested = filter.states.firstOrNull()
				if (requested != null) {
					val matchIdx = info.stateValues.firstOrNull { it.second == requested }?.first
					if (matchIdx != null) target.state = matchIdx
				}
			}
		}
		// Map content type filter
		if (hasType) {
			val target = baseFilters.getOrNull(info.typeFilterIndex)
			if (target is Filter.Select<*>) {
				val requested = filter.types.firstOrNull()
				if (requested != null) {
					val matchIdx = info.typeMappedValues.firstOrNull { it.second == requested }?.first
					if (matchIdx != null) target.state = matchIdx
				}
			}
		}
		// Map content rating filter
		if (hasRating) {
			val target = baseFilters.getOrNull(info.ratingFilterIndex)
			if (target is Filter.Select<*>) {
				val requested = filter.contentRating.firstOrNull()
				if (requested != null) {
					val matchIdx = info.ratingMappedValues.firstOrNull { it.second == requested }?.first
					if (matchIdx != null) target.state = matchIdx
				}
			}
		}
		// Map demographic filter
		if (hasDemo) {
			val target = baseFilters.getOrNull(info.demographicFilterIndex)
			if (target is Filter.Select<*>) {
				val requested = filter.demographics.firstOrNull()
				if (requested != null) {
					val matchIdx = info.demographicValues.firstOrNull { it.second == requested }?.first
					if (matchIdx != null) target.state = matchIdx
				}
			}
		}
		// Map year filter
		if (hasYear) {
			val target = baseFilters.getOrNull(info.yearFilterIndex)
			when (target) {
				is Filter.Text -> target.state = filter.year.toString()
				is Filter.Select<*> -> {
					val yearStr = filter.year.toString()
					val idx = target.values.indexOfFirst { it.toString() == yearStr }
					if (idx >= 0) target.state = idx
				}
				else -> {}
			}
		}
		// Map yearFrom filter
		if (hasYearFrom) {
			val target = baseFilters.getOrNull(info.yearFromFilterIndex)
			if (target is Filter.Text) target.state = filter.yearFrom.toString()
		}
		// Map yearTo filter
		if (hasYearTo) {
			val target = baseFilters.getOrNull(info.yearToFilterIndex)
			if (target is Filter.Text) target.state = filter.yearTo.toString()
		}
		// Map author filter
		if (hasAuthor) {
			val target = baseFilters.getOrNull(info.authorFilterIndex)
			if (target is Filter.Text) target.state = filter.author.orEmpty()
		}
		// Map original locale filter
		if (hasLocale) {
			val target = baseFilters.getOrNull(info.localeFilterIndex)
			if (target is Filter.Select<*>) {
				val requested = filter.originalLocale
				if (requested != null) {
					val matchIdx = info.localeValues.firstOrNull { it.second.language == requested.language }?.first
					if (matchIdx != null) target.state = matchIdx
				}
			}
		}
		// Map sort order
		if (hasSort && order != null) {
			val target = baseFilters.getOrNull(info.sortFilterIndex)
			if (target is Filter.Sort) {
				val mapping = info.sortMapping.firstOrNull { it.second == order }
				if (mapping != null) {
					val ascending = order.name.endsWith("_ASC")
					target.state = Filter.Sort.Selection(mapping.first, ascending)
				}
			}
		}
		return baseFilters
	}

	private data class FilterInfo(
		val tags: Set<MangaTag>,
		val genreGroups: List<Filter.Group<*>>,
		val hasTriStateGenres: Boolean,
		val stateFilterIndex: Int,
		val stateValues: List<Pair<Int, MangaState>>,
		val typeFilterIndex: Int,
		val typeMappedValues: List<Pair<Int, ContentType>>,
		val ratingFilterIndex: Int,
		val ratingMappedValues: List<Pair<Int, ContentRating>>,
		val demographicFilterIndex: Int,
		val demographicValues: List<Pair<Int, Demographic>>,
		val yearFilterIndex: Int,
		val hasYearText: Boolean,
		val yearFromFilterIndex: Int,
		val yearToFilterIndex: Int,
		val authorFilterIndex: Int,
		val localeFilterIndex: Int,
		val localeValues: List<Pair<Int, java.util.Locale>>,
		val sortFilterIndex: Int,
		val sortMapping: List<Pair<Int, SortOrder>>,
		val detectedSortOrders: Set<SortOrder>,
	)

	private fun extractFilterInfo(): FilterInfo {
		val filterList = try {
			tachiyomiSource.getFilterList()
		} catch (_: Exception) {
			return emptyFilterInfo()
		}
		val tags = LinkedHashSet<MangaTag>()
		val genreGroups = mutableListOf<Filter.Group<*>>()
		var hasTriState = false
		var stateIdx = -1; var stateVals = emptyList<Pair<Int, MangaState>>()
		var typeIdx = -1; var typeMapped = emptyList<Pair<Int, ContentType>>()
		var ratingIdx = -1; var ratingMapped = emptyList<Pair<Int, ContentRating>>()
		var demoIdx = -1; var demoVals = emptyList<Pair<Int, Demographic>>()
		var yearIdx = -1; var hasYearText = false
		var yearFromIdx = -1; var yearToIdx = -1
		var authorIdx = -1
		var localeIdx = -1; var localeVals = emptyList<Pair<Int, java.util.Locale>>()
		var sortIdx = -1; var sortMap = emptyList<Pair<Int, SortOrder>>()
		val detectedOrders = mutableSetOf<SortOrder>()

		for ((index, filter) in filterList.withIndex()) {
			val name = filter.name.lowercase(Locale.ROOT)
			if (LANGUAGE_KEYWORDS.any { it in name }) continue

			when (filter) {
				is Filter.Sort -> {
					if (sortIdx < 0) {
						sortIdx = index
						val mapping = mutableListOf<Pair<Int, SortOrder>>()
						filter.values.forEachIndexed { i, v ->
							matchSortOrder(v)?.let { order ->
								mapping += i to order
								detectedOrders += order
							}
						}
						sortMap = mapping
					}
				}
				is Filter.Select<*> -> {
					val values = filter.values.mapIndexed { i, v -> i to v.toString() }
					when {
						stateIdx < 0 && STATE_KEYWORDS.any { it in name } -> {
							stateIdx = index
							stateVals = values.mapNotNull { (i, v) -> matchState(v)?.let { i to it } }
						}
						typeIdx < 0 && TYPE_KEYWORDS.any { it in name } -> {
							typeIdx = index
							typeMapped = values.mapNotNull { (i, v) -> matchContentType(v)?.let { i to it } }
						}
						ratingIdx < 0 && RATING_KEYWORDS.any { it in name } -> {
							ratingIdx = index
							ratingMapped = values.mapNotNull { (i, v) -> matchContentRating(v)?.let { i to it } }
						}
						demoIdx < 0 && DEMOGRAPHIC_KEYWORDS.any { it in name } -> {
							demoIdx = index
							demoVals = values.mapNotNull { (i, v) -> matchDemographic(v)?.let { i to it } }
						}
						yearIdx < 0 && YEAR_KEYWORDS.any { it in name } -> {
							yearIdx = index
						}
						localeIdx < 0 && ORIGINAL_LANG_KEYWORDS.any { it in name } -> {
							localeIdx = index
							localeVals = values.mapNotNull { (i, v) ->
								val tag = v.trim().takeIf { it.length in 2..5 } ?: return@mapNotNull null
								i to java.util.Locale.forLanguageTag(tag)
							}
						}
					}
				}
				is Filter.Text -> {
					val matched = when {
						!hasYearText && YEAR_KEYWORDS.any { it in name } -> {
							yearIdx = index; hasYearText = true; true
						}
						yearFromIdx < 0 && YEAR_FROM_KEYWORDS.any { it in name } -> {
							yearFromIdx = index; true
						}
						yearToIdx < 0 && YEAR_TO_KEYWORDS.any { it in name } -> {
							yearToIdx = index; true
						}
						authorIdx < 0 && AUTHOR_KEYWORDS.any { it in name } -> {
							authorIdx = index; true
						}
						else -> false
					}
					@Suppress("UNUSED_EXPRESSION")
					matched // suppress unused
				}
				is Filter.Group<*> -> {
					val children = filter.state
					if (children.isEmpty()) continue
					val isCheckBoxGroup = children.all { it is Filter.CheckBox }
					val isTriStateGroup = children.all { it is Filter.TriState }
					if (!isCheckBoxGroup && !isTriStateGroup) continue
					val isGenreLike = GENRE_GROUP_KEYWORDS.any { it in name } || children.size >= MIN_GENRE_GROUP_SIZE
					if (!isGenreLike) continue
					genreGroups += filter
					if (isTriStateGroup) hasTriState = true
					for (child in children) {
						val childName = when (child) {
							is Filter.CheckBox -> child.name
							is Filter.TriState -> child.name
							else -> continue
						}
						if (childName.isBlank()) continue
						tags += MangaTag(
							title = childName,
							key = childName.lowercase(Locale.ROOT),
							source = source,
						)
					}
				}
				else -> {}
			}
		}
		return FilterInfo(
			tags = tags, genreGroups = genreGroups, hasTriStateGenres = hasTriState,
			stateFilterIndex = stateIdx, stateValues = stateVals,
			typeFilterIndex = typeIdx, typeMappedValues = typeMapped,
			ratingFilterIndex = ratingIdx, ratingMappedValues = ratingMapped,
			demographicFilterIndex = demoIdx, demographicValues = demoVals,
			yearFilterIndex = yearIdx, hasYearText = hasYearText,
			yearFromFilterIndex = yearFromIdx, yearToFilterIndex = yearToIdx,
			authorFilterIndex = authorIdx,
			localeFilterIndex = localeIdx, localeValues = localeVals,
			sortFilterIndex = sortIdx, sortMapping = sortMap,
			detectedSortOrders = detectedOrders,
		)
	}

	private fun emptyFilterInfo() = FilterInfo(
		tags = emptySet(), genreGroups = emptyList(), hasTriStateGenres = false,
		stateFilterIndex = -1, stateValues = emptyList(),
		typeFilterIndex = -1, typeMappedValues = emptyList(),
		ratingFilterIndex = -1, ratingMappedValues = emptyList(),
		demographicFilterIndex = -1, demographicValues = emptyList(),
		yearFilterIndex = -1, hasYearText = false,
		yearFromFilterIndex = -1, yearToFilterIndex = -1,
		authorFilterIndex = -1,
		localeFilterIndex = -1, localeValues = emptyList(),
		sortFilterIndex = -1, sortMapping = emptyList(),
		detectedSortOrders = emptySet(),
	)

	private fun matchState(value: String): MangaState? {
		val v = value.lowercase(Locale.ROOT).trim()
		return when {
			v.contains("ongoing") || v.contains("publishing") || v.contains("releasing") -> MangaState.ONGOING
			v.contains("completed") || v.contains("finished") || v.contains("complete") -> MangaState.FINISHED
			v.contains("hiatus") || v.contains("paused") || v.contains("on hold") -> MangaState.PAUSED
			v.contains("cancelled") || v.contains("canceled") || v.contains("dropped") || v.contains("discontinued") -> MangaState.ABANDONED
			v.contains("upcoming") || v.contains("not yet") || v.contains("announced") -> MangaState.UPCOMING
			v.contains("licensed") -> MangaState.RESTRICTED
			else -> null
		}
	}

	private fun matchContentType(value: String): ContentType? {
		val v = value.lowercase(Locale.ROOT).trim()
		return when {
			v == "manga" || v.contains("japanese") -> ContentType.MANGA
			v == "manhwa" || v.contains("korean") -> ContentType.MANHWA
			v == "manhua" || v.contains("chinese") -> ContentType.MANHUA
			v.contains("comic") || v.contains("western") -> ContentType.COMICS
			v.contains("novel") || v.contains("light novel") -> ContentType.NOVEL
			v.contains("one-shot") || v.contains("oneshot") || v.contains("one shot") -> ContentType.ONE_SHOT
			v.contains("doujin") -> ContentType.DOUJINSHI
			else -> null
		}
	}

	private fun matchContentRating(value: String): ContentRating? {
		val v = value.lowercase(Locale.ROOT).trim()
		return when {
			v.contains("safe") || v.contains("everyone") || v.contains("all ages") || v == "g" || v == "pg" -> ContentRating.SAFE
			v.contains("suggestive") || v.contains("ecchi") || v.contains("teen") || v.contains("16+") || v == "pg-13" -> ContentRating.SUGGESTIVE
			v.contains("adult") || v.contains("explicit") || v.contains("mature") || v.contains("18+") || v.contains("nsfw") || v.contains("pornographic") || v == "r" || v == "r+" -> ContentRating.ADULT
			else -> null
		}
	}

	private fun matchDemographic(value: String): Demographic? {
		val v = value.lowercase(Locale.ROOT).trim()
		return when {
			v.contains("shounen") || v.contains("shonen") || v.contains("shōnen") -> Demographic.SHOUNEN
			v.contains("shoujo") || v.contains("shojo") || v.contains("shōjo") -> Demographic.SHOUJO
			v.contains("seinen") -> Demographic.SEINEN
			v.contains("josei") -> Demographic.JOSEI
			v.contains("kodomo") || v.contains("kids") -> Demographic.KODOMO
			else -> null
		}
	}

	private fun matchSortOrder(value: String): SortOrder? {
		val v = value.lowercase(Locale.ROOT).trim()
		return when {
			v == "latest" || v.contains("latest update") || v.contains("last update") -> SortOrder.UPDATED
			v == "newest" || v.contains("newest") || v.contains("recently added") || v.contains("new") -> SortOrder.NEWEST
			v.contains("popular") || v.contains("most view") || v.contains("trending") || v.contains("hot") -> SortOrder.POPULARITY
			v.contains("rating") || v.contains("top rated") || v.contains("score") || v.contains("best") -> SortOrder.RATING
			v.contains("a-z") || v.contains("alphabetical") || v.contains("title") || v.contains("name") -> SortOrder.ALPHABETICAL
			v.contains("z-a") -> SortOrder.ALPHABETICAL_DESC
			v.contains("relevance") || v.contains("relevant") -> SortOrder.RELEVANCE
			v.contains("oldest") -> SortOrder.NEWEST_ASC
			v.contains("year") -> SortOrder.NEWEST
			else -> null
		}
	}

	companion object {
		private const val DEFAULT_PAGE_SIZE = 30
		private const val DEFAULT_UA =
			"Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"
		private val LATEST_SORT_ORDERS = setOf(
			SortOrder.UPDATED, SortOrder.UPDATED_ASC,
			SortOrder.NEWEST, SortOrder.NEWEST_ASC,
			SortOrder.ADDED, SortOrder.ADDED_ASC,
		)
		private val CHAPTER_NUMBER_REGEX = Regex("""(?i)(?:ch(?:apter)?|ep(?:isode)?|#)?\s*(\d+(?:\.\d+)?)""")
		private val VOLUME_REGEX = Regex("""(?i)(?:vol(?:ume)?)\s*(\d+)""")
		private const val MIN_ORDER_COMPARISONS = 3
		private const val ORDER_DOMINANCE_FACTOR = 1.2f
		private const val CHAPTER_NUMBER_EPSILON = 0.0001f
		private const val MIN_GENRE_GROUP_SIZE = 5
		private val GENRE_GROUP_KEYWORDS = listOf("genre", "tag", "category", "themes", "demographic")
		private val CAPTCHA_KEYWORDS = listOf("cloudflare", "turnstile", "captcha", "verify you are human", "just a moment", "webview")
		private val STATE_KEYWORDS = listOf("status", "state", "publication status", "publication")
		private val TYPE_KEYWORDS = listOf("type", "format", "comic type", "manga type")
		private val RATING_KEYWORDS = listOf("content rating", "rating", "maturity", "age rating")
		private val DEMOGRAPHIC_KEYWORDS = listOf("demographic", "target audience", "audience", "reader type")
		private val YEAR_KEYWORDS = listOf("year", "release year", "start year")
		private val YEAR_FROM_KEYWORDS = listOf("year from", "from year", "min year", "year min", "start year")
		private val YEAR_TO_KEYWORDS = listOf("year to", "to year", "max year", "year max", "end year")
		private val AUTHOR_KEYWORDS = listOf("author", "artist", "creator", "writer")
		private val ORIGINAL_LANG_KEYWORDS = listOf("original language", "original lang", "origin")
		private val LANGUAGE_KEYWORDS = listOf("language", "lang", "translation", "translated")
	}
}

