# CardLens

Personal Android app for comping raw singles at the counter. Snap a photo of a Pokémon, One Piece or Yu-Gi-Oh card and get its TCGplayer market price. Then pick the condition and add it to a running lot with a buy offer.

The old Whatnot screen-overlay version is saved on the `cardlens-whatnot-backup` branch.

## How it works
1. **Scan card** opens the camera (or **From photos** picks an existing picture).
2. Claude reads the card from the photo: name, set, set code, collector number, finish. This uses your Anthropic API key, entered in Settings.
3. The app looks that up in TCGplayer's catalog and daily market prices (via tcgcsv.com, a free daily mirror of TCGplayer data).
4. Tap the right printing (Normal / Holofoil / Reverse Holofoil / 1st Edition…) to add it at the selected condition.
5. The top card shows the lot's market total and your offer at your buy %. The ±5 buttons adjust it while you negotiate.

If the match is wrong, use **Fix and search again** to correct the set or number. Searching again is free and doesn't rescan.

Condition multipliers: NM 100%, LP 85%, MP 70%, HP 50%, DMG 30% of market. To change them, edit `Condition` in `AppViewModel.kt`.

## Install
Every push to `main` builds a signed APK and publishes it under **Releases** as `CardLens-N.apk`. Download the newest one on your phone and install it. New builds install over old ones and keep your settings and lot.

## Costs
- Card ID: a fraction of a cent per scan on the Fast model, more on Accurate. Billed to your Anthropic API account (console.anthropic.com), separate from a Claude subscription.
- Prices: free. They update once a day, so they can be up to ~24h old.

## Limits
- TCGplayer market price only. Cards with no recent sales show the lowest listing instead.
- Japanese Pokémon is supported. Japanese One Piece and Yu-Gi-Oh OCG are not.
- Needs internet.
