# Media Sorter (SORTINGMEDIA)

A fast, low-memory media triage app for Android (**minSdk 21 / targetSdk 33**, Java, no
Kotlin) built for quickly tagging, filtering, renaming and organizing large local
photo & video folders.

## Core workflows

- **Swipe / D-Pad triage** — fully configurable multi-step gestures (apply tag,
  next/prev, skip, flag, done, filter-cycle). Re-applying a gesture tag undoes
  just that tag (per-tag undo stack).
- **Windowed browsing** — a memory-bounded sliding window over the index,
  LRU thumbnail cache (byte budget + count limit + disk cache), predictive
  precaching of adjacent files.
- **Tags everywhere** — tag lists (multiple named lists per workflow),
  per-file editing, batch multi-select tagging, quick-tag popup, auto-import
  of tags already written in files. Tags persist to standard XMP metadata
  (JPEG APP1, PNG iTXt, MP4 uuid) — **streamed writes, no full-file memory
  loads and no in-place truncation**.
- **Search** — boolean-ish terms: text, `-exclusion`, `type:`, `ext:`,
  `size:>10mb`, `width:`/`height:` with `<`/`>`, `tagged`/`untagged`;
  recent + saved search history.
- **Organizer rules** — conditions (tag/name/type/size/date/status/folder) and
  actions (move, copy, trash/delete, tag, status, rename, set date, change
  extension, prefix/suffix, strip metadata) with dry-run preview and undo.
- **Color analysis** — dominant-color extraction in CIE Lab space, tag/rename/
  group by color, harmony/temperature/grayscale/vibrance detection.
- **Duplicates** — size + partial-hash (first 64 KB MD5) duplicate grouping.
- **Batch tools** — selection-order-aware batch rename (incl. undo), batch tag,
  color analysis, move-to-trash or permanent delete.

## Stability (recent renovation)

- XMP writer fully **streamed + crash-safe** (temp file + atomic swap).
- Preview decode race fixed (stale bitmaps are recycled, never displayed);
  OOM-guarded double-sampled decodes; video thumbs scaled instead of
  full-resolution frame grabs.
- Dashboard no longer ships the whole index through Binder
  (no TransactionTooLargeException on big libraries).
- Settings → **Main Window**: D-Pad visibility toggle, tag menus toggle.
- Sort menu gained **Shuffle** (Fisher–Yates via `features.RandomGenerator`).

## Build

Gradle 7.5, AGP 7.4.2, JDK 17:

```bash
gradle :app:assembleRelease
```

CI: GitHub Actions builds the release APK on pushes to `main`
(`.github/workflows/build.yml`) and uploads it as an artifact.
