#!/usr/bin/env bash
# Uploads the plugin's jar (target/biapy-fiji-<version>.jar, built with 'mvn package') to the BiaPy update site of
# Fiji (https://sites.imagej.net/BiaPyX/), through a Fiji installation used only to publish (its Updater does the
# upload and keeps the site's db.xml.gz in sync). The jar is the only file: the libraries the plugin uses (gson,
# snakeyaml, commons-compress, JNA) come with Fiji.
#
# Usage:
#   UPDATE_SITE_PASSWORD=... scripts/upload_update_site.sh <path to Fiji.app> [--simulate]
#
# Environment variables:
#   UPDATE_SITE_PASSWORD  upload password of the site's account (required for the WebDAV upload)
#   UPDATE_SITE           name of the update site in that Fiji (default: BiaPy, as in the list of update sites)
#   UPDATE_SITE_USER      upload account, which names the site on sites.imagej.net (default: BiaPyX)
#   UPDATE_SITE_URL       default: https://sites.imagej.net/$UPDATE_SITE_USER/
#   UPDATE_SITE_HOST      default: webdav:$UPDATE_SITE_USER:$UPDATE_SITE_PASSWORD (e.g. file:localhost to test with a
#                         local folder, with UPDATE_SITE_UPLOAD_DIR set to it)
#   UPDATE_SITE_UPLOAD_DIR  default: .
set -euo pipefail

FIJI=${1:?Usage: UPDATE_SITE_PASSWORD=... $0 <path to Fiji.app> [--simulate]}
SIMULATE=${2:-}
SITE=${UPDATE_SITE:-BiaPy}
USER_NAME=${UPDATE_SITE_USER:-BiaPyX}
URL=${UPDATE_SITE_URL:-https://sites.imagej.net/$USER_NAME/}
UPLOAD_DIR=${UPDATE_SITE_UPLOAD_DIR:-.}
if [ -n "${UPDATE_SITE_HOST:-}" ]; then
	HOST=$UPDATE_SITE_HOST
else
	HOST="webdav:$USER_NAME:${UPDATE_SITE_PASSWORD:?Set UPDATE_SITE_PASSWORD to the upload password of $SITE}"
fi

HERE=$(cd "$(dirname "$0")/.." && pwd)
JAR=$(ls "$HERE"/target/biapy-fiji-*.jar 2>/dev/null | grep -v -e '-sources' -e '-tests' | head -1 || true)
[ -f "$JAR" ] || { echo "No target/biapy-fiji-<version>.jar: run 'mvn package' first" >&2; exit 1; }
case "$JAR" in *SNAPSHOT*) echo "Refusing to upload a SNAPSHOT version: $JAR" >&2; exit 1 ;; esac

LAUNCHER=""
for l in ImageJ-linux64 fiji-linux-x64 ImageJ-macosx Contents/MacOS/ImageJ-macosx fiji-macos ImageJ-win64.exe fiji-windows-x64.exe; do
	if [ -x "$FIJI/$l" ]; then LAUNCHER="$FIJI/$l"; break; fi
done
[ -n "$LAUNCHER" ] || { echo "No Fiji launcher found in $FIJI" >&2; exit 1; }
updater() { (cd "$FIJI" && "$LAUNCHER" --headless --update "$@"); }

# The jar to upload replaces any other version of it in that Fiji
rm -f "$FIJI"/plugins/biapy-fiji*.jar
cp "$JAR" "$FIJI/plugins/"
NAME=plugins/$(basename "$JAR")

if updater list-update-sites "$SITE" 2>/dev/null | grep -q "$URL"; then
	updater edit-update-site "$SITE" "$URL" "$HOST" "$UPLOAD_DIR"
else
	updater add-update-site "$SITE" "$URL" "$HOST" "$UPLOAD_DIR"
fi
status=0
updater upload ${SIMULATE:+--simulate} --update-site "$SITE" "$NAME" || status=$?
# Don't leave the password in that Fiji's list of update sites
if [ -z "${UPDATE_SITE_HOST:-}" ]; then updater edit-update-site "$SITE" "$URL" "webdav:$USER_NAME" "$UPLOAD_DIR" >/dev/null; fi
[ $status -eq 0 ] && echo "Uploaded $NAME to $URL${SIMULATE:+ (simulated)}"
exit $status
