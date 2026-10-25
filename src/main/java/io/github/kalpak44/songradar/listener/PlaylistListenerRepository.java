package io.github.kalpak44.songradar.listener;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface PlaylistListenerRepository extends ListCrudRepository<PlaylistListener, Long> {

  List<PlaylistListener> findAllByPlaylistSourceIdOrderByCreatedAtAsc(long playlistSourceId);

  Optional<PlaylistListener> findByIdAndPlaylistSourceId(long id, long playlistSourceId);

  List<PlaylistListener> findAllByStatus(ListenerStatus status);

  @Modifying
  @Query(
      """
      update playlist_listeners
      set last_recording_at = cast(:lastRecordingAt as timestamptz),
          next_recording_at = case
              when status = 'ACTIVE' then cast(:nextRecordingAt as timestamptz)
              else null
          end,
          updated_at = cast(:updatedAt as timestamptz)
      where id = :id
      """)
  int updateAfterRecording(
      @Param("id") long id,
      @Param("lastRecordingAt") Instant lastRecordingAt,
      @Param("nextRecordingAt") Instant nextRecordingAt,
      @Param("updatedAt") Instant updatedAt);
}
