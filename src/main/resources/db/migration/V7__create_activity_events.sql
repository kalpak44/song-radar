create table activity_events
(
    id          bigserial primary key,
    occurred_at timestamptz not null default now(),
    kind        varchar(20) not null check (kind in
                                            ('RECORDED', 'DETECTED', 'NOT_IDENTIFIED', 'SEARCHING',
                                             'SAVED', 'UNAVAILABLE')),
    listener    varchar(200),
    track       varchar(500),
    source      varchar(40),
    message     varchar(500)
);

-- The page reads one day, newest first, and nothing reads a single event by id.
create index activity_events_occurred_at_idx on activity_events (occurred_at desc, id desc);
