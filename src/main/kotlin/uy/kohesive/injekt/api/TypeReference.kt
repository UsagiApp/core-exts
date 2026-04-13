package uy.kohesive.injekt.api

import java.lang.reflect.Type

interface TypeReference {
	fun getType(): Type
}
