package com.chromascape;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = com.chromascape.web.ChromaScapeApplication.class)
@ActiveProfiles("test")
class ChromaScapeApplicationTests {

  @Test
  void contextLoads() {}
}
