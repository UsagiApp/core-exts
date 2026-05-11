package eu.kanade.tachiyomi.source

import androidx.preference.PreferenceScreen

public interface ConfigurableSource {
    public fun setupPreferenceScreen(screen: PreferenceScreen)
}
