package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.listener.ListenerRecording;
import io.github.kalpak44.songradar.listener.ListenerRecordingRepository;
import io.github.kalpak44.songradar.listener.ListenerScheduler;
import io.github.kalpak44.songradar.listener.ListenerService;
import io.github.kalpak44.songradar.listener.PlaylistListener;
import io.github.kalpak44.songradar.listener.RecordingStatus;
import io.github.kalpak44.songradar.playlist.PlaylistChannel;
import io.github.kalpak44.songradar.playlist.PlaylistChannelService;
import io.github.kalpak44.songradar.playlist.PlaylistSource;
import io.github.kalpak44.songradar.playlist.PlaylistSourceService;
import io.github.kalpak44.songradar.playlist.PlaylistUnavailableException;
import io.github.kalpak44.songradar.web.form.ListenerForm;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PlaylistController {

  private static final String LISTENER_FORM = "listenerForm";
  private static final String SUCCESS_MESSAGE = "successMessage";

  private final PlaylistSourceService playlists;
  private final PlaylistChannelService channels;
  private final ListenerService listeners;
  private final ListenerRecordingRepository recordings;
  private final ListenerScheduler scheduler;
  private final Path recordingsRoot;
  private final ZoneId zone;

  public PlaylistController(
      PlaylistSourceService playlists,
      PlaylistChannelService channels,
      ListenerService listeners,
      ListenerRecordingRepository recordings,
      ListenerScheduler scheduler,
      @Value("${songradar.storage.recordings}") Path recordingsRoot,
      Clock clock) {
    this.playlists = playlists;
    this.channels = channels;
    this.listeners = listeners;
    this.recordings = recordings;
    this.scheduler = scheduler;
    this.recordingsRoot = recordingsRoot.toAbsolutePath().normalize();
    this.zone = clock.getZone();
  }

  @GetMapping("/playlists/{playlistId}")
  public String detail(@PathVariable long playlistId, Model model) {
    PlaylistSource playlist = requirePlaylist(playlistId);
    model.addAttribute("activeNav", "settings");
    model.addAttribute("playlist", playlist);
    model.addAttribute("listeners", ListenerView.of(listeners.findAll(playlistId), zone));
    return "playlist-detail";
  }

  @GetMapping("/playlists/{playlistId}/listeners/new")
  public String createForm(@PathVariable long playlistId, Model model) {
    return renderEditor(requirePlaylist(playlistId), null, new ListenerForm(), model);
  }

  @GetMapping("/playlists/{playlistId}/listeners/{listenerId}")
  public String editForm(
      @PathVariable long playlistId, @PathVariable long listenerId, Model model) {
    PlaylistListener listener = requireListener(playlistId, listenerId);
    return renderEditor(requirePlaylist(playlistId), listener, ListenerForm.from(listener), model);
  }

  @GetMapping("/playlists/{playlistId}/listeners/{listenerId}/recordings/{recordingId}/audio")
  public ResponseEntity<Resource> recordingAudio(
      @PathVariable long playlistId,
      @PathVariable long listenerId,
      @PathVariable long recordingId) {
    PlaylistListener listener = requireListener(playlistId, listenerId);
    ListenerRecording recording =
        recordings
            .findById(recordingId)
            .filter(
                item ->
                    item.listenerId().equals(listener.id())
                        && item.status() == RecordingStatus.COMPLETED
                        && item.filePath() != null)
            .orElseThrow(this::notFound);
    Resource resource = resolveRecording(recording.filePath());
    MediaType contentType =
        MediaTypeFactory.getMediaType(resource).orElse(MediaType.parseMediaType("audio/mpeg"));
    return ResponseEntity.ok().contentType(contentType).body(resource);
  }

  @PostMapping("/playlists/{playlistId}/listeners")
  public String create(
      @PathVariable long playlistId,
      @Valid @ModelAttribute(LISTENER_FORM) ListenerForm form,
      BindingResult binding,
      Model model,
      RedirectAttributes redirect) {
    PlaylistSource playlist = requirePlaylist(playlistId);
    PlaylistChannel channel = validate(form, binding, playlistId);
    if (binding.hasErrors()) {
      return renderEditor(playlist, null, form, model);
    }
    PlaylistListener saved =
        listeners.create(
            playlistId,
            form.getName(),
            channel,
            form.getIntervalMinutes(),
            form.getDurationSeconds(),
            form.isActive());
    scheduler.refresh(saved);
    redirect.addFlashAttribute(SUCCESS_MESSAGE, "Listener added.");
    return redirectToPlaylist(playlistId);
  }

  @PostMapping("/playlists/{playlistId}/listeners/{listenerId}")
  public String update(
      @PathVariable long playlistId,
      @PathVariable long listenerId,
      @Valid @ModelAttribute(LISTENER_FORM) ListenerForm form,
      BindingResult binding,
      Model model,
      RedirectAttributes redirect) {
    PlaylistSource playlist = requirePlaylist(playlistId);
    PlaylistListener existing = requireListener(playlistId, listenerId);
    PlaylistChannel channel = validate(form, binding, playlistId);
    if (binding.hasErrors()) {
      return renderEditor(playlist, existing, form, model);
    }
    PlaylistListener saved =
        listeners.update(
            existing,
            form.getName(),
            channel,
            form.getIntervalMinutes(),
            form.getDurationSeconds(),
            form.isActive());
    scheduler.refresh(saved);
    redirect.addFlashAttribute(SUCCESS_MESSAGE, "Listener updated.");
    return redirectToPlaylist(playlistId);
  }

  @PostMapping("/playlists/{playlistId}/listeners/{listenerId}/enable")
  public String enable(
      @PathVariable long playlistId, @PathVariable long listenerId, RedirectAttributes redirect) {
    PlaylistListener saved = listeners.setActive(requireListener(playlistId, listenerId), true);
    scheduler.refresh(saved);
    redirect.addFlashAttribute(SUCCESS_MESSAGE, "Listener enabled.");
    return redirectToPlaylist(playlistId);
  }

  @PostMapping("/playlists/{playlistId}/listeners/{listenerId}/pause")
  public String pause(
      @PathVariable long playlistId, @PathVariable long listenerId, RedirectAttributes redirect) {
    PlaylistListener saved = listeners.setActive(requireListener(playlistId, listenerId), false);
    scheduler.refresh(saved);
    redirect.addFlashAttribute(SUCCESS_MESSAGE, "Listener paused.");
    return redirectToPlaylist(playlistId);
  }

  @PostMapping("/playlists/{playlistId}/listeners/{listenerId}/delete")
  public String delete(
      @PathVariable long playlistId, @PathVariable long listenerId, RedirectAttributes redirect) {
    PlaylistListener listener = requireListener(playlistId, listenerId);
    scheduler.cancel(listenerId);
    listeners.delete(listener);
    redirect.addFlashAttribute(SUCCESS_MESSAGE, "Listener deleted.");
    return redirectToPlaylist(playlistId);
  }

  private PlaylistChannel validate(ListenerForm form, BindingResult binding, long playlistId) {
    if (form.getIntervalMinutes() > 0
        && form.getDurationSeconds() >= (long) form.getIntervalMinutes() * 60) {
      binding.rejectValue(
          "durationSeconds", "duration.tooLong", "Duration must be shorter than the interval.");
    }
    if (binding.hasFieldErrors("channelUrl")) {
      return null;
    }
    try {
      return channels.requireChannel(playlistId, form.getChannelUrl());
    } catch (IllegalArgumentException | PlaylistUnavailableException e) {
      binding.rejectValue("channelUrl", "channel.invalid", e.getMessage());
      return null;
    }
  }

  private String renderEditor(
      PlaylistSource playlist, PlaylistListener listener, ListenerForm form, Model model) {
    model.addAttribute("activeNav", "settings");
    model.addAttribute("playlist", playlist);
    model.addAttribute("editing", listener != null);
    model.addAttribute("listenerId", listener == null ? null : listener.id());
    if (listener != null) {
      model.addAttribute(
          "recordings",
          RecordingView.of(
              recordings.findAllByListenerIdOrderByStartedAtDesc(listener.id()), zone));
    }
    if (!model.containsAttribute(LISTENER_FORM)) {
      model.addAttribute(LISTENER_FORM, form);
    }
    try {
      model.addAttribute("channels", channels.channels(playlist.id()));
    } catch (PlaylistUnavailableException e) {
      model.addAttribute("channels", java.util.List.of());
      model.addAttribute("channelLoadError", e.getMessage());
    }
    return "listener-detail";
  }

  private PlaylistSource requirePlaylist(long playlistId) {
    return playlists
        .find(playlistId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private PlaylistListener requireListener(long playlistId, long listenerId) {
    return listeners
        .find(playlistId, listenerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private Resource resolveRecording(String filePath) {
    try {
      Path root = recordingsRoot.toRealPath();
      Path file = recordingsRoot.resolve(filePath).normalize().toRealPath();
      if (!file.startsWith(root) || !Files.isRegularFile(file) || !Files.isReadable(file)) {
        throw notFound();
      }
      return new FileSystemResource(file);
    } catch (IOException e) {
      throw notFound();
    }
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  private static String redirectToPlaylist(long playlistId) {
    return "redirect:/playlists/" + playlistId;
  }
}
