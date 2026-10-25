package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.activity.ActivityService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ActivityController {

  private final ActivityService activity;

  public ActivityController(ActivityService activity) {
    this.activity = activity;
  }

  @GetMapping("/activity")
  public String activity(Model model) {
    var rows = activity.lastDay();
    model.addAttribute("activeNav", "activity");
    model.addAttribute("rows", rows);
    // The page says so when the window is full, because the oldest of the day is then missing
    // and an empty-looking gap would otherwise read as nothing having happened.
    model.addAttribute("truncated", rows.size() >= activity.pageLimit());
    return "activity";
  }

  @PostMapping("/activity/clear")
  public String clear(RedirectAttributes redirect) {
    long removed = activity.clear();
    redirect.addFlashAttribute(
        "successMessage",
        removed == 0
            ? "There was nothing to clear."
            : "Cleared " + removed + (removed == 1 ? " entry." : " entries."));
    return "redirect:/activity";
  }
}
