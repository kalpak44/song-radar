package io.github.kalpak44.songradar.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class UploadSizeExceptionHandlerTest {

  @Test
  void redirectsToSettingsWithAnError() {
    RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

    String view = new UploadSizeExceptionHandler().handleTooLarge(redirect);

    assertEquals("redirect:/settings", view);
    assertEquals("That file is too large.", redirect.getFlashAttributes().get("errorMessage"));
  }
}
