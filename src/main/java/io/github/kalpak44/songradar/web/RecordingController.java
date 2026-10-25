package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.listener.ListenerRecordingRepository;
import io.github.kalpak44.songradar.listener.ListenerService;
import io.github.kalpak44.songradar.listener.PlaylistListener;
import io.github.kalpak44.songradar.listener.RecordingService;
import io.github.kalpak44.songradar.playlist.PlaylistSource;
import io.github.kalpak44.songradar.playlist.PlaylistSourceService;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class RecordingController {

  private final ListenerRecordingRepository recordings;
  private final ListenerService listeners;
  private final PlaylistSourceService playlists;
  private final RecordingService recordingService;
  private final ZoneId zone;

  private static final int PAGE_SIZE = 25;

  public RecordingController(
      ListenerRecordingRepository recordings,
      ListenerService listeners,
      PlaylistSourceService playlists,
      RecordingService recordingService,
      Clock clock) {
    this.recordings = recordings;
    this.listeners = listeners;
    this.playlists = playlists;
    this.recordingService = recordingService;
    this.zone = clock.getZone();
  }

  @GetMapping("/recordings")
  public String recordings(
      @RequestParam(required = false) Long listenerId,
      @RequestParam(required = false) Long playlistId,
      @RequestParam(defaultValue = "newest") String sort,
      @RequestParam(defaultValue = "1") int page,
      Model model) {
    Map<Long, PlaylistListener> listenerById =
        listeners.findAll().stream().collect(Collectors.toMap(PlaylistListener::id, item -> item));
    Map<Long, PlaylistSource> playlistById =
        playlists.findAll().stream().collect(Collectors.toMap(PlaylistSource::id, item -> item));
    Map<Long, java.util.List<RecordingView>> grouped = new LinkedHashMap<>();

    String sortOrder = "oldest".equals(sort) ? "oldest" : "newest";
    long totalRecordings = recordings.countFiltered(listenerId, playlistId);
    int totalPages = (int) Math.max(1, (totalRecordings + PAGE_SIZE - 1) / PAGE_SIZE);
    int currentPage = Math.max(1, Math.min(page, totalPages));
    long offset = (long) (currentPage - 1) * PAGE_SIZE;
    var pageRecordings =
        sortOrder.equals("oldest")
            ? recordings.findPageOldest(listenerId, playlistId, PAGE_SIZE, offset)
            : recordings.findPageNewest(listenerId, playlistId, PAGE_SIZE, offset);

    pageRecordings.forEach(
        recording -> {
          PlaylistListener listener = listenerById.get(recording.listenerId());
          if (listener != null
              && (playlistId == null || listener.playlistSourceId().equals(playlistId))) {
            grouped
                .computeIfAbsent(listener.id(), _ -> new ArrayList<>())
                .add(RecordingView.of(recording, zone));
          }
        });

    var groups =
        grouped.entrySet().stream()
            .map(
                entry -> {
                  PlaylistListener listener = listenerById.get(entry.getKey());
                  PlaylistSource playlist = playlistById.get(listener.playlistSourceId());
                  return playlist == null
                      ? null
                      : new RecordingGroupView(
                          playlist.id(),
                          playlist.name(),
                          listener.id(),
                          listener.name(),
                          listener.channelName(),
                          entry.getValue());
                })
            .filter(java.util.Objects::nonNull)
            .toList();

    model.addAttribute("activeNav", "recordings");
    model.addAttribute("groups", groups);
    model.addAttribute("listenerFilter", listenerId);
    model.addAttribute("playlistFilter", playlistId);
    model.addAttribute("sortOrder", sortOrder);
    model.addAttribute("currentPage", currentPage);
    model.addAttribute("totalPages", totalPages);
    model.addAttribute("totalRecordings", totalRecordings);
    model.addAttribute("pageSize", PAGE_SIZE);
    model.addAttribute("firstRecordNumber", totalRecordings == 0 ? 0 : offset + 1);
    model.addAttribute("lastRecordNumber", Math.min(offset + PAGE_SIZE, totalRecordings));
    return "recordings";
  }

  @PostMapping("/recordings/delete-all")
  public String deleteAll(RedirectAttributes redirect) {
    RecordingService.DeletionSummary result = recordingService.deleteAll();
    if (result.failed() > 0) {
      redirect.addFlashAttribute(
          "errorMessage",
          "Deleted "
              + result.deleted()
              + " recordings; "
              + result.failed()
              + " could not be removed.");
    } else {
      redirect.addFlashAttribute(
          "successMessage", "Deleted all " + result.deleted() + " recordings.");
    }
    return "redirect:/recordings";
  }
}
