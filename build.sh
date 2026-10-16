#!/usr/bin/env bash
# 沙盒内本地构建 + 测试脚本（离线环境，用预下载的 jar 代替 Gradle）
set -euo pipefail
cd "$(dirname "$0")"

CP="$(ls libs/*.jar | tr '\n' ':')"
KOTLINC="java -Dkotlin.compiler.execution.strategy=in-process -cp $CP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib"

rm -rf build/classes
mkdir -p build/classes/main build/classes/test

echo "== compiling main =="
$KOTLINC -cp "$CP" -d build/classes/main $(find crypto-core/src/main -name '*.kt')

echo "== compiling tests =="
$KOTLINC -cp "build/classes/main:$CP" -d build/classes/test $(find crypto-core/src/test -name '*.kt')

echo "== running tests =="
java -jar libs/junit-platform-console-standalone-1.10.2.jar execute \
  -cp "build/classes/main:build/classes/test:$CP" \
  --scan-classpath \
  --details=summary \
  --disable-banner

# client-core：纯 Kotlin，无 Android 依赖，可在任意 JDK 上编译与测试
CPX="$(ls libs/*.jar | tr '\n' ':')"
mkdir -p build/classes/clientcore build/classes/clientcoretest
java -Dkotlin.compiler.execution.strategy=in-process -cp "$CPX" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
  -cp "build/classes/main:$CPX" -d build/classes/clientcore $(find client-core/src/main -name '*.kt')

java -Dkotlin.compiler.execution.strategy=in-process -cp "$CPX" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
  -cp "build/classes/main:build/classes/clientcore:$CPX" -d build/classes/clientcoretest $(find client-core/src/test -name '*.kt')

echo "== run client-core tests =="
java -cp "build/classes/main:build/classes/clientcore:build/classes/clientcoretest:$CPX" \
  org.junit.platform.console.ConsoleLauncher execute \
  --select-class com.enchat.clientcore.ConnectionStateMachineTest \
  --select-class com.enchat.clientcore.ConversationResolverTest \
  --details=summary --disable-banner
