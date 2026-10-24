-- A listener owns one channel selection and one recording schedule. Several listeners
-- may intentionally point at the same channel.
create table playlist_listeners
(
    id                         bigserial primary key,
    playlist_source_id         bigint       not null references playlist_sources (id) on delete cascade,
    name                       varchar(200) not null,
    channel_name               varchar(300) not null,
    channel_url                text         not null,
    recording_interval_seconds integer      not null,
    recording_duration_seconds integer      not null,
    status                     varchar(20)  not null,
    created_at                 timestamptz  not null default now(),
    updated_at                 timestamptz  not null default now(),
    last_recording_at          timestamptz,
    next_recording_at          timestamptz,
    constraint playlist_listeners_status_check check (status in ('ACTIVE', 'PAUSED')),
    constraint playlist_listeners_interval_check check (recording_interval_seconds >= 60),
    constraint playlist_listeners_duration_check check (
        recording_duration_seconds > 0
            and recording_duration_seconds < recording_interval_seconds
        )
);

create index playlist_listeners_playlist_idx
    on playlist_listeners (playlist_source_id, created_at);

-- Keep every attempt, including failures, so stream outages remain observable without
-- turning a transient failure into a stopped schedule.
create table listener_recordings
(
    id           bigserial primary key,
    listener_id  bigint       not null references playlist_listeners (id) on delete cascade,
    file_path    text,
    started_at   timestamptz  not null,
    completed_at timestamptz  not null,
    status       varchar(20)  not null,
    size_bytes   bigint       not null default 0,
    error_message varchar(500),
    constraint listener_recordings_status_check check (status in ('COMPLETED', 'FAILED')),
    constraint listener_recordings_size_check check (size_bytes >= 0),
    constraint listener_recordings_result_check check (
        (status = 'COMPLETED' and file_path is not null and error_message is null)
            or (status = 'FAILED' and file_path is null and error_message is not null)
        )
);

create index listener_recordings_listener_idx
    on listener_recordings (listener_id, started_at desc);
