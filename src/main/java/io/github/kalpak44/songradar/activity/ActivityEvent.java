package io.github.kalpak44.songradar.activity;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One line of the activity feed. The listener and track are copied in as text rather than
 * referenced: the feed has to keep reading correctly after a recording is pruned or a listener
 * renamed, which is exactly when it is worth reading.
 */
@Table("activity_events")
public record ActivityEvent(
    @Id Long id,
    Instant occurredAt,
    ActivityKind kind,
    String listener,
    String track,
    String source,
    String message) {}
