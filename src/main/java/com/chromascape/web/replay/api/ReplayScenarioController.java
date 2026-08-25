package com.chromascape.web.replay.api;

import com.chromascape.web.replay.api.dto.CompileScenarioRequest;
import com.chromascape.web.replay.api.dto.ScenarioManifestResponse;
import com.chromascape.web.replay.compiler.ScenarioCompiler;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/replay")
@Validated
public class ReplayScenarioController {

    private final ScenarioCompiler compiler;
    private final ScenarioManifestRepository manifestRepository;

    public ReplayScenarioController(ScenarioCompiler compiler,
                                    ScenarioManifestRepository manifestRepository) {
        this.compiler = compiler;
        this.manifestRepository = manifestRepository;
    }

    @PostMapping("/scenarios")
    public ResponseEntity<ScenarioManifestResponse> compile(
            @Valid @RequestBody CompileScenarioRequest request) {
        ScenarioManifest manifest = compiler.compile(
                request.recordingId(),
                UUID.fromString(request.curationId()),
                request.seed());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(manifest));
    }

    @GetMapping("/scenarios/{manifestId}")
    public ResponseEntity<ScenarioManifestResponse> getManifest(@PathVariable UUID manifestId) {
        ScenarioManifest manifest = manifestRepository.findById(manifestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Manifest not found"));
        return ResponseEntity.ok(toResponse(manifest));
    }

    private ScenarioManifestResponse toResponse(ScenarioManifest m) {
        return new ScenarioManifestResponse(
                m.getId(), m.getRecordingId(), m.getCurationId(),
                m.getManifestChecksum(), m.getCompleteness(),
                m.getPublishState(), m.getCreatedAt());
    }
}
