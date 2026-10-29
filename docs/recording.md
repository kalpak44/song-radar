# Recording

How `StreamRecordingClient` turns a listener's HLS stream into the WAV the detector fingerprints.
Two stages: the segments are fetched by this process, and ffmpeg decodes them.

## ffmpeg decodes, because the in-process decoder lied

Recordings used to be decoded here, by jcodec wrapping jaad. It produced captures that were exactly
the right length, parsed correctly, and had **holes of digital silence punched through them at
random**.

The decoder does not fail on a frame it cannot handle. It returns a correctly sized buffer of zeros
and throws nothing, so there is no error to catch, no short file to notice, and no format check that
can see it. Captures reached the detector with pieces missing and were logged as successes.

Measured on one bitstream decoded two ways — jaad against ffmpeg, frame for frame:

| source | frames blanked by jaad |
|--------|------------------------|
| 440 Hz tone, 96 kbps | 127 / 131 (96.9%) |
| music, 96 kbps 44.1 kHz | 1096 / 2585 (42%) |
| music, 128 kbps 48 kHz | 103 / 2814 (3.7%) |
| music, 160 kbps | 203 / 2585 (7.9%) |
| music, 256 kbps | 63 / 2585 (2.4%) |
| music, 320 kbps | 11 / 2585 (0.4%) |

The rate rises as the bitrate falls, and radio HLS lives at the bad end of that table. That is why
it looked random: it tracks what the encoder did in that frame, nothing on this side. Blanking hits
ordinary long-window stereo frames, so there is no narrow feature to special-case around.

**Nothing in this repository contributed to it**, which took some proving before the decoder could
be blamed:

- The MPEG-TS demuxing here was **byte-identical** to ffmpeg's. Five segments demuxed to 327,078
  bytes of ADTS; `ffmpeg -c:a copy -f adts` produced the same 327,078 bytes, and `cmp` agreed.
- The ADTS frame walk was exact — every byte of the stream inside a frame, nothing skipped between
  frames, nothing trailing.
- jcodec stripped exactly 7 header bytes from every frame.
- jaad run **standalone** — its own jar, its own `ADTSDemultiplexer`, nothing of this project's on
  the classpath — blanked the identical frames.

0.8.8 is the last release of that fork, so there was no version to upgrade to.

**`decodesEveryFrameRatherThanBlankingTheOnesItCannotHandle` is the regression test.** It records
`src/test/resources/listener/tone-96k.ts` through the whole capture path and asserts not one frame
comes back silent. Under the old decoder that fixture was 96.9% silence.

jaad is still a dependency: `Mp3Encoding` uses it to transcode the AAC a music source serves, and
that path has the same defect at the rates in the table above. It is one reason the sources needing
no decode are ranked first — see docs/music-sources.md.

## The segments are fetched here, not by ffmpeg

ffmpeg could take the stream URL directly and the whole fetch loop could go. It does not, because
the URL belongs to a listener and ffmpeg speaks a great many protocols. What this process enforces
before anything is written would be lost: HTTP or HTTPS only, at the playlist and at every segment
and every redirect; encrypted playlists refused; a cap on playlist size; a bound on how deep
variants may nest. ffmpeg is handed a local file this process wrote.

**ffmpeg is required.** The image already carries it on `PATH` for yt-dlp (see `pom.xml`), so no
configuration is normally needed; `SONGRADAR_RECORDING_FFMPEG` points at it otherwise. If it cannot
be run the recording fails and says so — which is the point. The decoder it replaced never failed.

## The length of a recording comes from the file

`-t` caps to decode at the requested duration, but a stream that ends early gives less, so the
duration reported is read back out of the WAV's own `fmt ` and `data` chunks rather than assumed.
The chunks are walked; ffmpeg is free to write its own chunks ahead of the audio, and a fixed
offset into the header breaks on one that does.

Anything under 250 ms is treated as a failed capture rather than a recording.
