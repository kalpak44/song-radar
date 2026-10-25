alter table listener_recordings
    add column duration_millis bigint not null default 0;

update listener_recordings
set duration_millis = greatest(0, floor(extract(epoch from (completed_at - started_at)) * 1000)::bigint)
where status = 'COMPLETED';

alter table listener_recordings
    add constraint listener_recordings_duration_check check (duration_millis >= 0);
