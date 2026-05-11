package eu.kanade.tachiyomi.source.model

public data class FilterList(public val list: List<Filter<*>>) : List<Filter<*>> by list {
    public constructor(vararg fs: Filter<*>) : this(if (fs.isNotEmpty()) fs.asList() else emptyList())
}
