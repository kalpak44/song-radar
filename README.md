# SongRadar

Self-hosted music discovery from IPTV and radio streams. Automatically identify songs, find available
audio, and manage your personal music library.

> **Status: initial scaffold.** Starts, connects to PostgreSQL and serves a homepage. No endpoints,
> no auth, none of the music functionality yet.

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
`SONGRADAR_DB_PASSWORD`, `SONGRADAR_PORT`, `SONGRADAR_TEMPLATE_CACHE`.

`SONGRADAR_DB_PASSWORD` has no default — a fallback password is one that reaches production unnoticed.

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
