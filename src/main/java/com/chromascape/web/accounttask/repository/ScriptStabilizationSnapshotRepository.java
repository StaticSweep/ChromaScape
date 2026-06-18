package com.chromascape.web.accounttask.repository;

import com.chromascape.web.accounttask.entity.ScriptStabilizationSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptStabilizationSnapshotRepository
    extends JpaRepository<ScriptStabilizationSnapshot, Long> {
  List<ScriptStabilizationSnapshot> findTop20ByOrderByCapturedAtDesc();

  List<ScriptStabilizationSnapshot> findTop20ByFamilyOrderByCapturedAtDesc(String family);

  List<ScriptStabilizationSnapshot> findTop20ByScriptKeyOrderByCapturedAtDesc(String scriptKey);

  List<ScriptStabilizationSnapshot> findTop20ByScriptKeyAndFamilyOrderByCapturedAtDesc(
      String scriptKey, String family);

  Optional<ScriptStabilizationSnapshot>
      findFirstByScriptKeyAndFamilyOrderByCapturedAtDesc(String scriptKey, String family);
}
