package io.github.kalpak44.songradar.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * @WebMvcTest leaves the datasource out, so the homepage is checked without a database.
 */
@WebMvcTest(HomeController.class)
class HomeControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void homepageRendersWelcome() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(view().name("index"))
        .andExpect(content().string(containsString("Hello")))
        .andExpect(content().string(containsString("SongRadar")));
  }
}
