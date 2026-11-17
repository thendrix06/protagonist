#!/usr/bin/env bash
# Builds docs/recording/<name>.mp4 from RecordingTest frames, with captions and the real capture timing.
# Usage: ./gradlew :app:testDebugUnitTest --tests '*RecordingTest*' -Pprotagonist.recording=true && scripts/make_recording.sh
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p docs/recording
for d in build/recording/*/; do
  name=$(basename "$d")
  python3 scripts/caption_frames.py "$d"
  ffmpeg -loglevel error -y -f concat -safe 0 -i "$d/concat.txt" \
    -vf "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p" -r 30 -c:v libx264 -crf 23 -movflags +faststart \
    "docs/recording/protagonist-$name.mp4"
  echo "docs/recording/protagonist-$name.mp4"
done
