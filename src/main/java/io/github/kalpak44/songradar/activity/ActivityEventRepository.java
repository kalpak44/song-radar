package io.github.kalpak44.songradar.activity;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ActivityEventRepository extends CrudRepository<ActivityEvent, Long> {

  @Query(
      "select * from activity_events where occurred_at >= :since "
          + "order by occurred_at desc, id desc limit :limit")
  List<ActivityEvent> findSince(@Param("since") Instant since, @Param("limit") int limit);

  @Modifying
  @Query("delete from activity_events where occurred_at < :before")
  int deleteOlderThan(@Param("before") Instant before);
}
