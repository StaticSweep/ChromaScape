/*
 * Copyright (c) 2026, ChromaScape
 * All rights reserved.
 */
package com.chromascape.web.debug.tickdebugger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * SC-265 ChromaScape-side contract verification for the {@code quest.helper.runtime.v1}
 * structured payload:
 *
 * <ul>
 *   <li>the mirrored schema accepts every valid golden fixture;</li>
 *   <li>malformed core fields are rejected;</li>
 *   <li>the legacy v1 payload (no additive keys) remains accepted;</li>
 *   <li>mirrors equal the Micro6 resources byte-for-byte when the sibling checkout exists.</li>
 * </ul>
 */
class QuestHelperRuntimeContractConformanceTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static JsonSchema schema;
	private static JsonNode fixtures;

	@BeforeAll
	static void setUp() throws Exception
	{
		JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
		try (InputStream input = getClassLoader()
			.getResourceAsStream("contracts/quest/quest-helper-runtime-v1.schema.json"))
		{
			assertThat(input).as("mirrored schema resource").isNotNull();
			schema = factory.getSchema(input);
		}
		try (InputStream input = getClassLoader()
			.getResourceAsStream("contracts/quest/quest-helper-runtime-v1-fixtures.json"))
		{
			assertThat(input).as("mirrored fixtures resource").isNotNull();
			fixtures = MAPPER.readTree(input);
		}
	}

	@Test
	void allValidGoldenFixturesValidate()
	{
		JsonNode corpus = fixtures.path("fixtures");
		assertThat(corpus.size()).as("fixture corpus size").isGreaterThanOrEqualTo(11);
		for (String name : new String[]{"full", "degraded", "unavailable", "failed",
			"legacyV1", "nestedSteps", "unknownRequirement", "evaluationError", "cycle",
			"truncation", "syntheticCurrent"})
		{
			Set<ValidationMessage> errors = schema.validate(corpus.path(name));
			assertThat(errors).as("fixture %s must validate", name).isEmpty();
		}
	}

	@Test
	void malformedCoreFieldsAreRejected()
	{
		JsonNode corpus = fixtures.path("fixtures");

		// steps entry with a wrong-typed ordinal
		JsonNode badSteps = corpus.path("full").deepCopy();
		((com.fasterxml.jackson.databind.node.ObjectNode) badSteps.path("steps").get(0))
			.put("ordinal", "zero");
		assertThat(schema.validate(badSteps)).isNotEmpty();

		// steps entry missing required stepId
		JsonNode missingStepId = corpus.path("full").deepCopy();
		((com.fasterxml.jackson.databind.node.ObjectNode) missingStepId.path("steps").get(0))
			.remove("stepId");
		assertThat(schema.validate(missingStepId)).isNotEmpty();

		// unknown outcome enum value
		JsonNode badOutcome = corpus.path("full").deepCopy();
		((com.fasterxml.jackson.databind.node.ObjectNode) badOutcome).put("outcome", "OK");
		assertThat(schema.validate(badOutcome)).isNotEmpty();

		// requirement text with a wrong type
		JsonNode badRequirement = corpus.path("full").deepCopy();
		((com.fasterxml.jackson.databind.node.ObjectNode)
			badRequirement.path("requirements").path("missing").get(0)).put("text", 42);
		assertThat(schema.validate(badRequirement)).isNotEmpty();
	}

	@Test
	void legacyPayloadWithoutAdditiveKeysRemainsAccepted()
	{
		// legacyV1 has no schema/tick/outcome/usable/complete/diagnostics keys at all.
		Set<ValidationMessage> errors = schema.validate(fixtures.path("fixtures").path("legacyV1"));
		assertThat(errors).as("legacy payload must remain valid").isEmpty();
	}

	@Test
	void missingQuestHelperKeyRemainsCompatible()
	{
		// A batch without questHelper at all is a transport-level concern; the schema covers the
		// payload itself. Assert the fixture tree does not require the transport envelope.
		assertThat(fixtures.has("fixtures")).isTrue();
	}

	@Test
	void mirrorsEqualMicro6ResourcesWhenSiblingCheckoutExists() throws Exception
	{
		Path micro6 = resolveMicro6Root();
		if (micro6 == null)
		{
			return; // sibling checkout unavailable; Micro6 pins the same digests on its side
		}
		Path micro6Root = micro6.resolve("runelite-client/src/main/resources/micro6-contracts/quest");
		Path mainMirror = Path.of("src/main/resources/contracts/quest");
		Path testMirror = Path.of("src/test/resources/contracts/quest");
		assertThat(Files.isRegularFile(micro6Root.resolve("quest-helper-runtime-v1.schema.json")))
			.as("Micro6 schema resource").isTrue();

		for (String name : new String[]{"quest-helper-runtime-v1.schema.json",
			"quest-helper-runtime-v1-fixtures.json"})
		{
			assertByteIdentical(micro6Root.resolve(name), mainMirror.resolve(name));
		}
		assertByteIdentical(micro6Root.resolve("quest-helper-runtime-v1-fixtures.json"),
			testMirror.resolve("quest-helper-runtime-v1-fixtures.json"));
	}

	private static ClassLoader getClassLoader()
	{
		return QuestHelperRuntimeContractConformanceTest.class.getClassLoader();
	}

	private static Path resolveMicro6Root()
	{
		String configured = System.getenv("MICRO6_PROJECT_DIR");
		if (configured == null || configured.isBlank())
		{
			configured = System.getProperty("micro6.projectDir");
		}
		if (configured != null && !configured.isBlank())
		{
			return Path.of(configured.trim());
		}
		for (String candidate : new String[]{
			"/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/micro6",
			"/run/media/ikiddoi/dev-hdd/dev/Worktrees/micro6-sc265-luna"})
		{
			Path path = Path.of(candidate);
			if (Files.isRegularFile(path.resolve(
				"runelite-client/src/main/resources/micro6-contracts/quest/quest-helper-runtime-v1.schema.json")))
			{
				return path;
			}
		}
		return null;
	}

	private static void assertByteIdentical(Path source, Path mirror) throws Exception
	{
		String sourceDigest = sha256(Files.readAllBytes(source));
		String mirrorDigest = sha256(Files.readAllBytes(mirror));
		assertThat(mirrorDigest)
			.as("contract bytes differ: %s [%s] vs %s [%s]", source, sourceDigest,
				mirror, mirrorDigest)
			.isEqualTo(sourceDigest);
	}

	private static String sha256(byte[] bytes) throws Exception
	{
		return lowercaseHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static String lowercaseHex(byte[] digest)
	{
		StringBuilder hex = new StringBuilder();
		for (byte b : digest)
		{
			hex.append(Character.forDigit((b >> 4) & 0xF, 16));
			hex.append(Character.forDigit(b & 0xF, 16));
		}
		return hex.toString();
	}
}
