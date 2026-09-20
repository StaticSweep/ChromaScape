/*
 * Copyright (c) 2026, ChromaScape
 * All rights reserved.
 */
package com.chromascape.web.replay.playback;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.replay.playback.dto.ReplayPlaybackFramePageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SC-265 replay preservation: the structured {@code questHelper} payload stored in
 * {@code TickSnapshot} must reach replay clients as byte-equivalent JSON structure — exact step
 * order, requirement buckets, and diagnostics unchanged. ChromaScape must not reorder or
 * lossy-rewrite the payload.
 */
class QuestHelperReplayPreservationTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode fixture(String name) throws Exception
	{
		try (InputStream input = QuestHelperReplayPreservationTest.class.getClassLoader()
			.getResourceAsStream("contracts/quest/quest-helper-runtime-v1-fixtures.json"))
		{
			assertThat(input).as("fixture corpus").isNotNull();
			return MAPPER.readTree(input).path("fixtures").path(name);
		}
	}

	@Test
	void replayFramePageCarriesStructuredPayloadUnchanged() throws Exception
	{
		JsonNode full = fixture("full");

		ReplayPlaybackFramePageResponse.FrameData frame =
			new ReplayPlaybackFramePageResponse.FrameData();
		frame.setQuestHelper(full);
		ReplayPlaybackFramePageResponse response = new ReplayPlaybackFramePageResponse(
			"rec-1", "sess-1", List.of(frame), null, false);

		String serialized = MAPPER.writeValueAsString(response);
		JsonNode roundTripped = MAPPER.readTree(serialized)
			.path("frames").get(0).path("questHelper");

		assertThat(roundTripped).isEqualTo(full);
	}

	@Test
	void degradedDiagnosticsAndOrderSurviveReplayRoundTrip() throws Exception
	{
		JsonNode degraded = fixture("degraded");

		ReplayPlaybackFramePageResponse.FrameData frame =
			new ReplayPlaybackFramePageResponse.FrameData();
		frame.setQuestHelper(degraded);
		ReplayPlaybackFramePageResponse response = new ReplayPlaybackFramePageResponse(
			"rec-1", "sess-1", List.of(frame), null, false);

		JsonNode roundTripped = MAPPER.readTree(MAPPER.writeValueAsString(response))
			.path("frames").get(0).path("questHelper");

		assertThat(roundTripped.path("outcome").asText()).isEqualTo("DEGRADED");
		assertThat(roundTripped.path("diagnostics").size()).isEqualTo(2);
		assertThat(roundTripped.path("diagnostics").get(0).path("code").asText())
			.isEqualTo("REQUIREMENT_UNSUPPORTED");
		assertThat(roundTripped.path("steps").get(0).path("stepId").asText())
			.isEqualTo(degraded.path("steps").get(0).path("stepId").asText());
	}

	@Test
	void liveTickAndReplayCardsReceiveEqualPayloads() throws Exception
	{
		// The same fixture stored via ingestion must serialize identically whether it reaches a
		// live debugger card (raw batch field) or a replay card (frame page DTO).
		JsonNode fixture = fixture("failed");

		com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage batch =
			new com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage();
		batch.setQuestHelper(MAPPER.convertValue(fixture,
			new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>()
			{
			}));
		JsonNode liveCard = MAPPER.valueToTree(batch).path("questHelper");

		ReplayPlaybackFramePageResponse.FrameData frame =
			new ReplayPlaybackFramePageResponse.FrameData();
		frame.setQuestHelper(fixture);
		ReplayPlaybackFramePageResponse response = new ReplayPlaybackFramePageResponse(
			"rec-1", "sess-1", List.of(frame), null, false);
		JsonNode replayCard = MAPPER.readTree(MAPPER.writeValueAsString(response))
			.path("frames").get(0).path("questHelper");

		assertThat(liveCard).isEqualTo(replayCard);
		assertThat(liveCard.path("outcome").asText()).isEqualTo("FAILED");
		assertThat(liveCard.path("diagnostics").get(0).path("severity").asText())
			.isEqualTo("ERROR");
	}
}
