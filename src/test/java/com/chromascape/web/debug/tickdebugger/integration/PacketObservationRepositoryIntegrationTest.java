package com.chromascape.web.debug.tickdebugger.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chromascape.web.debug.tickdebugger.entity.PacketObservation;
import com.chromascape.web.debug.tickdebugger.entity.PacketObservationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL regression coverage for nullable packet filters being bound as bytea. */
@DataJpaTest(
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@org.springframework.test.context.ContextConfiguration(
    classes = PacketObservationRepositoryIntegrationTest.PacketObservationSlice.class)
class PacketObservationRepositoryIntegrationTest {

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = PacketObservation.class)
  @EnableJpaRepositories(basePackageClasses = PacketObservationRepository.class)
  static class PacketObservationSlice {}

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("packet_repository")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private PacketObservationRepository repository;

  @Test
  void nullableAndCaseInsensitiveTextFiltersExecuteWithoutByteaInference() {
    PacketObservation packet =
        new PacketObservation("session-1", 42L, "source-1", "rsprox-packet/v2", 1L, 10L);
    packet.setDirection("SERVER_TO_CLIENT");
    packet.setOpcode(81);
    packet.setProtocolName("NPC_UPDATE");
    packet.setEncodedSize(12);
    packet.setDecodedType("NpcMovement");
    packet.setDecodeStatus("DECODED");
    packet.setDecodeError("Readable Failure");
    repository.saveAndFlush(packet);

    List<PacketObservation> unfiltered =
        repository.searchPacketsPageOrdered(
            "session-1", null, 0L, 100L, null, null, null, null, null,
            null, null, null, null, null, PageRequest.of(0, 20));
    assertEquals(1, unfiltered.size());

    List<PacketObservation> filtered =
        repository.searchPacketsPageOrdered(
            "session-1", null, 0L, 100L, null, "npc_update", null, null, "movement",
            null, null, null, null, null, PageRequest.of(0, 20));
    assertEquals(1, filtered.size());
  }
}
