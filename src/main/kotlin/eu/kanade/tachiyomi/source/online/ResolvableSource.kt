package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga

@Suppress("unused")
interface ResolvableSource : Source {
	fun canResolveUri(uri: String): Boolean

	suspend fun getManga(uri: String): SManga?
}

