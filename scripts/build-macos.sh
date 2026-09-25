set -eu
cd "$(dirname "$0")/.."
seedy_java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"
cmake -S native -B native/build-overlay -DCMAKE_BUILD_TYPE=Release "-DJAVA_HOME=$seedy_java_home" '-DCMAKE_OSX_ARCHITECTURES=arm64;x86_64'
cmake --build native/build-overlay --target seedy --config Release
