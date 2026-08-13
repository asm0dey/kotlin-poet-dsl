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
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

// ADR 0004's variant table and ADR 0002's shadow members, generated so they cannot drift apart.
val generateArities = tasks.register<ArityGeneratorTask>("generateArities") {
    outputDir.set(layout.buildDirectory.dir("generated/source/dsl"))
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateArities)
}
