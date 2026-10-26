-- Which provider identifies new recordings. A singleton row rather than configuration
-- because it is switched from the settings page while the application is running —
-- provider credentials stay in the environment, only the choice lives here.
create table recognition_settings
(
    id         smallint primary key,
    provider   varchar(20) not null,
    updated_at timestamptz not null default now(),
    constraint recognition_settings_singleton_check check (id = 1),
    constraint recognition_settings_provider_check check (provider in ('audd', 'shazam', 'none'))
);

insert into recognition_settings (id, provider)
values (1, 'audd');
