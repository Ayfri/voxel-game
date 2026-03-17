plugins {
	kotlin("jvm") version "2.3.20"
	application
}

group = "com.ayfri.minecraft"
version = "1.0.0"

repositories {
	mavenCentral()
}

dependencies {
	val koolVersion = "0.19.0"
	implementation("de.fabmax.kool:kool-core:$koolVersion")
}

kotlin {
	jvmToolchain(25)
	compilerOptions {
		freeCompilerArgs = listOf("-Xcontext-parameters")
	}
}

tasks.named<JavaExec>("run") {
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

application {
	mainClass = "MainKt"
	applicationDefaultJvmArgs = listOf(
		"--enable-native-access=ALL-UNNAMED"
	)
}