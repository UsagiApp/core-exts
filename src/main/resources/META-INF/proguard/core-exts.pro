-keep class org.koitharu.kotatsu.parsers.MangaLoaderContext { *; }
-keep class * extends org.koitharu.kotatsu.parsers.MangaLoaderContext { *; }

-keep interface org.koitharu.kotatsu.parsers.MangaParser { *; }
-keep interface org.koitharu.kotatsu.parsers.MangaSourceParser { *; }

-keep class org.koitharu.kotatsu.parsers.config.** { *; }
-keep class org.koitharu.kotatsu.parsers.model.** { *; }
-keep class org.koitharu.kotatsu.parsers.util.LinkResolver { *; }
-keep class org.koitharu.kotatsu.parsers.util.LinkResolver$* { *; }

-keepclassmembers class kotlin.jvm.internal.Intrinsics {
	public static void checkExpressionValueIsNotNull(...);
	public static void checkNotNullExpressionValue(...);
	public static void checkReturnedValueIsNotNull(...);
	public static void checkFieldIsNotNull(...);
	public static void checkParameterIsNotNull(...);
	public static void checkNotNullParameter(...);
}
