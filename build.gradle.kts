plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.bcv)
}

group = "site.asm0dey"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api(libs.kotlinpoet)
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kctfork.core)
    // E3: the two Kotlin versions this project had in play, aligned.
    //
    // kctfork 0.13.0 pins `kotlin-compiler-embeddable:2.4.0`, while the Kotlin plugin and the
    // `jsStdlib`/`wasmStdlib` klibs are 2.4.10 — so every `assertCompiles` in this suite ran the
    // **older** frontend while every diagnostic quoted in D36-D42 came from 2.4.10 at a command
    // line, and the JS/Wasm rows fed 2.4.10 klibs to a 2.4.0 frontend. The deviations file's version
    // note recorded the split and left the decision to this round.
    //
    // Aligned rather than labelled, because the argument for leaving them apart — that the suite
    // then tests the conservative, older frontend — does not survive the klib mismatch: nothing was
    // testing 2.4.0 *consistently*. The whole suite passes on 2.4.10 with no expectation changed,
    // which is the measurement that made this cheap. `the in-suite compiler is the version this
    // project quotes` pins it, so a future kctfork bump cannot reopen the split silently.
    testImplementation(libs.kotlin.compiler.embeddable)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

// E2c: the Kotlin/JS and Kotlin/Wasm frontends, reachable from a test.
//
// Every cross-platform claim in this project — D36's `expect` rules, D37's `external` property, D40's
// three-frontend tables, D41's matrix — was measured by hand at a command line and pinned by no test.
// kctfork ships `KotlinJsCompilation`, which needs a `kotlin-stdlib-js` klib it cannot find for
// itself: `HostEnvironment.kotlinStdLibJsJar` matches `kotlin-stdlib-js-<version>.jar` and 2.4.10
// ships a `.klib`. Nor can the klib simply be a `testImplementation` dependency — Gradle's variant
// matching refuses a `js` component to a `jvm` consumer ("No matching variant … attribute
// 'org.jetbrains.kotlin.platform.type' with value 'js'"). So each frontend gets a resolvable
// configuration carrying its own platform attribute, and the resolved klib reaches the test JVM as a
// system property. See `compileJs`/`compileWasm` in TestSupport for the rest.
val jsStdlib: Configuration = configurations.create("jsStdlib") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.js,
        )
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "kotlin-api"))
    }
}

// Kotlin/Wasm has **no** kctfork compilation of its own; `KotlinJsCompilation` reaches it through
// `kotlincArguments = listOf("-Xwasm")`, which the compiler accepts today with *"Use
// `KotlinWasmCompiler` when compiling to Wasm. Using Wasm related arguments with `K2JSCompiler` will
// become an error in a future compiler version."* That the flag really switches the target is not
// assumed: `the wasm harness really targets wasm` feeds it the **JS** klib and asserts the loader's
// *Library failed platform-specific check*.
val wasmStdlib: Configuration = configurations.create("wasmStdlib") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.wasm,
        )
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "kotlin-api"))
    }
}

dependencies {
    jsStdlib("org.jetbrains.kotlin:kotlin-stdlib-js:2.4.10")
    wasmStdlib("org.jetbrains.kotlin:kotlin-stdlib-wasm-js:2.4.10")
}

tasks.test {
    useJUnitPlatform()
    val klibs = jsStdlib.incoming.files
    val wasmKlibs = wasmStdlib.incoming.files
    inputs.files(klibs, wasmKlibs)
    doFirst {
        systemProperty(
            "kotlin.stdlib.js",
            klibs.files.filter { it.name.endsWith(".klib") }.joinToString(File.pathSeparator),
        )
        systemProperty(
            "kotlin.stdlib.wasm",
            wasmKlibs.files.filter { it.name.endsWith(".klib") }.joinToString(File.pathSeparator),
        )
    }
}

// ADR 0004's variant table and ADR 0002's shadow members, generated so they cannot drift apart.
val generateArities = tasks.register<ArityGeneratorTask>("generateArities") {
    outputDir.set(layout.buildDirectory.dir("generated/source/dsl"))
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateArities)
}
