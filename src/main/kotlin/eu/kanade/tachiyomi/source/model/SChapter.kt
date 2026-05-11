package eu.kanade.tachiyomi.source.model

public interface SChapter {
    public var url: String
    public var name: String
    public var date_upload: Long
    public var chapter_number: Float
    public var scanlator: String?

    public companion object {
        public fun create(): SChapter = SChapterImpl()
    }
}

public class SChapterImpl : SChapter {
    override var url: String = ""
    override var name: String = ""
    override var date_upload: Long = 0L
    override var chapter_number: Float = -1f
    override var scanlator: String? = null
}
