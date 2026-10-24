package io.github.kalpak44.songradar.playlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class PlaylistSourceServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

  private PlaylistSourceRepository repository;
  private PlaylistStorage storage;
  private PlaylistSourceService service;

  @BeforeEach
  void setUp() {
    repository = mock(PlaylistSourceRepository.class);
    storage = mock(PlaylistStorage.class);
    service = new PlaylistSourceService(repository, storage, Clock.fixed(NOW, ZoneOffset.UTC));
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void findAllReturnsTheNewestFirst() {
    PlaylistSource source = PlaylistSource.ofUrl("a", "https://e.com/a.m3u", NOW);
    when(repository.findAllByOrderByAddedAtDesc()).thenReturn(List.of(source));

    assertEquals(List.of(source), service.findAll());
  }

  @Test
  void findDelegatesToTheRepository() {
    PlaylistSource source = PlaylistSource.ofUrl("a", "https://e.com/a.m3u", NOW);
    when(repository.findById(7L)).thenReturn(Optional.of(source));

    assertEquals(Optional.of(source), service.find(7L));
  }

  @Test
  void addUrlKeepsTheSuppliedNameAndTrims() {
    PlaylistSource saved = service.addUrl("  https://e.com/a.m3u  ", "  Music TV  ");

    assertEquals("Music TV", saved.name());
    assertEquals("https://e.com/a.m3u", saved.url());
    assertEquals(SourceType.URL, saved.type());
    assertEquals(NOW, saved.addedAt());
  }

  @Test
  void addUrlDerivesTheNameFromTheLastPathSegmentWhenNoneIsGiven() {
    assertEquals("playlist", service.addUrl("https://e.com/lists/playlist.m3u8", null).name());
    assertEquals("playlist", service.addUrl("https://e.com/lists/playlist.m3u8", "  ").name());
  }

  @Test
  void addUrlFallsBackToTheHostWhenThePathCarriesNoName() {
    assertEquals("e.com", service.addUrl("https://e.com/", null).name());
    assertEquals("e.com", service.addUrl("https://e.com", null).name());
  }

  @Test
  void addFileStoresTheUploadThenRecordsIt() {
    when(storage.store(any(), anyString())).thenReturn("stored-name.m3u");
    MockMultipartFile upload =
        new MockMultipartFile(
            "file", "Local Channels.m3u", "audio/x-mpegurl", "#EXTM3U".getBytes());

    PlaylistSource saved = service.addFile(upload, null);

    assertEquals("Local Channels", saved.name(), "the name falls back to the uploaded filename");
    assertEquals("stored-name.m3u", saved.storedPath());
    assertEquals(SourceType.FILE, saved.type());
    assertEquals(NOW, saved.addedAt());
  }

  @Test
  void addFilePrefersTheSuppliedName() {
    when(storage.store(any(), anyString())).thenReturn("stored.m3u");
    MockMultipartFile upload = new MockMultipartFile("file", "ignored.m3u", null, "x".getBytes());

    assertEquals("Chosen", service.addFile(upload, " Chosen ").name());
  }

  @Test
  void addFileUsesAFallbackWhenTheFilenameHasNoStem() {
    when(storage.store(any(), anyString())).thenReturn("stored.m3u");
    MockMultipartFile upload = new MockMultipartFile("file", ".m3u", null, "x".getBytes());

    assertEquals("Uploaded playlist", service.addFile(upload, null).name());
  }

  @Test
  void addFileRemovesTheStoredFileWhenTheDatabaseWriteFails() {
    when(storage.store(any(), anyString())).thenReturn("stored.m3u");
    when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("offline"));
    MockMultipartFile upload = new MockMultipartFile("file", "list.m3u", null, "x".getBytes());

    assertThrows(DataAccessResourceFailureException.class, () -> service.addFile(upload, null));

    verify(storage).delete("stored.m3u");
  }

  @Test
  void addFileKeepsACleanupFailureAsSuppressed() {
    when(storage.store(any(), anyString())).thenReturn("stored.m3u");
    DataAccessResourceFailureException saveFailure =
        new DataAccessResourceFailureException("offline");
    when(repository.save(any())).thenThrow(saveFailure);
    doThrow(new IllegalStateException("delete failed")).when(storage).delete("stored.m3u");
    MockMultipartFile upload = new MockMultipartFile("file", "list.m3u", null, "x".getBytes());

    DataAccessResourceFailureException thrown =
        assertThrows(DataAccessResourceFailureException.class, () -> service.addFile(upload, null));

    assertSame(saveFailure, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals("delete failed", thrown.getSuppressed()[0].getMessage());
  }

  @Test
  void addFileWrapsAnUnreadableUpload() throws Exception {
    MultipartFile upload = mock(MultipartFile.class);
    when(upload.getOriginalFilename()).thenReturn("list.m3u");
    when(upload.getInputStream()).thenThrow(new IOException("unreadable"));

    UncheckedIOException failure =
        assertThrows(UncheckedIOException.class, () -> service.addFile(upload, null));

    assertEquals("could not read the uploaded playlist", failure.getMessage());
  }

  @Test
  void removeDeletesTheRowAndTheStoredFile() {
    PlaylistSource source = new PlaylistSource(7L, "a", SourceType.FILE, null, "stored.m3u", NOW);
    when(repository.findById(7L)).thenReturn(Optional.of(source));

    assertTrue(service.remove(7L));

    verify(repository).delete(source);
    verify(storage).delete("stored.m3u");
  }

  @Test
  void removeLeavesStorageAloneForAUrlSource() {
    PlaylistSource source =
        new PlaylistSource(8L, "a", SourceType.URL, "https://e.com/a.m3u", null, NOW);
    when(repository.findById(8L)).thenReturn(Optional.of(source));

    assertTrue(service.remove(8L));

    verify(repository).delete(source);
    verify(storage, never()).delete(anyString());
  }

  @Test
  void removeReportsFalseForAnIdThatIsAlreadyGone() {
    when(repository.findById(9L)).thenReturn(Optional.empty());

    assertFalse(service.remove(9L));

    verify(repository, never()).delete(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"a.m3u", "a.M3U", "a.m3u8", "list.M3U8"})
  void allowedExtensionsAreAcceptedRegardlessOfCase(String filename) {
    assertTrue(PlaylistSourceService.hasAllowedExtension(filename));
  }

  @ParameterizedTest
  @ValueSource(strings = {"a.txt", "a.m3u.exe", "m3u", "a."})
  void otherExtensionsAreRejected(String filename) {
    assertFalse(PlaylistSourceService.hasAllowedExtension(filename));
  }

  @Test
  void aMissingFilenameIsRejected() {
    assertFalse(PlaylistSourceService.hasAllowedExtension(null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://e.com/a.m3u", "https://e.com/a.m3u", "  https://e.com  "})
  void httpAndHttpsUrlsAreAccepted(String url) {
    assertTrue(PlaylistSourceService.isHttpUrl(url));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-a-url",
        "ftp://e.com/a.m3u",
        "file:///etc/passwd",
        "/a.m3u",
        "",
        "  ",
        "http://"
      })
  void anythingElseIsRejected(String url) {
    assertFalse(PlaylistSourceService.isHttpUrl(url));
  }

  @Test
  void aNullUrlIsRejected() {
    assertFalse(PlaylistSourceService.isHttpUrl(null));
  }

  @Test
  void theStoredFilenameIsTakenFromTheUploadNotTheDisplayName() {
    when(storage.store(any(), anyString())).thenReturn("stored.m3u");
    MockMultipartFile upload = new MockMultipartFile("file", "original.m3u", null, "x".getBytes());

    service.addFile(upload, "Display");

    ArgumentCaptor<String> filename = ArgumentCaptor.forClass(String.class);
    verify(storage).store(any(), filename.capture());
    assertEquals("original.m3u", filename.getValue());
  }
}
