-- Playlist sources the user has registered. Channels parsed out of them are a later
-- concern; this table holds only where a playlist came from.
create table playlist_sources
(
    id          bigserial primary key,
    name        varchar(200) not null,
    type        varchar(20)  not null,
    -- Set for a URL source, null for an uploaded one.
    url         text,
    -- Path of the stored upload, relative to the storage root so the root can move
    -- without rewriting every row. Null for a URL source.
    stored_path text,
    added_at    timestamptz  not null default now(),
    constraint playlist_sources_type_check check (type in ('URL', 'FILE')),
    -- Exactly one of the two locations, matching the type. A row with neither is a
    -- source nothing can ever read.
    constraint playlist_sources_location_check check (
        (type = 'URL' and url is not null and stored_path is null)
            or (type = 'FILE' and stored_path is not null and url is null)
        )
);

-- The list is always read newest first.
create index playlist_sources_added_at_idx on playlist_sources (added_at desc);
