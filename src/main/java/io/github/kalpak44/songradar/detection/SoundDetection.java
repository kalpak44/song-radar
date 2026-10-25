package io.github.kalpak44.songradar.detection;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("sound_detections")
public record SoundDetection(
    @Id Long id,
    Long recordingId,
    DetectionStatus status,
    String provider,
    String trackKey,
    String title,
    String artist,
    String album,
    String releaseDate,
    String trackUrl,
    Instant firstDetectedAt,
    Instant detectedAt,
    int detectionCount,
    String errorMessage) {}
