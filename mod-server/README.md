# 8CEE Banjo-Kazooie mod catalog

The Android app reads `index.json` from this directory.

Each entry in `mods` uses this shape:

```json
{
  "id": "example-mod",
  "name": "Example Mod",
  "author": "Author",
  "version": "1.0.0",
  "description": "Short description shown in the Android mod browser.",
  "type": "mod",
  "game_id": "bk",
  "min_app_version_code": 1,
  "homepage": "https://example.com/mod-page",
  "download_url": "https://example.com/example-mod.nrm",
  "sha256": "optional lowercase SHA-256 of the download"
}
```

Rules:

- `download_url` must use HTTPS.
- The downloaded file is passed to BanjoRecomp's existing mod installer, so normal NRM/RTZ/package validation still applies.
- Set `sha256` for published mods whenever possible.
- The catalog intentionally starts empty; publishing a catalog entry makes it visible in the app without rebuilding the APK.


Compatibility fields are optional. `game_id` defaults to `bk`, `type` defaults to `mod`, and `min_app_version_code` defaults to 0. Entries requiring a newer APK or another game remain visible but cannot be installed.
