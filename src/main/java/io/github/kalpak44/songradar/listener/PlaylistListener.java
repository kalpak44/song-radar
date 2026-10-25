package io.github.kalpak44.songradar.listener;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("playlist_listeners")
public record PlaylistListener(
    @Id Long id,
    Long playlistSourceId,
    String name,
    String channelName,
    String channelUrl,
    int recordingIntervalSeconds,
    int recordingDurationSeconds,
    String listenWindows,
    ListenerStatus status,
    Instant createdAt,
    Instant updatedAt,
    Instant lastRecordingAt,
    Instant nextRecordingAt) {}
