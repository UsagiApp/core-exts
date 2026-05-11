@file:Suppress("unused")

package rx

/**
 * Minimal RxJava Observable shim for Tachiyomi extension compatibility.
 * Extensions don't directly subscribe to Observables - the host app does.
 * This shim provides just enough API surface for compilation.
 */
public class Observable<T> private constructor(private val supplier: () -> T) {

    public fun <R> map(mapper: (T) -> R): Observable<R> = Observable { mapper(supplier()) }

    public fun toBlocking(): BlockingObservable<T> = BlockingObservable(supplier)

    public companion object {
        @JvmStatic
        public fun <T> just(item: T): Observable<T> = Observable { item }

        @JvmStatic
        public fun <T> fromCallable(callable: () -> T): Observable<T> = Observable(callable)

        @JvmStatic
        public fun <T> defer(supplier: () -> Observable<T>): Observable<T> = Observable { supplier().supplier() }

        @JvmStatic
        public fun <T> empty(): Observable<T> = Observable { throw NoSuchElementException("Empty observable") }

        @JvmStatic
        public fun <T> error(e: Throwable): Observable<T> = Observable { throw e }
    }

    public class BlockingObservable<T>(private val supplier: () -> T) {
        public fun first(): T = supplier()
        public fun single(): T = supplier()
    }
}
