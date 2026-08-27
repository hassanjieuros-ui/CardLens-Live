# CardLens Live — Android MVP

CardLens Live is a native Android proof-of-concept for recognizing trading cards while you watch a live Whatnot auction and showing market information in a draggable overlay.

## v0.1 features

- User-approved Android `MediaProjection` screen sharing.
- Bundled Google ML Kit OCR processed **on-device**.
- Raw screen frames stay in memory only; CardLens does not save or transmit screenshots.
- Requires the same collector number (for example `148/142`) on two consecutive OCR passes before doing a lookup.
- Pokémon TCG API v2 matching.
- TCGplayer market-price data when available.
- Draggable floating overlay with card name, set, market value/range, and conservative 80% / 70% buy levels.
- Session caching to reduce repeat API requests.
- Ambiguous collector-number matches are rejected rather than assigned a guessed price.

## Privacy model

Screen sharing only starts after the Android system permission screen is approved by the user. Frames are processed locally in memory by ML Kit and immediately discarded. Raw screenshots are not uploaded. Only the parsed trading-card lookup is sent to the Pokémon card API.

## Current limitations

1. Pokémon only in v0.1.
2. Collector-number OCR is the primary identifier; card-art matching is planned next.
3. Finish/parallel can be ambiguous. When multiple TCGplayer finishes exist, CardLens shows a range and uses the lower market value for the 80% / 70% thresholds.
4. PSA/BGS/CGC graded comps are not included yet.
5. Whatnot or Android may restrict capture in some contexts, so physical-device testing is required.
6. The unauthenticated Pokémon TCG API has a lower request limit. An API key can be supplied without committing it to the repo.

## Build requirements

- Android 16 / API 36 SDK
- Android Gradle Plugin 8.13.2
- Gradle 8.13
- JDK 17+

### Optional Pokémon TCG API key

Add the key to your user Gradle properties or pass it as a Gradle property:

```properties
POKEMON_TCG_API_KEY=your_key_here
```

## First-run flow

1. Install and open CardLens Live.
2. Tap **Allow floating overlay** and enable “appear on top”.
3. Tap **Start Whatnot live scan**.
4. Approve Android's screen-sharing dialog. On supported Android versions, choose single-app sharing and select Whatnot.
5. Return to the auction.
6. When a card's collector number is clear for two OCR passes, CardLens looks it up and updates the floating overlay.
7. Drag the overlay out of the way as needed.

## GitHub APK build

`.github/workflows/build-android.yml` runs unit tests and builds a debug APK on pushes to `main`. The resulting `CardLensLive-debug-apk` artifact can be installed on an Android device for testing.

## Next targets

- Card-art/perceptual matching when the collector number is unreadable.
- One Piece support.
- Recent sold comps and graded-card providers.
- PSA 9 / PSA 10 / raw comparison.
- Configurable max-bid profiles.
- Scan history with timestamp and auction price.
- Confidence display and manual correction.
