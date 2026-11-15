#!/bin/sh
# Builds a native executable of the MCP server, like HeadlessMc's scripts/build-native.sh.
# Needs GraalVM 25 with native-image, or pass -Dquarkus.native.container-build=true to build a Linux executable in a container.
# The executable is build/headlessmc-mcp-<version>-runner(.exe).
# Unlike HeadlessMc, runtime options are parsed, so that -Dhmc.files.location=... works as an argument of the executable.
#
# Environment variables:
#   NATIVE_MUSL  if true, builds a fully static executable against musl instead of glibc,
#                x64 Linux only, needs x86_64-linux-musl-gcc with zlib on the PATH

set -e

BUILD_ARGS="--initialize-at-run-time=org.jline.nativ,--initialize-at-run-time=org.jline.terminal.impl.ffm,-H:+UnlockExperimentalVMOptions,-H:+SharedArenaSupport,-H:-UnlockExperimentalVMOptions"
if [ "$NATIVE_MUSL" = "true" ]; then
  BUILD_ARGS="$BUILD_ARGS,--static,--libc=musl"
fi

./gradlew quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false \
-Dquarkus.native.additional-build-args="$BUILD_ARGS" "$@"
