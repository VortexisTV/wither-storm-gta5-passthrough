#!/bin/bash
# Build from WSL on a Windows drive, or copy gta/ to <PASSTHROUGH_WIN_DIR>\gta and run MSVC there.
#   ./build.sh          build\MCPassthrough.asi (build.bat); run fetch_deps.sh first
#   ./build.sh tests    that, then tests\fakegta.exe and tests\ws_test.exe (tests\build_fakegta.bat)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
WIN=${PASSTHROUGH_WIN_DIR:-$(wslpath -w "$HERE/..")}
DST=$(wslpath -u "$WIN\\gta")
case "$DST" in
	/mnt/[a-zA-Z]/*) ;;
	*) echo 'Use a checkout on a Windows drive, or set PASSTHROUGH_WIN_DIR to a Windows working directory.'; exit 1 ;;
esac
mkdir -p "$DST"
if [ "$(realpath "$DST")" != "$HERE" ]; then
	rsync -a --exclude build --exclude 'tests/*.exe' --exclude 'tests/*.obj' "$HERE/" "$DST/"
fi
# cmd.exe starts in the mirror; .\ because cmd may not look in the current folder (NoDefaultCurrentDirectoryInExePath)
cd "$DST"
BATS='.\build.bat'
[ "$1" = tests ] && BATS='.\build.bat && .\tests\build_fakegta.bat'
cmd.exe /c "$BATS" 2>&1 | grep -v "UNC paths\|CMD.EXE was started\|Defaulting to Windows"
exit ${PIPESTATUS[0]}
