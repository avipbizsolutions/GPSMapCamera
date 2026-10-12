# GPS Map Camera (Android)

Take a photo and get a stamp burned into it: mini map with a pin, address, latitude/longitude and date/time. Photos are saved to **Pictures/GPSMapCamera** in your gallery (with GPS in the EXIF too) and can be shared from the app.

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
| `PhotoSaver.kt` | Rotates, saves to gallery, writes EXIF GPS |

Note: OpenStreetMap's free tiles are meant for light use; fine for personal use. If you ever publish this widely, switch to a tile provider with a free tier and your own key.
