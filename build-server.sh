#!/usr/bin/env bash
# 沙盒内离线构建：编译 crypto-core + server，并运行服务端集成测试
set -euo pipefail
cd "$(dirname "$0")"
CP="$(ls libs/*.jar | tr '\n' ':')"
CP2="$(ls libs2/*.jar | tr '\n' ':')"
FULL="$CP$CP2"
KOTLINC="java -Dkotlin.compiler.execution.strategy=in-process -cp $FULL org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib"

rm -rf build/classes/server build/classes/servertest
mkdir -p build/classes/server build/classes/servertest

echo "== compile server main =="
$KOTLINC -cp "build/classes/main:$FULL" -d build/classes/server $(find server/src/main -name '*.kt')

echo "== compile server tests =="
$KOTLINC -cp "build/classes/main:build/classes/server:$FULL" -d build/classes/servertest $(find server/src/test -name '*.kt')

echo "== run server integration tests =="
java -cp "build/classes/main:build/classes/server:build/classes/servertest:$FULL" \
  org.junit.platform.console.ConsoleLauncher execute \
  --select-class com.enchat.server.IntegrationTest \
  --details=summary --disable-banner
