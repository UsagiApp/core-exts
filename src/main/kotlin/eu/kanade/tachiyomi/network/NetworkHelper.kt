package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.RuntimeContext
import okhttp3.OkHttpClient

class NetworkHelper(
	@Suppress("UNUSED_PARAMETER")
	context: Context = RuntimeContext.requireContext(),
) {
	val client: OkHttpClient by lazy {
		RuntimeContext.httpClient()
	}

	val cloudflareClient: OkHttpClient by lazy {
		RuntimeContext.cloudflareClient()
	}

	fun defaultUserAgentProvider(): String {
		return RuntimeContext.defaultUserAgent().ifBlank {
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
		}
	}
}

