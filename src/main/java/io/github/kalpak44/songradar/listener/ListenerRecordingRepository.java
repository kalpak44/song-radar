package io.github.kalpak44.songradar.listener;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface ListenerRecordingRepository extends ListCrudRepository<ListenerRecording, Long> {

  List<ListenerRecording> findAllByListenerIdOrderByStartedAtDesc(Long listenerId);
}
