# AVIP Tools – Google Play listing and submission guide

App ID on Google Play: **in.avipsolar.tools** (permanent – cannot be changed after the first upload)
Privacy policy URL: **https://avipbizsolutions.github.io/GPSMapCamera/privacy.html**
(AVIP Tools was previously called AVIP GPSCam. The link version keeps its old ID so existing users update automatically.)

## 1. Store listing (Grow → Store presence → Main store listing)

**App name** (max 30): `AVIP Tools: GPS Cam & Scanner`

**Short description** (max 80):
`GPS map camera, tilt meter, PDF scanner & serial number scanner. Free, no ads.`

**Full description:**

AVIP Tools puts the everyday field tools of a solar, electrical or construction team into one free app: a GPS map camera, a tilt meter, a multi-page document scanner and a barcode / serial number scanner.

**Why AVIP Tools**
• 100% free – no charges, ever
• No ads – nothing to skip or close
• Fast and reliable – opens quickly and finds your location while it loads
• Light on your phone – will not slow it down

**GPS Camera**
• Stamps photos and videos with a mini map, address, latitude/longitude, date and time
• North arrow and "Facing" direction on photos
• See the stamp live on the camera screen before you shoot
• Portrait or landscape, HD or SD, zoom, tap to focus, grid lines and level
• Built-in gallery: swipe through, select several, share or delete

**Tilt Meter**
• Lay the phone on a roof or panel to read its angle
• Bubble level, hold reading, set zero
• Shows which way the surface faces and the best fixed panel tilt for your location

**Document Scanner**
• Scan many pages into one PDF with automatic cropping and clean-up
• Saved in Documents/AVIP Tools – open, share or delete

**Serial Scanner**
• Make a list per site or customer and scan panel and inverter barcodes / QR codes – each new code is added automatically
• Read printed serial numbers with text recognition and tap to add
• Torch for dark inverter rooms, manual entry, copy all
• Share the list as an Excel (CSV) file or as a text message

Everything you capture stays on your phone and is shared only when you choose.

Made by AVIP Business Solutions LLP, Patna – one stop solution for all your solar requirements.
Contact: 9288076289 | 9939476289 | contact@avipsolar.in

**Graphics**
- App icon: `play-icon-512.png` (512 × 512)
- Feature graphic: `play-feature-graphic-1024x500.png` (1024 × 500)
- Phone screenshots (2–8, take on your phone): home screen; GPS camera with the live stamp; a saved photo with the stamp; Tilt Meter on a roof/panel; Document Scanner with saved PDFs; Serial Scanner with a list of scanned serials.

**Category:** Tools  **Tags:** Camera, Productivity, Tools
**Contact details:** email contact@avipsolar.in, phone +91 9288076289, website https://avipsolar.in

## 2. App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | https://avipbizsolutions.github.io/GPSMapCamera/privacy.html |
| App access | All functionality is available without special access (registration needs no login – anyone can fill the form) |
| Ads | No, the app does not contain ads |
| Content rating | Category: "All other app types" / Utility. Answer **No** to violence, sexual content, language, drugs, gambling, and "users can interact/exchange content"; **No** to sharing location with other users; no purchases. Expected rating: Everyone / 3+ |
| Target audience | **18 and over** only (keeps the app out of the Families programme) |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |
| Data safety | See section 3 |

## 3. Data safety form

- Does your app collect or share any of the required user data types? **Yes**
- Is all user data encrypted in transit? **Yes**
- Do you provide a way for users to request that their data be deleted? **Yes** – via the privacy policy (email contact@avipsolar.in or call 9288076289)

| Data type | Collected | Shared | Optional? | Purposes |
|---|---|---|---|---|
| Personal info → Name | Yes | No | Required | Developer communications, Marketing |
| Personal info → Phone number | Yes | No | Required | Developer communications, Marketing |
| Personal info → Other info (company name) | Yes | No | Required | Developer communications, Marketing |
| Device or other IDs (random install ID created by the app; ML Kit installation/device identifier) | Yes | No | Required | App functionality, Analytics |
| App info and performance → Diagnostics (ML Kit performance and error information, sent to Google) | Yes | No | Required | Analytics |
| Location → Approximate location (map tiles requested from OpenStreetMap) | Yes, processed ephemerally | Yes (OpenStreetMap, for the map image) | Required for the map stamp | App functionality |

Not collected (stay on the phone): precise location, photos, videos, scanned documents, scanned serial numbers, audio, files, contacts. Sharing a photo, PDF or serial list is started by the user and is not declared.
Google ML Kit's own disclosure: https://developers.google.com/ml-kit/android-data-disclosure

## 4. Release (Test and release)

1. **Play App Signing:** keep Google's default (Google manages the app signing key). Our file `avip-gpscam-upload.jks` is the **upload key** (the name is from before the rename; it is still the right key).
2. Personal developer account: create a **Closed testing** track first, add at least 12 testers, keep them opted in for 14 days, then apply for production. Organization account: go straight to **Production**.
3. Upload `AVIP-Tools-play-<version>.aab`. Release notes:
   `First release of AVIP Tools – GPS map camera, tilt meter, multi-page PDF document scanner and barcode / serial number scanner. Free, no ads.`
4. Countries: India (add others if wanted). Price: Free.
5. Send for review (usually a few days for a new app).

## 5. Future updates

Every change pushed to GitHub builds a new Play bundle with a higher version number automatically. It is published unsigned (`AVIP-Tools-play-unsigned.aab`) and signed with the upload key before uploading to Play. To have GitHub sign it automatically, add these repository secrets (GitHub → repo → Settings → Secrets and variables → Actions):
`UPLOAD_KEYSTORE_BASE64` (the .jks file, base64), `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS` = `upload`, `UPLOAD_KEY_PASSWORD`.

## 6. Keep safe

- `avip-gpscam-upload.jks` and its password: needed for every Play update. If lost, Google can reset the upload key (Play Console → Setup → App signing → Request upload key reset).
- Upload key certificate SHA-256: `DF:09:0D:EA:F9:4B:E3:49:DB:1E:67:CA:B3:1B:D2:1C:8D:AC:C5:D6:55:A7:FF:F9:F0:50:0C:74:A6:10:FF:DB`
