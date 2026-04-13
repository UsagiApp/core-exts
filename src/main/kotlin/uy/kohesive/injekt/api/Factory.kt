package uy.kohesive.injekt.api

@Suppress("UNCHECKED_CAST")
inline fun <reified T> InjektFactory.get(): T {
	return getInstance(fullType<T>()) as T
}

@Suppress("UNCHECKED_CAST")
inline fun <reified T> InjektFactory.getOrNull(): T? {
	return runCatching { getInstance(fullType<T>()) as T }.getOrNull()
}
