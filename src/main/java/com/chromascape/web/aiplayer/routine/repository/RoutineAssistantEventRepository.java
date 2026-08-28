package com.chromascape.web.aiplayer.routine.repository;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RoutineAssistantEventRepository extends JpaRepository<RoutineAssistantEvent,Long>{
  List<RoutineAssistantEvent> findByConversationIdAndEventIdGreaterThanOrderByEventIdAsc(String conversationId,Long eventId);
  Optional<RoutineAssistantEvent> findTopByConversationIdOrderByEventIdDesc(String conversationId);
  List<RoutineAssistantEvent> findByConversationIdAndEventIdGreaterThanOrderByEventIdAsc(String conversationId, Long eventId, Pageable pageable);
  Optional<RoutineAssistantEvent> findFirstByConversationIdAndTypeOrderByEventIdDesc(String conversationId, String type);
}