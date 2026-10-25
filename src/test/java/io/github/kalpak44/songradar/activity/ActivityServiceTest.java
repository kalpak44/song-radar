package io.github.kalpak44.songradar.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ActivityServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

  private ActivityEventRepository events;
  private ActivityService service;

  @BeforeEach
  void setUp() {
    events = mock(ActivityEventRepository.class);
    service = new ActivityService(events, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void recordsTheEventAtTheCurrentInstant() {
    service.record(ActivityKind.SAVED, "Radio One", "Daft Punk - Get Lucky", "Audius", "Saved it");

    ArgumentCaptor<ActivityEvent> saved = ArgumentCaptor.forClass(ActivityEvent.class);
    verify(events).save(saved.capture());
    assertThat(saved.getValue().occurredAt()).isEqualTo(NOW);
    assertThat(saved.getValue().kind()).isEqualTo(ActivityKind.SAVED);
    assertThat(saved.getValue().listener()).isEqualTo("Radio One");
    assertThat(saved.getValue().track()).isEqualTo("Daft Punk - Get Lucky");
    assertThat(saved.getValue().source()).isEqualTo("Audius");
  }

  @Test
  void collapsesWhitespaceAndStoresBlanksAsNothing() {
    service.record(ActivityKind.RECORDED, "  Radio\n One  ", "   ", null, "a\t\tmessage");

    ArgumentCaptor<ActivityEvent> saved = ArgumentCaptor.forClass(ActivityEvent.class);
    verify(events).save(saved.capture());
    assertThat(saved.getValue().listener()).isEqualTo("Radio One");
    assertThat(saved.getValue().track()).isNull();
    assertThat(saved.getValue().source()).isNull();
    assertThat(saved.getValue().message()).isEqualTo("a message");
  }

  @Test
  void truncatesTextThatWouldNotFitTheColumn() {
    service.record(ActivityKind.SEARCHING, null, "t".repeat(900), null, null);

    ArgumentCaptor<ActivityEvent> saved = ArgumentCaptor.forClass(ActivityEvent.class);
    verify(events).save(saved.capture());
    assertThat(saved.getValue().track()).hasSize(500);
  }

  /** The feed reports on the work; it must never be able to stop it. */
  @Test
  void swallowsAFailureToWrite() {
    when(events.save(any())).thenThrow(new IllegalStateException("database is away"));

    service.record(ActivityKind.SAVED, "Radio One", "A track", "Audius", null);
  }

  @Test
  void readsOnlyTheLastDay() {
    when(events.findSince(any(), eq(service.pageLimit()))).thenReturn(List.of());

    service.lastDay();

    verify(events).findSince(NOW.minus(Duration.ofDays(1)), service.pageLimit());
  }

  @Test
  void prunesOlderThanTheRetentionWindow() {
    when(events.deleteOlderThan(any())).thenReturn(3);

    service.prune();

    verify(events).deleteOlderThan(NOW.minus(Duration.ofDays(7)));
  }
}
