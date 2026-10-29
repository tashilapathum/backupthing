#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Generates synthetic test media (no personal photos/videos ever enter the repo).
# Usage: scripts/gen-fixtures.sh <output-dir>
set -euo pipefail
out="${1:?output dir}"
mkdir -p "$out/Pics" "$out/Vids"

# Phone-like 8-bit full-range HEVC with AAC audio, stored in the "wrong" folder like Pixel/Syncthing setups.
ffmpeg -nostdin -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=3" \
  -f lavfi -i "sine=frequency=440:duration=3" \
  -c:v libx265 -pix_fmt yuvj420p -color_range pc -x265-params log-level=error \
  -c:a aac -metadata creation_time=2024-06-01T10:15:00Z -metadata location="+06.9271+079.8612/" \
  "$out/Pics/PXL_20240601_101500123.TS.mp4"

IM="$(command -v magick || command -v convert)"

photo() {
  "$IM" -size 1200x900 plasma:fractal -seed 7 -quality 92 "$1"
  exiv2 -q \
    -M"set Exif.Image.Make Google" \
    -M"set Exif.Image.Model Pixel" \
    -M"set Exif.Image.Orientation 6" \
    -M"set Exif.Photo.DateTimeOriginal 2024:06:02 18:30:12" \
    -M"set Exif.GPSInfo.GPSLatitudeRef N" \
    -M"set Exif.GPSInfo.GPSLatitude 6/1 55/1 3756/100" \
    -M"set Exif.GPSInfo.GPSLongitudeRef E" \
    -M"set Exif.GPSInfo.GPSLongitude 79/1 51/1 4032/100" \
    "$1"
}
photo "$out/Vids/PXL_20240602_183012456.jpg"

# Fake motion photo: JPEG still + appended MP4, as Google cameras do.
photo "$out/Vids/PXL_20240603_074530789.MP.jpg"
clip="$(mktemp --suffix .mp4)"
ffmpeg -nostdin -v error -y -f lavfi -i "testsrc2=size=320x240:rate=30:duration=1" -c:v libx264 "$clip"
cat "$clip" >> "$out/Vids/PXL_20240603_074530789.MP.jpg"
rm -f "$clip"

# Syncthing noise that must be ignored.
mkdir -p "$out/.stfolder" "$out/.stversions"
touch "$out/Vids/.syncthing.tmp.jpg.tmp" "$out/Vids/~syncthing~x.jpg.tmp"
echo "Fixtures written to $out"
