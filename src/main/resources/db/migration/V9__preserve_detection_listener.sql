alter table sound_detections
    add column listener_name varchar(200);

update sound_detections detection
set listener_name = listener.name
from listener_recordings recording
         join playlist_listeners listener on listener.id = recording.listener_id
where detection.recording_id = recording.id;
