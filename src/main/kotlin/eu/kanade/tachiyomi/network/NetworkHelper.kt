package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

/**
 * NetworkHelper that can be initialized with the host app's OkHttpClient.
 * The adapter sets the client before any source methods are called.
 */
public class NetworkHelper private constructor() {

    public var client: OkHttpClient = OkHttpClient.Builder().build()
        internal set

    public var cloudflareClient: OkHttpClient = client
        internal set

    public companion object {
        public val instance: NetworkHelper = NetworkHelper()

        /**
         * Called by TachiyomiSourceAdapter to inject the proper OkHttpClient
         * from MangaLoaderContext before any extension source methods run.
         */
        public fun setClient(httpClient: OkHttpClient) {
            instance.client = httpClient
            instance.cloudflareClient = httpClient
        }
    }
}
