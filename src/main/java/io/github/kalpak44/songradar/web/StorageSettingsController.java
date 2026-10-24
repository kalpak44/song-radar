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

  private final StorageService storage;

  public StorageSettingsController(StorageService storage) {
    this.storage = storage;
  }

  @GetMapping("/settings/storage")
  public String storage(Model model) {
    model.addAttribute("activeNav", "storage");
    try {
      StorageView view = StorageView.of(storage.snapshot());
      model.addAttribute("storage", view);
      if (!model.containsAttribute("storageLimitForm")) {
        StorageLimitForm form = new StorageLimitForm();
        form.setLimitGb(new java.math.BigDecimal(view.limitGb()));
        model.addAttribute("storageLimitForm", form);
      }
    } catch (UncheckedIOException | DataAccessException e) {
      model.addAttribute(
          "storageError",
          "Storage information is temporarily unavailable. Check the configured path.");
    }
    return "storage";
  }

  @PostMapping("/settings/storage")
  public String save(
      @Valid @ModelAttribute("storageLimitForm") StorageLimitForm form,
      BindingResult binding,
      RedirectAttributes redirect) {
    if (binding.hasErrors()) {
      preserveValidation(binding, form, redirect);
      return REDIRECT_STORAGE;
    }

    try {
      storage.saveLimit(form.getLimitGb());
      redirect.addFlashAttribute("successMessage", "Storage limit saved.");
    } catch (IllegalArgumentException e) {
      binding.rejectValue("limitGb", "limit.invalid", e.getMessage());
      preserveValidation(binding, form, redirect);
    } catch (UncheckedIOException | DataAccessException e) {
      redirect.addFlashAttribute(
          "errorMessage", "The storage limit could not be saved. Try again.");
    }
    return REDIRECT_STORAGE;
  }

  private static void preserveValidation(
      BindingResult binding, StorageLimitForm form, RedirectAttributes redirect) {
    redirect.addFlashAttribute(
        "org.springframework.validation.BindingResult.storageLimitForm", binding);
    redirect.addFlashAttribute("storageLimitForm", form);
  }
}
