create table sound_detections
(
    id             bigserial primary key,
    recording_id   bigint references listener_recordings (id) on delete set null,
    status         varchar(20) not null check (status in ('PENDING', 'MATCHED', 'NO_MATCH', 'UNAVAILABLE', 'FAILED')),
    provider       varchar(40) not null,
    track_key      varchar(1100) unique,
    title          varchar(500),
    artist         varchar(500),
    album          varchar(500),
    release_date   varchar(40),
    track_url      text,
    first_detected_at timestamptz not null default now(),
    detected_at    timestamptz not null default now(),
    detection_count integer not null default 1 check (detection_count > 0),
    error_message  varchar(400)
);

create index sound_detections_detected_at_idx on sound_detections (detected_at desc, id desc);
