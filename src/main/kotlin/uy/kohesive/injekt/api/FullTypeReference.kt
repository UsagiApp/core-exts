package uy.kohesive.injekt.api

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

abstract class FullTypeReference<T> : TypeReference {
	override fun getType(): Type {
		val superType = javaClass.genericSuperclass
		return (superType as? ParameterizedType)
			?.actualTypeArguments
			?.firstOrNull()
			?: Any::class.java
	}
}
