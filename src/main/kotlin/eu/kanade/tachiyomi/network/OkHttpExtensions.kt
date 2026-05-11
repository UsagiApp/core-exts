package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import rx.Observable
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public fun Call.asObservable(): Observable<Response> = Observable.fromCallable { execute() }

public fun Call.asObservableSuccess(): Observable<Response> = Observable.fromCallable {
    val response = execute()
    if (!response.isSuccessful) {
        response.close()
        throw Exception("HTTP error ${response.code}")
    }
    response
}

public suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) {
                cont.resumeWithException(e)
            }
        }
    })
    cont.invokeOnCancellation { cancel() }
}

public suspend fun Call.awaitSuccess(): Response {
    val response = await()
    if (!response.isSuccessful) {
        response.close()
        throw Exception("HTTP error ${response.code}")
    }
    return response
}
