package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.BehaviorGraphDefinition;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BehaviorGraphDefinitionRepository extends JpaRepository<BehaviorGraphDefinition, Long> { Optional<BehaviorGraphDefinition> findByGraphId(String graphId); }
