package eu.kanade.tachiyomi

import android.content.Context
import okhttp3.OkHttpClient

object RuntimeContext {
	@Volatile
	private var appContext: Context? = null
	@Volatile
	private var okHttpClient: OkHttpClient? = null
	@Volatile
	private var cloudflareClient: OkHttpClient? = null
	@Volatile
	private var userAgentProvider: (() -> String)? = null
	@Volatile
	private var webSessionSync: (suspend (String) -> Unit)? = null

	fun init(context: Context) {
		appContext = context.applicationContext
	}

	fun installNetwork(
		client: OkHttpClient,
		cloudflare: OkHttpClient = client,
		userAgent: (() -> String)? = null,
		webSessionSync: (suspend (String) -> Unit)? = null,
	) {
		okHttpClient = client
		cloudflareClient = cloudflare
		userAgentProvider = userAgent
		this.webSessionSync = webSessionSync
	}

	fun requireContext(): Context {
		return appContext ?: throw IllegalStateException("Tachiyomi runtime is not initialized")
	}

	fun httpClient(): OkHttpClient {
		return okHttpClient ?: OkHttpClient.Builder().build()
	}

	fun cloudflareClient(): OkHttpClient {
		return cloudflareClient ?: httpClient()
	}

	fun defaultUserAgent(): String {
		return userAgentProvider?.invoke().orEmpty()
	}

	suspend fun syncWebSession(url: String) {
		webSessionSync?.invoke(url)
	}
}
