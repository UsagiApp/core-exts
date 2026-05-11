package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

public fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    return addInterceptor(RateLimitInterceptor(permits, period, unit))
}

public fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    return addInterceptor(HostRateLimitInterceptor(httpUrl, permits, period, unit))
}

private class RateLimitInterceptor(
    permits: Int,
    private val period: Long,
    private val unit: TimeUnit,
) : Interceptor {
    private val semaphore = Semaphore(permits)

    override fun intercept(chain: Interceptor.Chain): Response {
        semaphore.acquire()
        try {
            val response = chain.proceed(chain.request())
            // Release after period
            Thread {
                try { Thread.sleep(unit.toMillis(period)) } catch (_: Exception) {}
                semaphore.release()
            }.start()
            return response
        } catch (e: Exception) {
            semaphore.release()
            throw e
        }
    }
}

private class HostRateLimitInterceptor(
    private val httpUrl: HttpUrl,
    permits: Int,
    private val period: Long,
    private val unit: TimeUnit,
) : Interceptor {
    private val semaphore = Semaphore(permits)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host == httpUrl.host) {
            semaphore.acquire()
            try {
                val response = chain.proceed(request)
                Thread {
                    try { Thread.sleep(unit.toMillis(period)) } catch (_: Exception) {}
                    semaphore.release()
                }.start()
                return response
            } catch (e: Exception) {
                semaphore.release()
                throw e
            }
        }
        return chain.proceed(request)
    }
}
