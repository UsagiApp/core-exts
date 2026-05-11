package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable

public interface CatalogueSource : Source {
    public val lang: String
    public val supportsLatest: Boolean
    public fun fetchPopularManga(page: Int): Observable<MangasPage>
    public fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage>
    public fun fetchLatestUpdates(page: Int): Observable<MangasPage>
    public fun getFilterList(): FilterList

    public val supportsRelatedMangas: Boolean get() = false
    public val disableRelatedMangasBySearch: Boolean get() = false
    public val disableRelatedMangas: Boolean get() = false
    public suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = throw UnsupportedOperationException("Unsupported!")
}
