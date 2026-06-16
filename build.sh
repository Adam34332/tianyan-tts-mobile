#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/.local/share/android-sdk}"
BUILD_TOOLS="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
APP_ID="com.adam.paseotts"
ICON_SOURCE="${APP_ICON_SOURCE:-}"
BUILD="$ROOT/build"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/out"

cp -R "$ROOT/res" "$BUILD/res"
python3 - "$ICON_SOURCE" "$BUILD/res" <<'PY'
import sys
from pathlib import Path
from PIL import Image, ImageDraw

src = Path(sys.argv[1]) if sys.argv[1] else None
out = Path(sys.argv[2])

if src and src.exists():
    im = Image.open(src).convert("RGB")
    w, h = im.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    im = im.crop((left, top, left + side, top + side))
else:
    im = Image.new("RGB", (512, 512), "#0f172a")
    draw = ImageDraw.Draw(im)
    draw.ellipse((76, 146, 436, 366), fill="#e0f2fe")
    draw.ellipse((158, 106, 354, 402), fill="#0ea5e9")
    draw.ellipse((206, 154, 306, 354), fill="#082f49")
    draw.ellipse((238, 190, 274, 226), fill="#f8fafc")
    draw.rounded_rectangle((160, 416, 352, 454), radius=19, fill="#22c55e")

sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for folder, size in sizes.items():
    target = out / folder
    target.mkdir(parents=True, exist_ok=True)
    resized = im.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(target / "ic_launcher.png")
    resized.save(target / "ic_launcher_round.png")
PY

"$BUILD_TOOLS/aapt2" compile --dir "$BUILD/res" -o "$BUILD/compiled-res.zip"

"$BUILD_TOOLS/aapt2" link \
  --manifest "$ROOT/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  -A "$ROOT/assets" \
  "$BUILD/compiled-res.zip" \
  --java "$BUILD/gen" \
  --min-sdk-version 23 \
  --target-sdk-version 36 \
  -o "$BUILD/out/unsigned.apk"

javac -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$BUILD/gen" \
  -d "$BUILD/classes" \
  $(find "$ROOT/src" "$BUILD/gen" -name '*.java' | sort)

"$BUILD_TOOLS/d8" --min-api 23 --lib "$ANDROID_JAR" --output "$BUILD/dex" $(find "$BUILD/classes" -name '*.class' | sort)
zip -q -j "$BUILD/out/unsigned.apk" "$BUILD/dex/classes.dex"

KEYSTORE="${PASEO_TTS_KEYSTORE:-$HOME/.local/share/paseo-tts-mobile/debug.keystore}"
mkdir -p "$(dirname "$KEYSTORE")"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

"$BUILD_TOOLS/zipalign" -f 4 "$BUILD/out/unsigned.apk" "$BUILD/out/aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$BUILD/paseo-tts-mobile-debug.apk" \
  "$BUILD/out/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose "$BUILD/paseo-tts-mobile-debug.apk"

echo "$BUILD/paseo-tts-mobile-debug.apk"
