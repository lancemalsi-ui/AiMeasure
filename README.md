# AI Measure (Android · ARCore + Claude)

Measure real objects in 3D with your phone camera — no reference card.

## How it works

1. **Claude finds the object.** When you tap *Measure object*, the app sends one camera frame to Claude, which returns the object you're aiming at: a tight bounding box, its name, its shape, and its typical real-world size.
2. **ARCore Depth reads the object.** For the next ~12 frames, every depth pixel inside that box is turned into a 3D world point using the camera's pose and intrinsics.
3. **The surface is found.** ARCore plane detection (with a depth-based fallback) finds the floor or table the object sits on.
4. **The box is fitted.** Points above the surface are kept, stray background points are trimmed, and a minimum-area rectangle is fitted to the footprint (robust 2–98 % percentiles). Height is the top of the point cloud above the surface.
5. **Results are drawn in AR.** A 3D box with length, width and height labels stays anchored to the object as you move. Claude's typical size is used as a sanity check.

**Tape mode** lets you tap any two (or more) points for straight-line 3D distances. It works even on phones without Depth support.

## Get the APK from GitHub (no Android Studio needed)

1. Create a new repository on GitHub (private is fine) and upload **everything in this folder**, including the hidden `.github` folder.
   - Easiest: on the new repo page, click **uploading an existing file**, drag the folder contents in, and commit to `main`.
   - Or with git: `git init && git add . && git commit -m "AI Measure" && git branch -M main && git remote add origin <your-repo-url> && git push -u origin main`
2. Open the repo’s **Actions** tab. The **Build APK** workflow starts by itself (about 5–8 minutes).
3. When it shows a green check, open the run and download **AiMeasure-…** under *Artifacts*. Unzip it to get the `.apk`.
4. Copy the APK to your phone, open it, and allow *Install unknown apps* when Android asks.

**Releases:** push a tag like `v1.0.0` (`git tag v1.0.0 && git push --tags`) and the APK is attached to a GitHub Release with a direct download link.

Every build is signed with the same shared debug key (`app/debug.keystore`), so newer APKs install over older ones. Before publishing on the Play Store, switch to your own private signing key.

## Connect Claude

On first launch the app asks for your **Claude API key** (create one at console.anthropic.com → API keys). It is saved only on your phone and sent only to Claude. Tap **Claude** at the top right any time to change the key or the model, and the app tests the connection when you save.

Claude is used for:
- **Finding the object** you're aiming at, its name, shape and typical size.
- **Ask Claude about this measurement:** after measuring, ask things like "Will this fit on a 40 cm shelf?" or "What size box do I need to ship it?" Claude gets the numbers (and the photo, in AI mode).

Tape mode works without a key.

## Build locally (optional)

Open the folder in Android Studio and press Run, or run `./gradlew assembleDebug`. You can also put a key in `local.properties` (see `local.properties.example`) for testing; a key typed in the app overrides it.

**Don't** bake a key into APKs you share: anyone can extract it. For apps you distribute, either let each person enter their own key (the default) or set `ANTHROPIC_BASE_URL` to your own server that holds the key.

## Tips for accuracy

- Stand **0.5–1.5 m** from the object, with some of the floor or table visible around it.
- Before measuring, sweep the phone slowly over the surface until the status says to aim at the object.
- Hold still during the 1-second depth reading.
- Measure again from a different side and compare. Expect about **±1–2 cm** on mid-range phones; phones with a time-of-flight sensor do better.
- Shiny, transparent, very dark, or very thin objects are hard for any depth sensor.

## Security note

The in-app key is stored in the app's private storage. Anyone who has your unlocked phone and root access could read it, so use a key with a spending limit set in the Anthropic console.

## Project map

| File | Purpose |
|---|---|
| `MainActivity.kt` | AR session, render loop, AI pipeline state machine, UI |
| `ClaudeClient.kt` | Object detection, Ask Claude, connection test |
| `.github/workflows/build-apk.yml` | Builds the APK on every push; attaches it to Releases on `v*` tags |
| `Measurer.kt` | Depth → 3D points → surface → fitted 3D box |
| `OverlayView.kt` | Projects 3D points/box to the screen with labels |
| `BackgroundRenderer.kt` | Draws the camera feed with OpenGL ES |
| `ImageUtil.kt` | YUV → JPEG, rotation, box coordinate mapping |
