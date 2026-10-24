package io.github.kalpak44.songradar.playlist;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PlaylistSourceService {

  private static final List<String> ALLOWED_EXTENSIONS = List.of(".m3u", ".m3u8");

  private final PlaylistSourceRepository repository;
  private final PlaylistStorage storage;
  private final Clock clock;

  public PlaylistSourceService(
      PlaylistSourceRepository repository, PlaylistStorage storage, Clock clock) {
    this.repository = repository;
    this.storage = storage;
    this.clock = clock;
  }

  public List<PlaylistSource> findAll() {
    return repository.findAllByOrderByAddedAtDesc();
  }

  public Optional<PlaylistSource> find(long id) {
    return repository.findById(id);
  }

  public PlaylistSource addUrl(String url, String name) {
    String resolvedName = hasText(name) ? name.trim() : nameFromUrl(url);
    return repository.save(PlaylistSource.ofUrl(resolvedName, url.trim(), clock.instant()));
  }

  /**
   * Stores the upload, then records it. The file is written first so a failed write leaves no row;
   * the reverse order would leave a source pointing at nothing.
   */
  @Transactional
  public PlaylistSource addFile(MultipartFile file, String name) {
    String original = file.getOriginalFilename();
    String resolvedName = hasText(name) ? name.trim() : stripExtension(baseName(original));
    if (!hasText(resolvedName)) {
      resolvedName = "Uploaded playlist";
    }
    String storedPath;
    try {
      storedPath = storage.store(file.getInputStream(), original);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the uploaded playlist", e);
    }
    try {
      return repository.save(PlaylistSource.ofFile(resolvedName, storedPath, clock.instant()));
    } catch (RuntimeException e) {
      try {
        storage.delete(storedPath);
      } catch (RuntimeException cleanupFailure) {
        e.addSuppressed(cleanupFailure);
      }
      throw e;
    }
  }

  /**
   * Removes the row and, for an upload, its file. Returns false when the id is already gone, so a
   * double-submitted remove reports nothing rather than failing.
   */
  @Transactional
  public boolean remove(long id) {
    return repository
        .findById(id)
        .map(
            source -> {
              repository.delete(source);
              if (source.type() == SourceType.FILE) {
                storage.delete(source.storedPath());
              }
              return true;
            })
        .orElse(false);
  }

  /** True when the name ends in an extension this application can read. */
  public static boolean hasAllowedExtension(String filename) {
    if (filename == null) {
      return false;
    }
    String lower = filename.toLowerCase(Locale.ROOT);
    return ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
  }

  /** True for an absolute http or https URL. */
  public static boolean isHttpUrl(String value) {
    if (!hasText(value)) {
      return false;
    }
    try {
      URI uri = new URI(value.trim());
      String scheme = uri.getScheme();
      return uri.isAbsolute()
          && uri.getHost() != null
          && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
    } catch (URISyntaxException _) {
      return false;
    }
  }

  private static String nameFromUrl(String url) {
    String path = URI.create(url.trim()).getPath();
    String last = path == null ? "" : baseName(path);
    String stripped = stripExtension(last);
    return stripped.isEmpty() ? URI.create(url.trim()).getHost() : stripped;
  }

  private static String baseName(String path) {
    if (path == null) {
      return "";
    }
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static String stripExtension(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? filename : filename.substring(0, dot);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
