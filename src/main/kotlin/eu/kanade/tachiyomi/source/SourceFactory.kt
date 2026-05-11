package eu.kanade.tachiyomi.source

public interface SourceFactory {
    public fun createSources(): List<Source>
}
