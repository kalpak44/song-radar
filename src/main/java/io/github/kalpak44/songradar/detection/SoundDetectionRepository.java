package io.github.kalpak44.songradar.detection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

public interface SoundDetectionRepository extends ListCrudRepository<SoundDetection, Long> {

  List<SoundDetection> findAllByStatus(DetectionStatus status);

  Optional<SoundDetection> findByTrackKey(String trackKey);

  Optional<SoundDetection> findFirstByRecordingIdAndStatusOrderByIdDesc(
      Long recordingId, DetectionStatus status);

  @Query(
      """
      select * from sound_detections
      order by detected_at desc, id desc
      limit :limit offset :offset
      """)
  List<SoundDetection> findPage(int limit, long offset);

  @Query("select count(*) from sound_detections")
  long countAll();

  long countAllByStatus(DetectionStatus status);
}
