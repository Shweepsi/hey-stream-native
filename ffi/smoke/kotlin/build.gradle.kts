// Vérifie que les liaisons Kotlin générées chargent la bibliothèque native et
// que les erreurs arrivent typées. Lancé par la CI après scripts/bindings.sh.
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("net.java.dev.jna:jna:5.14.0")
}

kotlin { jvmToolchain(17) }

val repoRoot = rootDir.resolve("../../..")

sourceSets.main {
    kotlin.srcDir(repoRoot.resolve("bindings/kotlin"))
}

application { mainClass.set("SmokeKt") }

tasks.named<JavaExec>("run") {
    systemProperty("jna.library.path", repoRoot.resolve("target/debug").absolutePath)
}
