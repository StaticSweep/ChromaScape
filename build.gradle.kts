plugins {
	java
	checkstyle
	id("org.springframework.boot") version "3.5.3"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.diffplug.spotless") version "6.19.0"
}

group = "com.chromascape"
version = "0.5.0"

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(17))
	}
}

springBoot {
	mainClass.set("com.chromascape.web.ChromaScapeApplication")
}

repositories {
	mavenCentral()
}

configurations.all {
	exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
}

dependencies {
	// Spring Boot starters
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	implementation("org.springframework.boot:spring-boot-starter")

	// Logging
	implementation("org.springframework.boot:spring-boot-starter-log4j2")
	annotationProcessor("org.apache.logging.log4j:log4j-core:2.24.3")

	// Other libraries
	implementation("com.github.kwhat:jnativehook:2.2.2")
	implementation("commons-io:commons-io:2.14.0")
	implementation("net.java.dev.jna:jna:5.13.0")
	implementation("net.java.dev.jna:jna-platform:5.13.0")
	implementation("org.bytedeco:javacv-platform:1.5.11")
	implementation("org.apache.commons:commons-math3:3.6.1")

	// Testing
	testImplementation(platform("org.junit:junit-bom:5.10.0"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.mockito:mockito-core")
	testImplementation("org.mockito:mockito-junit-jupiter")
	testImplementation("org.springframework:spring-test:6.1.6")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation("org.springframework.boot:spring-boot-starter-test")

}

tasks.test {
	useJUnitPlatform()
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
	dependsOn("spotlessApply", "spotlessCheck", "checkstyleMain")
}
