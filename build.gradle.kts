plugins {
	java
	checkstyle
	id("org.springframework.boot") version "3.5.3"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.diffplug.spotless") version "6.19.0"
}

group = "com.chromascape"
version = "0.0.1-SNAPSHOT"

// Customize build directories - put DLLs in build/dist
layout.buildDirectory.set(file("build"))

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(17))
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("com.github.kwhat:jnativehook:2.2.2")
	implementation("commons-io:commons-io:2.14.0")
	implementation("net.java.dev.jna:jna:5.13.0")
	implementation("net.java.dev.jna:jna-platform:5.13.0")
	implementation("org.bytedeco:javacv-platform:1.5.11")
	implementation("org.apache.commons:commons-math3:3.6.1")
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation(platform("org.junit:junit-bom:5.10.0"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

checkstyle {
	toolVersion = "10.26.1"
	configFile = file("config/checkstyle/google_checks.xml")
	configProperties["org.checkstyle.google.suppressionfilter.config"] =
		file("config/checkstyle/checkstyle-suppressions.xml").absolutePath
	isIgnoreFailures = false
}

spotless {
	java {
		googleJavaFormat("1.17.0")
		trimTrailingWhitespace()
		endWithNewline()
	}
}

tasks.named("check") {
	dependsOn("spotlessCheck", "checkstyleMain")
}

// KInput native build configuration
val buildKInput by tasks.registering(Exec::class) {
	group = "native"
	description = "Build KInput.dll"
	workingDir = file("src/main/resources/native/KInput/KInput/KInput")
	
	// Use cmd.exe to run make commands on Windows
	commandLine("cmd", "/c", "make clean && make release")
	
	outputs.file("${workingDir}/bin/Release/KInput.dll")
	
	// Only run if make is available and library doesn't exist
	onlyIf {
		val outputFile = file("${workingDir}/bin/Release/KInput.dll")
		!outputFile.exists() && try {
			exec {
				commandLine("make", "--version")
			}
			true
		} catch (e: Exception) {
			false
		}
	}
	
	doFirst {
		file("${workingDir}/bin/Release").mkdirs()
	}
}

val buildKInputCtrl by tasks.registering(Exec::class) {
	group = "native"
	description = "Build KInputCtrl.dll"
	workingDir = file("src/main/resources/native/KInput/KInput/KInputCtrl")
	
	commandLine("cmd", "/c", "make clean && make release")
	
	outputs.file("${workingDir}/bin/Release/KInputCtrl.dll")
	
	onlyIf {
		val outputFile = file("${workingDir}/bin/Release/KInputCtrl.dll")
		!outputFile.exists() && try {
			exec {
				commandLine("make", "--version")
			}
			true
		} catch (e: Exception) {
			false
		}
	}
	
	doFirst {
		file("${workingDir}/bin/Release").mkdirs()
	}
}

// Copy built DLLs to build/dist folder
val copyNativeLibraries by tasks.registering(Copy::class) {
	group = "native"
	description = "Copy built native libraries to build/dist"
	dependsOn(buildKInput, buildKInputCtrl)
	
	// Always run this task to ensure build/dist directory exists
	// If native build failed, copy existing pre-built libraries
	onlyIf {
		// Check if we have either built libraries or existing pre-built ones
		val kInputBuilt = file("src/main/resources/native/KInput/KInput/KInput/bin/Release/KInput.dll").exists()
		val kInputCtrlBuilt = file("src/main/resources/native/KInput/KInput/KInputCtrl/bin/Release/KInputCtrl.dll").exists()
		val kInputExisting = file("src/main/resources/native/KInput64.dll").exists()
		val kInputCtrlExisting = file("src/main/resources/native/KInputCtrl64.dll").exists()
		
		kInputBuilt && kInputCtrlBuilt || kInputExisting && kInputCtrlExisting
	}
	
	doFirst {
		// Ensure build/dist directory exists
		file("build/dist").mkdirs()
	}
	
	// Copy from built libraries if they exist, otherwise from existing ones
	from("src/main/resources/native/KInput/KInput/KInput/bin/Release")
	from("src/main/resources/native/KInput/KInput/KInputCtrl/bin/Release")
	from("src/main/resources/native")
	into("build/dist")
	
	include("*.dll")
}

// Make build depend on native library building
tasks.named("build") {
	dependsOn(copyNativeLibraries)
}

tasks.named("jar") {
	dependsOn(copyNativeLibraries)
}

