package org.koitharu.kotatsu.parsers.model

public interface MangaSource {

	public val name: String
	public val title: String
	public val locale: String
	public val contentType: ContentType
	public val isBroken: Boolean
}
