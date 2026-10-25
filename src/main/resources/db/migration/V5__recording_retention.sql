alter table storage_settings
    drop constraint storage_settings_positive_limit_check,
    drop column limit_bytes,
    add column records_per_listener integer not null default 20,
    add constraint storage_settings_records_per_listener_check
        check (records_per_listener between 1 and 1000);
