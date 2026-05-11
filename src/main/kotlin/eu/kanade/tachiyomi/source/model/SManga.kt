package eu.kanade.tachiyomi.source.model

public interface SManga {
    public var url: String
    public var title: String
    public var artist: String?
    public var author: String?
    public var description: String?
    public var genre: String?
    public var status: Int
    public var thumbnail_url: String?
    public var update_strategy: UpdateStrategy
    public var initialized: Boolean

    public companion object {
        public const val UNKNOWN: Int = 0
        public const val ONGOING: Int = 1
        public const val COMPLETED: Int = 2
        public const val LICENSED: Int = 3
        public const val PUBLISHING_FINISHED: Int = 4
        public const val CANCELLED: Int = 5
        public const val ON_HIATUS: Int = 6

        public fun create(): SManga = SMangaImpl()
    }
}

public class SMangaImpl : SManga {
    override var url: String = ""
    override var title: String = ""
    override var artist: String? = null
    override var author: String? = null
    override var description: String? = null
    override var genre: String? = null
    override var status: Int = SManga.UNKNOWN
    override var thumbnail_url: String? = null
    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    override var initialized: Boolean = false
}
