-- One application-wide quota. Enforcement and automatic deletion are deliberately
-- deferred; this row only records the limit selected on the storage settings page.
create table storage_settings
(
    id          smallint primary key,
    limit_bytes bigint      not null,
    updated_at  timestamptz not null default now(),
    constraint storage_settings_singleton_check check (id = 1),
    constraint storage_settings_positive_limit_check check (limit_bytes > 0)
);
