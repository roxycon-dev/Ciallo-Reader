# Keiyoushi protocol reference

The independently implemented public MCCMS adapter at `app/src/main/assets/js_extra/aimanhua.js` references the public site protocol used by [MCCMSWeb.kt](https://github.com/keiyoushi/extensions-source/blob/main/lib-multisrc/mccms/src/eu/kanade/tachiyomi/multisrc/mccms/MCCMSWeb.kt). No Kotlin source was copied into the app. The upstream project uses Apache License 2.0; its license is retained here for attribution.

Site-specific search selectors, the complete chapter API and image rendition behavior were verified against the current public website on 2026-10-13. This adapter is bundled locally and is independent of the Venera source repository.

The Xipi adapter at `app/src/main/assets/js_extra/xipi.js` ports the public API,
image-list decoding and decoy removal protocol from `XipiManhua.kt` and `Dto.kt`
at commit `cfc019928453a4650669d0514723360629c4af56`. Changes include JavaScript host
integration, bounded catalogue paging, and input/response validation. Attribution
and the full Apache 2.0 license are also packaged under `assets/licenses/`.
