#!/usr/bin/env bash
# stockexchange-patched 云端构建（GitHub Actions / Linux）
# 公开依赖用 Maven 拉取；Residence 本体从 Zrips/Residence 源码先行构建（RESIDENCE_JAR 传入）；
# 服务器私有 GMZC 插件与 Slimefun 用 ci/stubs 的 ABI 桩对齐签名（不打包进 jar）。
set -euo pipefail

# javac/java classpath separator: Linux uses ':', Git Bash on Windows uses ';'
CP_SEP=':'
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;;
esac

cd "$(dirname "$0")/.."

MVN="${MVN:-mvn}"
RESIDENCE_JAR="${RESIDENCE_JAR:-build/deps/Residence6.0.2.2.jar}"
if [ ! -f "$RESIDENCE_JAR" ]; then
    echo "缺少 Residence 构建产物：$RESIDENCE_JAR（请先从 Zrips/Residence 源码 mvn package）" >&2
    exit 1
fi

rm -rf build/ci-lib build/stub-classes build/ci-stubs.jar build/plugin-classes build/test-classes build/StockExchange-1.0.0-gmzc.jar
mkdir -p build/ci-lib build/stub-classes build/plugin-classes build/test-classes

echo "== 1/5 拉取公开依赖 =="
"$MVN" -B -q -f ci/deps-pom.xml dependency:copy-dependencies -DoutputDirectory="$PWD/build/ci-lib"

echo "== 2/5 编译 ABI 桩 =="
find ci/stubs -name '*.java' | sort > build/stub-sources.txt
javac -encoding UTF-8 -proc:none -cp "build/ci-lib/*${CP_SEP}$RESIDENCE_JAR" -d build/stub-classes @build/stub-sources.txt
jar --create --file build/ci-stubs.jar -C build/stub-classes .

CP="build/ci-lib/*${CP_SEP}build/ci-stubs.jar${CP_SEP}$RESIDENCE_JAR"

echo "== 3/5 编译插件 =="
find src -name '*.java' | sort > build/sources.txt
javac -encoding UTF-8 -proc:none -cp "$CP" -d build/plugin-classes @build/sources.txt

# 先复制资源（与本地 build.ps1 一致：测试前资源必须在 classpath 上）
cp plugin.yml config.yml vanilla-zh-cn.properties build/plugin-classes/
mkdir -p build/plugin-classes/data
cp data/item_database.json build/plugin-classes/data/

echo "== 4/5 运行测试 =="
if find test -name '*.java' 2>/dev/null | grep -q .; then
    find test -name '*.java' | sort > build/test-sources.txt
    javac -encoding UTF-8 -proc:none -cp "$CP${CP_SEP}build/plugin-classes" -d build/test-classes @build/test-sources.txt
    find build/test-classes -name '*Test.class' | sed 's|build/test-classes/||; s|\.class$||; s|/|.|g' | while read -r cls; do
        java -ea -cp "$CP${CP_SEP}build/plugin-classes${CP_SEP}build/test-classes" "$cls"
    done
fi

echo "== 5/5 打包 =="
jar --create --file build/StockExchange-1.0.0-gmzc.jar -C build/plugin-classes .
echo "Built build/StockExchange-1.0.0-gmzc.jar"