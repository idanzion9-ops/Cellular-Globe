# Cellular Globe

An offline Android app for looking up mobile operator spectrum: which bands each operator
in each country holds, the exact MHz blocks, the absolute channel numbers (ARFCN, UARFCN,
EARFCN, NR-ARFCN), and — the point of the whole thing — whether each block is actually on
air or just held.

Ships with 63 countries, 207 operators and roughly 1,800 spectrum blocks as a starting dataset. Everything is
editable in the app, and new countries can be researched from the web.

## Getting the APK

You do not build this on your phone. Every push to `main` triggers a GitHub Actions run
that builds the APK on GitHub's servers.

1. Open the **Actions** tab of this repository.
2. Click the most recent **Build APK** run.
3. Download the **cellular-globe-apk** artifact at the bottom of the page.
4. Unzip it and install `cellular-globe.apk` on the phone. Android will ask you to allow
   installs from that source the first time.

Every push to `main` also refreshes a rolling release called **Latest build**, so the
Releases page always carries a permanent link to the newest APK. Running the workflow by
hand from the Actions tab produces only the 90-day artifact, not a release.

For a permanent, versioned release, push a tag:

```
git tag v1.0.0
git push origin v1.0.0
```

## What is inside

```
app/src/main/assets/index.html    the whole application — one self-contained page
                                  (also the live-update source)
app/src/main/java/.../MainActivity.java   WebView host, file picker, export bridge
.github/workflows/build-apk.yml   the cloud build
```

The page is served to the WebView over `https://appassets.androidplatform.net` rather than
`file://`. That matters for two reasons: browser storage persists reliably against a stable
origin, and the page is allowed to reach `api.anthropic.com` for the country lookup.

## Live updates

The page is not locked inside the APK. On first run the shell copies it into internal
storage and serves it from there; on every launch it fetches

```
https://raw.githubusercontent.com/idanzion9-ops/Cellular-Globe/main/app/src/main/assets/index.html
```

compares the SHA-256 against the cached copy, and if they differ writes the new one and
offers a **Reload now** button. **Check for updates** in the toolbar forces the same check.

So editing `index.html` on GitHub updates the running app — no reinstall, no new APK. Both
copies are served from the same `https://appassets.androidplatform.net` origin, so your
saved data survives the swap. GitHub's raw CDN caches for a few minutes, so an update can
take up to about five minutes to appear.

A rebuilt APK is still needed for anything outside the page: the Java shell, the icon,
permissions or the manifest.

## Your data

Edits live in the app's own storage and survive updates but not an uninstall. Use
**Export JSON** now and then — inside the app that opens Android's share sheet, so the file
can go to Drive, email or anywhere else. **Import JSON** takes it back.

## The country lookup

**Edit mode → Auto-fill from the web** asks Claude to research a country's regulator
records and auction results and return operators and spectrum in this app's schema. Results
are shown for review before anything is written, and the importer corrects common mistakes
(uplink figures given instead of downlink, blocks outside band edges, unknown bands).

In the app this needs your own Anthropic API key from
[console.anthropic.com](https://console.anthropic.com), entered once in the lookup dialog
and stored on the device only. Without a key, **Copy the prompt instead** gives you the same
research prompt to paste into any Claude chat, and you paste the JSON back.

## About the data

The bundled dataset is a curated starting point, not an authoritative register. Band-level
assignments are sound; exact block edges for many operators are approximations, and the
Israeli 1800 and 2100 MHz splits in particular are inferred. Each country links out to
spectrummonitoring.com and spectrum-tracker.com for cross-checking. Correct it as you go —
that is what the editor is for.

## Building locally

Requires JDK 17 and the Android SDK (compileSdk 34).

```
gradle assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.
