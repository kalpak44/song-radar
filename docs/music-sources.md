# Music sources

Where `SourcedMusicDownloader` gets a detected track. Three sources are tried in order; the first
that returns a playable MP3 wins. They are third-party APIs, two of them undocumented, so they are
expected to rot — `SourceAvailabilityTest` downloads from each one on a schedule and fails when one
stops working.

| Order | Source | API | Audio served | Transcode |
|-------|--------|-----|--------------|-----------|
| 1 | Audius | documented, public | MP3 320 kbps | none |
| 2 | SoundCloud | undocumented | HLS: MPEG or AAC in fMP4 | only for the AAC variants |
| 3 | JioSaavn | undocumented | progressive AAC in MP4 | always |

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

**Audius is reached through a discovery node** rather than a fixed host; the registry at
`api.audius.co` names the ones currently serving.

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

## Reference implementation

<https://github.com/Shabinder/SpotiFlyer> solved the same problem and its providers are worth reading
for the API shapes — the SoundCloud client-id scrape and the JioSaavn endpoint both came from there.
Two of its paths are dead and should not be copied: `yt1s.com`, which it used for direct MP3, now
answers 404, and YouTube's player endpoint rejects the mobile-client spoofs it relies on. SpotiFlyer
shells out to ffmpeg for its conversion, which is not an option here — this application launches no
subprocesses and the image carries a JRE and nothing else.
