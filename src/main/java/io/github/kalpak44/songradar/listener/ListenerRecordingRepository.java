package io.github.kalpak44.songradar.listener;

import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

public interface ListenerRecordingRepository extends ListCrudRepository<ListenerRecording, Long> {

  List<ListenerRecording> findAllByOrderByStartedAtDesc();

  List<ListenerRecording> findAllByListenerIdOrderByStartedAtDesc(Long listenerId);

  @Query(
      """
      select count(*)
      from listener_recordings
      where listener_id = :listenerId and status = 'COMPLETED'
      """)
  long countCompletedByListenerId(Long listenerId);

  @Query(
      """
      select *
      from listener_recordings
      where listener_id = :listenerId and status = 'COMPLETED'
      order by started_at asc, id asc
      limit :count
      """)
  List<ListenerRecording> findOldestCompletedByListenerId(Long listenerId, long count);

  @Query(
      """
      select r.*
      from listener_recordings r
      join playlist_listeners l on l.id = r.listener_id
      where (:listenerId is null or r.listener_id = :listenerId)
        and (:playlistId is null or l.playlist_source_id = :playlistId)
      order by r.started_at desc, r.id desc
      limit :limit offset :offset
      """)
  List<ListenerRecording> findPageNewest(Long listenerId, Long playlistId, int limit, long offset);

  @Query(
      """
      select r.*
      from listener_recordings r
      join playlist_listeners l on l.id = r.listener_id
      where (:listenerId is null or r.listener_id = :listenerId)
        and (:playlistId is null or l.playlist_source_id = :playlistId)
      order by r.started_at asc, r.id asc
      limit :limit offset :offset
      """)
  List<ListenerRecording> findPageOldest(Long listenerId, Long playlistId, int limit, long offset);

  @Query(
      """
      select count(*)
      from listener_recordings r
      join playlist_listeners l on l.id = r.listener_id
      where (:listenerId is null or r.listener_id = :listenerId)
        and (:playlistId is null or l.playlist_source_id = :playlistId)
      """)
  long countFiltered(Long listenerId, Long playlistId);
}
