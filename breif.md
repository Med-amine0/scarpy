# Vaults — App Brief

## What the App Is

Vaults is a private, PIN-locked Android media gallery app. It lets the user organize images, GIFs, and videos into named galleries with infinite nesting. It supports three distinct content source types, a swipe card mode, adjustable grid columns, lazy or eager loading, and edit/reorder mode. The UI is dark with pink and purple accents and a customizable SVG pattern background.

---

## Architecture Overview

**Language:** Kotlin
**Min SDK:** 26 (Android 8)
**Build system:** Gradle 8.3 with GitHub Actions CI producing a debug APK as a downloadable artifact on every push to main.

**Pattern:** MVVM — single shared `GalleryViewModel` backed by Room DB, observed via LiveData and StateFlow from all Activities.

**Key libraries:**
- Room — local SQLite database
- OkHttp — HTTP scraping (no Retrofit, intentionally lightweight)
- Glide — image loading and thumbnail rendering
- ExoPlayer (Media3) — video playback
- CardStackView (yuyakaido) — Tinder-style swipe cards
- PhotoView (chrisbanes) — pinch-zoom for images in fullscreen
- Material Components — dialogs, sliders, switches, FAB

---

## Database Schema

Two Room entities:

**Gallery** — represents a gallery or folder
- `id` (PK, autoincrement)
- `parentId` (nullable Long — null means root level, non-null means nested inside another gallery)
- `name` (String)
- `type` (enum: NORMAL, PORNHUB, REDGIF, FOLDER)
- `loadMode` (enum: LAZY, ALL)
- `viewMode` (enum: GRID, SWIPE)
- `columnCount` (Int, default 2)
- `sortOrder` (Int — for manual drag reorder)

**GalleryItem** — represents one piece of content inside a gallery
- `id` (PK, autoincrement)
- `galleryId` (FK to Gallery)
- `value` (String — URL for NORMAL, bare ID for PORNHUB and REDGIF)
- `sortOrder` (Int)

The nesting is self-referential via `parentId`. There is no depth limit — a FOLDER type gallery can contain any mix of child galleries of any type.

---

## Gallery Types

### NORMAL
Stores full image or video URLs directly. On display, the stored URL is used as-is. Works for any direct-link image (jpg, png, gif, webm, mp4).

### PORNHUB
Stores only the numeric GIF ID (e.g. `42528191`), not the CDN URL. This is because PornHub CDN URLs are signed with a 4-hour expiry window (`validfrom`/`validto` Unix timestamps + HMAC hash). Storing the raw CDN URL would break within hours.

At display time, `PHScraper` fetches `https://www.pornhub.com/embedgif/{id}`, parses the HTML response with regex to extract `fileWebm`, `fileMp4`, and `fileGif` JS variables, and returns the best available URL (webm preferred over mp4 over gif). This gives a fresh signed URL every time. The embed page itself does not expire.

### REDGIF
Stores the RedGif clip ID (e.g. `idstring`). At display time, `RedGifScraper` tries the RedGifs v2 API (`api.redgifs.com/v2/gifs/{id}`) to get a direct HD/SD mp4 URL. If that fails, it falls back to the iframe embed URL (`redgifs.com/ifr/{id}`). The embed URL is always available and shown via a WebView in the player. Direct URLs are shown via ExoPlayer.

---

## Scrapers

Located in `scraper/Scrapers.kt`.

**HttpClient** — shared OkHttp singleton with 15s timeouts and a mobile User-Agent header.

**PHScraper.getFreshUrl(gifId)** — suspending function, runs on IO dispatcher. Fetches the embed page, uses Regex to extract the three file URL variables from the inline JS block. Returns a `PHResult` data class or null on failure.

**RedGifScraper.getDirectUrl(gifId)** — suspending function. Cleans the input ID (strips full URLs down to bare ID), hits the v2 API, parses JSON with Regex for `hd`/`sd`/`gif` keys, returns a `RedGifResult` with both directUrl and embedUrl fields.

**InputParser.parse(input)** — handles the bulk input format. Splits on commas and newlines, strips surrounding quotes and whitespace, deduplicates. Accepts `"id1","id2"` or `id1\nid2` or mixed formats transparently.

---

## ViewModel

`GalleryViewModel` is an `AndroidViewModel` shared across all activities. It holds:

- `rootGalleries` — Flow-backed LiveData of root-level galleries
- `resolvedItems` — MutableStateFlow of `ResolvedItem` list for the currently open gallery
- `currentGallery` — LiveData of the currently open Gallery entity
- `editMode` — LiveData Boolean toggle

`ResolvedItem` is a wrapper around `GalleryItem` that adds runtime state: `resolvedUrl`, `embedUrl`, `isLoading`, `error`. These fields are mutated in place and the list is re-emitted to trigger UI updates.

`resolveItem()` is a suspending function that takes a GalleryType and a ResolvedItem, performs the appropriate scrape or direct URL assignment, and calls `notifyItemChanged()` which finds the item by ID in the current list, replaces it, and re-emits the StateFlow.

`loadGallery()` fetches all items from the DB for a gallery, initialises them as unresolved ResolvedItems, emits the list, then either resolves all immediately (LoadMode.ALL) or leaves them for lazy on-demand resolution (LoadMode.LAZY).

---

## Activities

### PinActivity
Entry point. Reads saved PIN from SharedPreferences. If no PIN exists, it's setup mode — saves the entered 4-digit PIN. If PIN exists, validates input. Wrong PIN triggers a shake animation on the dots row. Correct PIN or first-time setup launches MainActivity. No biometrics — just 4-digit numeric PIN.

### MainActivity
Root screen. Shows all root-level galleries in a 2-column grid. Has a FAB that opens a type-picker dialog then a name dialog to create a new gallery. Toolbar has Edit button (toggles edit mode on adapter) and Settings button. In edit mode, gallery cards show Rename/Delete controls and drag-to-reorder is enabled via ItemTouchHelper. Loads background pattern from SharedPreferences via Glide on onCreate and onResume.

### GalleryActivity
Opened for any gallery by passing `gallery_id` as an Intent extra. Fetches the gallery via `vm.getGalleryById()` on a coroutine at startup.

If the gallery type is FOLDER: shows `subGalleryRecycler` with child galleries, supports nested creation, rename, delete, drag reorder.

If any media type: shows either `mediaRecycler` (grid) or `cardStackView` (swipe) depending on the gallery's `viewMode` setting. Collects `resolvedItems` StateFlow and pushes updates to the adapter. Settings button opens `dialog_gallery_settings` with a column slider (1–6), lazy/all toggle, and swipe mode toggle. Applying settings calls `vm.updateGallerySettings()` then `recreate()` to rebuild the view.

Add button opens a text input dialog. For FOLDER type it opens a sub-gallery creation flow. For media types it takes raw input, calls `vm.addItems()` which runs InputParser then inserts non-duplicate items to DB.

### PlayerActivity
Fullscreen viewer. Receives `url`, `is_embed` (Boolean), and `type` via Intent extras. Three display modes:
- **Video** (mp4/webm URL) → ExoPlayer with loop and autoplay
- **Image** (other URL) → PhotoView for pinch-zoom
- **Embed** (is_embed=true) → WebView loading a minimal HTML page wrapping the iframe with JS enabled and `mediaPlaybackRequiresUserGesture = false`

Close button and system UI hidden for full immersion.

### SettingsActivity
Two sections: background pattern (text input accepting any URL or SVG data URI, with reset-to-default button) and PIN change. Background value stored in SharedPreferences under key `background_url`. Default pattern is the pink diagonal SVG data URI defined as a companion object constant.

---

## Adapters

All in `adapters/Adapters.kt`.

**MediaGridAdapter** — ListAdapter for ResolvedItems. On bind, if the item has no resolved URL and isn't loading, calls `onNeedResolve` callback which triggers lazy resolution from the Activity. Shows loading spinner, error placeholder, play icon overlay, or delete overlay in edit mode. Uses Glide for image loading.

**GalleryListAdapter** — ListAdapter for Gallery entities. Shows type emoji, name, edit controls. Supports `onItemMove()` for drag reorder. Has `setTouchHelper()` to receive the ItemTouchHelper reference.

**SwipeCardAdapter** — Standard RecyclerView adapter (not ListAdapter) for CardStackView. Binds ResolvedItems to swipe cards with thumbnail, progress, play icon.

---

## Layouts

- `activity_pin.xml` — centered vertical layout, app name, subtitle, dot row, 3×4 GridLayout numpad
- `activity_main.xml` — CoordinatorLayout, background ImageView, toolbar LinearLayout, RecyclerView, FAB
- `activity_gallery.xml` — same structure, has both mediaRecycler and subGalleryRecycler and cardStackView, visibility toggled in code
- `activity_player.xml` — FrameLayout with PlayerView, PhotoView, WebView all stacked, visibility toggled in code
- `activity_settings.xml` — scrollable LinearLayout with two sections
- `item_media.xml` — FrameLayout with fixed 160dp ImageView, overlaid progress/play/delete
- `item_gallery.xml` — card with pink/purple gradient left border accent, name, type label, edit controls
- `item_swipe_card.xml` — full-bleed image with bottom gradient scrim
- `dialog_gallery_settings.xml` — inline view for the settings dialog, column slider + two switches

---

## Known Limitations for the Next Agent

1. **gradle-wrapper.jar is missing from the repo.** GitHub Actions bootstraps it automatically on first run via the distribution URL in `gradle-wrapper.properties`. If building locally, run `gradle wrapper` once to generate it.

2. **No mipmap/launcher icons.** The app will build but use a default icon. Add `ic_launcher` and `ic_launcher_round` PNG sets to `res/mipmap-*` folders.

3. **PH scraper is fragile.** It uses Regex against PornHub's embed page HTML. If PH changes their embed JS variable names the scraper silently returns null. The regex targets `fileWebm\s*=\s*'([^']+)'` — update this if it breaks.

4. **RedGif API may require auth headers.** The v2 API currently works without a token but RedGifs has been tightening access. If direct URLs stop working the fallback iframe path will still work.

5. **CORS proxy not needed** — all scraping happens natively in OkHttp on the device, not in a browser context, so CORS is irrelevant.

6. **No pagination on DB queries.** If a gallery has thousands of items the initial `getItemsOnce()` will load them all into memory. Add Paging 3 if large galleries become a concern.

7. **`recreate()` on settings apply** is a blunt instrument. It works but causes a flash. A cleaner approach would be to update the LayoutManager and adapter in place without recreating the Activity.

8. **No export/backup.** Gallery structure and IDs live only in the local Room DB. Consider adding JSON export/import in a future iteration.