package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.storage.FileExplorerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class FileExplorerController {
  private static final String CLIPBOARD = "songradar.fileClipboard";
  private final FileExplorerService files;

  public FileExplorerController(FileExplorerService files) {
    this.files = files;
  }

  @GetMapping({"/", "/library", "/files"})
  public String explore(
      @RequestParam(defaultValue = "") String path,
      Model model,
      HttpSession session,
      HttpServletRequest request)
      throws IOException {
    boolean libraryScope = !request.getRequestURI().equals("/files");
    if (libraryScope) Files.createDirectories(files.root().resolve("library"));
    String relativeViewPath = libraryScope ? scopedPath(path, "library") : path;
    Path current =
        relativeViewPath.isBlank() ? files.root() : files.fileOrDirectory(relativeViewPath);
    String relative = files.relative(current);
    String displayPath =
        libraryScope && relative.startsWith("library")
            ? relative.substring("library".length()).replaceFirst("^/", "")
            : relative;
    List<Breadcrumb> breadcrumbs = new ArrayList<>();
    breadcrumbs.add(new Breadcrumb("", libraryScope ? "library" : "data"));
    StringBuilder accumulated = new StringBuilder();
    for (String part :
        displayPath.isBlank() ? List.<String>of() : List.of(displayPath.split("/"))) {
      if (!accumulated.isEmpty()) accumulated.append('/');
      accumulated.append(part);
      breadcrumbs.add(new Breadcrumb(accumulated.toString(), part));
    }
    model.addAttribute("activeNav", libraryScope ? "library" : "dashboard");
    List<io.github.kalpak44.songradar.storage.LibraryFile> listing = files.list(relative);
    model.addAttribute("files", listing);
    List<io.github.kalpak44.songradar.storage.LibraryFile> playableTracks =
        libraryScope ? files.listPlayableRecursively("library") : List.of();
    model.addAttribute("playableTracks", playableTracks);
    model.addAttribute("playableCount", playableTracks.size());
    model.addAttribute("currentPath", displayPath);
    model.addAttribute("scope", libraryScope ? "library" : "all");
    model.addAttribute("breadcrumbs", breadcrumbs);
    model.addAttribute("clipboardCount", clipboard(session).size());
    model.addAttribute(
        "storageRoot", files.root().resolve(libraryScope ? "library" : "").normalize().toString());
    return "dashboard";
  }

  @PostMapping("/files/folder")
  public String createFolder(
      @RequestParam(defaultValue = "") String path,
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam String name,
      RedirectAttributes redirect) {
    return perform(
        path,
        scope,
        redirect,
        () -> files.createFolder(scopedPath(path, scope), name),
        "Folder created.");
  }

  @PostMapping("/files/upload")
  public String upload(
      @RequestParam(defaultValue = "") String path,
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam("files") List<MultipartFile> uploads,
      RedirectAttributes redirect) {
    return perform(
        path,
        scope,
        redirect,
        () -> files.upload(scopedPath(path, scope), uploads),
        "Files uploaded.");
  }

  /**
   * Nothing is moved here: the cut only records what to move, and the paste that follows is what
   * touches the filesystem. A selection that is cut and never pasted therefore stays where it is.
   */
  @PostMapping("/files/cut")
  public String cut(
      @RequestParam(defaultValue = "") String path,
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam("paths") List<String> selected,
      HttpSession session,
      RedirectAttributes redirect) {
    try {
      var validated = files.resolveAll(selected);
      session.setAttribute(CLIPBOARD, validated.stream().map(files::relative).toList());
      redirect.addFlashAttribute(
          "successMessage", "Cut " + validated.size() + " item(s). Paste them in a folder.");
    } catch (RuntimeException e) {
      redirect.addFlashAttribute("errorMessage", message(e));
    }
    return go(path, scope);
  }

  @PostMapping("/files/paste")
  public String paste(
      @RequestParam(defaultValue = "") String path,
      @RequestParam(defaultValue = "all") String scope,
      HttpSession session,
      RedirectAttributes redirect) {
    List<String> selected = clipboard(session);
    return perform(
        path,
        scope,
        redirect,
        () -> {
          if (selected.isEmpty()) throw new IllegalArgumentException("The clipboard is empty.");
          files.paste(selected, scopedPath(path, scope));
          session.removeAttribute(CLIPBOARD);
        },
        "Items moved.");
  }

  @PostMapping("/files/delete")
  public String delete(
      @RequestParam(defaultValue = "") String path,
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam("paths") List<String> selected,
      RedirectAttributes redirect) {
    return perform(path, scope, redirect, () -> files.delete(selected), "Items deleted.");
  }

  @GetMapping("/files/download")
  public ResponseEntity<Resource> download(@RequestParam String path) throws IOException {
    Path file = files.file(path);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            disposition(ContentDisposition.attachment(), file.getFileName().toString()))
        .contentLength(Files.size(file))
        .body(new FileSystemResource(file));
  }

  @PostMapping("/files/download-selected")
  public ResponseEntity<StreamingResponseBody> downloadSelected(
      @RequestParam("paths") List<String> selected) throws IOException {
    Path archive = files.createArchive(selected);
    long size = Files.size(archive);
    StreamingResponseBody body =
        output -> {
          try (var input = Files.newInputStream(archive)) {
            input.transferTo(output);
          } finally {
            Files.deleteIfExists(archive);
          }
        };
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("songradar-download.zip").build().toString())
        .contentLength(size)
        .body(body);
  }

  /**
   * The filename is encoded per RFC 6266 rather than written into the header raw. A library track
   * is named after its artist and title, so it routinely contains characters the header's own
   * charset cannot hold — and the server then drops the whole header rather than the name.
   */
  private static String disposition(ContentDisposition.Builder builder, String filename) {
    return builder.filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString();
  }

  @GetMapping("/files/audio")
  public ResponseEntity<Resource> audio(@RequestParam String path) throws IOException {
    Path file = files.file(path);
    MediaType type =
        MediaTypeFactory.getMediaType(file.getFileName().toString())
            .orElse(MediaType.APPLICATION_OCTET_STREAM);
    return ResponseEntity.ok()
        .contentType(type)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            disposition(ContentDisposition.inline(), file.getFileName().toString()))
        .contentLength(Files.size(file))
        .body(new FileSystemResource(file));
  }

  private String perform(
      String path, String scope, RedirectAttributes redirect, IoAction action, String success) {
    try {
      action.run();
      redirect.addFlashAttribute("successMessage", success);
    } catch (Exception e) {
      redirect.addFlashAttribute("errorMessage", message(e));
    }
    return go(path, scope);
  }

  private static String message(Exception exception) {
    return exception instanceof IllegalArgumentException && exception.getMessage() != null
        ? exception.getMessage()
        : "That file operation could not be completed.";
  }

  private static String go(String path, String scope) {
    return ("library".equals(scope) ? "redirect:/library" : "redirect:/")
        + (path == null || path.isBlank()
            ? ""
            : "?path=" + java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8));
  }

  private static String scopedPath(String path, String scope) {
    if (!"library".equals(scope)) return path == null ? "" : path;
    String suffix = path == null ? "" : path;
    if (suffix.startsWith("/")
        || suffix.equals("..")
        || suffix.startsWith("../")
        || suffix.contains("/../")) {
      throw new IllegalArgumentException("Invalid library path.");
    }
    return suffix.isBlank() ? "library" : "library/" + suffix;
  }

  @SuppressWarnings("unchecked")
  private static List<String> clipboard(HttpSession session) {
    Object value = session.getAttribute(CLIPBOARD);
    return value instanceof List<?> list ? (List<String>) list : List.of();
  }

  @FunctionalInterface
  private interface IoAction {
    void run() throws Exception;
  }

  public record Breadcrumb(String path, String label) {}
}
