package com.chromascape.web.replay.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CreateReplayRunRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void acceptsWellFormedRequest() {
        var request = new com.chromascape.web.replay.run.dto.CreateReplayRunRequest(
                com.chromascape.web.replay.api.dto.ReplayMode.CAUSAL_SIMULATION,
                "0b9e6c53-5f1a-4d8e-9f6a-7c2b1d3e4f5a",
                "ui-run-abcdef12",
                null);
        assertTrue(validator.validate(request).isEmpty(), "expected no violations");
    }

    @Test
    void rejectsShortIdempotencyKey() {
        var request = new com.chromascape.web.replay.run.dto.CreateReplayRunRequest(
                com.chromascape.web.replay.api.dto.ReplayMode.CAUSAL_SIMULATION,
                "0b9e6c53-5f1a-4d8e-9f6a-7c2b1d3e4f5a",
                "short",
                null);
        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsInjectionCharactersInIdempotencyKey() {
        var request = new com.chromascape.web.replay.run.dto.CreateReplayRunRequest(
                com.chromascape.web.replay.api.dto.ReplayMode.CAUSAL_SIMULATION,
                "0b9e6c53-5f1a-4d8e-9f6a-7c2b1d3e4f5a",
                "<script>alert(1)</script>",
                null);
        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsMalformedManifestId() {
        var request = new com.chromascape.web.replay.run.dto.CreateReplayRunRequest(
                com.chromascape.web.replay.api.dto.ReplayMode.CAUSAL_SIMULATION,
                "not-a-uuid",
                "ui-run-abcdef12",
                null);
        assertFalse(validator.validate(request).isEmpty());
    }
}
