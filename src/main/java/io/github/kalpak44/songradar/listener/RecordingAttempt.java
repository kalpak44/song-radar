package io.github.kalpak44.songradar.listener;

import java.time.Instant;

public record RecordingAttempt(Instant startedAt, boolean completed) {}
