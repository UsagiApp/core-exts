package eu.kanade.tachiyomi.source.model

public class Page(
    public val index: Int,
    public val url: String = "",
    public var imageUrl: String? = null,
    public var uri: android.net.Uri? = null
)
