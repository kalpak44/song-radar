package io.github.kalpak44.songradar.playlist;

import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface PlaylistSourceRepository extends ListCrudRepository<PlaylistSource, Long> {

  List<PlaylistSource> findAllByOrderByAddedAtDesc();
}
