package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.HttpStatusException
import rx.Observable
import rx.Producer
import rx.Subscription
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun Call.asObservable(): Observable<Response> {
	return Observable.unsafeCreate { subscriber ->
		val call = clone()
		val requestArbiter = object : AtomicBoolean(), Producer, Subscription {
			override fun request(n: Long) {
				if (n == 0L || !compareAndSet(false, true)) return
				try {
					val response = call.execute()
					if (!subscriber.isUnsubscribed) {
						subscriber.onNext(response)
						subscriber.onCompleted()
					}
				} catch (e: Exception) {
					if (!subscriber.isUnsubscribed) {
						subscriber.onError(e)
					}
				}
			}

			override fun unsubscribe() {
				call.cancel()
			}

			override fun isUnsubscribed(): Boolean = call.isCanceled()
		}
		subscriber.add(requestArbiter)
		subscriber.setProducer(requestArbiter)
	}
}

fun Call.asObservableSuccess(): Observable<Response> = asObservable().doOnNext { response ->
	if (!response.isSuccessful) {
		val url = response.request.url.toString()
		response.close()
		throw HttpException(response.code, url)
	}
}

private suspend fun Call.await(callStack: Array<StackTraceElement>): Response {
	return suspendCancellableCoroutine { continuation ->
		val callback = object : Callback {
			override fun onResponse(call: Call, response: Response) {
				continuation.resume(response) { _, resource, _ ->
					response.body.close()
					resource.close()
				}
			}

			override fun onFailure(call: Call, e: IOException) {
				if (continuation.isCancelled) return
				val exception = IOException(e.message, e).apply { stackTrace = callStack }
				continuation.resumeWithException(exception)
			}
		}

		enqueue(callback)
		continuation.invokeOnCancellation {
			runCatching { cancel() }
		}
	}
}

suspend fun Call.await(): Response {
	val callStack = Exception().stackTrace.run { copyOfRange(1, size) }
	return await(callStack)
}

suspend fun Call.awaitSuccess(): Response {
	val callStack = Exception().stackTrace.run { copyOfRange(1, size) }
	val response = await(callStack)
	if (!response.isSuccessful) {
		val url = response.request.url.toString()
		response.close()
		throw HttpException(response.code, url).apply { stackTrace = callStack }
	}
	return response
}

fun OkHttpClient.newCachelessCallWithProgress(
	request: Request,
	listener: ProgressListener,
): Call {
	val progressClient = newBuilder()
		.cache(null)
		.addNetworkInterceptor { chain ->
			val originalResponse = chain.proceed(chain.request())
			originalResponse.newBuilder()
				.body(ProgressResponseBody(originalResponse.body, listener))
				.build()
		}.build()
	return progressClient.newCall(request)
}

class HttpException(
	code: Int,
	url: String = "",
) : HttpStatusException("HTTP error $code", code, url)
