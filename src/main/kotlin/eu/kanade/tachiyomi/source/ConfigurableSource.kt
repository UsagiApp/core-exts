package eu.kanade.tachiyomi.source

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.RuntimeContext

interface ConfigurableSource : Source {
	fun getSourcePreferences(): SharedPreferences {
		val ctx = RuntimeContext.requireContext()
		return ctx.getSharedPreferences(preferenceKey(), Context.MODE_PRIVATE)
	}

	fun setupPreferenceScreen(screen: PreferenceScreen)
}

fun ConfigurableSource.preferenceKey(): String = "source_$id"

fun ConfigurableSource.sourcePreferences(): SharedPreferences {
	return RuntimeContext.requireContext().getSharedPreferences(preferenceKey(), Context.MODE_PRIVATE)
}

fun sourcePreferences(key: String): SharedPreferences {
	return RuntimeContext.requireContext().getSharedPreferences(key, Context.MODE_PRIVATE)
}
