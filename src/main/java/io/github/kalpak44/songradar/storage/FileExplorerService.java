package io.github.kalpak44.songradar.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileExplorerService {
  private final Path root;

  public FileExplorerService(@Value("${songradar.storage.path}") Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public Path root() {
    return root;
  }

  public Path fileOrDirectory(String relative) {
    Path path = resolve(relative);
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("That folder does not exist.");
    }
    return path;
  }

  public List<LibraryFile> list(String relativeDirectory) throws IOException {
    Files.createDirectories(root);
    Path directory = resolve(relativeDirectory);
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("That folder does not exist.");
    }
    try (var children = Files.list(directory)) {
      return children
          .map(
              path -> {
                try {
                  if (Files.isSymbolicLink(path)
                      || path.getFileName().toString().startsWith(".songradar-download-"))
                    return null;
                  var attrs =
                      Files.readAttributes(
                          path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                  return new LibraryFile(
                      path.getFileName().toString(),
                      relative(path),
                      attrs.isDirectory(),
                      attrs.isRegularFile() ? attrs.size() : 0,
                      attrs.lastModifiedTime().toInstant());
                } catch (IOException e) {
                  return null;
                }
              })
          .filter(java.util.Objects::nonNull)
          .sorted(
              Comparator.comparing(LibraryFile::directory)
                  .reversed()
                  .thenComparing(LibraryFile::name, String.CASE_INSENSITIVE_ORDER))
          .toList();
    }
  }

  /** All playable files below a directory, without following symbolic links. */
  public List<LibraryFile> listPlayableRecursively(String relativeDirectory) throws IOException {
    Path directory = fileOrDirectory(relativeDirectory);
    try (var paths = Files.walk(directory)) {
      return paths
          .filter(path -> !path.equals(directory))
          .filter(path -> !Files.isSymbolicLink(path))
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> !path.getFileName().toString().startsWith(".songradar-download-"))
          .map(
              path ->
                  new LibraryFile(
                      path.getFileName().toString(),
                      relative(path),
                      false,
                      size(path),
                      modified(path)))
          .filter(LibraryFile::playable)
          .sorted(Comparator.comparing(LibraryFile::relativePath, String.CASE_INSENSITIVE_ORDER))
          .toList();
    }
  }

  private static long size(Path path) {
    try {
      return Files.size(path);
    } catch (IOException exception) {
      return 0;
    }
  }

  private static java.time.Instant modified(Path path) {
    try {
      return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
    } catch (IOException exception) {
      return java.time.Instant.EPOCH;
    }
  }

  public void createFolder(String parent, String name) throws IOException {
    Path folder = child(resolve(parent), name);
    Files.createDirectory(folder);
  }

  public void upload(String parent, List<MultipartFile> uploads) throws IOException {
    Path directory = resolve(parent);
    for (MultipartFile upload : uploads) {
      if (upload == null || upload.isEmpty()) continue;
      String name = safeName(upload.getOriginalFilename());
      if (!name.toLowerCase(Locale.ROOT).endsWith(".mp3")) {
        throw new IllegalArgumentException("Only MP3 files can be uploaded here.");
      }
      Path target = child(directory, name);
      try (InputStream input = upload.getInputStream()) {
        Files.copy(input, target);
      }
    }
  }

  public void delete(List<String> paths) throws IOException {
    for (String value : paths) {
      Path target = resolve(value);
      if (target.equals(root))
        throw new IllegalArgumentException("The storage root cannot be deleted.");
      deleteTree(target);
    }
  }

  public List<Path> resolveAll(List<String> paths) {
    if (paths == null || paths.isEmpty())
      throw new IllegalArgumentException("Select at least one item.");
    return paths.stream().map(this::resolve).toList();
  }

  public void paste(List<String> sources, String destination) throws IOException {
    Path targetDirectory = resolve(destination);
    if (!Files.isDirectory(targetDirectory, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(targetDirectory)) {
      throw new IllegalArgumentException("The destination folder is not available.");
    }
    for (String sourceValue : sources) {
      Path source = resolve(sourceValue);
      if (source.equals(root) || Files.isSymbolicLink(source))
        throw new IllegalArgumentException("That item cannot be copied.");
      Path target = child(targetDirectory, source.getFileName().toString());
      if (source.equals(target) || target.startsWith(source))
        throw new IllegalArgumentException("A folder cannot be copied into itself.");
      copyTree(source, target);
    }
  }

  public Path file(String relative) {
    Path path = resolve(relative);
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("That file is not available.");
    }
    return path;
  }

  public Path createArchive(List<String> selected) throws IOException {
    if (selected == null || selected.isEmpty())
      throw new IllegalArgumentException("Select at least one item to download.");
    List<Path> sources = resolveAll(selected);
    for (Path source : sources) {
      if (source.equals(root)
          || Files.isSymbolicLink(source)
          || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException("One of the selected items is no longer available.");
      }
    }
    Path archive = Files.createTempFile(root, ".songradar-download-", ".zip");
    try (ZipOutputStream zip =
        new ZipOutputStream(Files.newOutputStream(archive, StandardOpenOption.TRUNCATE_EXISTING))) {
      for (Path source : sources) {
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
          Files.walkFileTree(
              source,
              new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                  if (Files.isSymbolicLink(dir))
                    throw new IOException("Symbolic links cannot be downloaded.");
                  return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                  if (Files.isSymbolicLink(file))
                    throw new IOException("Symbolic links cannot be downloaded.");
                  addZipFile(
                      zip,
                      file,
                      relative(source)
                          + "/"
                          + source
                              .relativize(file)
                              .toString()
                              .replace(file.getFileSystem().getSeparator(), "/"));
                  return FileVisitResult.CONTINUE;
                }
              });
        } else {
          if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Only files and folders can be downloaded.");
          addZipFile(zip, source, source.getFileName().toString());
        }
      }
    } catch (Exception e) {
      Files.deleteIfExists(archive);
      throw e;
    }
    return archive;
  }

  private static void addZipFile(ZipOutputStream zip, Path file, String name) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    Files.copy(file, zip);
    zip.closeEntry();
  }

  public String relative(Path path) {
    return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
  }

  private Path resolve(String relative) {
    if (relative == null || relative.isBlank()) return root;
    Path requested = Path.of(relative);
    if (requested.isAbsolute()) throw new IllegalArgumentException("Invalid storage path.");
    Path resolved = root.resolve(requested).normalize();
    if (!resolved.startsWith(root)) throw new IllegalArgumentException("Invalid storage path.");
    Path cursor = root;
    for (Path part : root.relativize(resolved)) {
      cursor = cursor.resolve(part);
      if (Files.isSymbolicLink(cursor))
        throw new IllegalArgumentException("Symbolic links are not available in the file manager.");
    }
    return resolved;
  }

  private static Path child(Path parent, String suppliedName) {
    String name = safeName(suppliedName);
    Path result = parent.resolve(name).normalize();
    if (!result.getParent().equals(parent))
      throw new IllegalArgumentException("Use a simple file or folder name.");
    return result;
  }

  private static String safeName(String supplied) {
    if (supplied == null || supplied.isBlank())
      throw new IllegalArgumentException("A name is required.");
    String name = Path.of(supplied).getFileName().toString().trim();
    if (name.isBlank() || name.equals(".") || name.equals("..") || name.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid name.");
    return name;
  }

  private static void copyTree(Path source, Path target) throws IOException {
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (Files.isSymbolicLink(dir))
              throw new IOException("Symbolic links cannot be copied.");
            Files.createDirectory(target.resolve(source.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (Files.isSymbolicLink(file))
              throw new IOException("Symbolic links cannot be copied.");
            Files.copy(file, target.resolve(source.relativize(file)));
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void deleteTree(Path target) throws IOException {
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalArgumentException("That item no longer exists.");
    Files.walkFileTree(
        target,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException error)
              throws IOException {
            if (error != null) throw error;
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
