package eu.kanade.tachiyomi

import android.os.Build

object AppInfo {
	fun getVersionCode(): Int {
		val context = RuntimeContext.requireContext()
		val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
			context.packageManager.getPackageInfo(
				context.packageName,
				android.content.pm.PackageManager.PackageInfoFlags.of(0L),
			)
		} else {
			@Suppress("DEPRECATION")
			context.packageManager.getPackageInfo(context.packageName, 0)
		}
		return if (Build.VERSION.SDK_INT >= 28) {
			packageInfo.longVersionCode.toInt()
		} else {
			@Suppress("DEPRECATION")
			packageInfo.versionCode
		}
	}

	fun getVersionName(): String {
		val context = RuntimeContext.requireContext()
		val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
			context.packageManager.getPackageInfo(
				context.packageName,
				android.content.pm.PackageManager.PackageInfoFlags.of(0L),
			)
		} else {
			@Suppress("DEPRECATION")
			context.packageManager.getPackageInfo(context.packageName, 0)
		}
		return packageInfo.versionName.orEmpty()
	}

	fun getSupportedImageMimeTypes(): List<String> {
		return listOf(
			"image/jpeg",
			"image/png",
			"image/webp",
			"image/gif",
		)
	}
}
