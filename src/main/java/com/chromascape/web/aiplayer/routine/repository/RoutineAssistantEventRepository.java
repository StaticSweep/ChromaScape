package com.chromascape.web.aiplayer.routine.repository;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RoutineAssistantEventRepository extends JpaRepository<RoutineAssistantEvent,Long>{List<RoutineAssistantEvent> findByConversationIdAndEventIdGreaterThanOrderByEventIdAsc(String conversationId,Long eventId);}
