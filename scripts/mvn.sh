#!/bin/bash
# Maven 启动器
#
# 本机 D:\Program Files\apache-maven-3.9.14 的启动链坏了：
#   - bin/m2.conf 被改成 `main is org.apache.maven.cli.MavenCli`（不是合法入口）
#   - 改回标准写法后 classworlds 2.9.0 在 JDK 17.0.18 下 StackOverflowError
# 所以这里绕开 classworlds，直接把 Maven 的 lib 全部塞进 classpath 调 MavenCli。
#
# 用法：bash scripts/mvn.sh -v
#       bash scripts/mvn.sh -q -DskipTests package
set -e
M2_HOME_DIR="D:/dev/maven"
CP="$M2_HOME_DIR/lib/*;$M2_HOME_DIR/boot/plexus-classworlds-2.9.0.jar;$M2_HOME_DIR/conf/logging"
exec java \
  -cp "$CP" \
  -Dmaven.multiModuleProjectDirectory="$(pwd -W)" \
  -Dmaven.home="$M2_HOME_DIR" \
  org.apache.maven.cli.MavenCli "$@"
