# Wallpapers

The collection contains ten 3840 × 2160 WebP files, totalling 14,876,034 bytes (14.9 MB).
The project owner supplied these files. No wallpaper image ships inside the APK.
Plume stores the 4K files but currently renders still wallpapers at 1920 × 1080 to limit memory use.

## Publish a new wallpaper

1. Add a `.webp` file to this directory on `main`.
2. Use letters, numbers, underscores, or hyphens in the filename. Start with a letter or number.
3. Keep the same filename when you replace an existing wallpaper.

Names come from filenames. For example, `11_Future_Sunset.webp` appears as **Future Sunset**.
Each file must stay below 50 MB. The current directory API supports fewer than 1,000 entries.
No app catalogue edit, generated index, or app release is needed for later uploads.

## On the TV

Open **Plume settings → Wallpaper → Built-in**. Plume checks the GitHub directory when this screen opens.
It lists new photos and automatically updates photos already downloaded, using GitHub content hashes.
Select a new photo to download and use it. **More photos** downloads all missing photos.

Each download replaces only its own file after size, content hash, and WebP header checks pass.
A failed download keeps the previous file. Unchanged files do not download again.
Plume keeps other downloaded wallpapers, including files no longer listed on GitHub.
Saved selections use filenames, so replacing a file keeps its selection.

If GitHub is unavailable, downloaded wallpapers remain available.
Users need the app version that includes this feature once. Later wallpaper changes need no app update.
