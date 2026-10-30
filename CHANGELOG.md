# Changelog

## 0.3.1 - 2026-10-30

### Fixes
- After a package upgrade (e.g. `yay`), the running service kept serving from the removed old version and the
  dashboard lost its styling until restarted. It now notices within a minute and exits, so systemd restarts it on
  the new version.

## 0.3.0 - 2026-10-30

### Staging limit
- Compression no longer runs ahead of uploads without bound. It pauses while 10 compressed files wait to upload
  (Settings → Uploads → Staging limit: a number of files, a size, or no limit) and resumes as uploads finish.
- When uploads can't continue (sign-in needed, offline, upload cap), compression stops once staging is full; the
  remaining files wait for the next run instead of filling the disk. `run --dry-run` respects the limit too.

### Backup progress on the overview
- The overview card now shows how much of your library is backed up, by size: one bar split into backed up, ready
  to upload, not compressed yet and failed, with sizes and file counts, plus a bar per top-level folder (or per
  source folder when you back up several).

## 0.2.1 - 2026-10-29

### Fixes
- systemd unit: `BACKUPTHING_OPTS` is quoted, so `-XX:+UseSerialGC` reaches the JVM instead of systemd logging
  "Invalid environment assignment" and dropping it.

## 0.2.0 - 2026-10-29

First public release.

### Install
- `install.sh` in the release tarball: installs into your home folder (no root), sets up and starts the
  systemd user service, adds Backupthing to the app menu and opens the dashboard. Running a newer
  version's `install.sh` upgrades in place; `--uninstall` removes everything except your settings and
  history; `--no-service` skips the service.
- App menu entry and icon (also in the AUR package).
- `backupthing open`: opens the dashboard in the browser, starting the service first if it isn't running.

### Bandwidth
- Settings → Bandwidth: an upload speed limit shared by all parallel uploads, and optional daily and monthly
  upload caps (the month can start on any day, to match an internet plan). A file starts uploading only if it
  fits in what is left; the rest wait for the next day or month. A file bigger than a whole cap is reported
  as failed. Usage against the caps shows on the dashboard, in `/api/status` and in Waybar.
- Changing the speed limit applies to uploads already running.

### Interface
- Settings are shown as tabs: a rail on the side on wide screens, a scrolling strip on narrow ones. Switching tabs
  keeps unsaved edits, and a failed save opens the first tab with a problem. Related settings were regrouped:
  photo/video parallelism under Compression, parallel uploads and attempts under Uploads.
- About page: what Backupthing is and why it was made, privacy policy, terms, license and credits, contact and
  support links.
- Light/dark switch in the top bar. The choice is kept per browser; picking what the system uses follows the
  system again.

### Fixes
- `serve` printed `localhost` as the dashboard address when listening on a specific network address.

## 0.1.0 - 2026-10-25

First release.

### Backup
- Compresses photos to AVIF (`avifenc`) and videos to AV1 (`ffmpeg`), visually lossless by default.
  Keeps EXIF (date, time zone, GPS), ICC profiles, Ultra HDR gain maps, video rotation, full colour
  range, recording time and location. Motion photos: still only (default) or unchanged.
- Video encoders: NVIDIA NVENC, Intel Quick Sync, VA-API and SVT-AV1, chosen automatically, with a
  CPU fallback when a hardware encode fails. Every output is verified; if compressing doesn't make a
  file smaller, the original is uploaded instead.
- Uploads to a Proton Drive "My files" folder tree (with new revisions for changed files) or to the
  Proton Photos timeline, through the official `proton-drive` CLI.
- Finds new and changed files in your source folders, skips Syncthing temporary files and half-synced
  files, and refuses to run when a source folder is missing (e.g. an unmounted disk).
- Crash-safe: resumes after crashes, reboots and lost connections without re-uploading or duplicating;
  retries with limits; pauses uploads (without counting failures) when a Proton sign-in is needed or
  Proton can't be reached. Originals are only ever read.

### Interface
- `backupthing serve`: daily / every-N-hours / manual schedule that catches up missed runs, a web
  dashboard with live progress, totals, failed files with retry and run history, a JSON status API and
  a Waybar module.
- Settings page and first-run setup wizard: folder browser, Proton sign-in from the browser, a sample
  test with before/after preview, tool check, live log level, retry failed files, reset database.
  Hand edits to `config.toml` apply without a restart.
- Light and dark themes; Geist fonts and Phosphor icons bundled so the dashboard works offline.
- Logo: a capital B whose stem is a separate up arrow, in plum (favicon, dashboard, README, Waybar
  image module; colour and monochrome SVGs in `assets/`).
- Commands: `serve`, `run [--dry-run]`, `check`, `scan`, `--version`.

### Security
- Dashboard answers only to known host names, requires a per-session token and same-origin requests
  for every change, sets a strict content security policy, and requires an access token when reachable
  from the network.

### Packaging
- Release tarball with a systemd user unit and the Waybar snippet; AUR `PKGBUILD` in `packaging/aur/`.

### Known limitations
- Intel Quick Sync and VA-API encoding are untested on real hardware and their quality defaults are
  uncalibrated; a failed hardware encode falls back to the CPU.
- Photos timeline: re-compressing a changed file uploads a second copy (the timeline has no versions),
  and formats Photos doesn't accept (such as DNG) are reported as failed.
- The `proton-drive` CLI is pre-1.0; a change in its output may need a Backupthing update.
- Nothing is ever deleted remotely, so files removed from the source folders stay in Proton.
