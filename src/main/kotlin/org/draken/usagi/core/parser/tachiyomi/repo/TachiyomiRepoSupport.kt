package org.draken.usagi.core.parser.tachiyomi.repo

import android.content.Context
import androidx.core.content.edit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class TachiyomiRepository(
	val ownerTag: String,
	val indexUrl: String,
)

data class TachiyomiRepoSource(
	val sourceName: String,
	val sourceLang: String,
	val sourceId: String,
	val sourceBaseUrl: String? = null,
	val extensionName: String,
	val extensionPackageName: String,
	val extensionVersion: String?,
	val pluginFileName: String,
	val repoOwnerTag: String,
	val repoUrl: String,
	val downloadUrl: String,
	val isNsfwSource: Boolean,
) {
	val displayName: String
		get() = buildString {
			append(sourceName)
			if (sourceLang.isNotBlank()) {
				append(" (").append(sourceLang).append(')')
			}
			append(" • ").append(extensionName.removePrefix("Tachiyomi: "))
		}

	val stableApkFileName: String
		get() = TachiyomiRepoIndex.stableApkFileNameForPackage(extensionPackageName)

	val key: String
		get() = "$extensionPackageName:$sourceId"
}

object TachiyomiRepoIndex {

	fun normalizeRepoUrl(input: String): String? {
		val url = input.trim().toHttpUrlOrNull() ?: return null
		val path = url.encodedPath
		if (path.endsWith(".json", ignoreCase = true)) {
			return url.toString()
		}
		if (path.endsWith("/repo") || path.endsWith("/repo/")) {
			return url.newBuilder().addPathSegment("index.min.json").build().toString()
		}
		return null
	}

	fun ownerTagFromIndexUrl(indexUrl: String): String {
		val url = indexUrl.toHttpUrlOrNull()
		if (url != null) {
			if (url.host.equals("raw.githubusercontent.com", ignoreCase = true)) {
				val owner = url.pathSegments.getOrNull(0).orEmpty()
				if (owner.isNotBlank()) {
					return normalizeOwnerTag(owner)
				}
			}
			if (url.host.contains("github", ignoreCase = true)) {
				val owner = url.pathSegments.getOrNull(0).orEmpty()
				if (owner.isNotBlank()) {
					return normalizeOwnerTag(owner)
				}
			}
			val hostPart = url.host.substringBefore('.').ifBlank { "repo" }
			return normalizeOwnerTag(hostPart)
		}
		return normalizeOwnerTag(indexUrl)
	}

	fun parseIndex(indexUrl: String, body: String): List<TachiyomiRepoSource> {
		if (body.isBlank()) return emptyList()
		val repoRoot = indexUrl.substringBeforeLast('/', "") + "/"
		if (repoRoot == "/") return emptyList()
		val ownerTag = ownerTagFromIndexUrl(indexUrl)
		val array = JSONArray(body)
		val result = ArrayList<TachiyomiRepoSource>(array.length())
		for (i in 0 until array.length()) {
			val extension = array.optJSONObject(i) ?: continue
			val extensionName = extension.optString("name").ifBlank { continue }
			val packageName = extension.optString("pkg").ifBlank { continue }
			val apkName = extension.optString("apk").ifBlank { continue }
			val extensionLang = extension.optString("lang")
			val version = extension.optString("version").ifBlank { null }
			val isNsfw = parseNsfw(extension.opt("nsfw"))
			val downloadUrl = "$repoRoot" + "apk/$apkName"

			val sources = extension.optJSONArray("sources")
			if (sources == null || sources.length() == 0) {
				val sourceName = extensionName.removePrefix("Tachiyomi: ").ifBlank { packageName }
				val sourceLang = extensionLang.ifBlank { "all" }
				result += TachiyomiRepoSource(
					sourceName = sourceName,
					sourceLang = sourceLang,
					sourceId = "$packageName:$sourceName:$sourceLang",
					sourceBaseUrl = extension.optString("baseUrl").ifBlank { null },
					extensionName = extensionName,
					extensionPackageName = packageName,
					extensionVersion = version,
					pluginFileName = apkName,
					repoOwnerTag = ownerTag,
					repoUrl = indexUrl,
					downloadUrl = downloadUrl,
					isNsfwSource = isNsfw,
				)
				continue
			}

			for (j in 0 until sources.length()) {
				val source = sources.optJSONObject(j) ?: continue
				val sourceName = source.optString("name").ifBlank {
					extensionName.removePrefix("Tachiyomi: ")
				}
				val sourceLang = source.optString("lang").ifBlank { extensionLang.ifBlank { "all" } }
				val sourceId = source.optString("id").ifBlank { "$packageName:$sourceName:$sourceLang" }
				result += TachiyomiRepoSource(
					sourceName = sourceName,
					sourceLang = sourceLang,
					sourceId = sourceId,
					sourceBaseUrl = source.optString("baseUrl").ifBlank { null },
					extensionName = extensionName,
					extensionPackageName = packageName,
					extensionVersion = version,
					pluginFileName = apkName,
					repoOwnerTag = ownerTag,
					repoUrl = indexUrl,
					downloadUrl = downloadUrl,
					isNsfwSource = isNsfw,
				)
			}
		}
		return result
			.distinctBy { it.key }
			.sortedBy { it.displayName.lowercase(Locale.ROOT) }
	}

	fun stableApkFileNameForPackage(packageName: String): String {
		val normalized = buildString(packageName.length) {
			for (ch in packageName.lowercase(Locale.ROOT)) {
				append(if (ch.isLetterOrDigit()) ch else '_')
			}
		}.trim('_')
		val suffix = if (normalized.isBlank()) "tachiyomi_source" else normalized
		return "tachiyomi_${suffix}.apk"
	}

	private fun parseNsfw(value: Any?): Boolean {
		return when (value) {
			is Boolean -> value
			is Int -> value == 1
			is Long -> value == 1L
			is String -> value == "1" || value.equals("true", ignoreCase = true)
			else -> false
		}
	}

	private fun normalizeOwnerTag(owner: String): String {
		val clean = owner.trim().removePrefix("@").ifBlank { "repo" }
		return "@${clean.lowercase(Locale.ROOT)}"
	}
}

object TachiyomiRepoStore {

	data class InstalledPluginMeta(
		val ownerTag: String,
		val indexUrl: String,
		val extensionPackageName: String,
	)

	fun listRepositories(context: Context): List<TachiyomiRepository> {
		val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		val raw = prefs.getString(KEY_REPOSITORIES, null).orEmpty()
		if (raw.isBlank()) return emptyList()
		return runCatching {
			val array = JSONArray(raw)
			val out = ArrayList<TachiyomiRepository>(array.length())
			for (i in 0 until array.length()) {
				val obj = array.optJSONObject(i) ?: continue
				val ownerTag = obj.optString(JSON_OWNER_TAG)
				val indexUrl = obj.optString(JSON_INDEX_URL)
				if (ownerTag.isBlank() || indexUrl.isBlank()) continue
				out += TachiyomiRepository(ownerTag = ownerTag, indexUrl = indexUrl)
			}
			out
		}.getOrElse { emptyList() }
			.distinctBy { it.ownerTag.lowercase(Locale.ROOT) }
			.sortedBy { it.ownerTag.lowercase(Locale.ROOT) }
	}

	fun saveRepository(context: Context, repository: TachiyomiRepository): Boolean {
		val normalizedOwner = normalizeOwnerTag(repository.ownerTag)
		val repo = repository.copy(ownerTag = normalizedOwner)
		val current = listRepositories(context).toMutableList()
		val index = current.indexOfFirst { it.ownerTag.equals(normalizedOwner, ignoreCase = true) }
		if (index >= 0) {
			if (current[index].indexUrl == repo.indexUrl) return true
			current[index] = repo
		} else {
			current += repo
		}
		return writeRepositories(context, current)
	}

	fun removeRepository(context: Context, ownerTag: String): Boolean {
		val normalizedOwner = normalizeOwnerTag(ownerTag)
		val current = listRepositories(context).toMutableList()
		if (!current.removeAll { it.ownerTag.equals(normalizedOwner, ignoreCase = true) }) return false
		return writeRepositories(context, current)
	}

	fun getInstalledPluginMeta(context: Context, pluginFileName: String): InstalledPluginMeta? {
		val meta = readInstalledMeta(context)
		return meta[pluginFileName]
	}

	fun saveInstalledPluginMeta(
		context: Context,
		pluginFileName: String,
		ownerTag: String,
		indexUrl: String,
		extensionPackageName: String,
	) {
		val meta = readInstalledMeta(context)
		meta[pluginFileName] = InstalledPluginMeta(
			ownerTag = normalizeOwnerTag(ownerTag),
			indexUrl = indexUrl,
			extensionPackageName = extensionPackageName,
		)
		writeInstalledMeta(context, meta)
	}

	fun removeInstalledPluginMeta(context: Context, pluginFileName: String) {
		val meta = readInstalledMeta(context)
		if (meta.remove(pluginFileName) != null) {
			writeInstalledMeta(context, meta)
		}
	}

	fun findInstalledPluginFilesByPackage(context: Context, extensionPackageName: String): List<String> {
		return readInstalledMeta(context)
			.filterValues { it.extensionPackageName == extensionPackageName }
			.keys
			.toList()
	}

	fun findInstalledPluginFilesByOwner(context: Context, ownerTag: String): List<String> {
		val normalizedOwner = normalizeOwnerTag(ownerTag)
		return readInstalledMeta(context)
			.filterValues { it.ownerTag.equals(normalizedOwner, ignoreCase = true) }
			.keys
			.toList()
	}

	fun cleanupInstalledPluginMeta(context: Context, installedFiles: Set<String>) {
		val meta = readInstalledMeta(context)
		if (meta.keys.retainAll(installedFiles)) {
			writeInstalledMeta(context, meta)
		}
	}

	private fun writeRepositories(context: Context, repositories: List<TachiyomiRepository>): Boolean {
		val array = JSONArray()
		repositories.forEach { repo ->
			array.put(
				JSONObject()
					.put(JSON_OWNER_TAG, normalizeOwnerTag(repo.ownerTag))
					.put(JSON_INDEX_URL, repo.indexUrl),
			)
		}
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
			putString(KEY_REPOSITORIES, array.toString())
		}
		return true
	}

	private fun readInstalledMeta(context: Context): MutableMap<String, InstalledPluginMeta> {
		val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		val raw = prefs.getString(KEY_INSTALLED_META, null).orEmpty()
		if (raw.isBlank()) return LinkedHashMap()
		return runCatching {
			val json = JSONObject(raw)
			val out = LinkedHashMap<String, InstalledPluginMeta>(json.length())
			val keys = json.keys()
			while (keys.hasNext()) {
				val key = keys.next()
				val obj = json.optJSONObject(key) ?: continue
				val ownerTag = obj.optString(JSON_OWNER_TAG)
				val indexUrl = obj.optString(JSON_INDEX_URL)
				val extensionPackageName = obj.optString(JSON_EXTENSION_PACKAGE)
				if (ownerTag.isBlank() || indexUrl.isBlank() || extensionPackageName.isBlank()) continue
				out[key] = InstalledPluginMeta(
					ownerTag = normalizeOwnerTag(ownerTag),
					indexUrl = indexUrl,
					extensionPackageName = extensionPackageName,
				)
			}
			out
		}.getOrElse { LinkedHashMap() }
	}

	private fun writeInstalledMeta(context: Context, meta: Map<String, InstalledPluginMeta>) {
		val json = JSONObject()
		meta.forEach { (pluginFileName, value) ->
			json.put(
				pluginFileName,
				JSONObject()
					.put(JSON_OWNER_TAG, normalizeOwnerTag(value.ownerTag))
					.put(JSON_INDEX_URL, value.indexUrl)
					.put(JSON_EXTENSION_PACKAGE, value.extensionPackageName),
			)
		}
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
			putString(KEY_INSTALLED_META, json.toString())
		}
	}

	private fun normalizeOwnerTag(ownerTag: String): String {
		val clean = ownerTag.trim().removePrefix("@").ifBlank { "repo" }
		return "@${clean.lowercase(Locale.ROOT)}"
	}

	private const val PREFS_NAME = "tachiyomi_repo_support"
	private const val KEY_REPOSITORIES = "repositories"
	private const val KEY_INSTALLED_META = "installed_meta"
	private const val JSON_OWNER_TAG = "owner_tag"
	private const val JSON_INDEX_URL = "index_url"
	private const val JSON_EXTENSION_PACKAGE = "extension_package"
}
