# Music sources

Where `SourcedMusicDownloader` gets a detected track. Five sources are tried in order; the first
that returns a playable MP3 wins. They are third-party APIs, four of them undocumented, so they are
expected to rot — `SourceAvailabilityTest` downloads from each one on a schedule and fails when one
stops working.

| Order | Source | API | Audio served | What is filed |
|-------|--------|-----|--------------|---------------|
| 1 | Audius | documented, public | MP3 320 kbps | the same bytes |
| 2 | JioSaavn | undocumented | progressive AAC 320 kbps in MP4 | MP3 192 kbps |
| 3 | YouTube Music (yt-dlp) | none — a subprocess | Opus ~160 kbps | MP3, LAME V0, by ffmpeg |
| 4 | SoundCloud | undocumented | HLS: MPEG 128 kbps, or AAC 160/256 kbps | the MPEG bytes, or MP3 192 kbps |
| 5 | Bandcamp | undocumented | MP3 128 kbps | the same bytes |

## The order is the audio, best first

The first source that answers wins, so nothing downstream gets another chance at a better copy —
which makes the order a quality ranking and not a preference. Two things decide a place in it: the
bitrate the source serves, and whether this project has to decode and re-encode it to get an MP3.

Audius leads because 320 kbps MP3 needs neither. JioSaavn serves the same bitrate as AAC, which
costs a decode and lands at 192 kbps. YouTube Music's Opus is nominally the lowest bitrate of the
three, but Opus at 160 kbps is worth more than the 128 kbps MP3 the last two hand over, and yt-dlp
re-encodes it with ffmpeg at LAME V0 rather than through the transcoder here. SoundCloud and
Bandcamp are both 128 kbps; SoundCloud goes first because its catalogue is wider.

**`@Order` on each class is what Spring reads, and `TrackSourceOrderTest` is what keeps the values
apart.** Audius and SoundCloud were both `@Order(1)` for a while, which left the choice between a
320 kbps source and a 128 kbps one to the order the classpath happened to be scanned in.

**Ranking YouTube Music third costs time on every miss above it.** A search there is one flat
listing plus five extractions, about eight seconds, and it runs before SoundCloud is ever asked.
YouTube also refuses datacenter addresses, so a deployment that is blocked pays that on every
download without ever getting a file. Moving it back to the end is a one-line change to its
`@Order`.

**The transcoder caps what the AAC sources can deliver.** `Mp3Encoding` encodes at 192 kbps CBR, so
JioSaavn's 320 kbps source and SoundCloud's `aac_256k` both arrive better than they are filed. The
decoder behind it also blanks frames it cannot handle — 0.4% at 320 kbps, 2.4% at 256 kbps, 7.9% at
160 kbps — which is why the sources that need no decode are ranked above the ones that do. See
docs/recording.md for what that decoder does and how it was measured.

## What breaks, and where to look

**SoundCloud needs a `client_id` that is published nowhere.** It is scraped out of the site's own
JavaScript bundles, newest first. SoundCloud rotates it rather than revoking it, so a single
re-scrape separates an expired id from the site being unreachable. A stream request also needs the
`track_authorization` that came with the search result; without it the stream answers 404.

**An advertised SoundCloud variant may not exist.** Uploads list an `audio/mpegurl` transcoding that
frequently 404s on fetch, so every advertised variant is tried in turn rather than just the best one.

**JioSaavn returns JSON labelled `text/html`,** which no message converter will touch — the body is
read as text and parsed directly. Its media URL is DES-encrypted, but the site's own
`song.generateAuthToken` endpoint resolves the encrypted path to a signed URL, so the key never has
to live in this application.

**A query must be handed to the HTTP client as a URI variable, never encoded into the string.**
A `String` passed to `RestClient.uri` is treated as a template and encoded a second time. It does not
fail: SoundCloud answers a double-encoded Cyrillic query with ten unrelated uploads, the matcher
rejects them all, and the source looks like a catalogue that simply does not have the track. Every
detection from a non-Latin station went undownloaded this way. A pre-built `java.net.URI` is the
other safe form, and is what the sources that fetch an API-supplied URL use.

**Bandcamp's stream URL is on the track page, not in the search result** — in a `data-tralbum`
attribute holding HTML-escaped JSON. Its catalogue leans towards remixes and reworks, which is what
the matcher's marker list is for.

**Audius is reached through a discovery node** rather than a fixed host; the registry at
`api.audius.co` names the ones currently serving.

## YouTube Music runs a subprocess, and that is a deliberate exception

Nothing else reaches YouTube. Its own player endpoints answer `LOGIN_REQUIRED` or `ERROR` to every
client — the mobile-client spoofs that SpotiFlyer relies on included — and the public mirrors are
gone: Invidious instances answer 401, 403 or 530, and Piped is dead. The only route left is yt-dlp,
which is a program, not a library. It is the slowest source and the only one that can be broken by
something outside this repository, which is why it sat last in the chain until the order was made a
quality ranking; what keeps it third now is the audio it serves, not what it costs to run.

**It searches YouTube Music, not YouTube.** Both are the same site to yt-dlp and serve the same
media, but a YouTube Music result is a song carrying an artist, an album and a length, while a
YouTube result is whatever a channel uploaded under that name. The artist is the half of the match
that decides whether a result is the right recording, and `TrackMatching` had only a channel name to
score it against — "Around the World" uploaded by "topic" channels, DJ sets and reuploads all answer
the same search.

**That costs a second process per search.** The YouTube Music search listing is flat: its entries
carry an id and a title and leave the artist, the album and the duration null, so the five
candidates are extracted in one further yt-dlp run, which was measured at about a second and a half
each. The search URL carries YouTube Music's own *Songs* filter (`sp=EgWKAQIIAWoKEAoQCRADEAQQBQ==`),
an opaque blob that may quietly stop being honoured — the search then answers with the mixed listing
that includes artist and album rows, and an album row extracts to a whole playlist rather than a
song, so only entries yt-dlp labels `ie_key: Youtube` are described.

**A candidate that fails its own extraction must not take the other four with it.** yt-dlp exits
non-zero when any URL in the run failed, so the describe step reads the output it did produce and
only treats the exit code as fatal when nothing came back at all.

**`duration` is null in a flat result, and Jackson 3 refuses to map null onto a primitive** — it is
a `Double` on the entry for that reason. A `double` field fails the whole parse with
`Cannot map null into type double`, which surfaces as this source silently finding nothing.

**It is paired with ffmpeg and cannot run without it.** The M4A stream YouTube serves needs a
container fixup before anything can decode it — with `--fixup never` the file is rejected by this
project's parser *and* by CoreAudio. Since ffmpeg has to be installed beside yt-dlp anyway, yt-dlp
extracts the MP3 itself rather than handing AAC to the transcoder here.

**The image ships both binaries, so nothing has to be installed around it.** `pom.xml` pins
`ytdlp.version` and `ffmpeg.version` with the checksums their publishers state, and
download-maven-plugin fetches them at `prepare-package` into `/usr/local/bin`, which jib copies into
the image and marks executable. That directory is already on `PATH`, so the application finds
`yt-dlp` under its default configuration and yt-dlp finds ffmpeg without `--ffmpeg-location`. The
ffmpeg binary is `ffmpeg-musl-forge`'s fully static musl build: the glibc-linked BtbN and Jellyfin
builds do not start on this Alpine base image. FFmpeg 9 also stopped embedding the literal
`%s version <version>` string the image scanner classifies on, so `src/main/jib-metadata/ffmpeg` is
filtered into `/usr/local/share/song-radar/ffmpeg` with the pinned version. It is metadata only;
removing it makes grype blind to the bundled ffmpeg. Two details are easy to get wrong: the base
image is Alpine, so it needs the **musl** build of yt-dlp — `yt-dlp_linux` is glibc and will not
start — and yt-dlp is a self-extracting bundle, so the directory it unpacks into must allow
execution. `readOnlyRootFilesystem` is fine because the Deployment mounts an `emptyDir` at `/tmp`;
a `noexec` mount there is not, and it fails as `Error loading shared library libz.so.1: Operation
not permitted`, which reads like a missing library rather than a mount option. ffprobe is not
bundled: it costs 80 MB and audio extraction was measured to work without it.

**A yt-dlp that is months old fails in a way that reads as a bug here.** Measured: version
2026.03.17 lists formats and reports metadata perfectly, then fails the media download with
`HTTP Error 403`. 2026.10.21 downloads the same track in about eleven seconds. Pin a recent build
and move the pin; the scheduled source check is what reports it when it rots.

**YouTube refuses datacenter addresses,** so this source is expected to be unavailable on a CI
runner even when it works at home. Its probe in the source check is therefore reported but not
asserted — a check that is permanently red is a check nobody reads.

**Arguments are passed as a list, never through a shell.** The query is a title that came from a
recognition provider, so quoting it into a command line would be an injection.

## HE-AAC is rejected, not ranked last

The transcoder decodes AAC-LC faithfully and HE-AAC badly, and the difference was measured against
CoreAudio decoding the same bytes: AAC-LC reproduces the reference envelope at 0.95 correlation with
the same zero-crossing rate, while HE-AAC comes back at 0.3 with silent gaps punched through it and
the high band missing. Dumping the decoder's PCM straight to WAV shows those gaps before the MP3
encoder sees them, so it is the decoder, not the encoding — this build reconstructs no spectral band
replication.

Nothing about the resulting file gives it away. It is the right length, the right sample rate, and
parses as valid MP3, so the length and format checks in `SourcedMusicDownloader` pass. Only listening
to it catches it.

So HE-AAC variants are filtered out of the candidate list entirely rather than tried last. An upload
offering nothing else is passed over while searching, which lets a lower-scoring upload that can be
transcoded faithfully take its place, and a track available nowhere but HE-AAC is simply not
downloaded. No file beats a file of noise.

Ruled out while chasing this, and worth not re-checking: sample byte order (the decoder emits
big-endian unless told otherwise, which is now set explicitly), the `trun` `data_offset` (now
honoured), dropped or failed frames (every frame decodes to a full 1024 samples), and the sample
rate.

**Internet Archive was tried and dropped.** It serves MP3 and needs no credential, which is why it
looked worth having, but a search for a song answers with sermons, podfics and audiobooks that
mention the words — its catalogue is not current music. It contributed nothing the other four did
not already cover, while adding a search to every failed download and a probe that kept reporting
the whole chain broken.

## Reference implementation

<https://github.com/Shabinder/SpotiFlyer> solved the same problem and its providers are worth reading
for the API shapes — the SoundCloud client-id scrape and the JioSaavn endpoint both came from there.
Two of its paths are dead and should not be copied: `yt1s.com`, which it used for direct MP3, now
answers 404, and YouTube's player endpoint rejects the mobile-client spoofs it relies on. SpotiFlyer
shells out to ffmpeg for its conversion, which is not an option here — this application launches no
subprocesses and the image carries a JRE and nothing else.
