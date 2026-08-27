# Project status

## Implemented in v0.1 source
- Android MediaProjection foreground service with explicit system consent.
- User-controlled SYSTEM_ALERT_WINDOW overlay.
- Bundled ML Kit Latin OCR processed on-device.
- Raw screen frames are processed in memory only and are not saved or transmitted.
- Two-frame collector-number stability gate.
- Pokémon TCG API lookup with TCGplayer market fields.
- Conservative price-range handling and 70% / 80% buy thresholds.
- Session LRU lookup cache.
- Draggable live overlay.
- Stop action in persistent foreground notification.
- Unit tests for collector-number parsing.
- GitHub Actions debug APK build.

## Current validation stage
The project is now in GitHub and the first CI build is the next validation checkpoint. After CI passes, the APK needs a physical-device Whatnot screen-share test.
