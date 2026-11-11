# AstroCam

Astrophotography camera for Android: manual ISO / exposure, infinity focus, RAW (DNG) light frames, batch capture.

## Get an APK from GitHub

1. Create a new empty repository on GitHub.
2. Upload everything in this folder (keep the `.github` folder), or:
   ```
   git init
   git add .
   git commit -m "AstroCam step 1"
   git branch -M main
   git remote add origin https://github.com/YOUR_USER/AstroCam.git
   git push -u origin main
   ```
3. Open the **Actions** tab, wait for **Build APK** to finish (about 3-5 min).
4. Open the finished run, download **AstroCam-debug-apk** from Artifacts, unzip it.
5. Copy `app-debug.apk` to your phone and install it (allow "install unknown apps").

Frames are saved to `Android/data/com.example.astrocam/files/AstroCam/` as `.dng`.

## Requirements
Phone must support RAW and manual sensor control (Camera2 `RAW` + `MANUAL_SENSOR`).

## Roadmap
- [x] Step 1: manual RAW capture, burst, red night UI
- [ ] Step 2: frame types (light/dark/flat/bias), intervalometer, save to gallery
- [ ] Step 3: lens switching (logical multi-camera / physical IDs)
- [ ] Step 4: live stacking (star detection, alignment, sigma-clip)
- [ ] Step 5: planner, plate-solve overlay; iOS port
