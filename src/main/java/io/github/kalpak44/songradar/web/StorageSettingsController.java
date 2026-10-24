package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.storage.StorageService;
import io.github.kalpak44.songradar.web.form.StorageLimitForm;
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
  private static final String STORAGE_LIMIT_FORM = "storageLimitForm";
  private static final String SUCCESS_MESSAGE = "successMessage";
  private static final String ERROR_MESSAGE = "errorMessage";
  private static final String BINDING_RESULT_STORAGE_LIMIT_FORM =
      "org.springframework.validation.BindingResult." + STORAGE_LIMIT_FORM;

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
      if (!model.containsAttribute(STORAGE_LIMIT_FORM)) {
        StorageLimitForm form = new StorageLimitForm();
        form.setLimitGb(new java.math.BigDecimal(view.limitGb()));
        model.addAttribute(STORAGE_LIMIT_FORM, form);
      }
    } catch (UncheckedIOException | DataAccessException _) {
      model.addAttribute(
          "storageError",
          "Storage information is temporarily unavailable. Check the configured path.");
    }
    return STORAGE;
  }

  @PostMapping("/settings/storage")
  public String save(
      @Valid @ModelAttribute(STORAGE_LIMIT_FORM) StorageLimitForm form,
      BindingResult binding,
      RedirectAttributes redirect) {
    if (binding.hasErrors()) {
      preserveValidation(binding, form, redirect);
    } else {
      try {
        storageService.saveLimit(form.getLimitGb());
        redirect.addFlashAttribute(SUCCESS_MESSAGE, "Storage limit saved.");
      } catch (IllegalArgumentException e) {
        binding.rejectValue("limitGb", "limit.invalid", e.getMessage());
        preserveValidation(binding, form, redirect);
      } catch (UncheckedIOException | DataAccessException _) {
        redirect.addFlashAttribute(
            ERROR_MESSAGE, "The storage limit could not be saved. Try again.");
      }
    }
    return REDIRECT_STORAGE;
  }

  private static void preserveValidation(
      BindingResult binding, StorageLimitForm form, RedirectAttributes redirect) {
    redirect.addFlashAttribute(BINDING_RESULT_STORAGE_LIMIT_FORM, binding);
    redirect.addFlashAttribute(STORAGE_LIMIT_FORM, form);
  }
}
