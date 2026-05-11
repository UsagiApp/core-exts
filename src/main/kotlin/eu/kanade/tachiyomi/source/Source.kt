package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable

public interface Source {
    public val id: Long
    public val name: String
    public fun fetchMangaDetails(manga: SManga): Observable<SManga>
    public fun fetchChapterList(manga: SManga): Observable<List<SChapter>>
    public fun fetchPageList(chapter: SChapter): Observable<List<Page>>
}
