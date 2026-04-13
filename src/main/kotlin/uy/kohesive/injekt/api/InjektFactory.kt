package uy.kohesive.injekt.api

import java.lang.reflect.Type

fun interface InjektFactory {
	fun getInstance(type: Type): Any
}
