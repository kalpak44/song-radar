package io.github.kalpak44.songradar.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * An upload over the configured limit is rejected before any controller runs, so without this the
 * user gets a stack trace page instead of being told the file is too large.
 */
@ControllerAdvice
public class UploadSizeExceptionHandler {

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public String handleTooLarge(RedirectAttributes redirect) {
    redirect.addFlashAttribute("errorMessage", "That file is too large.");
    return "redirect:/settings";
  }
}
