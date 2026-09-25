package com.chromascape.web.debug.patchloop.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.debug.patchloop.graph.EdgeCondition;
import com.chromascape.web.debug.patchloop.graph.GraphCompilationException;
import com.chromascape.web.debug.patchloop.graph.PatchLoopGraphCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultBmadGraphTemplateTest {

  @Test
  @DisplayName("Default BMAD template compiles and supports retry loop semantics")
  void defaultTemplateCompilesWithRetryLoop() throws GraphCompilationException {
    PatchLoopGraphCompiler compiler = new PatchLoopGraphCompiler();

    var graph = compiler.compile(DefaultBmadGraphTemplate.getTemplate());
    assertNotNull(graph);

    assertNotNull(graph.getNode("E"));
    assertNotNull(graph.getNode("F"));
    assertNotNull(graph.getNode("G"));
    assertNotNull(graph.getNode("H"));
    assertNotNull(graph.getNode("I"));

    var outgoing = graph.getOutgoingEdges("I");
    assertEquals(3, outgoing.size(), "evaluate_success node should have 3 outgoing edges");

    assertTrue(outgoing.stream().anyMatch(e -> e.condition() == EdgeCondition.SUCCESS));
    assertTrue(outgoing.stream().anyMatch(e -> e.condition() == EdgeCondition.RETRY));
    assertTrue(outgoing.stream().anyMatch(e -> e.condition() == EdgeCondition.FAILURE));
    assertTrue(
        outgoing.stream()
            .anyMatch(e -> e.condition() == EdgeCondition.SUCCESS && "J".equals(e.to())));
    assertTrue(
        outgoing.stream()
            .anyMatch(e -> e.condition() == EdgeCondition.RETRY && "D".equals(e.to())));
    assertTrue(
        outgoing.stream()
            .anyMatch(e -> e.condition() == EdgeCondition.FAILURE && "J".equals(e.to())));
  }
}
