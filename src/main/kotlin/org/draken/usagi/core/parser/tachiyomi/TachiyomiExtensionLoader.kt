package org.draken.usagi.core.parser.tachiyomi

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dalvik.system.DexClassLoader
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File

data class TachiyomiExtensionMeta(
	val packageName: String,
	val className: String,
	val isNsfw: Boolean,
)

data class TachiyomiExtensionLoadResult(
	val classLoader: ClassLoader,
	val extensionMeta: TachiyomiExtensionMeta,
	val sources: List<CatalogueSource>,
)

object TachiyomiExtensionLoader {

	fun loadFromApk(
		context: Context,
		apk: File,
		optimizedDirectory: String,
		parent: ClassLoader,
	): TachiyomiExtensionLoadResult? {
		val meta = readExtensionMeta(context, apk) ?: return null
		val classLoader = DexClassLoader(apk.absolutePath, optimizedDirectory, null, parent)
		val sources = runCatching {
			createSources(classLoader, meta.className)
		}.getOrElse { emptyList() }
		if (sources.isEmpty()) return null
		return TachiyomiExtensionLoadResult(
			classLoader = classLoader,
			extensionMeta = meta,
			sources = sources,
		)
	}

	fun readExtensionMeta(context: Context, apk: File): TachiyomiExtensionMeta? {
		val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
			context.packageManager.getPackageArchiveInfo(
				apk.absolutePath,
				PackageManager.PackageInfoFlags.of((PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS).toLong()),
			)
		} else {
			@Suppress("DEPRECATION")
			context.packageManager.getPackageArchiveInfo(
				apk.absolutePath,
				PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS,
			)
		}
		val appInfo = packageInfo?.applicationInfo ?: return null
		val metaData = appInfo.metaData ?: return null
		var className = metaData.getString(METADATA_SOURCE_CLASS).orEmpty().trim()
		if (className.isBlank()) return null
		if (className.startsWith('.')) {
			className = packageInfo.packageName + className
		}
		val packageName = packageInfo.packageName.ifBlank { apk.nameWithoutExtension }
		val isNsfw = parseNsfw(metaData.get(METADATA_NSFW))
		return TachiyomiExtensionMeta(
			packageName = packageName,
			className = className,
			isNsfw = isNsfw,
		)
	}

	fun createSources(classLoader: ClassLoader, className: String): List<CatalogueSource> {
		val clazz = classLoader.loadClass(className)
        val allSources = when (val instance = clazz.getDeclaredConstructor().newInstance()) {
			is Source -> listOf(instance)
			is SourceFactory -> instance.createSources()
			else -> emptyList()
		}
		return allSources.filterIsInstance<CatalogueSource>()
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

	private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
	private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
	private const val TAG = "TachiyomiExtLoader"
}
