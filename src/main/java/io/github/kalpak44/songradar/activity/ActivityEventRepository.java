package io.github.kalpak44.songradar.activity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ActivityEventRepository extends CrudRepository<ActivityEvent, Long> {

  @Query(
      "select * from activity_events where occurred_at >= :since "
          + "order by occurred_at desc, id desc limit :limit")
  List<ActivityEvent> findSince(@Param("since") Instant since, @Param("limit") int limit);

  @Query(
      "select listener from activity_events where kind = 'DETECTED' and track = :track "
          + "order by occurred_at desc, id desc limit 1")
  Optional<String> findLatestDetectedListener(@Param("track") String track);

  @Modifying
  @Query("delete from activity_events where occurred_at < :before")
  int deleteOlderThan(@Param("before") Instant before);
}
