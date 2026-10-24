# SongRadar

Self-hosted music discovery from IPTV and radio streams. Automatically identify songs, find available
audio, and manage your personal music library.

> **Status: initial scaffold.** The application starts, connects to PostgreSQL and serves a styled
> homepage. There are no REST endpoints, no authentication and none of the music functionality yet.

## Stack

| Piece       | What                                                          |
|-------------|---------------------------------------------------------------|
| Language    | Java 25                                                       |
| Framework   | Spring Boot 4.1 — Spring Web MVC, Thymeleaf, Spring Data JDBC |
| Database    | PostgreSQL                                                    |
| Styling     | Tailwind CSS 4, compiled by the Maven build                   |
| Build       | Maven (wrapper included — no Maven install required)          |

## Requirements

- JDK 25
- A reachable PostgreSQL database
- Docker, if you want the quickest way to get that database

Node is **not** required: the build downloads its own copy into the project to compile the stylesheet.

## Running locally

### 1. Start PostgreSQL

```bash
docker run -d --name songradar-pg \
  -e POSTGRES_DB=songradar \
  -e POSTGRES_USER=songradar \
  -e POSTGRES_PASSWORD=songradar \
  -p 5432:5432 \
  postgres:18-alpine
```

### 2. Configure the environment

```bash
cp .env.example .env
set -a && source .env && set +a
```

`.env.example` matches the container above, so it works unedited. `.env` itself is gitignored.

### 3. Run

```bash
./mvnw spring-boot:run
```

Then open <http://localhost:8080>.

On startup the log reports the database it reached:

```
i.g.k.s.config.DatabaseStartupCheck : Connected to PostgreSQL 18.6 on aarch64-unknown-linux-musl ...
```

A wrong host or password fails here, at startup, rather than on the first page that reads from the
database.

## Configuration

Every setting is an environment variable with a local-development default, so the same jar runs
against a container on a laptop and a managed database with nothing but the environment changed.

| Variable                   | Default     | Notes                                                       |
|----------------------------|-------------|--------------------------------------------------------------|
| `SONGRADAR_DB_HOST`        | `localhost` |                                                               |
| `SONGRADAR_DB_PORT`        | `5432`      |                                                               |
| `SONGRADAR_DB_NAME`        | `songradar` |                                                               |
| `SONGRADAR_DB_USERNAME`    | `songradar` |                                                               |
| `SONGRADAR_DB_PASSWORD`    | *(none)*    | Required. No default — a fallback password reaches production unnoticed |
| `SONGRADAR_PORT`           | `8080`      | HTTP port                                                     |
| `SONGRADAR_TEMPLATE_CACHE` | `true`      | Set `false` to re-render templates on every request           |

## Build

```bash
./mvnw clean package     # compiles the stylesheet, runs the tests, builds the jar
./mvnw test              # tests only
java -jar target/song-radar-0.0.1-SNAPSHOT.jar
```

## Styling

Tailwind input is `src/main/tailwind/app.css`; the compiled stylesheet is generated into
`target/classes/static/css/app.css` and served at `/css/app.css`. It is a build artifact and is not
committed.

`./mvnw` rebuilds it on every run. To rebuild on save while editing templates:

```bash
npm run watch:css
```

Templates live outside the Tailwind input's directory, so `src/main/tailwind/app.css` registers them
with `@source`. A template added anywhere else needs its own `@source` line, or the classes it uses
are stripped from the build.

## Layout

```
src/main/java/io/github/kalpak44/songradar/
├── SongRadarApplication.java        # entry point
├── config/DatabaseStartupCheck.java # proves the database connection at startup
└── web/HomeController.java          # GET /
src/main/resources/
├── application.yml                  # environment-driven configuration
└── templates/index.html             # the homepage
src/main/tailwind/app.css            # Tailwind input
```
