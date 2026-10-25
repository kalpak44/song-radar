package io.github.kalpak44.songradar.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.kalpak44.songradar.activity.ActivityEventRepository;
import io.github.kalpak44.songradar.library.MusicDownloader;
import io.github.kalpak44.songradar.listener.ListenerRecording;
import io.github.kalpak44.songradar.listener.ListenerRecordingRepository;
import io.github.kalpak44.songradar.listener.PlaylistListenerRepository;
import io.github.kalpak44.songradar.listener.RecordingStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

class SoundDetectionServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

  @TempDir Path root;

  private SoundDetectionRepository detections;
  private ActivityEventRepository activityEvents;
  private ListenerRecordingRepository recordings;
  private PlaylistListenerRepository listeners;
  private MusicDownloader downloader;
  private AuddRecognitionClient recognition;
  private TaskExecutor executor;
  private SoundDetectionService service;

  @BeforeEach
  void setUp() {
    detections = mock(SoundDetectionRepository.class);
    activityEvents = mock(ActivityEventRepository.class);
    recordings = mock(ListenerRecordingRepository.class);
    listeners = mock(PlaylistListenerRepository.class);
    downloader = mock(MusicDownloader.class);
    recognition = mock(AuddRecognitionClient.class);
    executor = mock(TaskExecutor.class);
    service =
        new SoundDetectionService(
            root,
            root.resolve("library"),
            detections,
            activityEvents,
            recordings,
            listeners,
            downloader,
            recognition,
            executor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            io.github.kalpak44.songradar.activity.ActivityLog.NONE);
  }

  @Test
  void repeatedTrackUpdatesExistingRowInsteadOfCreatingADuplicate() {
    Instant firstDetected = Instant.parse("2026-10-20T10:00:00Z");
    SoundDetection existing =
        new SoundDetection(
            9L,
            13L,
            DetectionStatus.MATCHED,
            "AudD",
            "artist|song",
            "Song",
            "Artist",
            null,
            null,
            null,
            firstDetected,
            firstDetected,
            2,
            null);
    SoundDetection pending = pending(10L, 14L);
    when(detections.findByTrackKey("artist|song")).thenReturn(Optional.of(existing));

    service.saveMatched(pending, Map.of("title", "Song", "artist", "Artist"));

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections).save(saved.capture());
    assertThat(saved.getValue().id()).isEqualTo(9L);
    assertThat(saved.getValue().recordingId()).isEqualTo(14L);
    assertThat(saved.getValue().detectionCount()).isEqualTo(3);
    assertThat(saved.getValue().firstDetectedAt()).isEqualTo(firstDetected);
    assertThat(saved.getValue().detectedAt()).isEqualTo(Instant.parse("2026-10-25T10:00:00Z"));
    verify(detections).deleteById(10L);
  }

  @Test
  void clearingReportsHowManyDetectionsItRemoved() {
    when(detections.count()).thenReturn(31L);

    assertThat(service.clear()).isEqualTo(31);

    verify(detections).deleteAll();
  }

  @Test
  void downloadUsesPersistedListenerWhenSourceRecordingHasExpired() {
    SoundDetection detection =
        new SoundDetection(
            41L,
            99L,
            DetectionStatus.MATCHED,
            "AudD",
            "artist|song",
            "Song",
            "Artist",
            null,
            null,
            null,
            NOW,
            NOW,
            1,
            null,
            "Radio One Listener");
    when(detections.findById(41L)).thenReturn(Optional.of(detection));
    when(recordings.findById(99L)).thenReturn(Optional.empty());

    assertThat(service.planDownload(41L))
        .isEqualTo(SoundDetectionService.DownloadPlanResult.PLANNED);

    verify(downloader).download(detection, root.resolve("library/Radio One Listener"));
  }

  /** The recording existed to be identified; once it has been, it only costs volume space. */
  @Test
  void identifiedRecordingIsDiscardedWithItsAudio() throws Exception {
    java.nio.file.Path audio = root.resolve("14.wav");
    java.nio.file.Files.createDirectories(root);
    java.nio.file.Files.writeString(audio, "audio");
    ListenerRecording recording =
        new ListenerRecording(
            14L, 3L, "14.wav", NOW, NOW, RecordingStatus.COMPLETED, 5, 1000, null);
    when(recordings.findById(14L)).thenReturn(Optional.of(recording));
    when(detections.findByTrackKey("artist|song")).thenReturn(Optional.empty());

    service.saveMatched(pending(10L, 14L), Map.of("title", "Song", "artist", "Artist"));

    verify(recordings).deleteById(14L);
    assertThat(java.nio.file.Files.exists(audio)).isFalse();
  }

  @Test
  void firstTrackMatchKeepsThePendingRow() {
    SoundDetection pending = pending(10L, 14L);
    when(detections.findByTrackKey("artist|song")).thenReturn(Optional.empty());

    service.saveMatched(pending, Map.of("title", "Song", "artist", "Artist"));

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections).save(saved.capture());
    assertThat(saved.getValue().id()).isEqualTo(10L);
    assertThat(saved.getValue().trackKey()).isEqualTo("artist|song");
    assertThat(saved.getValue().status()).isEqualTo(DetectionStatus.MATCHED);
  }

  @Test
  void manualDetectionQueuesACompletedRecordingOnce() {
    ListenerRecording recording = recording(14L);
    when(recognition.isConfigured()).thenReturn(true);
    when(detections.findFirstByRecordingIdAndStatusOrderByIdDesc(14L, DetectionStatus.PENDING))
        .thenReturn(Optional.empty());
    when(detections.save(any(SoundDetection.class)))
        .thenReturn(
            new SoundDetection(
                15L,
                14L,
                DetectionStatus.PENDING,
                "AudD",
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-10-25T10:00:00Z"),
                Instant.parse("2026-10-25T10:00:00Z"),
                1,
                null));

    assertThat(service.manualAttempt(recording))
        .isEqualTo(SoundDetectionService.ManualAttemptResult.QUEUED);

    verify(executor).execute(any(Runnable.class));
  }

  @Test
  void manualDetectionDoesNotQueueASecondPendingRequest() {
    when(recognition.isConfigured()).thenReturn(true);
    when(detections.findFirstByRecordingIdAndStatusOrderByIdDesc(14L, DetectionStatus.PENDING))
        .thenReturn(Optional.of(pending(10L, 14L)));

    assertThat(service.manualAttempt(recording(14L)))
        .isEqualTo(SoundDetectionService.ManualAttemptResult.ALREADY_PENDING);

    verify(detections, never()).save(any(SoundDetection.class));
    verify(executor, never()).execute(any(Runnable.class));
  }

  @Test
  void manualDetectionIsDisabledWithoutRecognitionCredentials() {
    when(recognition.isConfigured()).thenReturn(false);

    assertThat(service.manualAttempt(recording(14L)))
        .isEqualTo(SoundDetectionService.ManualAttemptResult.DISABLED);

    verify(detections, never()).save(any(SoundDetection.class));
  }

  @Test
  void manualDetectionRejectsAnIncompleteRecording() {
    when(recognition.isConfigured()).thenReturn(true);

    assertThat(service.manualAttempt(recording(null, "capture.wav")))
        .isEqualTo(SoundDetectionService.ManualAttemptResult.INVALID_RECORDING);
    assertThat(service.manualAttempt(recording(14L, null)))
        .isEqualTo(SoundDetectionService.ManualAttemptResult.INVALID_RECORDING);

    verify(detections, never()).save(any(SoundDetection.class));
  }

  @Test
  void enqueueIgnoresRecordingsThatCannotBeIdentified() {
    service.enqueue(recording(null, "capture.wav"));
    service.enqueue(recording(14L, null));

    verifyNoInteractions(detections, recognition, executor);
  }

  @Test
  void enqueueStoresAnUnavailableDetectionWhenRecognitionIsDisabled() {
    when(recognition.isConfigured()).thenReturn(false);
    when(detections.save(any(SoundDetection.class))).thenAnswer(i -> i.getArgument(0));

    service.enqueue(recording(14L));

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(DetectionStatus.UNAVAILABLE);
    assertThat(saved.getValue().errorMessage()).contains("SONGRADAR_AUDD_API_TOKEN");
    assertThat(saved.getValue().detectedAt()).isEqualTo(NOW);
    verify(executor, never()).execute(any(Runnable.class));
  }

  @Test
  void enqueueQueuesAPendingDetectionWhenRecognitionIsConfigured() {
    when(recognition.isConfigured()).thenReturn(true);
    when(detections.findFirstByRecordingIdAndStatusOrderByIdDesc(14L, DetectionStatus.PENDING))
        .thenReturn(Optional.empty());
    when(detections.save(any(SoundDetection.class))).thenAnswer(i -> i.getArgument(0));

    service.enqueue(recording(14L));

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(DetectionStatus.PENDING);
    assertThat(saved.getValue().errorMessage()).isNull();
    verify(executor).execute(any(Runnable.class));
  }

  @Test
  void enqueueDoesNotQueueASecondPendingDetection() {
    when(recognition.isConfigured()).thenReturn(true);
    when(detections.findFirstByRecordingIdAndStatusOrderByIdDesc(14L, DetectionStatus.PENDING))
        .thenReturn(Optional.of(pending(10L, 14L)));

    service.enqueue(recording(14L));

    verify(detections, never()).save(any(SoundDetection.class));
    verify(executor, never()).execute(any(Runnable.class));
  }

  @Test
  void resumePendingSchedulesEveryPendingDetectionWhenConfigured() {
    when(recognition.isConfigured()).thenReturn(true);
    when(detections.findAllByStatus(DetectionStatus.PENDING))
        .thenReturn(List.of(pending(1L, 11L), pending(2L, 12L)));

    service.resumePending();

    verify(executor, times(2)).execute(any(Runnable.class));
    verify(detections, never()).save(any(SoundDetection.class));
  }

  @Test
  void resumePendingMarksPendingDetectionsUnavailableWhenRecognitionIsDisabled() {
    when(recognition.isConfigured()).thenReturn(false);
    when(detections.findAllByStatus(DetectionStatus.PENDING)).thenReturn(List.of(pending(1L, 11L)));

    service.resumePending();

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(DetectionStatus.UNAVAILABLE);
    assertThat(saved.getValue().errorMessage()).contains("SONGRADAR_AUDD_API_TOKEN");
    verify(executor, never()).execute(any(Runnable.class));
  }

  @Test
  void scheduledRecognitionStoresAMatchedTrack() throws Exception {
    Path audio = audioFile("3/7/capture.wav");
    ListenerRecording recording = recording(14L, "3/7/capture.wav");
    when(recordings.findById(14L)).thenReturn(Optional.of(recording));
    when(recognition.recognize(audio))
        .thenReturn(Map.of("title", "Song", "artist", "Artist", "album", "Album"));
    when(detections.findByTrackKey("artist|song")).thenReturn(Optional.empty());

    queueRecognition(recording).run();

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections, times(2)).save(saved.capture());
    SoundDetection match = saved.getAllValues().get(1);
    assertThat(match.status()).isEqualTo(DetectionStatus.MATCHED);
    assertThat(match.trackKey()).isEqualTo("artist|song");
    assertThat(match.title()).isEqualTo("Song");
    assertThat(match.album()).isEqualTo("Album");
    assertThat(match.detectedAt()).isEqualTo(NOW);
  }

  @Test
  void scheduledRecognitionStoresNoMatchWhenTheProviderIdentifiesNothing() throws Exception {
    Path audio = audioFile("3/7/capture.wav");
    ListenerRecording recording = recording(14L, "3/7/capture.wav");
    when(recordings.findById(14L)).thenReturn(Optional.of(recording));
    when(recognition.recognize(audio)).thenReturn(Map.of("title", " ", "artist", "Artist"));

    queueRecognition(recording).run();

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections, times(2)).save(saved.capture());
    assertThat(saved.getAllValues().get(1).status()).isEqualTo(DetectionStatus.NO_MATCH);
  }

  @Test
  void scheduledRecognitionFailsWhenTheRecordingWasRemoved() {
    ListenerRecording recording = recording(14L, "3/7/capture.wav");
    when(recordings.findById(14L)).thenReturn(Optional.empty());

    queueRecognition(recording).run();

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections, times(2)).save(saved.capture());
    SoundDetection failed = saved.getAllValues().get(1);
    assertThat(failed.status()).isEqualTo(DetectionStatus.FAILED);
    assertThat(failed.errorMessage()).isEqualTo("The recording has been removed.");
  }

  @Test
  void scheduledRecognitionFailsWhenTheAudioFileIsMissing() {
    ListenerRecording recording = recording(14L, "missing.wav");
    when(recordings.findById(14L)).thenReturn(Optional.of(recording));

    queueRecognition(recording).run();

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections, times(2)).save(saved.capture());
    SoundDetection failed = saved.getAllValues().get(1);
    assertThat(failed.status()).isEqualTo(DetectionStatus.FAILED);
    assertThat(failed.errorMessage()).isEqualTo("The recording audio file is unavailable.");
  }

  @Test
  void scheduledRecognitionUsesAFallbackForABlankProviderError() throws Exception {
    Path audio = audioFile("3/7/capture.wav");
    ListenerRecording recording = recording(14L, "3/7/capture.wav");
    when(recordings.findById(14L)).thenReturn(Optional.of(recording));
    when(recognition.recognize(audio)).thenThrow(new IllegalStateException(" "));

    queueRecognition(recording).run();

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections, times(2)).save(saved.capture());
    assertThat(saved.getAllValues().get(1).errorMessage())
        .isEqualTo("Recognition could not be completed.");
  }

  @Test
  void scheduledRecognitionTruncatesLongProviderErrors() throws Exception {
    Path audio = audioFile("3/7/capture.wav");
    ListenerRecording recording = recording(14L, "3/7/capture.wav");
    when(recordings.findById(14L)).thenReturn(Optional.of(recording));
    when(recognition.recognize(audio)).thenThrow(new IllegalStateException("x".repeat(450)));

    queueRecognition(recording).run();

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections, times(2)).save(saved.capture());
    assertThat(saved.getAllValues().get(1).errorMessage()).hasSize(400);
  }

  @Test
  void normalizedTrackKeyMatchesAnExistingAccentedTrack() {
    SoundDetection existing =
        new SoundDetection(
            9L,
            13L,
            DetectionStatus.MATCHED,
            "AudD",
            "beyonce|halo",
            "Halo",
            "Beyoncé",
            "Album",
            "2020",
            "https://e.com/halo",
            NOW.minusSeconds(3600),
            NOW.minusSeconds(3600),
            1,
            null);
    when(detections.findByTrackKey("beyonce|halo")).thenReturn(Optional.of(existing));

    service.saveMatched(pending(10L, 14L), Map.of("title", "HALO!", "artist", "Beyonce"));

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections).save(saved.capture());
    assertThat(saved.getValue().id()).isEqualTo(9L);
    assertThat(saved.getValue().detectionCount()).isEqualTo(2);
    assertThat(saved.getValue().album()).isEqualTo("Album");
    verify(detections).deleteById(10L);
  }

  @Test
  void aMatchedRowWithTheSameIdIsUpdatedInPlace() {
    SoundDetection existing = pending(10L, 14L);
    when(detections.findByTrackKey("artist|song")).thenReturn(Optional.of(existing));

    service.saveMatched(existing, Map.of("title", "Song", "artist", "Artist"));

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections).save(saved.capture());
    assertThat(saved.getValue().id()).isEqualTo(10L);
    assertThat(saved.getValue().status()).isEqualTo(DetectionStatus.MATCHED);
    assertThat(saved.getValue().detectionCount()).isEqualTo(1);
    verify(detections, never()).deleteById(any());
  }

  @Test
  void paginationAndCountsDelegateToTheRepository() {
    when(detections.findPage(30, 60L)).thenReturn(List.of(pending(1L, 11L)));
    when(detections.countAll()).thenReturn(100L);
    when(detections.countAllByStatus(DetectionStatus.PENDING)).thenReturn(4L);
    when(recognition.isConfigured()).thenReturn(true);

    assertThat(service.page(3, 30)).hasSize(1);
    assertThat(service.count()).isEqualTo(100L);
    assertThat(service.pendingCount()).isEqualTo(4L);
    assertThat(service.isConfigured()).isTrue();
  }

  private static SoundDetection pending(long id, long recordingId) {
    Instant now = Instant.parse("2026-10-25T09:00:00Z");
    return new SoundDetection(
        id,
        recordingId,
        DetectionStatus.PENDING,
        "AudD",
        null,
        null,
        null,
        null,
        null,
        null,
        now,
        now,
        1,
        null);
  }

  private Runnable queueRecognition(ListenerRecording recording) {
    when(recognition.isConfigured()).thenReturn(true);
    when(detections.findFirstByRecordingIdAndStatusOrderByIdDesc(
            recording.id(), DetectionStatus.PENDING))
        .thenReturn(Optional.empty());
    when(detections.save(any(SoundDetection.class))).thenAnswer(i -> i.getArgument(0));

    service.manualAttempt(recording);

    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    verify(executor, times(1)).execute(task.capture());
    return task.getValue();
  }

  private Path audioFile(String relativePath) throws Exception {
    Path audio = root.resolve(relativePath);
    Files.createDirectories(audio.getParent());
    return Files.write(audio, new byte[] {1, 2, 3});
  }

  private static ListenerRecording recording(long id) {
    return recording(id, "playlist/listener/capture.wav");
  }

  private static ListenerRecording recording(Long id, String filePath) {
    Instant now = Instant.parse("2026-10-25T09:00:00Z");
    return new ListenerRecording(
        id, 3L, filePath, now, now, RecordingStatus.COMPLETED, 128, 12_000, null);
  }
}
