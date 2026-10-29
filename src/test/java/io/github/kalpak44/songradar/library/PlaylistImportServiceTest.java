package io.github.kalpak44.songradar.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.songradar.activity.ActivityLog;
import io.github.kalpak44.songradar.detection.SoundDetection;
import io.github.kalpak44.songradar.library.PlaylistImportService.ImportRequest;
import io.github.kalpak44.songradar.library.YtDlpTrackSource.ImportedTrack;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class PlaylistImportServiceTest {

  private static final String PLAYLIST = "https://music.youtube.com/watch?v=abc&list=RDAMVMxyz";

  @TempDir Path library;

  private YtDlpTrackSource youtubeMusic;
  private MusicDownloader downloader;
  private PlaylistImportService service;

  @BeforeEach
  void setUp() {
    youtubeMusic = mock(YtDlpTrackSource.class);
    downloader = mock(MusicDownloader.class);
    service = importer(100);
  }

  /** Runs the import on the calling thread, so a test sees the finished state. */
  private PlaylistImportService importer(int maxTracks) {
    return new PlaylistImportService(
        library,
        maxTracks,
        youtubeMusic,
        downloader,
        Runnable::run,
        ActivityLog.NONE,
        Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void refusesAnythingThatIsNotAYouTubeLink() {
    assertThat(service.start("https://example.com/playlist")).isEqualTo(ImportRequest.INVALID_LINK);
    assertThat(service.start("file:///etc/passwd")).isEqualTo(ImportRequest.INVALID_LINK);
    assertThat(service.start("not a url")).isEqualTo(ImportRequest.INVALID_LINK);
    assertThat(service.start("")).isEqualTo(ImportRequest.INVALID_LINK);
    assertThat(service.start(null)).isEqualTo(ImportRequest.INVALID_LINK);
    // A host that merely ends in the real one is a different site.
    assertThat(service.start("https://music.youtube.com.evil.test/watch?v=a"))
        .isEqualTo(ImportRequest.INVALID_LINK);
    assertThat(service.progress()).isEmpty();
  }

  @Test
  void acceptsTheYouTubeHostsAPlaylistLinkCanCarry() throws IOException {
    when(youtubeMusic.tracksAt(any(), eq(100))).thenReturn(List.of());

    assertThat(service.start("https://music.youtube.com/playlist?list=abc"))
        .isEqualTo(ImportRequest.STARTED);
    assertThat(service.start("https://www.youtube.com/watch?v=abc"))
        .isEqualTo(ImportRequest.STARTED);
    assertThat(service.start("https://youtu.be/abc")).isEqualTo(ImportRequest.STARTED);
  }

  @Test
  void savesEveryTrackOfThePlaylistIntoTheImportFolder() throws IOException {
    when(youtubeMusic.tracksAt(PLAYLIST, 100))
        .thenReturn(
            List.of(
                new ImportedTrack("a1", "Даже не половина", "ЗОМБ"),
                new ImportedTrack("a2", "Black", "GAZIROVKA")));
    when(downloader.download(any(), any())).thenReturn(true);

    assertThat(service.start(PLAYLIST)).isEqualTo(ImportRequest.STARTED);

    ArgumentCaptor<SoundDetection> detections = ArgumentCaptor.forClass(SoundDetection.class);
    ArgumentCaptor<Path> folders = ArgumentCaptor.forClass(Path.class);
    verify(downloader, org.mockito.Mockito.times(2))
        .download(detections.capture(), folders.capture());

    assertThat(folders.getAllValues())
        .allMatch(folder -> folder.equals(library.resolve("YouTube Music")));
    assertThat(detections.getAllValues())
        .extracting(SoundDetection::artist, SoundDetection::title)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("ЗОМБ", "Даже не половина"),
            org.assertj.core.groups.Tuple.tuple("GAZIROVKA", "Black"));

    PlaylistImportService.ImportProgress progress = service.progress().orElseThrow();
    assertThat(progress.running()).isFalse();
    assertThat(progress.total()).isEqualTo(2);
    assertThat(progress.saved()).isEqualTo(2);
    assertThat(progress.message()).isEqualTo("Imported every track.");
  }

  /**
   * Searching by name is what finds the better copy, but it can also find nothing — and the link
   * already said exactly which recording was wanted.
   */
  @Test
  void fallsBackToTheExactTrackWhenNoCatalogueHasIt() throws IOException {
    SourceTrack exact = new SourceTrack("YouTube Music", "Black", "GAZIROVKA", 166_000, out -> {});
    when(youtubeMusic.tracksAt(PLAYLIST, 100))
        .thenReturn(List.of(new ImportedTrack("a2", "Black", "GAZIROVKA")));
    when(downloader.download(any(), any())).thenReturn(false);
    when(youtubeMusic.trackById("a2")).thenReturn(Optional.of(exact));
    when(downloader.save(eq(exact), any(), eq("GAZIROVKA"), eq("Black"))).thenReturn(true);

    service.start(PLAYLIST);

    verify(downloader).save(eq(exact), eq(library.resolve("YouTube Music")), any(), any());
    assertThat(service.progress().orElseThrow().saved()).isEqualTo(1);
  }

  @Test
  void countsTheTracksNoSourceCouldDeliver() throws IOException {
    when(youtubeMusic.tracksAt(PLAYLIST, 100))
        .thenReturn(
            List.of(
                new ImportedTrack("a1", "One", "Artist"),
                new ImportedTrack("a2", "Two", "Artist")));
    when(downloader.download(any(), any())).thenReturn(true, false);
    when(youtubeMusic.trackById("a2")).thenReturn(Optional.empty());

    service.start(PLAYLIST);

    PlaylistImportService.ImportProgress progress = service.progress().orElseThrow();
    assertThat(progress.saved()).isEqualTo(1);
    assertThat(progress.done()).isEqualTo(2);
    assertThat(progress.message()).isEqualTo("Imported 1 of 2 tracks.");
  }

  /**
   * A YouTube Music link carrying a radio mix resolves to thousands of entries. The limit is what
   * keeps one paste from becoming a download measured in days.
   */
  @Test
  void asksForNoMoreTracksThanTheLimitAllows() throws IOException {
    PlaylistImportService limited = importer(5);
    when(youtubeMusic.tracksAt(PLAYLIST, 5)).thenReturn(List.of());

    limited.start(PLAYLIST);

    verify(youtubeMusic).tracksAt(PLAYLIST, 5);
  }

  @Test
  void refusesASecondImportWhileOneIsRunning() throws IOException {
    List<ImportRequest> attempted = new ArrayList<>();
    when(youtubeMusic.tracksAt(PLAYLIST, 100))
        .thenReturn(List.of(new ImportedTrack("a1", "One", "Artist")));
    when(downloader.download(any(), any()))
        .thenAnswer(
            invocation -> {
              attempted.add(service.start(PLAYLIST));
              return true;
            });

    assertThat(service.start(PLAYLIST)).isEqualTo(ImportRequest.STARTED);

    assertThat(attempted).containsExactly(ImportRequest.ALREADY_RUNNING);
  }

  @Test
  void reportsALinkThatCouldNotBeRead() throws IOException {
    when(youtubeMusic.tracksAt(PLAYLIST, 100)).thenThrow(new IOException("yt-dlp exited 1"));

    service.start(PLAYLIST);

    PlaylistImportService.ImportProgress progress = service.progress().orElseThrow();
    assertThat(progress.running()).isFalse();
    assertThat(progress.message()).contains("Could not read that link").contains("yt-dlp exited 1");
    verify(downloader, never()).download(any(), any());
  }

  @Test
  void reportsALinkThatHeldNoTracks() throws IOException {
    when(youtubeMusic.tracksAt(PLAYLIST, 100)).thenReturn(List.of());

    service.start(PLAYLIST);

    assertThat(service.progress().orElseThrow().message()).isEqualTo("That link holds no tracks.");
  }
}
