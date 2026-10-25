package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.storage.StorageService;
import io.github.kalpak44.songradar.web.form.RecordRetentionForm;
import jakarta.validation.Valid;
import java.io.UncheckedIOException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class StorageSettingsController {

  private static final String REDIRECT_STORAGE = "redirect:/settings/storage";
  private static final String STORAGE = "storage";
  private static final String RECORD_RETENTION_FORM = "recordRetentionForm";
  private static final String SUCCESS_MESSAGE = "successMessage";
  private static final String ERROR_MESSAGE = "errorMessage";
  private static final String BINDING_RESULT_RECORD_RETENTION_FORM =
      "org.springframework.validation.BindingResult." + RECORD_RETENTION_FORM;

  private final StorageService storageService;

  public StorageSettingsController(StorageService storageService) {
    this.storageService = storageService;
  }

  @GetMapping("/settings/storage")
  public String storage(Model model) {
    model.addAttribute("activeNav", STORAGE);
    try {
      StorageView view = StorageView.of(storageService.snapshot());
      model.addAttribute(STORAGE, view);
    } catch (UncheckedIOException | DataAccessException _) {
      model.addAttribute(
          "storageError",
          "Storage information is temporarily unavailable. Check the configured path.");
    }
    if (!model.containsAttribute(RECORD_RETENTION_FORM)) {
      RecordRetentionForm form = new RecordRetentionForm();
      try {
        form.setRecordsPerListener(storageService.recordsPerListener());
      } catch (DataAccessException _) {
        model.addAttribute(
            "storageError", "Storage settings are temporarily unavailable. Try again shortly.");
      }
      model.addAttribute(RECORD_RETENTION_FORM, form);
    }
    return STORAGE;
  }

  @PostMapping("/settings/storage/record-retention")
  public String save(
      @Valid @ModelAttribute(RECORD_RETENTION_FORM) RecordRetentionForm form,
      BindingResult binding,
      RedirectAttributes redirect) {
    if (binding.hasErrors()) {
      preserveValidation(binding, form, redirect);
    } else {
      try {
        storageService.saveRecordsPerListener(form.getRecordsPerListener());
        redirect.addFlashAttribute(SUCCESS_MESSAGE, "Recording retention saved.");
      } catch (IllegalArgumentException e) {
        binding.rejectValue("recordsPerListener", "retention.invalid", e.getMessage());
        preserveValidation(binding, form, redirect);
      } catch (UncheckedIOException | DataAccessException _) {
        redirect.addFlashAttribute(
            ERROR_MESSAGE, "Recording retention could not be saved. Try again.");
      }
    }
    return REDIRECT_STORAGE;
  }

  private static void preserveValidation(
      BindingResult binding, RecordRetentionForm form, RedirectAttributes redirect) {
    redirect.addFlashAttribute(BINDING_RESULT_RECORD_RETENTION_FORM, binding);
    redirect.addFlashAttribute(RECORD_RETENTION_FORM, form);
  }
}
