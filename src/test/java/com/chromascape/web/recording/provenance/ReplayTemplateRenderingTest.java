package com.chromascape.web.recording.provenance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class ReplayTemplateRenderingTest {
  @Test
  void rendersCompleteCatalogScriptWithNestedJavaScriptArrays() {
    var resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    var engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    var html = engine.process("replay", new Context());
    assertTrue(html.contains("[['account', 'Account → Task']"));
    assertTrue(html.contains("const catalogLoadMore"));
    assertTrue(html.contains("</html>"));
  }
}
