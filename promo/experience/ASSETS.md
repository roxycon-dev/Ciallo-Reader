# Web experience assets

## Current edition (v5, 2026-09-27)

The current film uses only authentic device frames and natural crops for product UI. No mocked search, download, translation, or reading record data is shown. DOM text, shapes, particles, and camera motion outside the images are promotional packaging. Additional frame provenance is in `native-manifest.json` and `kinetic-manifest.json`.

The v3 HTML simulation has been archived and is not part of this edition.

Additional original recording frames: `comic-first.webp` — clip 06 at 2.9 s, crop (0,460,1224,2250); `comic-native.webp` — clip 06 at 11.3 s, complete frame; `download-native.webp` — clip 03 at 19.8 s, complete frame. The two selected comic pages are excerpts, not consecutive source pages. All transformations are presentation motion; no claim is made about native app frame rate. Full records are in `asset-manifest.json`.

The older asset workflow notes below describe extraction of the stills only.

All application and manga imagery comes from the user-supplied phone recordings. Files are sharp static frames or natural crops; no blur, pixel masking, frame interpolation, AI manga, or reconstructed app UI is used.

The experience animates these stills in the browser. It deliberately does not replay the recordings: the source includes uneven gestures, loading frames, broken page turns around 4–8 seconds in clip 06, and hold frames. These motions are not represented as smooth native app behavior.

| File | Source | Time | Crop (left, top, right, bottom) |
|---|---|---|---|
| shelf.webp | 04_shelf.mp4 | 1.6 | None |
| glass.webp | 11_glass_ui.mp4 | 18.0 | None |
| reader.webp | 07_comic_glass.mp4 | 4.0 | None |
| comic-page.webp | 06_comic_read.mp4 | 10.0 | (0, 460, 1224, 2250) |
| comic-detail-a.webp | 06_comic_read.mp4 | 10.0 | (700, 1560, 1210, 2230) |
| comic-detail-b.webp | 06_comic_read.mp4 | 2.2 | (780, 1410, 1150, 2215) |
| comic-detail-c.webp | 06_comic_read.mp4 | 10.0 | (80, 610, 840, 1110) |
| novel-controls.webp | 09_novel_settings.mp4 | 9.4 | (0, 1740, 1224, 2660) |
| search.webp | 02_search.mp4 | 3.2 | (0, 230, 1224, 445) |
| download.webp | 03_detail_download.mp4 | 19.8 | (0, 2020, 1224, 2700) |
| roxy.webp | app/src/main/res/drawable-nodpi/mascot_reading.webp | — | — |
| roxy-hello.webp | app/src/main/res/drawable-nodpi/mascot_celebrate.webp | — | — |

Fonts: bundled Noto Serif SC and Noto Sans SC from the app.
Music: existing original procedural piano cue from promo/production/prepare_audio.py, off by default. V5 adds seven restrained transition accents using three existing Mixkit assets; source attribution is preserved in AUDIO-ATTRIBUTION.md.
Roxy artwork: existing application artwork. Manga and covers: user-selected third-party material; this workflow does not confer additional rights.
No phone was connected, modified, or recorded for this web revision.
