package uy.kohesive.injekt.api

import java.lang.reflect.Type

inline fun <reified T> fullType(): Type {
	return object : FullTypeReference<T>() {}.getType()
}
