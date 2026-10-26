# SongRadar

Self-hosted music discovery from IPTV and radio streams. Automatically identify songs, find available
audio, and manage your personal music library.

> **Status: early development.** Playlist sources, scheduled channel listeners, storage settings,
> recordings, sound detections, track downloads and an activity feed are available; music library
> management and authentication are still to come.

Java 25 · Spring Boot 4.1 (Web MVC, Thymeleaf, Data JDBC) · PostgreSQL · Tailwind CSS 4

## Run

Needs JDK 25 and a PostgreSQL database. Node is not required — the build fetches its own to compile
the stylesheet.

```bash
docker run -d --name songradar-pg -p 5432:5432 \
  -e POSTGRES_DB=songradar -e POSTGRES_USER=songradar -e POSTGRES_PASSWORD=songradar \
  postgres:18-alpine

cp .env.example .env && set -a && source .env && set +a
./mvnw spring-boot:run
```

<http://localhost:8080>. The startup log reports the database it reached, so a wrong host or password
fails there rather than on the first page that reads from it.

## Configuration

All environment variables, defaulted for local development in `.env.example`:
`SONGRADAR_DB_HOST`, `SONGRADAR_DB_PORT`, `SONGRADAR_DB_NAME`, `SONGRADAR_DB_USERNAME`,
`SONGRADAR_DB_PASSWORD`, `SONGRADAR_PORT`, `SONGRADAR_TEMPLATE_CACHE`, and
`SONGRADAR_STORAGE_PATH`, `SONGRADAR_STORAGE_LIBRARY`, `SONGRADAR_STORAGE_CAPACITY_GB`,
`SONGRADAR_LISTENER_THREADS`, `SONGRADAR_TIMEZONE`, and the optional `SONGRADAR_AUDD_API_TOKEN`.

`SONGRADAR_DB_PASSWORD` has no default — a fallback password is one that reaches production unnoticed.
`SONGRADAR_STORAGE_PATH` points at the writable mounted volume. `SONGRADAR_STORAGE_LIBRARY`
overrides where downloaded tracks are filed — one subfolder per listener — and defaults to a
`library` folder inside the storage path. The application defaults to `/data`
for the cluster; `.env.example` selects `./data` for local development.
`SONGRADAR_STORAGE_CAPACITY_GB` optionally supplies a logical capacity for storage such as NFS,
where filesystem statistics describe the whole server rather than the application's volume.
`SONGRADAR_TIMEZONE` is the zone a listener's listening windows are read in — they are written as
wall-clock times and the application itself runs on UTC, so a window means nothing without it.

`SONGRADAR_LISTENER_THREADS` controls how many different listeners may record concurrently. A
single listener always waits for its current recording to finish before scheduling its next one.
`SONGRADAR_RECOGNITION_THREADS` controls the separate background recognition worker pool.
Track recognition is chosen on the Settings page, not in the environment: pick AudD, Shazam or
Disabled, and the choice applies to the next recording rather than the next restart. The Sound
detections page groups repeated matches by normalized artist and title. While recognition is
disabled, recordings are still captured and listed as recognition unavailable.

AudD uploads each recording to the AudD API and needs `SONGRADAR_AUDD_API_TOKEN`; selecting it
without one leaves recordings unidentified and says so. The free trial is 300 requests in total,
which a listener capturing every few minutes spends in well under a day, after which every
recognition fails until the plan is paid for.

Shazam needs no account, no token and has no quota. SongRadar fingerprints the capture itself and
sends only its spectral peaks — never the audio — to the endpoint Shazam's own Android app uses.
That endpoint is undocumented and carries no contract: it can change without notice, in which case
recognition starts failing on an unparseable response rather than a stated error, and Shazam
throttles by source address, so one capture every few minutes is fine where a burst is not.
`ShazamRecognitionAvailabilityTest` recognises real recordings against the live service and is the
check for whether the client still works:

```bash
./mvnw test -Dtest=ShazamRecognitionAvailabilityTest -Dexcluded.test.groups= \
    -Dsongradar.recordings=./data/recordings
```

## Checks

CI runs these on every pull request and a failure blocks the merge:

```bash
./mvnw fmt:check     # google-java-format; fmt:format fixes it
./mvnw verify        # tests + JaCoCo coverage
```

SonarCloud's quality gate is waited on in CI, so a gate failure fails the check.

## Image

Built by jib from the compiled classes — no Dockerfile; the base image is a property in `pom.xml`.
CI publishes `ghcr.io/kalpak44/song-radar` from `main`, tagged `latest` and the short SHA.

```bash
./mvnw package jib:dockerBuild -Djib.to.image=song-radar:local
```

## Styling

Tailwind input is `src/main/tailwind/app.css`; the build generates `target/classes/static/css/app.css`
and is not committed. `npm run watch:css` rebuilds on save. Templates sit outside the input's
directory, so they are registered with `@source` — a template added elsewhere needs its own line, or
its classes are stripped.
