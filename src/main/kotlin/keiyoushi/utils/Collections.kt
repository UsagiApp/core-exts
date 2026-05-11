package keiyoushi.utils

public inline fun <reified T> Iterable<*>.firstInstance(): T = first { it is T } as T

public inline fun <reified T> Iterable<*>.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T
