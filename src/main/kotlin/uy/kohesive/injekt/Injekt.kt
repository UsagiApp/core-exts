package uy.kohesive.injekt

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.RuntimeContext
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import uy.kohesive.injekt.api.FullTypeReference
import uy.kohesive.injekt.api.InjektFactory
import uy.kohesive.injekt.api.InjektScope
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import kotlin.LazyThreadSafetyMode

private class RuntimeInjektScope : InjektScope {

	private val instances = ConcurrentHashMap<String, Any>()
	private val providers = ConcurrentHashMap<String, () -> Any>()

	fun register(type: Type, provider: () -> Any) {
		providers[type.typeName] = provider
	}

	override fun getInstance(type: Type): Any {
		val key = type.typeName
		instances[key]?.let { return it }

		providers[key]?.let { provider ->
			return provider().also { instances[key] = it }
		}

		val rawType = resolveRawClass(type)
		val context = RuntimeContext.requireContext()
		resolveSerializationBinding(rawType)?.let { resolved ->
			instances[key] = resolved
			return resolved
		}
		val resolved = when {
			rawType == null -> null
			Application::class.java.isAssignableFrom(rawType) -> context.applicationContext as? Application
			Context::class.java.isAssignableFrom(rawType) -> context
			rawType == NetworkHelper::class.java -> NetworkHelper(context)
			rawType == OkHttpClient::class.java -> RuntimeContext.httpClient()
			else -> tryCreate(rawType, context)
		} ?: throw IllegalStateException("No Injekt binding for $type")

		instances[key] = resolved
		return resolved
	}

	private fun tryCreate(rawType: Class<*>, context: Context): Any? {
		tryFieldInstance(rawType)?.let { return it }
		tryConstructor(rawType)?.let { return it }
		tryConstructor(rawType, Context::class.java, context)?.let { return it }
		(context.applicationContext as? Application)?.let { app ->
			tryConstructor(rawType, Application::class.java, app)?.let { return it }
		}
		tryConstructor(rawType, OkHttpClient::class.java, RuntimeContext.httpClient())?.let { return it }
		return null
	}

	private fun tryFieldInstance(rawType: Class<*>): Any? {
		return try {
			val instanceField = rawType.getDeclaredField("INSTANCE")
			instanceField.isAccessible = true
			instanceField.get(null)
		} catch (_: Throwable) {
			null
		}
	}

	private fun tryConstructor(rawType: Class<*>): Any? {
		return try {
			val constructor = rawType.getDeclaredConstructor()
			constructor.isAccessible = true
			constructor.newInstance()
		} catch (_: Throwable) {
			null
		}
	}

	private fun tryConstructor(rawType: Class<*>, argType: Class<*>, arg: Any): Any? {
		return try {
			val constructor = rawType.getDeclaredConstructor(argType)
			constructor.isAccessible = true
			constructor.newInstance(arg)
		} catch (_: Throwable) {
			null
		}
	}

	private fun tryStaticMethod(rawType: Class<*>, name: String): Any? {
		return try {
			val method = rawType.getDeclaredMethod(name)
			method.isAccessible = true
			method.invoke(null)
		} catch (_: Throwable) {
			null
		}
	}

	private fun resolveSerializationBinding(rawType: Class<*>?): Any? {
		rawType ?: return null
		return when (rawType.name) {
			"kotlinx.serialization.json.Json" -> DEFAULT_JSON
			"kotlinx.serialization.json.JSON" -> {
				tryFieldInstance(rawType)
					?: tryStaticMethod(rawType, "getInstance")
					?: tryStaticMethod(rawType, "getDefault")
					?: tryConstructor(rawType)
					?: tryConstructor(rawType, Json::class.java, DEFAULT_JSON)
					?: DEFAULT_JSON.takeIf { rawType.isInstance(it) }
			}
			else -> null
		}
	}

	private fun resolveRawClass(type: Type): Class<*>? = when (type) {
		is Class<*> -> type
		is ParameterizedType -> type.rawType as? Class<*>
		else -> null
	}
}

private val DEFAULT_JSON: Json by lazy {
	Json {
		ignoreUnknownKeys = true
		isLenient = true
		coerceInputValues = true
	}
}

private val runtimeScope = RuntimeInjektScope()

@Volatile
private var injekt: InjektScope = runtimeScope

fun getInjekt(): InjektScope = injekt

fun setInjekt(scope: InjektScope) {
	injekt = scope
}

fun resetInjekt() {
	injekt = runtimeScope
}

fun registerInjekt(type: Type, provider: () -> Any) {
	runtimeScope.register(type, provider)
}

inline fun <reified T> injectLazy(): Lazy<T> {
	return lazy(LazyThreadSafetyMode.NONE) {
		@Suppress("UNCHECKED_CAST")
		(getInjekt() as InjektFactory).getInstance(object : FullTypeReference<T>() {}.getType()) as T
	}
}

inline fun <reified T> injectLazy(
	mode: LazyThreadSafetyMode,
): Lazy<T> {
	return lazy(mode) {
		@Suppress("UNCHECKED_CAST")
		(getInjekt() as InjektFactory).getInstance(object : FullTypeReference<T>() {}.getType()) as T
	}
}
