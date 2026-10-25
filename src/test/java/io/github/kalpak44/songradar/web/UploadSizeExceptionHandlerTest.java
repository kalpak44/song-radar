package io.github.kalpak44.songradar.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class UploadSizeExceptionHandlerTest {

  @Test
  void redirectsToSettingsWithAnError() {
    RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

    MockHttpServletRequest request = new MockHttpServletRequest();
    String view = new UploadSizeExceptionHandler().handleTooLarge(redirect, request);

    assertEquals("redirect:/settings", view);
    assertEquals("That file is too large.", redirect.getFlashAttributes().get("errorMessage"));
  }

  @Test
  void redirectsLargeLibraryUploadBackToTheFileManager() {
    RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/files/upload");

    String view = new UploadSizeExceptionHandler().handleTooLarge(redirect, request);

    assertEquals("redirect:/", view);
    assertEquals("That file is too large.", redirect.getFlashAttributes().get("errorMessage"));
  }

  @Test
  void keepsLargeLibraryUploadsInsideTheLibraryView() {
    RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/files/upload");
    request.setQueryString("scope=library");

    String view = new UploadSizeExceptionHandler().handleTooLarge(redirect, request);

    assertEquals("redirect:/library", view);
  }
}
