package com.chromascape.web.replay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Registers replay configuration. {@link ReplayProperties} is bound
 * unconditionally: controllers, services, and workers are component-scanned
 * regardless of the enabled flag and require the bean at startup. Runtime
 * behavior (worker scheduling, xRSPS handshake) is gated by
 * {@code replay.enabled} / {@code replay.worker.enabled} checks instead.
 */
@Configuration
@EnableConfigurationProperties(ReplayProperties.class)
@EnableScheduling
public class ReplayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ReplayConfiguration.class);

    public ReplayConfiguration(ReplayProperties replayProperties) {
        log.info(
                "Replay configuration loaded: enabled={}, worker={}, xrsps executable={}",
                replayProperties.isEnabled(),
                replayProperties.getWorker().isEnabled(),
                replayProperties.getXrsps().getExecutable());
    }

    /**
     * Canonical JSON mapper for replay artifacts. Must produce byte-identical
     * output to the TypeScript canonical JSON implementation.
     */
    // defaultCandidate=false keeps this specialised mapper out of unqualified by-type
    // injection, so it never competes with the application-wide ObjectMapper.
    @Bean(name = "replayCanonicalMapper", defaultCandidate = false)
    public ObjectMapper replayCanonicalMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, false);
        return mapper;
    }
}
