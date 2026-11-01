package com.chromascape.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The SC-2150 review workspace must be reachable from a clean build. The route once existed only
 * as an uncommitted local edit while the template was committed, so a clean checkout served the
 * page as 404 while the owner guide linked to it.
 */
class ServePagesMouseVideoTest {

  @Test
  void servesTheMouseVideoWorkspace() throws Exception {
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new ServePages()).build();

    mvc.perform(get("/mouse/video")).andExpect(status().isOk()).andExpect(view().name("mouse-video"));
  }

  @Test
  void theMouseVideoTemplateShipsWithTheRoute() {
    assertThat(new ClassPathResource("templates/mouse-video.html").exists()).isTrue();
  }
}
