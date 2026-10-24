package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.playlist.PlaylistSourceService;
import io.github.kalpak44.songradar.web.form.AddUrlForm;
import jakarta.validation.Valid;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Plain form posts, each ending in a redirect so a refresh after submitting does not resubmit it.
 * Messages are carried across that redirect as flash attributes.
 */
@Controller
public class SettingsController {

  private static final String REDIRECT_SETTINGS = "redirect:/settings";
  private static final String ADD_URL_FORM = "addUrlForm";
  private static final String SUCCESS_MESSAGE = "successMessage";
  private static final String ERROR_MESSAGE = "errorMessage";
  private static final String BINDING_RESULT_ADD_URL_FORM =
      "org.springframework.validation.BindingResult." + ADD_URL_FORM;

  private final PlaylistSourceService service;
  private final ZoneId zone;

  public SettingsController(PlaylistSourceService service, Clock clock) {
    this.service = service;
    this.zone = clock.getZone();
  }

  @GetMapping("/settings")
  public String settings(Model model) {
    model.addAttribute("activeNav", "settings");
    model.addAttribute("sources", PlaylistSourceView.of(service.findAll(), zone));
    if (!model.containsAttribute(ADD_URL_FORM)) {
      model.addAttribute(ADD_URL_FORM, new AddUrlForm());
    }
    return "settings";
  }

  @PostMapping("/settings/sources/url")
  public String addUrl(
      @Valid @ModelAttribute(ADD_URL_FORM) AddUrlForm form,
      BindingResult binding,
      RedirectAttributes redirect) {

    if (!binding.hasFieldErrors("url") && !PlaylistSourceService.isHttpUrl(form.getUrl())) {
      binding.rejectValue("url", "url.invalid", "Enter a full http:// or https:// URL.");
    }
    if (binding.hasErrors()) {
      // The form bean and its errors have to survive the redirect, or the page renders
      // an empty form with no explanation of what was wrong.
      redirect.addFlashAttribute(BINDING_RESULT_ADD_URL_FORM, binding);
      redirect.addFlashAttribute(ADD_URL_FORM, form);
    } else {
      try {
        var saved = service.addUrl(form.getUrl(), form.getName());
        redirect.addFlashAttribute(SUCCESS_MESSAGE, "Added \"" + saved.name() + "\".");
      } catch (DataAccessException _) {
        redirect.addFlashAttribute(ERROR_MESSAGE, "That playlist could not be saved.");
      }
    }
    return REDIRECT_SETTINGS;
  }

  @PostMapping("/settings/sources/upload")
  public String addFile(
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "name", required = false) String name,
      RedirectAttributes redirect) {

    if (file == null || file.isEmpty()) {
      redirect.addFlashAttribute(ERROR_MESSAGE, "Choose a file to upload.");
    } else if (!PlaylistSourceService.hasAllowedExtension(file.getOriginalFilename())) {
      redirect.addFlashAttribute(ERROR_MESSAGE, "Only .m3u and .m3u8 files can be uploaded.");
    } else if (name != null && name.trim().length() > 200) {
      redirect.addFlashAttribute(ERROR_MESSAGE, "Use a playlist name of at most 200 characters.");
    } else {
      try {
        var saved = service.addFile(file, name);
        redirect.addFlashAttribute(SUCCESS_MESSAGE, "Uploaded \"" + saved.name() + "\".");
      } catch (UncheckedIOException | DataAccessException _) {
        redirect.addFlashAttribute(ERROR_MESSAGE, "That file could not be stored.");
      }
    }
    return REDIRECT_SETTINGS;
  }

  @PostMapping("/settings/sources/{id}/remove")
  public String remove(@PathVariable long id, RedirectAttributes redirect) {
    try {
      if (service.remove(id)) {
        redirect.addFlashAttribute(SUCCESS_MESSAGE, "Playlist removed.");
      } else {
        redirect.addFlashAttribute(ERROR_MESSAGE, "That playlist no longer exists.");
      }
    } catch (UncheckedIOException | DataAccessException _) {
      redirect.addFlashAttribute(ERROR_MESSAGE, "That playlist could not be removed.");
    }
    return REDIRECT_SETTINGS;
  }
}
