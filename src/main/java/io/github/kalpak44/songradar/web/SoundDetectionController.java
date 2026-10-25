package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.detection.SoundDetectionService;
import io.github.kalpak44.songradar.detection.SoundDetectionService.DownloadPlanResult;
import io.github.kalpak44.songradar.detection.SoundDetectionService.ManualAttemptResult;
import io.github.kalpak44.songradar.listener.ListenerRecording;
import io.github.kalpak44.songradar.listener.ListenerRecordingRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class SoundDetectionController {

  private static final int PAGE_SIZE = 30;

  private final SoundDetectionService service;
  private final ListenerRecordingRepository recordings;

  public SoundDetectionController(
      SoundDetectionService service, ListenerRecordingRepository recordings) {
    this.service = service;
    this.recordings = recordings;
  }

  @GetMapping("/detections")
  public String detections(@RequestParam(defaultValue = "1") int page, Model model) {
    long total = service.count();
    int pages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    int current = Math.max(1, Math.min(page, pages));
    var rows = service.page(current, PAGE_SIZE);
    model.addAttribute("activeNav", "detections");
    model.addAttribute("rows", rows);
    model.addAttribute("configured", service.isConfigured());
    model.addAttribute("pendingCount", service.pendingCount());
    model.addAttribute("total", total);
    model.addAttribute("currentPage", current);
    model.addAttribute("totalPages", pages);
    return "detections";
  }

  @PostMapping("/recordings/{recordingId}/detect")
  public String detectRecording(
      @PathVariable long recordingId,
      org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
    ListenerRecording recording = recordings.findById(recordingId).orElse(null);
    if (recording == null || recording.filePath() == null) {
      redirect.addFlashAttribute(
          "errorMessage", "That recording is no longer available to detect.");
      return "redirect:/recordings";
    }
    ManualAttemptResult result = service.manualAttempt(recording);
    switch (result) {
      case QUEUED -> redirect.addFlashAttribute("successMessage", "Detection attempt queued.");
      case ALREADY_PENDING ->
          redirect.addFlashAttribute(
              "successMessage", "Detection is already in progress for that recording.");
      case DISABLED ->
          redirect.addFlashAttribute(
              "errorMessage",
              "Detection is not configured. Add SONGRADAR_AUDD_API_TOKEN to enable it.");
      case INVALID_RECORDING ->
          redirect.addFlashAttribute("errorMessage", "That recording cannot be detected.");
    }
    return "redirect:/recordings";
  }

  @PostMapping("/detections/clear")
  public String clear(org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
    long removed = service.clear();
    redirect.addFlashAttribute(
        "successMessage",
        removed == 0
            ? "There was nothing to clear."
            : "Cleared " + removed + (removed == 1 ? " detection." : " detections."));
    return "redirect:/detections";
  }

  @PostMapping("/detections/{detectionId}/download")
  public String findAndDownload(
      @PathVariable long detectionId,
      org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
    DownloadPlanResult result = service.planDownload(detectionId);
    switch (result) {
      case PLANNED ->
          redirect.addFlashAttribute(
              "successMessage",
              "Searched the music sources for that track. Check the listener's library folder.");
      case NOT_FOUND -> redirect.addFlashAttribute("errorMessage", "Detection not found.");
      case NOT_IDENTIFIED ->
          redirect.addFlashAttribute("errorMessage", "Only identified tracks can be downloaded.");
      case RECORDING_EXPIRED ->
          redirect.addFlashAttribute(
              "errorMessage",
              "The source recording has expired, so its listener library folder is unavailable.");
    }
    return "redirect:/detections";
  }
}
