package keiyoushi.lib.i18n

import org.jetbrains.annotations.PropertyKey
import java.io.InputStreamReader
import java.text.Collator
import java.util.Locale
import java.util.PropertyResourceBundle

/**
 * A simple wrapper to make internationalization easier to use in sources.
 */
class Intl(
    language: String,
    availableLanguages: Set<String>,
    private val baseLanguage: String,
    private val classLoader: ClassLoader,
    private val createMessageFileName: (String) -> String = { createDefaultMessageFileName(it) },
) {

    val chosenLanguage: String = when (language) {
        in availableLanguages -> language
        else -> baseLanguage
    }

    private val locale: Locale = Locale.forLanguageTag(chosenLanguage)

    val collator: Collator = Collator.getInstance(locale)

    private val baseBundle: PropertyResourceBundle by lazy { createBundle(baseLanguage) }

    private val bundle: PropertyResourceBundle by lazy {
        if (chosenLanguage == baseLanguage) baseBundle else createBundle(chosenLanguage)
    }

    /**
     * Returns the string from the message file.
     */
    @Suppress("InvalidBundleOrProperty")
    operator fun get(@PropertyKey(resourceBundle = "i18n.messages") key: String): String = when {
        bundle.containsKey(key) -> bundle.getString(key)
        baseBundle.containsKey(key) -> baseBundle.getString(key)
        else -> "[$key]"
    }

    /**
     * Uses the string as a format string.
     */
    @Suppress("InvalidBundleOrProperty")
    fun format(@PropertyKey(resourceBundle = "i18n.messages") key: String, vararg args: Any?) = get(key).format(locale, *args)

    fun languageDisplayName(localeCode: String): String = Locale.forLanguageTag(localeCode)
        .getDisplayName(locale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    private fun createBundle(lang: String): PropertyResourceBundle {
        val fileName = createMessageFileName(lang)
        val fileContent = classLoader.getResourceAsStream(fileName) ?: throw IllegalStateException("Resource not found: $fileName")

        return PropertyResourceBundle(InputStreamReader(fileContent, "UTF-8"))
    }

    companion object {
        fun createDefaultMessageFileName(lang: String): String {
            val langSnakeCase = lang.replace("-", "_").lowercase()

            return "assets/i18n/messages_$langSnakeCase.properties"
        }
    }
}
