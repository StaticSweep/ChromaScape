package com.chromascape.web.aiplayer.schema;

import jakarta.persistence.Entity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/** Uses the same entity scan and naming convention as ChromaScape's application. */
public final class CurrentEntityMappings {
  private CurrentEntityMappings() {}

  static Metadata metadata(StandardServiceRegistry registry) throws ClassNotFoundException {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
    var sources = new MetadataSources(registry);
    // Include package-private provider/orchestration entities, which Spring's scanner sees as well.
    for (var candidate : scanner.findCandidateComponents("com.chromascape")) {
      sources.addAnnotatedClass(Class.forName(candidate.getBeanClassName()));
    }
    return sources
        .getMetadataBuilder()
        .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
        .build();
  }

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    Files.createDirectories(output.getParent());
    Files.deleteIfExists(output);
    Map<String, Object> settings = new HashMap<>();
    settings.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    settings.put("hibernate.boot.allow_jdbc_metadata_access", false);
    settings.put("jakarta.persistence.schema-generation.database.action", "none");
    settings.put("jakarta.persistence.schema-generation.scripts.action", "create");
    settings.put("jakarta.persistence.schema-generation.scripts.create-target", output.toString());
    settings.put("hibernate.hbm2ddl.delimiter", ";");
    try (var registry = new StandardServiceRegistryBuilder().applySettings(settings).build()) {
      SchemaManagementToolCoordinator.process(metadata(registry), registry, settings, action -> {});
    }
    System.out.println("Exported current entity DDL to " + output);
  }
}
