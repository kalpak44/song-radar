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
read as text and parsed directly. Its media URL is DES-encrypted with a key the site itself ships.

**Audius is reached through a discovery node** rather than a fixed host; the registry at
`api.audius.co` names the ones currently serving.

## Known defect: the AAC transcode is not faithful

Measured against CoreAudio decoding the same bytes, the AAC path produces audio with silent gaps
punched through it and a missing high band. It is not an encoding fault — dumping the decoder's PCM
straight to WAV shows the same gaps before the MP3 encoder sees it.

What has been ruled out: sample byte order (the decoder emits big-endian unless told otherwise, and
that is now set explicitly), the `trun` `data_offset` (now honoured), truncated or failed frames
(every frame decodes and yields a full 1024 samples), and the sample rate.

What has not been ruled out: SBR. The 96 kbps SoundCloud variant is HE-AAC, and a decoder that
ignores its spectral band replication produces exactly this — quiet, dull audio. The transcodings are
therefore ordered so that anything needing no transcode is preferred, then AAC-LC, then HE-AAC last.

## Reference implementation

<https://github.com/Shabinder/SpotiFlyer> solved the same problem and its providers are worth reading
for the API shapes — the SoundCloud client-id scrape and the JioSaavn DES key both came from there.
Two of its paths are dead and should not be copied: `yt1s.com`, which it used for direct MP3, now
answers 404, and YouTube's player endpoint rejects the mobile-client spoofs it relies on. SpotiFlyer
shells out to ffmpeg for its conversion, which is not an option here — this application launches no
subprocesses and the image carries a JRE and nothing else.
