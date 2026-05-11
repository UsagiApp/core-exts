package eu.kanade.tachiyomi.network

import android.content.Context

public class JavaScriptEngine(context: Any?) {
    @Suppress("UNCHECKED_CAST")
    public suspend fun <T> evaluate(script: String): T {
        throw UnsupportedOperationException("JavaScript engine not available in JVM shim")
    }
}
