-- Null or empty means the listener records around the clock, which is how every existing
-- listener behaved before this column existed.
alter table playlist_listeners
    add column listen_windows varchar(200);
