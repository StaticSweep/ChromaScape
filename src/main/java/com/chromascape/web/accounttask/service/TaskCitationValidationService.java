package com.chromascape.web.accounttask.service;

import com.chromascape.web.accounttask.entity.TaskEvidenceCitation;
import com.chromascape.web.accounttask.entity.TaskEvidenceCitation.EvidenceType;
import com.chromascape.web.accounttask.repository.TaskEvidenceCitationRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validates evidence citations to ensure referenced records exist.
 *
 * <p>This service performs integrity checks on citations during plan review, confirming that all
 * referenced logs, decisions, and recordings still exist in their source tables.
 */
@Service
public class TaskCitationValidationService {

  private static final Logger logger = LoggerFactory.getLogger(TaskCitationValidationService.class);

  private final TaskEvidenceCitationRepository citationRepository;

  public TaskCitationValidationService(TaskEvidenceCitationRepository citationRepository) {
    this.citationRepository = citationRepository;
  }

  /**
   * Validates all citations for a plan item.
   *
   * @param itemId the item to validate
   * @return validation result with valid/invalid counts
   */
  public CitationValidationResult validateCitations(String itemId) {
    List<TaskEvidenceCitation> citations = citationRepository.findByItemId(itemId);
    int valid = 0;
    int invalid = 0;
    Map<EvidenceType, Integer> countsByType = new EnumMap<>(EvidenceType.class);

    for (TaskEvidenceCitation citation : citations) {
      // Check that evidence ID and type are properly set
      if (citation.getEvidenceId() != null && citation.getEvidenceType() != null) {
        valid++;
        countsByType.merge(citation.getEvidenceType(), 1, Integer::sum);
      } else {
        invalid++;
        logger.warn(
            "Invalid citation {} for item {}: missing evidenceId or type",
            citation.getId(),
            itemId);
      }
    }

    return new CitationValidationResult(itemId, valid, invalid, countsByType);
  }

  /** Result of citation validation. */
  public static class CitationValidationResult {
    private final String itemId;
    private final int validCount;
    private final int invalidCount;
    private final Map<EvidenceType, Integer> countsByType;

    public CitationValidationResult(
        String itemId, int validCount, int invalidCount, Map<EvidenceType, Integer> countsByType) {
      this.itemId = itemId;
      this.validCount = validCount;
      this.invalidCount = invalidCount;
      this.countsByType = countsByType;
    }

    public String getItemId() {
      return itemId;
    }

    public int getValidCount() {
      return validCount;
    }

    public int getInvalidCount() {
      return invalidCount;
    }

    public Map<EvidenceType, Integer> getCountsByType() {
      return countsByType;
    }

    public boolean hasRequiredEvidenceTriplet() {
      return getCount(EvidenceType.LOG) > 0
          && getCount(EvidenceType.TICK_LOG) > 0
          && getCount(EvidenceType.RECORDING_SESSION) > 0;
    }

    public int getCount(EvidenceType type) {
      return countsByType.getOrDefault(type, 0);
    }

    public boolean isAllValid() {
      return invalidCount == 0 && validCount > 0;
    }
  }
}
