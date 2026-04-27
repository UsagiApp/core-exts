plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    `maven-publish`
}

group = "org.draken"
version = "1.0.3"

kotlin {
    jvmToolchain(11)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=org.koitharu.kotatsu.parsers.InternalParsersApi"
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

dependencies {
    implementation(libs.androidx.collection.ktx)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.jsoup)
    implementation(libs.json)

    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
}
