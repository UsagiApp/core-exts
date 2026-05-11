package uy.kohesive.injekt.api

public interface InjektRegistrar {
    public fun <T : Any> addSingletonFactory(clazz: Class<T>, factory: () -> T)
}

public inline fun <reified T : Any> InjektRegistrar.addSingletonFactory(noinline factory: () -> T) {
    addSingletonFactory(T::class.java, factory)
}

/** Extension so `import uy.kohesive.injekt.api.get` + `Injekt.get<T>()` compiles */
public inline fun <reified T : Any> uy.kohesive.injekt.Injekt.get(): T = this.get(T::class.java)
