# Ciallo Reader promo v2b

**Internal review draft.** This 70-second, 16:9 cut uses the supplied real phone recordings, the safe onboarding capture, and the app's own Roxy artwork. It has no narration, privacy blur, mosaic, or review watermark. Manga rights were not independently verified; confirm publication rights before public release.

## Deliverables

- `../output/CialloReader-v2b-1080p.mp4` — 70.06s, 1920×1080 at 30fps, H.264 + AAC-LC stereo 48kHz, 31,712,852 bytes.
- `../output/CialloReader-v2b-540p.mp4` — 70.06s, 960×540 at 30fps, H.264 + AAC-LC stereo 48kHz, 6,304,191 bytes. Low-resolution review render.
- `remotion/src/video.tsx` — editable Remotion composition and timeline.
- `prepare_v2_media.py` — source crops and full-width CFR proxies for the 1080p render.
- `contact-sheets/v2b-full-540p-contact-2s.jpg` — whole-cut contact sheet sampled every two seconds.
- `contact-sheets/final-v2b-keyframes-contact.jpg` — 1080p frames from the opening, manga, climax, Roxy, and CTA.
- `v1-backup/` — saved v1 source and README.

## Timeline and source windows

| Output | Genuine source | Edit |
|---|---|---|
| 00–06s | `04_shelf.mp4` 00–06s | Moving bookshelf hero with one quiet title. |
| 06–08.8s | `02_search.mp4` 01.0–03.8s | Full real search row, including the changing query. Cropped to the original row; no search-source result row is included. |
| 08.8–12s | `03_detail_download.mp4` 18.0–21.2s | Genuine download drawer crop. |
| 12–14s | `04_shelf.mp4` 00–02s | Brief real shelf return. |
| 14–19s | `05_comic_open.mp4` from 02s | Real comic opening screen in a slight camera pull. |
| 19–23s | `06_comic_read.mp4` 01.2–03.0s | Real manga crop in a phone frame; the frame expands while the surrounding environment changes from dark to paper white. |
| 23–24.8s | `06_comic_read.mp4` 01.2–03.0s | Bright full-canvas crop of the first clean page. |
| 24.8–27.6s | `06_comic_read.mp4` 08.9–11.7s | A later, different real page, cut in on the reading progression. The broken 04–08s loading/page transition is omitted. |
| 27.6–34.2s | `07_comic_glass.mp4` 04.0–10.6s | Real glass reading controls appear as the cut returns from paper to dark glass. |
| 34.2–42s | `07_comic_glass.mp4` 10.6–18.4s | Continuation of the genuine settings interaction. |
| 42–47s | `06_comic_read.mp4` still at 02.2s | The only panel-split climax, using a genuine high-resolution frame. |
| 47–53s | `09_novel_settings.mp4` 08–14s | Natural crop of the native settings panel; adult prose outside the crop is excluded. |
| 53–59s | `11_glass_ui.mp4` 16–22s | Genuine glass/theme controls. |
| 59–64s | `roxy-onboarding.png` + `mascot_reading.webp` | Safe real onboarding screen behind the app-owned Roxy mascot, which moves forward out of its frame. `10_roxy.mp4` is omitted. |
| 64–70s | Remotion typography | Brand, large Chinese download/project labels, and the confirmed GitHub URLs. |

The novel page-turn recording `08_novel_turn.mp4` and Roxy trigger recording `10_roxy.mp4` are omitted because their surrounding novel text is unsuitable to show. No artificial cover, blur, or redaction is used. The recorded MangaDex search suggestion is excluded by the search-row crop. The #07 settings panel's natural dimming is part of the original app capture.

## Motion and sound

The manga sequence moves from a framed real page into a bright full-screen paper view, then cuts to a later source page before returning to the real glass controls. The 42–47s climax uses the `comic-panel-split` idea once. Roxy scales and travels forward from the genuine onboarding frame with restrained parallax.

The score is the existing procedurally generated piano cue. At the comic climax, a low `transition-soft` swell leads into a quiet `paper-page-turn` accent; one select click and a glass slide remain elsewhere. SFX are from the installed video-shotcraft skill's Mixkit SFX Free License collection; exact source URLs and attribution are in `C:\Users\GuanXingRen\.codex\skills\video-shotcraft\assets\audio\ATTRIBUTION.md`. No source-device audio is used.

## Build

From `promo/production/remotion/`:

```powershell
python ..\prepare_v2_media.py
npx remotion render src/index.ts CialloPromo ..\..\output\CialloReader-v2b-1080p.mp4 --scale=1 --crf=20 --codec=h264 --audio-codec=aac
```

The final-render proxies keep the source's original 1224px width at 30fps. Search/download/settings and projected page crops are also exported from the originals. The 540p review render was made once before the final high-resolution proxies were added.

## Review notes

- The user approved retaining the manga footage for this nonprofit open-source promotion. Commercial artwork and 《无职转生》 remain visible; rights were not independently checked.
- No private notification or adult novel prose appears in the selected windows. The search query remains readable as part of the real search interaction.
- No unit tests were run. Final visual QA was done from the 540p two-second contact sheet and the 1080p keyframe contact sheet.
