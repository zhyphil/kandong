#!/bin/sh
# Package only the accepted compat product; never create keys or publish implicitly.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
[ -f .signing/release.properties ] || { echo 'Release credentials missing; see docs/RELEASING.md.' >&2; exit 2; }
if [ -n "${KANDONG_JAVA_HOME:-}" ]; then
  kandong_jdk="$KANDONG_JAVA_HOME"
elif [ "$(uname -s)" = Darwin ]; then
  kandong_jdk="$(/usr/libexec/java_home -v 17)"
else
  kandong_jdk="${JAVA_HOME:?Set JAVA_HOME or KANDONG_JAVA_HOME to JDK 17}"
fi
kandong_sdk="${KANDONG_ANDROID_SDK:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
kandong_tools="$kandong_sdk/build-tools/35.0.0"
[ -x "$kandong_tools/apksigner" ] || { echo 'Android Build Tools 35.0.0 required.' >&2; exit 2; }
env JAVA_HOME="$kandong_jdk" ./gradlew :compat:assembleRelease :compat:testReleaseUnitTest :compat:lintRelease --console=plain
kandong_apk=compat/build/outputs/apk/release/compat-release.apk
[ -f "$kandong_apk" ] || { echo 'Signed release APK was not produced.' >&2; exit 2; }
kandong_version="$(python3 -c 'import json; print(json.load(open("compat/build/outputs/apk/release/output-metadata.json"))["elements"][0]["versionName"])')"
case "$kandong_version" in *[!0-9.]*|'') echo 'Unexpected release version.' >&2; exit 2 ;; esac
kandong_out="dist/v$kandong_version"
mkdir -p "$kandong_out"
kandong_name="kandong-v$kandong_version-android.apk"
cp "$kandong_apk" "$kandong_out/$kandong_name"
env JAVA_HOME="$kandong_jdk" PATH="$kandong_jdk/bin:$PATH" "$kandong_tools/apksigner" verify --verbose --print-certs "$kandong_out/$kandong_name" > "$kandong_out/SIGNATURE.txt"
"$kandong_tools/zipalign" -c -P 16 4 "$kandong_out/$kandong_name"
"$kandong_tools/aapt2" dump badging "$kandong_out/$kandong_name" > "$kandong_out/APK-METADATA.txt"
python3 - "$kandong_out/APK-METADATA.txt" <<'PY_METADATA'
import pathlib,sys
metadata=pathlib.Path(sys.argv[1]).read_text()
assert "package: name='com.kandong.compat'" in metadata
assert 'application-debuggable' not in metadata
assert "uses-permission: name='android.permission.INTERNET'" not in metadata
PY_METADATA
cp "docs/releases/v$kandong_version.md" "$kandong_out/RELEASE-NOTES.md"
(
  cd "$kandong_out"
  shasum -a 256 "$kandong_name" > SHA256SUMS.txt
)
echo "Verified release artifacts: $kandong_out"
