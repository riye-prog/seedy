set -eu
platform="$1"
z3_root="$2"
solver="native/dist/$platform/libseedycrack.dylib"
library="native/dist/$platform/libz3.dylib"
rm -f "$library"
cp -L "$z3_root/lib/libz3.dylib" "$library"
dependency="$(otool -L "$solver" | awk '/libz3/{print $1; exit}')"
install_name_tool -change "$dependency" @loader_path/libz3.dylib "$solver"
