package io.github.kalpak44.songradar.playlist;

import java.io.Serial;

public class PlaylistUnavailableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public PlaylistUnavailableException(String message) {
    super(message);
  }

  public PlaylistUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
