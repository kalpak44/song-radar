package io.github.kalpak44.songradar.activity;

/**
 * Records what happened to a track. Kept to one method so the components that report through it
 * stay testable without a database.
 */
public interface ActivityLog {

  /** For the constructors that predate the feed, and for tests that are not about it. */
  ActivityLog NONE = (kind, listener, track, source, message) -> {};

  void record(ActivityKind kind, String listener, String track, String source, String message);
}
