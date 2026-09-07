package com.chromascape.web.aiplayer.routine.source;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.entity.CurrentSliceRun;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureOperation;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureRecord;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureOperationRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureRecordRepository;
import com.chromascape.web.aiplayer.routine.service.CaptureOperationResultService;
import com.chromascape.web.aiplayer.routine.service.CurrentSliceRunService;
import com.chromascape.web.aiplayer.routine.service.Micro6SourceManifestClient;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObject;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObjectRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReference;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReferenceRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBinding;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBindingRepository;
import com.chromascape.web.config.SourceTrustProperties;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal SC-810 PostgreSQL context.
 *
 * <p>The normal application component scan also scans test output because the SC-1431 test
 * bootstraps live in the same package tree. Those nested applications each publish a bean named
 * {@code orchestrationStore}; allowing them into this context makes an otherwise independent source
 * test fail before any source code runs. This configuration deliberately imports only the
 * source/run/capture services and scans only the repositories/entities they persist.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EntityScan(
    basePackageClasses = {
      CurrentSliceRun.class,
      RoutineCaptureOperation.class,
      RoutineCaptureRecord.class,
      SourceManifestReference.class,
      SourceRunBinding.class,
      SourceContentObject.class,
      ScriptStabilizationRun.class
    })
@EnableJpaRepositories(
    basePackageClasses = {
      CurrentSliceRunRepository.class,
      RoutineCaptureOperationRepository.class,
      RoutineCaptureRecordRepository.class,
      SourceManifestReferenceRepository.class,
      SourceRunBindingRepository.class,
      SourceContentObjectRepository.class,
      ScriptStabilizationRunRepository.class
    })
@Import({
  CurrentSliceIdentityValidator.class,
  SourceTrustProperties.class,
  Micro6SourceManifestClient.class,
  SourceManifestFetchService.class,
  VerifiedSourceContentCache.class,
  SourceManifestBindingService.class,
  CurrentSliceRunService.class,
  SourceRunOrchestrationService.class,
  CaptureOperationResultService.class
})
public class Sc810PostgresTestConfiguration {

  /**
   * CurrentSliceRunService only needs the stabilization PASS lookup for these source tests. Keep
   * that lookup real and backed by the isolated database without importing the full stabilization
   * ingestion graph (which is unrelated to SC-810 and pulls in many other repositories).
   */
  @Bean
  StabilizationIngestionService stabilizationIngestionService(
      ScriptStabilizationRunRepository runs) {
    StabilizationIngestionService stub = mock(StabilizationIngestionService.class);
    when(stub.getRunByRunIdAndAttemptId(anyString(), anyString()))
        .thenAnswer(
            invocation ->
                runs.findFirstByRunIdAndAttemptIdOrderByExecutedAtDesc(
                    invocation.getArgument(0), invocation.getArgument(1)));
    return stub;
  }
}
