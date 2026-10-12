# AVIP GPSCam (Android) — v2.0

Developed by AVIP Business Solutions LLP.

Take a photo or record a video and get a stamp burned into it: mini map with a pin, address, latitude/longitude and date/time. Everything is saved to **Pictures/GPSMapCamera** in your gallery (photos also get GPS in the EXIF).

- **HD / SD** chip (top-right): HD = full-size photos and 1080p video; SD = 1280px photos and 480p video for small files.
- **Zoom:** pinch on the preview, or tap the .5x / 1x / 2x / 5x buttons (works while recording too). Tap the preview to focus.
- **Grid & compass:** grid button (top-left) toggles rule-of-thirds lines; the live compass below it shows the direction the camera faces (true north). Photos get a north-arrow symbol (top-right) and a "Facing NE 45°" line in the stamp.
- **PHOTO / VIDEO** switch above the shutter. In video mode, tap the red button to start, tap again to stop; the stamp is then added to the video (takes a few seconds).
- **Bottom-left thumbnail** opens the app's gallery: tap photos/videos to select, then **Share** to send them all at once. Long-press an item to view it.
- **Share icon (right)** quickly shares the last photo or video.

## Automatic updates
Every push to `main` publishes a GitHub Release (`v2.0.<build>`). On launch the app checks the latest release; if it is newer it downloads and installs it from the splash screen, then shows "Updated to the latest version". The first time, Android asks once to allow the app to install updates; depending on the phone, each update may still need one tap on Android's "Update" prompt.

Latest APK: https://github.com/avipbizsolutions/GPSMapCamera/releases/latest

## 100% free – no API keys, no billing
- **Map thumbnail:** OpenStreetMap tiles (free, attribution shown on the map)
- **Address:** Android's built-in Geocoder (free)
- **Location:** Android's built-in GPS/network location (no Google Play Services needed)
- **Camera:** AndroidX CameraX (open source)

Works offline too: you still get coordinates and time; the map and address appear when there's internet.

## Get the APK – pick one (both free)

### Option A: GitHub (no software to install, works from a phone)
1. Create a free GitHub account and a new repository.
2. Upload all files from this folder (keep the folder structure, including `.github/`).
3. Open the **Actions** tab → **Build APK** → wait ~5 minutes.
4. Open the finished run → download **GPSMapCamera-apk** → unzip → copy `app-debug.apk` to your phone.
5. On the phone, tap the APK and allow "Install unknown apps" when asked.

### Option B: Android Studio (free, on a computer)
1. Install Android Studio → **Open** this folder → let it sync.
2. Plug in your phone (USB debugging on) and press ▶ Run,
   or use **Build → Build APK(s)**.

## Using it
Allow Camera and Location on first launch. Wait until the coordinates show at the bottom (step outside for a quick GPS fix), then tap the white shutter. Tap the thumbnail or share icon to send the last photo.

## Code map
| File | What it does |
|---|---|
| `MainActivity.kt` | Camera preview, permissions, live GPS, capture |
| `MapTiles.kt` | Downloads OSM tiles and draws the map + pin |
| `Stamp.kt` | Draws the info panel onto the photo |
| `Geocoding.kt` | Turns coordinates into an address |
| `PhotoSaver.kt` | Rotates, saves photos/videos to gallery, writes EXIF GPS |
| `VideoStamper.kt` | Burns the stamp into videos (AndroidX Media3, free) |
| `GalleryActivity.kt` | In-app gallery with multi-select sharing |
| `MediaRepository.kt` | Finds the app's saved photos and videos |

Note: OpenStreetMap's free tiles are meant for light use; fine for personal use. If you ever publish this widely, switch to a tile provider with a free tier and your own key.
