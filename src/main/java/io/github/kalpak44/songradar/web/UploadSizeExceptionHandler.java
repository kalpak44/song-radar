package io.github.kalpak44.songradar.web;

import jakarta.servlet.http.HttpServletRequest;
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
  public String handleTooLarge(RedirectAttributes redirect, HttpServletRequest request) {
    redirect.addFlashAttribute("errorMessage", "That file is too large.");
    if (!request.getRequestURI().equals("/files/upload")) return "redirect:/settings";
    return request.getQueryString() != null && request.getQueryString().contains("scope=library")
        ? "redirect:/library"
        : "redirect:/";
  }
}
