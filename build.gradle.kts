import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import javax.inject.Inject
import org.gradle.api.file.FileSystemOperations
import org.gradle.process.ExecOperations

val sc967VectorsPath =
	providers.gradleProperty("sc967VectorsPath")
		.orElse(providers.environmentVariable("SC967_VECTORS_PATH"))
		// The canonical Windows location is the default used by the authoritative gate. The
		// property/environment override remains available for CI and WSL path translation.
		.orElse("C:\\Dev\\IdeaProjects\\contracts\\sc967\\ed25519-vectors.json")

plugins {
	java
	checkstyle
	id("org.springframework.boot") version "3.5.3"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.diffplug.spotless") version "6.19.0"
}

group = "com.chromascape"
version = "0.2.0-SNAPSHOT"

// Customize build directories - put DLLs in build/dist
layout.buildDirectory.set(file("build"))

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(17))
	}
}

springBoot {
	mainClass.set("com.chromascape.web.ChromaScapeApplication")
}

// The revision is embedded at build time. Recording reads must never consult a moving Git HEAD.
val debuggerBuildMetadata = tasks.register("debuggerBuildMetadata") {
	val destination = layout.buildDirectory.dir("generated/debugger-build")
	outputs.dir(destination)
	outputs.upToDateWhen { false }
	doLast {
		fun gitValue(vararg arguments: String): String = try {
			val output = ByteArrayOutputStream()
			val result = project.exec {
				commandLine("git", *arguments)
				standardOutput = output
				errorOutput = ByteArrayOutputStream()
				isIgnoreExitValue = true
			}
			if (result.exitValue == 0) output.toString(Charsets.UTF_8).trim() else "unknown"
		} catch (_: Exception) { "unknown" }
		val revision = gitValue("rev-parse", "HEAD")
		val changes = gitValue("status", "--porcelain", "--untracked-files=no")
		val dirty = if (changes == "unknown") "unknown" else changes.isNotEmpty().toString()
		val file = destination.get().file("debugger-build.properties").asFile
		file.parentFile.mkdirs()
		file.writeText("version=${project.version}\ngitRevision=$revision\ndirty=$dirty\n")
	}
}
sourceSets.main { resources.srcDir(debuggerBuildMetadata) }

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
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")

	// Bean Validation for typed configuration and DTO constraints (SC-967).
	implementation("org.springframework.boot:spring-boot-starter-validation")

	// Draft 2020-12 JSON-schema validation for behavior-graph and assistant-diff contracts (SC-967).
	implementation("com.networknt:json-schema-validator:1.5.1")

	// Database
	runtimeOnly("org.postgresql:postgresql")

	// Versioned database migrations (SC-572): schema constraints are enforced by Flyway, never by
	// Hibernate ddl-auto update.
	implementation("org.flywaydb:flyway-core")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")

	// Logging
	implementation("org.springframework.boot:spring-boot-starter-log4j2")
	annotationProcessor("org.apache.logging.log4j:log4j-core:2.24.3")

	// Jackson for JSON serialization (LeeuwBot integration)
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")

	// Gson for JSON parsing (Micro6 integration)
	implementation("com.google.code.gson:gson:2.10.1")

	// Other libraries
	implementation("org.java-websocket:Java-WebSocket:1.5.4")
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
	testImplementation("org.awaitility:awaitility:4.2.0")

	// Real-PostgreSQL coverage for the replay subsystem (REPLAY-XRSPS-01.6.x): migrations,
	// native locking SQL, database-clock predicates, and multi-instance races cannot be proven
	// against mocks or an embedded database.
	testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")

}

tasks.test {
	useJUnitPlatform()
	maxHeapSize = providers.gradleProperty("testMaxHeapSize").orElse("512m").get()
	// Required for Mockito inline mock-maker to attach to running JVM
	// Used by tests that mock final classes and static methods
	jvmArgs("-Djdk.attach.allowAttachSelf=true")
	// Certification runs ONLY under the hard-gated replayCertificationTest task; the ordinary
	// suite must never discover it (its prerequisites are hard assertions, not skips).
	exclude("com/chromascape/web/replay/certification/**")
	// SC-967's shared fixture is an explicit ordinary-suite input. Replay-only/certification tasks
	// do not execute PatchCandidateManifestV2Test and must remain independently runnable.
	doFirst {
		val resolved = file(sc967VectorsPath.get()).canonicalFile
		if (!resolved.isFile) {
			throw GradleException("Resolved SC-967 fixture is not a regular file: ${resolved.absolutePath}")
		}
		systemProperty("sc967.vectors.path", resolved.absolutePath)
		logger.lifecycle("SC-967 vectors: ${resolved.absolutePath}")
	}
}

// Isolated replay verification (REPLAY-XRSPS-01.6.3): compiles and runs ONLY the
// replay test tree so replay coverage stays executable while unrelated Micro6
// test compilation is repaired separately. Run with: gradlew replayTest
sourceSets.create("replayTest") {
	java.srcDir(file("src/test/java/com/chromascape/web/replay"))
	// The retained legacy tick-card route is part of the replay API closure gate. Keep its
	// controller regression test in this isolated source set without pulling unrelated tests into
	// the replay build.
	java.srcDir(file("src/test/java/com/chromascape/web/debug/tickdebugger/controller"))
	resources.srcDir(file("src/test/resources"))
}
configurations.named("replayTestImplementation") {
	extendsFrom(configurations.testImplementation.get())
}
configurations.named("replayTestRuntimeOnly") {
	extendsFrom(configurations.testRuntimeOnly.get())
}
dependencies {
	"replayTestImplementation"(sourceSets.main.get().output)
	// Keep main classes on the custom source set's runtime classpath as well. Gradle treats the
	// file dependency above as compile-only when replayTestRuntimeClasspath is assembled.
	"replayTestRuntimeOnly"(sourceSets.main.get().output)
}

tasks.register<Test>("replayTest") {
	group = "verification"
	description = "Runs replay-scoped tests isolated from unrelated test compilation"
	testClassesDirs = sourceSets["replayTest"].output.classesDirs
	// A custom source set's runtime classpath does not consistently retain a file dependency on
	// main.output across Gradle versions. Add it explicitly so replay tests can resolve the entities
	// and controllers they exercise at runtime (compileClasspath already saw this dependency).
	classpath = sourceSets["replayTest"].output + sourceSets.main.get().output + sourceSets["replayTest"].runtimeClasspath
	useJUnitPlatform()
	jvmArgs("-Djdk.attach.allowAttachSelf=true")
	// Certification is a SEPARATE hard gate: ordinary replay runs must never silently skip it or
	// report misleading skips.
	exclude("com/chromascape/web/replay/certification/**")
	// Real native persistence has an explicit Docker/authored-worker lane, never an implicit skip.
	exclude("**/NativeCheckpointPersistenceTest.class")
	exclude("**/NativeDatabaseContinuationTest.class")
	exclude("**/NativeDatabaseRestartTest.class")
	shouldRunAfter(tasks.test)
}

tasks.named<Test>("test") {
	exclude("**/NativeCheckpointPersistenceTest.class")
	exclude("**/NativeDatabaseContinuationTest.class")
	exclude("**/NativeDatabaseRestartTest.class")
}

tasks.register<Test>("replayNativeCheckpointPersistenceTest") {
	group = "verification"
	description = "Real disposable PostgreSQL/artifact/native checkpoint proof; authored and non-certifying"
	testClassesDirs = sourceSets["replayTest"].output.classesDirs
	classpath = sourceSets["replayTest"].output + sourceSets.main.get().output + sourceSets["replayTest"].runtimeClasspath
	useJUnitPlatform()
	include("**/NativeCheckpointPersistenceTest.class")
	jvmArgs("-Djdk.attach.allowAttachSelf=true")
	shouldRunAfter(tasks.named("replayTest"))
}

tasks.register<Test>("replayNativeDatabaseContinuationTest") {
	group = "verification"
	description = "Authored fresh-native checkpoint continuation through committed PostgreSQL/artifacts"
	testClassesDirs = sourceSets["replayTest"].output.classesDirs
	classpath = sourceSets["replayTest"].output + sourceSets.main.get().output + sourceSets["replayTest"].runtimeClasspath
	useJUnitPlatform { includeTags("native-database-continuation") }
	filter {
		includeTestsMatching("com.chromascape.web.replay.worker.NativeDatabaseContinuationTest.finalizedDatabaseAnchorsRestoreInSixFreshNativeWorkers")
		isFailOnNoMatchingTests = true
	}
	jvmArgs("-Djdk.attach.allowAttachSelf=true")
	maxParallelForks = 1
	outputs.upToDateWhen { false }
	doFirst {
		for (name in listOf("SC276_NATIVE_CHECKPOINT_JDBC_URL", "SC276_NATIVE_DATABASE_INPUT",
			"SC276_NATIVE_DATABASE_OUTPUT", "SC274_NATIVE_REPLAY_ROOT")) {
			if (System.getenv(name).isNullOrBlank()) {
				throw GradleException("Required native/database prerequisite: $name")
			}
		}
		val input = file(System.getenv("SC276_NATIVE_DATABASE_INPUT"))
		val output = file(System.getenv("SC276_NATIVE_DATABASE_OUTPUT"))
		val worker = file(System.getenv("SC274_NATIVE_REPLAY_ROOT")).resolve("server/src/replay/worker-main.ts")
		if (!input.isFile || !worker.isFile || output.exists()) {
			throw GradleException("Native/database input/runtime must exist and output must be new")
		}
	}
	shouldRunAfter(tasks.named("replayNativeCheckpointPersistenceTest"))
}

for ((taskName, method) in mapOf(
	"replayNativeDatabasePrepareTest" to "prepareCommittedAnchorsAndQueuedChildren",
	"replayNativeDatabaseResumeTest" to "restoreCommittedChildrenInDifferentJvm"
)) {
	tasks.register<Test>(taskName) {
		group = "verification"
		description = "Explicit authored cross-JVM native/database continuation: $method"
		testClassesDirs = sourceSets["replayTest"].output.classesDirs
		classpath = sourceSets["replayTest"].output + sourceSets.main.get().output + sourceSets["replayTest"].runtimeClasspath
		useJUnitPlatform { includeTags("native-database-restart") }
		filter {
			includeTestsMatching("com.chromascape.web.replay.worker.NativeDatabaseRestartTest.$method")
			isFailOnNoMatchingTests = true
		}
		jvmArgs("-Djdk.attach.allowAttachSelf=true")
		maxParallelForks = 1
		outputs.upToDateWhen { false }
		doFirst {
			for (name in listOf("SC276_NATIVE_CHECKPOINT_JDBC_URL", "SC276_NATIVE_DATABASE_INPUT",
				"SC276_NATIVE_RESTART_ROOT", "SC274_NATIVE_REPLAY_ROOT")) {
				if (System.getenv(name).isNullOrBlank()) {
					throw GradleException("Required native restart prerequisite: $name")
				}
			}
		}
	}
}
tasks.named("replayNativeDatabaseResumeTest") {
	mustRunAfter(tasks.named("replayNativeDatabasePrepareTest"))
}

// Windows-only replay contract certification gate: drives the freshly built xRSPS synthetic
// contract worker over real PostgreSQL (Flyway V11-V22), a temporary REAL artifact root, and a
// fresh Node process for branch restoration. This proves synthetic-harness repeatability and
// lifecycle safety; it is not authoritative xRSPS game-fidelity evidence. Every prerequisite is a
// HARD assertion inside the suite AND the doFirst gate below: a missing prerequisite FAILS the
// task, never skips it.
tasks.register<Test>("replayCertificationTest") {
	group = "verification"
	description =
		"Windows-only replay contract certification against the synthetic xRSPS worker, PostgreSQL, and artifact storage"
	testClassesDirs = sourceSets["replayTest"].output.classesDirs
	classpath = sourceSets["replayTest"].output + sourceSets.main.get().output + sourceSets["replayTest"].runtimeClasspath
	useJUnitPlatform()
	include("com/chromascape/web/replay/certification/ReplayCertificationTest.class")
	jvmArgs("-Djdk.attach.allowAttachSelf=true")
	maxParallelForks = 1
	// Zero-skips requirement: the suite contains NO assumptions and every prerequisite is a hard
	// assertion, so a missing Windows/Docker/worker prerequisite FAILS the gate instead of
	// reporting a skipped certification test.
	doFirst {
		if (!isWindows) {
			throw GradleException("replayCertificationTest must run on the Windows host")
		}
		// The suite records the worker identity + PostgreSQL image here during @BeforeAll; a stale
		// record from a previous run must never be logged as this run's environment.
		delete(project.layout.buildDirectory.dir("replay-certification"))
	}
	doLast {
		// Execution-count gate: the task must FAIL if the filters discover or execute ZERO tests,
		// or if anything was skipped — a silent certification is worse than a failed one.
		val resultsDir = reports.junitXml.outputLocation.get().asFile
		val xmlFiles = resultsDir.listFiles { file ->
			file.isFile && file.name.startsWith("TEST-") && file.name.endsWith(".xml")
		}
		if (xmlFiles == null || xmlFiles.isEmpty()) {
			throw GradleException(
				"replayCertificationTest discovered 0 test suites; certification requires discovered > 0"
			)
		}
		var discovered = 0
		var failed = 0
		var skipped = 0
		val xmlFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
		xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
		for (xmlFile in xmlFiles) {
			val suite = xmlFactory.newDocumentBuilder().parse(xmlFile).documentElement
			discovered += suite.getAttribute("tests").ifBlank { "0" }.trim().toInt()
			failed += suite.getAttribute("failures").ifBlank { "0" }.trim().toInt()
			failed += suite.getAttribute("errors").ifBlank { "0" }.trim().toInt()
			skipped += suite.getAttribute("skipped").ifBlank { "0" }.trim().toInt()
		}
		val executed = discovered - skipped
		logger.lifecycle(
			"replayCertificationTest: discovered={} executed={} skipped={} failed={}",
			discovered,
			executed,
			skipped,
			failed
		)
		if (discovered <= 0) {
			throw GradleException("certification discovered $discovered tests; requires > 0")
		}
		if (executed <= 0) {
			throw GradleException("certification executed $executed tests; requires > 0")
		}
		if (skipped != 0) {
			throw GradleException("certification skipped $skipped tests; zero skips required")
		}
		if (failed != 0) {
			throw GradleException("certification reported $failed failures")
		}
		// Environment record: written by the suite's @BeforeAll after the git-HEAD and strict
		// validator proofs; its presence proves the logged environment belongs to THIS run.
		val identityFile =
			project.layout.buildDirectory.file("replay-certification/identity.properties").get().asFile
		if (!identityFile.isFile) {
			throw GradleException("certification did not record its worker identity environment")
		}
		logger.lifecycle("replayCertificationTest environment:")
		identityFile.readLines().filter { it.isNotBlank() }.forEach { logger.lifecycle("  {}", it) }
	}
	shouldRunAfter(tasks.named("replayTest"))
}

// Separate native Fedora SC-275 proof; the historical Windows gate remains unchanged.
tasks.register<Test>("replayFedoraCertificationTest") {
	group = "verification"
	description =
		"Fedora causal certification against the current production xRSPS worker, PostgreSQL, and artifact storage"
	testClassesDirs = sourceSets["replayTest"].output.classesDirs
	classpath = sourceSets["replayTest"].output + sourceSets.main.get().output + sourceSets["replayTest"].runtimeClasspath
	useJUnitPlatform()
	include("com/chromascape/web/replay/certification/Sc275CausalCertificationTest.class")
	jvmArgs("-Djdk.attach.allowAttachSelf=true")
	maxParallelForks = 1
	// External worker identity/cache inputs must be interrogated on every invocation.
	outputs.upToDateWhen { false }
	outputs.cacheIf { false }
	// Zero-skips requirement: the suite contains NO assumptions and every prerequisite is a hard
	// assertion, so a missing Linux/container/worker prerequisite FAILS the gate instead of
	// reporting a skipped certification test.
	doFirst {
		if (!org.gradle.internal.os.OperatingSystem.current().isLinux) {
			throw GradleException("replayFedoraCertificationTest must run natively on a Linux/Fedora host")
		}
		// The suite records the worker identity + PostgreSQL image here during @BeforeAll; a stale
		// record from a previous run must never be logged as this run's environment.
		delete(project.layout.buildDirectory.dir("replay-fedora-certification"))
	}
	doLast {
		// Execution-count gate: the task must FAIL if the filters discover or execute ZERO tests,
		// or if anything was skipped — a silent certification is worse than a failed one.
		val resultsDir = reports.junitXml.outputLocation.get().asFile
		val xmlFiles = resultsDir.listFiles { file ->
			file.isFile && file.name.startsWith("TEST-") && file.name.endsWith(".xml")
		}
		if (xmlFiles == null || xmlFiles.isEmpty()) {
			throw GradleException(
				"replayFedoraCertificationTest discovered 0 test suites; certification requires discovered > 0"
			)
		}
		var discovered = 0
		var failed = 0
		var skipped = 0
		val xmlFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
		xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
		for (xmlFile in xmlFiles) {
			val suite = xmlFactory.newDocumentBuilder().parse(xmlFile).documentElement
			discovered += suite.getAttribute("tests").ifBlank { "0" }.trim().toInt()
			failed += suite.getAttribute("failures").ifBlank { "0" }.trim().toInt()
			failed += suite.getAttribute("errors").ifBlank { "0" }.trim().toInt()
			skipped += suite.getAttribute("skipped").ifBlank { "0" }.trim().toInt()
		}
		val executed = discovered - skipped
		logger.lifecycle(
			"replayFedoraCertificationTest: discovered={} executed={} skipped={} failed={}",
			discovered,
			executed,
			skipped,
			failed
		)
		if (discovered <= 0) {
			throw GradleException("certification discovered $discovered tests; requires > 0")
		}
		if (executed <= 0) {
			throw GradleException("certification executed $executed tests; requires > 0")
		}
		if (skipped != 0) {
			throw GradleException("certification skipped $skipped tests; zero skips required")
		}
		if (failed != 0) {
			throw GradleException("certification reported $failed failures")
		}
		// Environment record: written by the suite's @BeforeAll after the git-HEAD and strict
		// validator proofs; its presence proves the logged environment belongs to THIS run.
		val identityFile =
			project.layout.buildDirectory.file("replay-fedora-certification/identity.properties").get().asFile
		if (!identityFile.isFile) {
			throw GradleException("certification did not record its worker identity environment")
		}
		logger.lifecycle("replayFedoraCertificationTest environment:")
		identityFile.readLines().filter { it.isNotBlank() }.forEach { logger.lifecycle("  {}", it) }
	}
	shouldRunAfter(tasks.named("replayTest"))
}

// Separate Windows-only synthetic-harness stress battery. It is intentionally not a dependency of the
// ordinary `check` task: the authoritative Windows gate invokes replayCertificationTest and this
// task explicitly after the normal replay checks. Keeping the task separate prevents a Linux/WSL
// developer check from silently reporting a skipped certification suite.
tasks.register<Test>("replayStressCertificationTest") {
	group = "verification"
	description =
		"Windows-only replay stress evidence: synthetic determinism-100, failure injection, and PostgreSQL races"
	var stressInvocationId: String? = null
	testClassesDirs = sourceSets["replayTest"].output.classesDirs
	classpath = sourceSets["replayTest"].output + sourceSets.main.get().output + sourceSets["replayTest"].runtimeClasspath
	useJUnitPlatform()
	include("com/chromascape/web/replay/certification/ReplayStressCertificationTest.class")
	jvmArgs("-Djdk.attach.allowAttachSelf=true")
	maxParallelForks = 1
	shouldRunAfter(tasks.named("replayCertificationTest"))
	doFirst {
		if (!isWindows) {
			throw GradleException("replayStressCertificationTest must run on the Windows host")
		}
		// Every invocation writes to an isolated staging directory. A filtered/local test run can
		// therefore never delete or overwrite the last complete evidence set in final/.
		val invocationId = UUID.randomUUID().toString()
		stressInvocationId = invocationId
		val stagingDir = project.layout.buildDirectory.dir("replay-certification/staging/$invocationId").get().asFile
		stagingDir.mkdirs()
		systemProperty("replay.certification.invocation-id", invocationId)
		systemProperty("replay.certification.output-dir", stagingDir.absolutePath)
		delete(reports.junitXml.outputLocation)
	}
	doLast {
		val resultsDir = reports.junitXml.outputLocation.get().asFile
		val xmlFiles = resultsDir.listFiles { file ->
			file.isFile && file.name.startsWith("TEST-") && file.name.endsWith(".xml")
		}
		if (xmlFiles == null || xmlFiles.isEmpty()) {
			throw GradleException(
				"replayStressCertificationTest discovered 0 test suites; stress certification requires discovered > 0"
			)
		}
		var discovered = 0
		var failed = 0
		var skipped = 0
		val xmlFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
		xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
		for (xmlFile in xmlFiles) {
			val suite = xmlFactory.newDocumentBuilder().parse(xmlFile).documentElement
			discovered += suite.getAttribute("tests").ifBlank { "0" }.trim().toInt()
			failed += suite.getAttribute("failures").ifBlank { "0" }.trim().toInt()
			failed += suite.getAttribute("errors").ifBlank { "0" }.trim().toInt()
			skipped += suite.getAttribute("skipped").ifBlank { "0" }.trim().toInt()
		}
		val executed = discovered - skipped
		logger.lifecycle(
			"replayStressCertificationTest: discovered={} executed={} skipped={} failed={}",
			discovered,
			executed,
			skipped,
			failed
		)
		val expectedMethods = setOf(
			"deterministicManifestProducesOneHundredIdenticalRuns",
			"failureInjectionBatteryFailsClosedAndLeavesNoFalseSuccess",
			"postgresRaceBatteryHasOneWinnerAndStableContinuation"
		)
		val discoveredMethods = xmlFiles.flatMap { xmlFile ->
			val suite = xmlFactory.newDocumentBuilder().parse(xmlFile).documentElement
			(0 until suite.getElementsByTagName("testcase").length).map { index ->
				suite.getElementsByTagName("testcase").item(index).attributes
					.getNamedItem("name").nodeValue.substringBefore('(')
			}
		}.toSet()
		if (discovered != 3 || executed != 3 || skipped != 0 || failed != 0 || discoveredMethods != expectedMethods) {
			throw GradleException(
				"replayStressCertificationTest counts/methods invalid: discovered=$discovered executed=$executed skipped=$skipped failed=$failed methods=$discoveredMethods"
			)
		}

		val invocationId = stressInvocationId
			?: throw GradleException("stress certification task lost its invocation id")
		val certificationDir = project.layout.buildDirectory.dir("replay-certification/staging/$invocationId").get().asFile
		val requiredArtifacts = listOf(
			"determinism-100.json",
			"failure-injection.json",
			"postgres-races.json",
			"capabilities.json",
			"identity.properties"
		)
		for (name in requiredArtifacts) {
			val artifact = certificationDir.resolve(name)
			if (!artifact.isFile || artifact.length() == 0L) {
				throw GradleException("stress certification artifact is missing or empty: ${artifact.absolutePath}")
			}
		}
		val jsonSlurper = groovy.json.JsonSlurper()
		val identityLines = certificationDir.resolve("identity.properties").readLines()
		val identityInvocation = identityLines.firstOrNull { it.startsWith("invocationId=") }
			?.substringAfter('=')
		if (identityInvocation != invocationId) {
			throw GradleException("identity.properties belongs to a different stress invocation")
		}
		val capabilities = jsonSlurper.parse(certificationDir.resolve("capabilities.json")) as Map<*, *>
		if (capabilities["invocationId"] != invocationId
			|| capabilities["fidelity"] != "SYNTHETIC_CONTRACT"
			|| capabilities["supportedActions"] != listOf("WAIT_UNTIL_TICK", "INTERACT_NPC")) {
			throw GradleException("capabilities.json is not the expected truthful synthetic v1 contract")
		}
		val determinism = jsonSlurper.parse(certificationDir.resolve("determinism-100.json")) as Map<*, *>
		if (determinism["invocationId"] != invocationId
			|| determinism["evidenceClass"] != "synthetic-harness-repeatability"
			|| determinism["fidelity"] != "SYNTHETIC_CONTRACT"
			|| (determinism["runCount"] as Number).toInt() != 100
			|| (determinism["passCount"] as Number).toInt() != 100
			|| (determinism["failureCount"] as Number).toInt() != 0
			|| (determinism["elapsedDurationMs"] as Number).toLong() < 0L) {
			throw GradleException("determinism-100.json does not certify the required 100-run evidence")
		}
		val failures = jsonSlurper.parse(certificationDir.resolve("failure-injection.json")) as Map<*, *>
		if (failures["invocationId"] != invocationId
			|| failures["evidenceClass"] != "synthetic-harness-failure-safety"
			|| failures["fidelity"] != "SYNTHETIC_CONTRACT"
			|| (failures["cases"] as Number).toInt() != 11) {
			throw GradleException("failure-injection.json does not certify all 11 failure cases")
		}
		val races = jsonSlurper.parse(certificationDir.resolve("postgres-races.json")) as Map<*, *>
		val raceRows = races["scenarios"] as? List<*> ?: emptyList<Any>()
		if (races["invocationId"] != invocationId
			|| races["evidenceClass"] != "replay-persistence-concurrency"
			|| races["fidelity"] != "SYNTHETIC_CONTRACT"
			|| raceRows.size != 7 || raceRows.any { row ->
			val map = row as Map<*, *>
			(map["repetitions"] as Number).toInt() != 25 || map["passed"] != true
		}) {
			throw GradleException("postgres-races.json does not certify seven passing 25-repetition scenarios")
		}

		// Validate every artifact before promotion. The final directory is replaced only after the
		// three exact test methods and all five same-invocation documents have passed.
		val finalDir = project.layout.buildDirectory.dir("replay-certification/final").get().asFile.toPath()
		val stagingPath = certificationDir.toPath()
		val promotionPath = finalDir.resolveSibling("final-$invocationId")
		fun moveForPromotion(source: Path, target: Path) {
			try {
				Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
			} catch (unsupported: AtomicMoveNotSupportedException) {
				Files.move(source, target)
			}
		}
		moveForPromotion(stagingPath, promotionPath)
		val previousPath = finalDir.resolveSibling("final-previous-$invocationId")
		var promotionCompleted = false
		try {
			if (Files.exists(finalDir)) {
				moveForPromotion(finalDir, previousPath)
			}
			moveForPromotion(promotionPath, finalDir)
			promotionCompleted = true
		} catch (promotionFailure: Exception) {
			// If the final replacement failed after the old directory was moved aside, restore that
			// directory before propagating the failure. A failed evidence promotion must never erase
			// the last complete certification result.
			if (Files.exists(previousPath) && !Files.exists(finalDir)) {
				try {
					moveForPromotion(previousPath, finalDir)
				} catch (restoreFailure: Exception) {
					promotionFailure.addSuppressed(restoreFailure)
				}
			}
			throw promotionFailure
		} finally {
			if (promotionCompleted && Files.exists(previousPath)) {
				project.delete(previousPath.toFile())
			}
		}
	}
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

// Windows-only native build configuration
val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows

// Copy prebuilt DLLs to build/dist folder
val copyNativeLibraries by tasks.registering(Copy::class) {
	group = "native"
	description = "Copy prebuilt native libraries to build/dist"
	
	// Only run on Windows
	onlyIf {
		isWindows
	}
	
	// Check if we have prebuilt libraries
	onlyIf {
		val kInputExists = file("third_party/KInput/KInput/KInput/bin/Release/KInput.dll").exists()
		val kInputCtrlExists = file("third_party/KInput/KInput/KInputCtrl/bin/Release/KInputCtrl.dll").exists()
		
		kInputExists && kInputCtrlExists
	}
	
	doFirst {
		// Ensure build/dist directory exists
		file("build/dist").mkdirs()
	}
	
	// Copy from prebuilt libraries
	from("third_party/KInput/KInput/KInput/bin/Release")
	from("third_party/KInput/KInput/KInputCtrl/bin/Release")
	into("build/dist")
	
	include("*.dll")
}

// Note: Removed copyNativeToResources task as the application loads DLLs directly from build/dist
// and doesn't use classpath fallback mechanism

// Make build depend on native library copying and quality checks
tasks.named("processResources") {
	dependsOn(copyNativeLibraries)
}

tasks.named("build") {
	dependsOn(copyNativeLibraries, "check")
}

tasks.named("jar") {
	dependsOn(copyNativeLibraries)
}

// Custom task to clean .chromascape directory
tasks.register("cleanChromascape") {
	group = "cleanup"
	description = "Remove the .chromascape directory"
	
	doLast {
		val chromascapeDir = file(".chromascape")
		if (chromascapeDir.exists()) {
			delete(chromascapeDir)
			println("Removed .chromascape directory")
		} else {
			println(".chromascape directory does not exist")
		}
	}
}

// Task to clean everything including .chromascape
tasks.register("cleanAll") {
	group = "cleanup"
	description = "Clean build artifacts and .chromascape directory"
	dependsOn("clean", "cleanChromascape")
}
sourceSets.create("aiPlayerSchemaTest") {
    java.srcDir("src/test/java/com/chromascape/web/aiplayer/schema")
    resources.srcDir("src/test/resources")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations.named("aiPlayerSchemaTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named("aiPlayerSchemaTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}
tasks.register<Test>("aiPlayerSchemaTest") {
    group = "verification"
    description = "Verifies the current AI Player schema against disposable PostgreSQL"
    testClassesDirs = sourceSets["aiPlayerSchemaTest"].output.classesDirs
    classpath = sourceSets["aiPlayerSchemaTest"].runtimeClasspath
    useJUnitPlatform()
}
tasks.register<JavaExec>("aiPlayerSchemaExport") {
    group = "development"
    description = "Exports current mapped DDL for review; never connects to a database"
    classpath = sourceSets["aiPlayerSchemaTest"].runtimeClasspath
    mainClass.set("com.chromascape.web.aiplayer.schema.CurrentEntityMappings")
    args(layout.buildDirectory.file("ai-player-schema/entities.sql").get().asFile.absolutePath)
}
tasks.register<JavaExec>("aiPlayerSchema") {
    group = "development"
    description = "Guarded AI Player development bootstrap/verify/reset (see docs/SC-665-CURRENT-SCHEMA.md)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.chromascape.web.aiplayer.schema.AiPlayerSchemaCli")
    args(providers.gradleProperty("schemaAction").getOrElse("verify"))
}
tasks.register<JavaExec>("aiPlayerSchemaFingerprintExport") {
    group = "development"
    description = "Prints a candidate fingerprint for review on an explicitly disposable test database"
    classpath = sourceSets["aiPlayerSchemaTest"].runtimeClasspath
    mainClass.set("com.chromascape.web.aiplayer.schema.SchemaFingerprintExport")
}

sourceSets.create("aiPlayerCatalogTest") {
    java.srcDir("src/test/java/com/chromascape/web/aiplayer/catalog")
    java.srcDir("src/test/java/com/chromascape/web/integration/micro6/service")
    java.include("Catalog*.java", "Micro6ExecutorCatalogServiceTest.java", "Micro6TestServers.java", "TestMicro6Catalog.java")
    resources.srcDir("src/test/resources")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations.named("aiPlayerCatalogTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
configurations.named("aiPlayerCatalogTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
tasks.register<Test>("aiPlayerCatalogTest") {
    group = "verification"
    description = "SC-641 backend and disposable PostgreSQL acceptance"
    testClassesDirs = sourceSets["aiPlayerCatalogTest"].output.classesDirs
    classpath = sourceSets["aiPlayerCatalogTest"].runtimeClasspath
    useJUnitPlatform()
}

// SC-609 isolated capture contract/cursor verification; unrelated legacy tests stay excluded.
sourceSets.create("recordingProvenanceTest") {
    java.srcDir("src/test/java/com/chromascape/web/recording/provenance")
    java.srcDir("src/test/java/com/chromascape/web/replay/playback")
    java.srcDir("src/test/java")
    java.include("*.java", "com/chromascape/web/debug/tickdebugger/service/DebugSessionServiceTest.java",
        "com/chromascape/web/debug/tickdebugger/dto/SessionStartRequestTest.java",
        "com/chromascape/web/debug/tickdebugger/websocket/TickStreamWebSocketHandlerTest.java",
        "com/chromascape/web/recording/service/TaskDetectionServiceTest.java")
    java.exclude("*IntegrationTest.java")
    resources.srcDir("src/test/resources")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations.named("recordingProvenanceTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
configurations.named("recordingProvenanceTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
tasks.register<Test>("recordingProvenanceTest") {
    group = "verification"
    testClassesDirs = sourceSets["recordingProvenanceTest"].output.classesDirs
    classpath = sourceSets["recordingProvenanceTest"].runtimeClasspath
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// SC-1662 - Linux RemoteInput native build, staging and verification.
//
// These tasks are Linux-only and deliberately kept OUT of the ordinary `check`
// graph: unit tests and task enumeration must never require a native compiler
// or a built native artifact. Windows packaging (copyNativeLibraries and its
// processResources/build/jar wiring) is untouched.
// ---------------------------------------------------------------------------

val isLinux = org.gradle.internal.os.OperatingSystem.current().isLinux
val remoteInputScript = layout.projectDirectory.file("scripts/native/build-remoteinput-linux.sh")
val remoteInputHelper = layout.projectDirectory.file("scripts/native/remoteinput_metadata.py")
val remoteInputBuildDir = layout.projectDirectory.dir("build/native/remoteinput/linux-x86_64")
val remoteInputStageDir = layout.projectDirectory.dir("build/dist/native/linux-x86_64")
val remoteInputDistributionDir = layout.buildDirectory.dir("dist/linux-x86_64")

// The native preflight, the JNI/CMake configuration and both verification modes run on Java 17
// resolved through Gradle's toolchain service. The Gradle daemon's own JVM is NOT necessarily
// Java 17, so System.getProperty("java.home") must never be used as JAVA_HOME here.
val remoteInputJava17 = javaToolchains.launcherFor {
	languageVersion.set(JavaLanguageVersion.of(17))
}

fun remoteInputJava17Home(): String =
	remoteInputJava17.get().metadata.installationPath.asFile.absolutePath

// -PremoteInputRepeatBuild=true forwards --repeat-build. That flag exists only on the shell
// script, so this property is the supported Gradle interface to it.
val remoteInputRepeatBuild = providers.gradleProperty("remoteInputRepeatBuild")
	.map { it.trim().lowercase() in setOf("true", "1", "yes") }
	.orElse(false)

// -PremoteInputJobs must be a positive integer in 1..256. Validated when the native task runs
// rather than at configuration time, so a bad value cannot break unrelated invocations.
fun remoteInputJobs(): String {
	val raw = providers.gradleProperty("remoteInputJobs").getOrElse("4").trim()
	require(raw.matches(Regex("[0-9]+")) && raw.toInt() in 1..256) {
		"-PremoteInputJobs must be a positive integer in 1..256 (got '$raw')"
	}
	return raw
}

// When -PremoteInputSkipBuild=true, buildRemoteInputLinux skips its own invocation of the build
// script. This exists for the containerized clean-Fedora build accepted for SC-1662: the compile
// runs inside a podman Fedora toolchain container whose host-mounted Gradle/JVM environment the
// task cannot reproduce, and staging/verification then run on the host against the container's
// output. The property is opt-in and explicitly trusts that the prebuilt pair was supplied by the
// documented container workflow. Staging still revalidates the pair's measurable properties
// (manifest digest, size, ELF identity, required-symbol inventory) before publishing, and
// verifyRemoteInputLinux validates the manifest again before loading — but that validation cannot
// establish provenance: a separately created artifact with a structurally valid manifest and the
// required symbols would also pass. Provenance for run evidence comes from the recorded container
// build sequence in native-build.md, not from this property.
val remoteInputSkipBuild =
	providers.gradleProperty("remoteInputSkipBuild")
		.map { it.trim().lowercase() in setOf("true", "1", "yes") }
		.orElse(false)

// Preflight and compile the shared library from the pinned vendored sources.
val buildRemoteInputLinux by tasks.registering(Exec::class) {
	group = "native"
	description = "Preflight and compile libRemoteInput.so from pinned sources (Linux x86_64)."

	onlyIf { isLinux && !remoteInputSkipBuild.get() }

	workingDir = projectDir
	doFirst {
		// Resolved at execution time so a host without a Java 17 toolchain can still enumerate
		// tasks and run unrelated builds.
		environment("JAVA_HOME", remoteInputJava17Home())
		val args = mutableListOf(
			"bash",
			remoteInputScript.asFile.absolutePath,
			"--no-stage",
			"--jobs",
			remoteInputJobs(),
		)
		if (remoteInputRepeatBuild.get()) {
			args.add("--repeat-build")
		}
		commandLine(args)
	}
	// Correctness over native-build caching: the resolved toolchain identity is not a declared
	// input, so an artifact whose manifest describes a different JDK or compiler must never be
	// reused from cache. The native build therefore always executes.
	outputs.upToDateWhen { false }
}

/**
 * Stages a validated library/manifest pair as an immutable, content-addressed payload directory
 * and then publishes it by switching a single symlink onto that directory.
 *
 * <p>The visible publish step is one {@code rename(2)} of the symlink, so an interrupted stage can
 * never expose a new library beside an old manifest (or the reverse): the publish point always
 * names a complete, re-validated pair. The previously published payload is left untouched until
 * the swap succeeds, and moving the payload into place is itself a directory rename.
 *
 * <p>Layout under {@code build/dist/native}: {@code .versions/<pair-identity>/} holds the pair
 * (identified by {@code sha256(artifact-sha256 ‖ 0x00 ‖ manifest-sha256)}, not by the artifact
 * alone) and {@code linux-x86_64} is the symlink that consumers resolve.
 */
abstract class StageRemoteInputLinuxTask : DefaultTask() {

	@get:InputFile
	abstract val metadataHelper: RegularFileProperty

	@get:Internal
	abstract val artifact: RegularFileProperty

	@get:Internal
	abstract val manifest: RegularFileProperty

	// The publish point is a symlink, not a directory output: it is not snapshotted, and the task
	// always re-validates (see the registration below).
	@get:Internal
	abstract val stageDir: DirectoryProperty

	@get:Inject
	abstract val execOperations: ExecOperations

	@TaskAction
	fun stage() {
		val libraryFile = artifact.get().asFile
		val manifestFile = manifest.get().asFile
		check(libraryFile.isFile && libraryFile.length() > 0) {
			"Refusing to stage: ${libraryFile.absolutePath} is missing or empty (the build did not produce a validated artifact)."
		}
		check(manifestFile.isFile && manifestFile.length() > 0) {
			"Refusing to stage: ${manifestFile.absolutePath} is missing or empty."
		}
		verify(manifestFile, libraryFile)

		val switchPoint = stageDir.get().asFile
		val parent = switchPoint.parentFile
		parent.mkdirs()
		val versionsDir = File(parent, VERSIONS_DIRECTORY)
		versionsDir.mkdirs()

		// Identified by the pair, not by the library alone: a byte-identical rebuild still produces
		// a different manifest (generatedAtUtc at minimum, and possibly toolchain, repeat-build or
		// patch provenance), and that manifest must not be silently replaced by an older one.
		val identity = pairIdentity(libraryFile, manifestFile)
		val payload = File(versionsDir, identity)

		if (payload.isDirectory) {
			// Already published: re-validate what is there instead of trusting it, and prove it is
			// the pair we were asked to publish. Validating the library against its own sibling
			// manifest is not enough on its own: a modified but internally consistent pair could
			// otherwise be reused under this pair's directory name.
			val existingLibrary = File(payload, LIBRARY_FILENAME)
			val existingManifest = File(payload, MANIFEST_FILENAME)
			verify(existingManifest, existingLibrary)
			check(pairIdentity(existingLibrary, existingManifest) == identity) {
				"Refusing to stage: the existing payload ${payload.absolutePath} validates against " +
					"its own manifest but is not the expected pair $identity. It is deliberately not " +
					"overwritten; remove it explicitly and re-run."
			}
		} else {
			val temporary = Files.createTempDirectory(parent.toPath(), STAGE_DIRECTORY_PREFIX)
			try {
				val stagedLibrary = temporary.resolve(LIBRARY_FILENAME)
				val stagedManifest = temporary.resolve(MANIFEST_FILENAME)
				Files.copy(libraryFile.toPath(), stagedLibrary, StandardCopyOption.REPLACE_EXISTING)
				Files.copy(manifestFile.toPath(), stagedManifest, StandardCopyOption.REPLACE_EXISTING)
				// Validate what actually landed in the temporary directory, not what we intended,
				// and prove the copy still carries the expected identity before publishing it.
				val stagedLibraryFile = stagedLibrary.toFile()
				val stagedManifestFile = stagedManifest.toFile()
				verify(stagedManifestFile, stagedLibraryFile)
				check(pairIdentity(stagedLibraryFile, stagedManifestFile) == identity) {
					"Refusing to stage: the copied pair in ${temporary.toFile().absolutePath} does " +
						"not reproduce the expected identity $identity."
				}
				// Normalise permissions before publishing: the pair is published into a
				// distribution, so it must not inherit the 0600 of a temporary file.
				publishReadable(stagedLibraryFile)
				publishReadable(stagedManifestFile)
				moveAtomically(temporary, payload.toPath(), "staged payload")
			} finally {
				temporary.toFile().deleteRecursively()
			}
		}

		// Captured before the swap: the payload being replaced is retained by identity, not by
		// modification time, so republishing an older payload cannot prune its own predecessor.
		val displaced = publishedIdentity(switchPoint)
		swapSwitchPoint(switchPoint, parent, identity)
		pruneVersions(versionsDir, setOfNotNull(identity, displaced))
		logger.lifecycle(
			"Staged validated artifact pair $identity at ${switchPoint.absolutePath} -> " +
				"$VERSIONS_DIRECTORY/$identity"
		)
	}

	/**
	 * Publishes the payload by renaming a freshly created symlink onto the publish point. A symlink
	 * rename is a single atomic operation, which is what makes the pair switch indivisible; when the
	 * filesystem cannot do it atomically, publishing fails rather than degrading to a non-atomic
	 * copy that could expose a partial pair.
	 */
	private fun swapSwitchPoint(switchPoint: File, parent: File, identity: String) {
		if (Files.exists(switchPoint.toPath()) && !Files.isSymbolicLink(switchPoint.toPath())) {
			// The previous publish layout used a real directory. Removing it here would delete the
			// previous publish point before the replacement exists, so migration is refused and
			// left to the operator instead.
			throw GradleException(
				"Refusing to publish: ${switchPoint.absolutePath} is a real directory, not the " +
					"versioned publish symlink. It is deliberately not removed automatically, " +
					"because an interruption would then leave no publish point at all. Remove it " +
					"explicitly (rm -rf '${switchPoint.absolutePath}', or run `gradlew clean`) and " +
					"re-run."
			)
		}
		val temporaryLink = File(parent, "$SWITCH_POINT_PREFIX${UUID.randomUUID()}")
		try {
			Files.createSymbolicLink(temporaryLink.toPath(), Path.of(VERSIONS_DIRECTORY, identity))
			moveAtomically(temporaryLink.toPath(), switchPoint.toPath(), "publish symlink")
		} finally {
			Files.deleteIfExists(temporaryLink.toPath())
		}
	}

	/** Retains the published pair and the payload it displaced; every older payload is discarded. */
	private fun pruneVersions(versionsDir: File, retained: Set<String>) {
		for (payload in versionsDir.listFiles().orEmpty()) {
			if (payload.isDirectory && payload.name !in retained) {
				payload.deleteRecursively()
			}
		}
	}

	private fun moveAtomically(source: Path, destination: Path, what: String) {
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
		} catch (notAtomic: AtomicMoveNotSupportedException) {
			throw GradleException(
				"Refusing to publish the $what: ${destination.parent} cannot rename atomically, " +
					"so an interrupted stage could expose a partial artifact pair"
			)
		}
	}

	private fun sha256Of(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { stream ->
			val buffer = ByteArray(1 shl 16)
			while (true) {
				val read = stream.read(buffer)
				if (read < 0) {
					break
				}
				digest.update(buffer, 0, read)
			}
		}
		return HexFormat.of().formatHex(digest.digest())
	}

	/**
	 * Identity of a published pair: the artifact digest bound to the manifest digest. Keying on the
	 * library alone would make a rebuild that changes only the manifest reuse the old payload and
	 * discard the new manifest.
	 */
	private fun pairIdentity(libraryFile: File, manifestFile: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		digest.update(sha256Of(libraryFile).toByteArray(Charsets.US_ASCII))
		digest.update(byteArrayOf(0))
		digest.update(sha256Of(manifestFile).toByteArray(Charsets.US_ASCII))
		return HexFormat.of().formatHex(digest.digest())
	}

	/** The identity currently published at the switch point, or null when nothing is published. */
	private fun publishedIdentity(switchPoint: File): String? {
		if (!Files.isSymbolicLink(switchPoint.toPath())) {
			return null
		}
		return try {
			Files.readSymbolicLink(switchPoint.toPath()).fileName?.toString()
		} catch (unreadable: IOException) {
			logger.lifecycle("Could not read the current publish point: ${unreadable.message}")
			null
		}
	}

	private fun verify(manifestFile: File, libraryFile: File) {
		execOperations.exec {
			commandLine(
				"python3",
				metadataHelper.get().asFile.absolutePath,
				"verify-manifest",
				"--manifest",
				manifestFile.absolutePath,
				"--artifact",
				libraryFile.absolutePath,
			)
		}
	}

	private fun publishReadable(file: File) {
		try {
			Files.setPosixFilePermissions(
				file.toPath(),
				PosixFilePermissions.fromString("rw-r--r--"),
			)
		} catch (notPosix: UnsupportedOperationException) {
			file.setReadable(true, false)
		}
	}

	private companion object {
		const val LIBRARY_FILENAME = "libRemoteInput.so"
		const val MANIFEST_FILENAME = "remoteinput-manifest.json"
		const val VERSIONS_DIRECTORY = ".versions"
		const val STAGE_DIRECTORY_PREFIX = ".sc1662-stage."
		const val SWITCH_POINT_PREFIX = ".linux-x86_64."
	}
}

val stageRemoteInputLinux by tasks.registering(StageRemoteInputLinuxTask::class) {
	group = "native"
	description = "Validate the built library and provenance manifest and publish the pair to build/dist atomically."

	onlyIf { isLinux }
	dependsOn(buildRemoteInputLinux)

	metadataHelper.set(remoteInputHelper)
	artifact.set(remoteInputBuildDir.file("libRemoteInput.so"))
	manifest.set(remoteInputBuildDir.file("remoteinput-manifest.json"))
	stageDir.set(remoteInputStageDir)
	// Always re-validate; staging must never be satisfied from a cache.
	outputs.upToDateWhen { false }
}

// Test-support staging task: the same StageRemoteInputLinuxTask implementation, but reading the
// fixture pair from - and publishing under - an isolated root, so the staging regressions never read
// or destroy the real build output in `build/native/remoteinput` and `build/dist/native`. Without
// this, a regression run would overwrite a real artifact and leave a synthetic payload (or a real
// directory) at the production publish point.
val remoteInputRegressionRoot = layout.buildDirectory.dir("sc1662/staging-regressions")
val stageRemoteInputLinuxRegression by tasks.registering(StageRemoteInputLinuxTask::class) {
	group = "verification"
	description = "Stage a fixture pair under an isolated root (staging regressions; never the real output)."

	onlyIf { isLinux }

	metadataHelper.set(remoteInputHelper)
	artifact.set(remoteInputRegressionRoot.map { it.file("native/linux-x86_64/libRemoteInput.so") })
	manifest.set(remoteInputRegressionRoot.map { it.file("native/linux-x86_64/remoteinput-manifest.json") })
	stageDir.set(remoteInputRegressionRoot.map { it.dir("dist/linux-x86_64") })
	outputs.upToDateWhen { false }
}

/**
 * Runs the smoke verifier as a bounded subprocess in three working directories, always against
 * the artifact located through the supplied application root. Nothing here starts application
 * services or attaches to a client process.
 *
 * <p>Development mode launches the verifier from the development runtime classpath; packaged
 * mode launches it from inside the assembled application jar through Spring Boot's
 * {@code PropertiesLauncher}, which is a supported way to select a main class without starting
 * the normal application.
 */
abstract class VerifyRemoteInputTask : DefaultTask() {

	@get:Input
	abstract val verificationMode: Property<String>

	@get:Input
	abstract val packaged: Property<Boolean>

	// Not declared as an input directory: the packaged root holds a ~700 MB jar and snapshotting
	// it would dominate the task. The task always runs, so this is not needed for correctness.
	@get:Internal
	abstract val applicationRoot: DirectoryProperty

	@get:Classpath
	abstract val developmentClasspath: ConfigurableFileCollection

	@get:Input
	abstract val javaHome: Property<String>

	@get:Internal
	abstract val scratchDir: DirectoryProperty

	@get:Inject
	abstract val execOperations: ExecOperations

	@TaskAction
	fun verify() {
		val mode = verificationMode.get()
		check((mode == MODE_PACKAGED) == packaged.get()) {
			"verification mode '$mode' contradicts the packaged flag"
		}

		val root = applicationRoot.get().asFile.canonicalFile
		check(root.isDirectory) { "application root is missing: ${root.absolutePath}" }
		if (packaged.get()) {
			check(File(root, "application.jar").isFile) {
				"packaged verification needs the assembled distribution, but ${root.absolutePath}/application.jar is missing"
			}
		}

		// 1. From the application root itself. Its digest becomes the reference the other two runs
		// are held to.
		val fromRoot = runVerifier(root, root, null, "application root")
		val sha256 = required(fromRoot, "sha256", "application root")
		assertReport(fromRoot, root, sha256, "application root")
		logger.lifecycle(
			"RemoteInput $mode verification OK: canonical=" +
				"${required(fromRoot, "canonical", "application root")} sha256=$sha256 " +
				"revision=${required(fromRoot, "revision", "application root")} " +
				"patch=${required(fromRoot, "patch", "application root")} " +
				"symbols=${fromRoot["symbols"]}"
		)

		// 2. From an unrelated working directory: resolution must not depend on the caller's cwd.
		val unrelated = Files.createTempDirectory("sc1662-unrelated-cwd-").toFile()
		try {
			assertReport(
				runVerifier(unrelated, root, sha256, "unrelated cwd"),
				root,
				sha256,
				"unrelated cwd",
			)
		} finally {
			unrelated.deleteRecursively()
		}

		// 3. From a copied distribution whose path contains spaces.
		val spaced = Files.createTempDirectory(
			scratchDir.get().asFile.toPath(),
			"sc1662 path with spaces ",
		).toFile()
		try {
			val copy = File(spaced, "distribution")
			materialiseCopy(root, copy)
			assertReport(
				runVerifier(copy, copy, sha256, "spaced copy"),
				copy,
				sha256,
				"spaced copy",
			)
		} finally {
			spaced.deleteRecursively()
		}
	}

	private fun runVerifier(
		workingDir: File,
		appRoot: File,
		expectedSha256: String?,
		scenario: String,
	): Map<*, *> {
		val stdout = ByteArrayOutputStream()
		val stderr = ByteArrayOutputStream()
		val java = File(File(javaHome.get(), "bin"), "java").absolutePath

		val result = execOperations.exec {
			this.workingDir = workingDir
			standardOutput = stdout
			errorOutput = stderr
			isIgnoreExitValue = true
			// Bound the subprocess: a native load failure can hang rather than throw.
			timeout.set(Duration.ofMinutes(5))

			val args = mutableListOf(java)
			if (packaged.get()) {
				args.add("-Dloader.main=$VERIFIER_CLASS")
				args.add("-cp")
				args.add(File(appRoot, "application.jar").absolutePath)
				args.add(SPRING_BOOT_LAUNCHER)
			} else {
				args.add("-cp")
				args.add(developmentClasspath.asPath)
				args.add(VERIFIER_CLASS)
			}
			args.add("--mode")
			args.add(verificationMode.get())
			args.add("--app-root")
			args.add(appRoot.absolutePath)
			if (expectedSha256 != null) {
				args.add("--expect-sha256")
				args.add(expectedSha256)
			}
			commandLine(args)
		}

		val text = stdout.toString()
		if (result.exitValue != 0) {
			throw GradleException(
				"RemoteInput ${verificationMode.get()} verification failed (exit ${result.exitValue}) " +
					"with working directory ${workingDir.absolutePath}\n--- stdout ---\n$text\n--- stderr ---\n$stderr"
			)
		}
		check(text.isNotEmpty()) { "verifier produced no output in ${workingDir.absolutePath}" }
		return parseReport(text, scenario)
	}

	/**
	 * Reads the single machine-readable report line. A marked JSON object is deliberate: the
	 * previous whitespace-delimited line silently truncated any path containing a space.
	 */
	private fun parseReport(stdout: String, scenario: String): Map<*, *> {
		val line =
			stdout
				.lineSequence()
				.map { it.trimStart() }
				.firstOrNull { it.startsWith(REPORT_MARKER) }
				?: throw GradleException("$scenario produced no $REPORT_MARKER line:\n$stdout")
		val json = line.removePrefix(REPORT_MARKER).trim()
		val parsed =
			try {
				JsonSlurper().parseText(json)
			} catch (malformed: Exception) {
				throw GradleException("$scenario reported malformed JSON: $json", malformed)
			}
		check(parsed is Map<*, *>) { "$scenario report is not a JSON object: $json" }
		return parsed
	}

	private fun required(report: Map<*, *>, name: String, scenario: String): String =
		(report[name] as? String)
			?: throw GradleException("$scenario report has no string '$name' field: $report")

	/**
	 * Every scenario must report the same mode, the same digest and the same canonical path.
	 * Comparing only the digest would not prove the path that was actually resolved, and the
	 * unrelated-cwd and spaced-path runs are only evidence if their own reports are examined.
	 */
	private fun assertReport(
		report: Map<*, *>,
		appRoot: File,
		expectedSha256: String,
		scenario: String,
	) {
		check(required(report, "status", scenario) == SMOKE_STATUS_OK) {
			"$scenario did not report $SMOKE_STATUS_OK: $report"
		}
		val reportedMode = required(report, "mode", scenario)
		check(reportedMode == verificationMode.get()) {
			"$scenario reported mode '$reportedMode', expected '${verificationMode.get()}'"
		}
		val reportedSha256 = required(report, "sha256", scenario)
		check(reportedSha256 == expectedSha256) {
			"$scenario loaded a different artifact: sha256 $reportedSha256 != $expectedSha256"
		}
		val expected = expectedCanonical(appRoot).absolutePath
		val reported = required(report, "canonical", scenario)
		check(reported == expected) { "$scenario resolved '$reported', expected '$expected'" }
	}

	/** The real path the verifier must resolve for this application root, symlinks included. */
	private fun expectedCanonical(appRoot: File): File =
		File(File(appRoot, NATIVE_SUBDIRECTORY), LIBRARY_FILENAME).canonicalFile

	/** Hard-link where possible, so copying a ~700 MB distribution stays cheap. */
	private fun materialiseCopy(source: File, target: File) {
		target.mkdirs()
		for (child in source.listFiles().orEmpty()) {
			val destination = File(target, child.name)
			if (child.isDirectory) {
				materialiseCopy(child, destination)
			} else {
				try {
					Files.createLink(destination.toPath(), child.toPath())
				} catch (linkUnsupported: Exception) {
					Files.copy(child.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
				}
			}
		}
	}

	private companion object {
		const val MODE_PACKAGED = "packaged"
		const val VERIFIER_CLASS =
			"com.chromascape.utils.core.input.remoteinput.RemoteInputSmokeVerifier"
		const val SPRING_BOOT_LAUNCHER =
			"org.springframework.boot.loader.launch.PropertiesLauncher"
		const val REPORT_MARKER = "SMOKE_REPORT"
		const val SMOKE_STATUS_OK = "SMOKE_OK"
		const val NATIVE_SUBDIRECTORY = "native/linux-x86_64"
		const val LIBRARY_FILENAME = "libRemoteInput.so"
	}
}

/**
 * Validates the two-factor RemoteInput distribution licence gate and returns a failure message when
 * it is unresolved, or null when it is closed. Shared by the gate task and the regression matrix so
 * both exercise exactly the same rule: exactly one machine-readable marker reading `resolved`, plus
 * a non-empty licence file.
 */
fun remoteInputDistributionLicenceFailure(provenanceFile: File, licenceFile: File): String? {
	if (!provenanceFile.isFile) {
		return "Distribution gate unresolved: ${provenanceFile.absolutePath} is missing; it is the " +
			"record that carries the machine-readable resolution marker this task reads."
	}
	val markerRegex = Regex(
		"^top-level-licence-resolution:[ \\t]*(resolved|unresolved)[ \\t]*\$",
		RegexOption.MULTILINE,
	)
	val markers = markerRegex.findAll(provenanceFile.readText()).map { it.groupValues[1] }.toList()
	if (markers.size != 1) {
		return "Distribution gate unresolved: ${provenanceFile.absolutePath} must carry exactly one " +
			"'top-level-licence-resolution: resolved|unresolved' marker, but ${markers.size} were " +
			"found. The marker is what records the human licensing decision."
	}
	if (markers.single() != "resolved") {
		return "Distribution gate unresolved: third-party/RemoteInput/PROVENANCE.md records no " +
			"top-level RemoteInput licence (marker 'unresolved'). Land the resolved licence as " +
			"a non-empty file at third-party/RemoteInput/LICENSE, flip the marker to 'resolved', " +
			"and re-run before distributing a RemoteInput payload. (An empty placeholder, or a licence " +
			"file with the marker still unresolved, does not close the gate; the licence terms " +
			"themselves remain a human review decision.)"
	}
	if (!licenceFile.isFile || licenceFile.length() == 0L) {
		return "Distribution gate unresolved: third-party/RemoteInput/PROVENANCE.md records the " +
			"resolution as 'resolved', but third-party/RemoteInput/LICENSE is missing or empty. " +
			"The provenance marker and the licence file must both be present, because the " +
			"licence terms themselves remain a human review decision."
	}
	return null
}

// The distribution licence gate, recorded in third-party/RemoteInput/PROVENANCE.md. It is its own
// task so it can be exercised on its own. It no longer guards `assembleLinuxDistribution`: the
// packaged distribution ships no RemoteInput payload at all, so the gate is only relevant to
// optional local work that stages and distributes a RemoteInput payload under
// <applicationRoot>/native/linux-x86_64/. The gate requires both a non-empty licence file *and* the
// machine-readable provenance marker, so landing any non-empty file at the licence path without
// recording the resolution in PROVENANCE.md does not close it. Copying the third-party licences that
// do exist is not a resolution, and there is deliberately no override property.
val verifyRemoteInputDistributionLicence by tasks.registering {
	group = "native"
	description = "Fail closed while the top-level RemoteInput licence gate is unresolved."

	onlyIf { isLinux }

	val resolvedLicence = layout.projectDirectory.file("third-party/RemoteInput/LICENSE")
	val provenance = layout.projectDirectory.file("third-party/RemoteInput/PROVENANCE.md")

	doLast {
		val failure = remoteInputDistributionLicenceFailure(provenance.asFile, resolvedLicence.asFile)
		if (failure != null) {
			throw GradleException(failure)
		}
		logger.lifecycle("Distribution licence gate resolved: ${resolvedLicence.asFile.absolutePath}")
	}
}

// Regression matrix for the two-factor licence gate: every marker state crossed with the licence
// file states that the documentation claims. Fixtures live in a temporary directory, so the real
// PROVENANCE.md and LICENSE are never read or modified. Test-support task, in the spirit of
// `nativeMetadataTests`; it is deliberately not wired into `check`.
val verifyRemoteInputDistributionLicenceMatrix by tasks.registering {
	group = "verification"
	description = "Exercise every state of the two-factor RemoteInput licence gate against fixtures."

	onlyIf { isLinux }
	outputs.upToDateWhen { false }

	doLast {
		data class GateCase(
			val name: String,
			val marker: String?,
			val licence: String?,
			val expectClosed: Boolean,
			val expectFragment: String?,
		)

		val cases = listOf(
			GateCase("unresolved + no licence", "top-level-licence-resolution: unresolved", null, false, "marker 'unresolved'"),
			GateCase("unresolved + non-empty licence", "top-level-licence-resolution: unresolved", "terms\n", false, "marker 'unresolved'"),
			GateCase("resolved + missing licence", "top-level-licence-resolution: resolved", null, false, "missing or empty"),
			GateCase("resolved + empty licence", "top-level-licence-resolution: resolved", "", false, "missing or empty"),
			GateCase("resolved + non-empty licence", "top-level-licence-resolution: resolved", "resolved terms\n", true, null),
			GateCase("missing marker", "provenance with no marker", "terms\n", false, "exactly one"),
			GateCase("malformed marker", "top-level-licence-resolution: maybe", "terms\n", false, "exactly one"),
			GateCase("duplicate marker", "top-level-licence-resolution: resolved\ntop-level-licence-resolution: unresolved", "terms\n", false, "exactly one"),
			GateCase("missing provenance file", null, "terms\n", false, "is missing"),
		)

		val root = Files.createTempDirectory("sc1662-licence-gate-matrix-")
		val failures = mutableListOf<String>()
		try {
			for (case in cases) {
				val dir = root.resolve(case.name.replace(Regex("[^A-Za-z0-9]+"), "-")).toFile()
				dir.mkdirs()
				val provenanceFile = File(dir, "PROVENANCE.md")
				if (case.marker != null) {
					provenanceFile.writeText(case.marker + "\n")
				}
				val licenceFile = File(dir, "LICENSE")
				if (case.licence != null) {
					licenceFile.writeText(case.licence)
				}

				val failure = remoteInputDistributionLicenceFailure(provenanceFile, licenceFile)
				val closed = failure == null
				val fragmentOk = case.expectFragment == null || failure?.contains(case.expectFragment) == true
				val caseOk = closed == case.expectClosed && fragmentOk
				if (!caseOk) {
					failures += "${case.name}: expected " +
						"${if (case.expectClosed) "closed" else "unresolved"}, got " +
						"${if (closed) "closed" else "unresolved"}${if (failure != null) " ($failure)" else ""}"
				}
				logger.lifecycle(
					"${if (caseOk) "PASS" else "FAIL"}: ${case.name} -> " +
						"${if (closed) "closed" else "unresolved"}",
				)
			}
		} finally {
			root.toFile().deleteRecursively()
		}
		if (failures.isNotEmpty()) {
			throw GradleException("licence gate matrix failed:\n" + failures.joinToString("\n"))
		}
		logger.lifecycle("licence gate matrix: ${cases.size}/${cases.size} states behaved as documented")
	}
}

/**
 * Assembles the Linux distribution layout —
 *
 * <pre>
 *   &lt;root&gt;/application.jar
 *   &lt;root&gt;/launcher
 *   &lt;root&gt;/licenses/ChromaScape-LICENSE
 * </pre>
 *
 * <p>into a caller-supplied output root. Production and regression assembly deliberately share this
 * one implementation with different output roots, so the regression exercises the shipping code
 * path rather than a copy of it.
 *
 * <p>RemoteInput is deliberately NOT part of this distribution. The vendored upstream sources carry
 * no licence at all, so no distributable terms exist for the sources, and a compiled library would
 * be a derivative work of them regardless. The distribution is cleaned before assembly — including
 * any {@code native/} directory a previous experiment left in the root — so a stale payload cannot
 * survive into a distribution that is supposed to be free of one. The distribution ships neither
 * {@code libRemoteInput.so} nor the third-party/RemoteInput tree nor any RemoteInput licence file;
 * a deployment that supplies its own library under {@code <applicationRoot>/native/linux-x86_64/}
 * is still resolved by the loader at runtime.
 */
abstract class AssembleLinuxDistributionTask : DefaultTask() {

	/** The Boot jar shipped as {@code application.jar}. */
	@get:InputFile
	abstract val bootJar: RegularFileProperty

	/** The ChromaScape licence shipped as {@code licenses/ChromaScape-LICENSE}. */
	@get:InputFile
	abstract val licenceFile: RegularFileProperty

	// The output root is not snapshotted: the task deletes and rebuilds it on every run, and the
	// regression harness asserts on the raw tree afterwards.
	@get:Internal
	abstract val outputRoot: DirectoryProperty

	@get:Inject
	abstract val fileSystemOperations: FileSystemOperations

	@TaskAction
	fun assemble() {
		val root = outputRoot.get().asFile
		root.mkdirs()
		deleteRecursively(root)

		val jarTarget = File(root, "application.jar")
		fileSystemOperations.copy {
			from(bootJar.get().asFile)
			into(root)
			rename { "application.jar" }
		}
		check(jarTarget.isFile && jarTarget.length() > 0) {
			"assembly produced no application.jar at ${jarTarget.absolutePath}"
		}

		// Attribution for what the distribution actually contains. The RemoteInput third-party
		// licences are not copied because no RemoteInput payload is shipped, and copying terms for
		// an absent library would imply a permission the distribution does not rely on.
		val licensesDir = File(root, "licenses")
		fileSystemOperations.copy {
			from(licenceFile.get().asFile)
			into(licensesDir)
			rename { "ChromaScape-LICENSE" }
		}
		val licenceTarget = File(licensesDir, "ChromaScape-LICENSE")
		check(licenceTarget.isFile && licenceTarget.length() > 0) {
			"assembly produced no licenses/ChromaScape-LICENSE at ${licenceTarget.absolutePath}"
		}

		// The launcher derives its application root from its own location, never the
		// caller's working directory.
		val launcher = File(root, "launcher")
		launcher.writeText(
			"""
			|#!/usr/bin/env sh
			|set -eu
			|APP_ROOT="${'$'}(cd -- "${'$'}(dirname -- "${'$'}0")" && pwd -P)"
			|exec "${'$'}{JAVA:-java}" \
			|  -jar "${'$'}APP_ROOT/application.jar" "${'$'}@"
			|""".trimMargin(),
		)
		launcher.setExecutable(true)
		check(launcher.canExecute()) {
			"launcher at ${launcher.absolutePath} is not executable after assembly"
		}
	}

	/** Deletes the previous distribution contents, stale RemoteInput payloads included. */
	private fun deleteRecursively(root: File) {
		for (child in root.listFiles().orEmpty()) {
			if (child.isDirectory) {
				deleteRecursively(child)
			}
			Files.deleteIfExists(child.toPath())
		}
	}
}

// Production assembly into build/dist/linux-x86_64.
val assembleLinuxDistribution by tasks.registering(AssembleLinuxDistributionTask::class) {
	group = "native"
	description = "Assemble the Linux distribution: application jar and launcher."

	onlyIf { isLinux }
	dependsOn(tasks.named("bootJar"))

	bootJar.set(tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar").map { it.archiveFile.get() })
	licenceFile.set(layout.projectDirectory.file("LICENSE"))
	outputRoot.set(remoteInputDistributionDir)
}

// Regression assembly: the same task class against an isolated output root, so the
// distribution-exclusion regression can pre-seed stale RemoteInput payloads and assert that assembly
// removes them without ever touching build/dist/linux-x86_64 or a real native artifact.
val remoteInputExclusionRegressionRoot = layout.buildDirectory.dir("sc1662/distribution-exclusion")
val assembleLinuxDistributionRegression by tasks.registering(AssembleLinuxDistributionTask::class) {
	group = "verification"
	description = "Assemble the distribution into an isolated root (distribution-exclusion regression; never the real output)."

	onlyIf { isLinux }
	dependsOn(tasks.named("bootJar"))

	bootJar.set(tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar").map { it.archiveFile.get() })
	licenceFile.set(layout.projectDirectory.file("LICENSE"))
	outputRoot.set(remoteInputExclusionRegressionRoot)
}

// The licence gate is no longer ordered ahead of the native build or the jar. It existed to stop a
// distribution shipping a library whose terms were unresolved; the distribution no longer contains
// RemoteInput at all, so the gate no longer guards the assembly. Keeping it in these task graphs
// would also fail local native builds over a condition they no longer affect.

// Development verification: load the staged artifact from the development runtime classpath.
val verifyRemoteInputLinux by tasks.registering(VerifyRemoteInputTask::class) {
	group = "native"
	description = "Load the staged artifact and verify required native symbols (no process attachment)."

	onlyIf { isLinux }
	dependsOn(stageRemoteInputLinux)

	verificationMode.set("development")
	packaged.set(false)
	// Exercise the application-root tier of the resolution contract.
	applicationRoot.set(layout.projectDirectory.dir("build/dist"))
	developmentClasspath.from(sourceSets["main"].runtimeClasspath)
	javaHome.set(providers.provider { remoteInputJava17Home() })
	scratchDir.set(layout.buildDirectory)
	outputs.upToDateWhen { false }
}

// There is deliberately no packaged verification task. It loaded the native artifact out of the
// assembled distribution, and the assembled distribution no longer contains one, so it could only
// ever report MISSING_LIBRARY. Development verification (`verifyRemoteInputLinux`) is what exercises
// the loader, and it is unaffected because it resolves through the staged build output rather than
// the distribution. `VerifyRemoteInputTask` keeps its packaged mode for a distribution that ships a
// licensed library again.

// Fixture-only tests for the metadata/provenance helper. Deliberately outside the ordinary
// `check` graph and independent of any native compiler: they need only Python 3.
val nativeMetadataTests by tasks.registering(Exec::class) {
	group = "native"
	description = "Run the fixture-only tests for the native metadata and provenance helper."

	onlyIf { isLinux }

	val tests = layout.projectDirectory.dir("scripts/native/tests").asFile.absolutePath
	// -B: no __pycache__ beside the sources.
	commandLine("python3", "-B", "-m", "unittest", "discover", "-s", tests, "-t", tests)
	outputs.upToDateWhen { false }
}

// Fixture-only SC-1667 Fedora preflight regressions. They operate on a synthetic procfs,
// os-release and ptrace policy tree and never inspect, attach to, signal or modify a real client.
val fedoraPreflightRegressions by tasks.registering(Exec::class) {
	group = "verification"
	description = "Run Fedora host-native preflight fixture regressions."

	onlyIf { isLinux }
	commandLine(
		"bash",
		layout.projectDirectory.file("scripts/fedora/tests/preflight-regressions.sh").asFile.absolutePath,
	)
	outputs.upToDateWhen { false }
}

// Cross-host evidence-schema tests. The production helper creates fail-closed records: a host
// matrix cannot become complete until two distinct Fedora hosts and one Windows host explicitly
// carry PASS evidence for every required case at the same application revision.
val sc1668EvidenceTests by tasks.registering(Exec::class) {
	group = "verification"
	description = "Run SC-1668 cross-host evidence contract regressions."

	val tests = layout.projectDirectory.dir("scripts/qualification/tests").asFile.absolutePath
	commandLine("python3", "-B", "-m", "unittest", "discover", "-s", tests, "-t", projectDir.absolutePath)
	outputs.upToDateWhen { false }
}

// FEDORA-01/02 packaging regressions. They build synthetic distributions and drive the real
// packaging, installer, uninstaller and certification scripts against them, so the shipping code
// path is exercised without a Gradle build, a native toolchain, a client or a database. Every
// path is under a private temporary root; build/dist and build/packages are never touched.
val fedoraPackagingRegressions by tasks.registering(Exec::class) {
	group = "verification"
	description = "Run FEDORA-01/02 packaging, install/uninstall and certification regressions."

	onlyIf { isLinux }
	commandLine(
		"bash",
		layout.projectDirectory.file("scripts/fedora/tests/packaging-regressions.sh").asFile.absolutePath,
	)
	outputs.upToDateWhen { false }
}

// FEDORA-02 packaged-distribution evidence contract. The record module is fail-closed: a package
// matrix cannot become complete until the tarball and RPM records agree on the exact application
// revision and package version, carry distinct artifact digests, and every required case is PASS.
val fedora02EvidenceTests by tasks.registering(Exec::class) {
	group = "verification"
	description = "Run FEDORA-02 packaged-distribution evidence contract regressions."

	val tests = layout.projectDirectory.dir("scripts/qualification/tests").asFile.absolutePath
	commandLine("python3", "-B", "-m", "unittest", "discover", "-s", tests, "-t", projectDir.absolutePath)
	outputs.upToDateWhen { false }
}

// FEDORA-03 live recording and debugger qualification. The live matrix needs a packaged install, a
// running client, a disposable database and a native pair, so it is executed on the qualification
// host by scripts/fedora/certify-live-recording-debugger.sh. These regressions cover everything
// that runs without those prerequisites: the fail-closed evidence contract and WebSocket probe
// through their real CLIs, the probe's frame parser, and the harness's argument refusals.
val fedora03LiveQualificationTests by tasks.registering(Exec::class) {
	group = "verification"
	description = "Run FEDORA-03 live qualification contract, WebSocket probe and harness regressions."

	commandLine(
		"bash",
		layout.projectDirectory.file("scripts/fedora/tests/live-recording-debugger-regressions.sh").asFile.absolutePath,
	)
	outputs.upToDateWhen { false }
}

// SC-754 immutable artifact registry and current Fishing publication closure.
sourceSets.create("artifactCompletionTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
    java.srcDir("src/test/java")
    java.include("com/chromascape/web/artifact/**")
    java.include("com/chromascape/web/aiplayer/prototype/FishingArtifactRegistryIntegrationTest.java")
    java.include("com/chromascape/web/replay/artifact/**")
    java.include("com/chromascape/web/replay/worker/ReplayWorkerPersistenceServiceTest.java")
    resources.srcDir("src/test/resources")
}
configurations.named("artifactCompletionTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
configurations.named("artifactCompletionTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
tasks.register<Test>("artifactCompletionTest") {
    group = "verification"
    description = "Verify SC-754 immutable artifact publication, access, limits, lineage and Fishing wiring."
    testClassesDirs = sourceSets["artifactCompletionTest"].output.classesDirs
    classpath = sourceSets["artifactCompletionTest"].runtimeClasspath
    useJUnitPlatform()
}

// SC-666 requests use actual PostgreSQL commit visibility, never an embedded DB or skipped lane.
sourceSets.create("sc666CompletionTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
    java.srcDir("src/test/java")
    java.include("com/chromascape/web/debug/buildergoal/service/BuilderGoalRunnerServiceTest.java")
    java.include("com/chromascape/web/debug/buildergoal/service/BuilderGoalCodexOrchestrationServiceTest.java")
    java.include("com/chromascape/web/debug/buildergoal/service/BuilderGoalStartRequestPostgresTest.java")
    java.include("com/chromascape/web/debug/buildergoal/service/BuilderGoalRunStateMutationServiceTest.java")
    java.include("com/chromascape/web/debug/buildergoal/controller/BuilderGoalRunnerControllerTest.java")
    java.include("com/chromascape/web/debug/buildergoal/controller/BuilderGoalCodexControllerTest.java")
    resources.srcDir("src/test/resources")
}
configurations.named("sc666CompletionTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
configurations.named("sc666CompletionTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
tasks.test { exclude("**/BuilderGoalStartRequestPostgresTest.class") }
tasks.register<Test>("sc666CompletionTest") {
    group = "verification"
    testClassesDirs = sourceSets["sc666CompletionTest"].output.classesDirs
    classpath = sourceSets["sc666CompletionTest"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("sc666.test.jdbc.url", providers.gradleProperty("sc666TestJdbcUrl").getOrElse(""))
}
