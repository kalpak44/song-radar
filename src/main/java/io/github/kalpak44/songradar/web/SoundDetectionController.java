package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.detection.SoundDetection;
import io.github.kalpak44.songradar.detection.SoundDetectionService;
import io.github.kalpak44.songradar.detection.SoundDetectionService.ManualAttemptResult;
import io.github.kalpak44.songradar.listener.ListenerRecording;
import io.github.kalpak44.songradar.listener.ListenerRecordingRepository;
import io.github.kalpak44.songradar.listener.PlaylistListener;
import io.github.kalpak44.songradar.listener.PlaylistListenerRepository;
import io.github.kalpak44.songradar.playlist.PlaylistSource;
import io.github.kalpak44.songradar.playlist.PlaylistSourceRepository;
import java.time.Clock;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
  private final PlaylistListenerRepository listeners;
  private final PlaylistSourceRepository playlists;
  private final Clock clock;

  public SoundDetectionController(
      SoundDetectionService service,
      ListenerRecordingRepository recordings,
      PlaylistListenerRepository listeners,
      PlaylistSourceRepository playlists,
      Clock clock) {
    this.service = service;
    this.recordings = recordings;
    this.listeners = listeners;
    this.playlists = playlists;
    this.clock = clock;
  }

  @GetMapping("/detections")
  public String detections(@RequestParam(defaultValue = "1") int page, Model model) {
    long total = service.count();
    int pages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    int current = Math.max(1, Math.min(page, pages));
    Map<Long, PlaylistListener> listenerById =
        listeners.findAll().stream()
            .collect(Collectors.toMap(PlaylistListener::id, Function.identity()));
    Map<Long, PlaylistSource> playlistById =
        playlists.findAll().stream()
            .collect(Collectors.toMap(PlaylistSource::id, Function.identity()));
    var rows =
        service.page(current, PAGE_SIZE).stream()
            .map(detection -> row(detection, listenerById, playlistById))
            .toList();
    model.addAttribute("activeNav", "detections");
    model.addAttribute("rows", rows);
    model.addAttribute("configured", service.isConfigured());
    model.addAttribute("pendingCount", service.pendingCount());
    model.addAttribute("total", total);
    model.addAttribute("currentPage", current);
    model.addAttribute("totalPages", pages);
    model.addAttribute("localZone", clock.getZone().getId());
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

  private DetectionRow row(
      SoundDetection detection,
      Map<Long, PlaylistListener> listenerById,
      Map<Long, PlaylistSource> playlistById) {
    ListenerRecording recording =
        detection.recordingId() == null
            ? null
            : recordings.findById(detection.recordingId()).orElse(null);
    PlaylistListener listener = recording == null ? null : listenerById.get(recording.listenerId());
    PlaylistSource playlist =
        listener == null ? null : playlistById.get(listener.playlistSourceId());
    return new DetectionRow(
        detection,
        listener == null ? null : listener.id(),
        listener == null ? "Removed listener" : listener.name(),
        listener == null ? "" : listener.channelName(),
        playlist == null ? "Removed playlist" : playlist.name(),
        playlist == null ? null : playlist.id(),
        recording == null ? null : recording.id(),
        recording == null ? null : recording.completedAt());
  }

  public record DetectionRow(
      SoundDetection detection,
      Long listenerId,
      String listenerName,
      String channelName,
      String playlistName,
      Long playlistId,
      Long recordingId,
      java.time.Instant recordedAt) {}
}
