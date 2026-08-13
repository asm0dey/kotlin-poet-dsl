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
