package com.chromascape.web.aiplayer.routine.repository;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantConversation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RoutineAssistantConversationRepository extends JpaRepository<RoutineAssistantConversation,Long>{Optional<RoutineAssistantConversation> findByConversationId(String conversationId);}
