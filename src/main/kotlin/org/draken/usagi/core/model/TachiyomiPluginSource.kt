package org.draken.usagi.core.model

import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource

data class TachiyomiPluginSource(
	override val name: String,
	override val title: String,
	override val locale: String,
	val sourceId: Long,
	val extensionPackageName: String,
	val extensionClassName: String,
	val pluginFileName: String,
	val isNsfwSource: Boolean,
	val repoOwnerTag: String = "",
) : MangaSource {
	override val contentType: ContentType
		get() = if (isNsfwSource) ContentType.HENTAI else ContentType.MANGA
}
