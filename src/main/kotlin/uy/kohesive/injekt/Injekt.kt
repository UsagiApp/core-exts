package uy.kohesive.injekt

import uy.kohesive.injekt.api.InjektRegistrar

/**
 * Minimal Injekt DI shim - simple map-based registry for JVM compatibility.
 * Tachiyomi extensions use Injekt.get<Application>() and Injekt.get<Json>().
 */
public object Injekt : InjektRegistrar {
    private val registry = mutableMapOf<Class<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> get(clazz: Class<T>): T {
        return registry[clazz] as? T
            ?: throw IllegalStateException("No binding for ${clazz.name}. Register with Injekt.addSingleton<T>(instance).")
    }

    public fun <T : Any> addSingleton(clazz: Class<T>, instance: T) {
        registry[clazz] = instance
    }

    public inline fun <reified T : Any> addSingleton(instance: T) {
        addSingleton(T::class.java, instance)
    }

    override fun <T : Any> addSingletonFactory(clazz: Class<T>, factory: () -> T) {
        registry[clazz] = factory()
    }
}

/** Top-level get extension matching uy.kohesive.injekt.api.get import pattern */
public inline fun <reified T : Any> Injekt.get(): T = this.get(T::class.java)

/** Top-level injectLazy matching uy.kohesive.injekt.injectLazy import pattern */
public inline fun <reified T : Any> injectLazy(): Lazy<T> = lazy { Injekt.get<T>() }
