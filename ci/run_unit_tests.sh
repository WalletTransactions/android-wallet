#!/usr/bin/env bash
# Гоняет `./gradlew :app:testProdDebugUnitTest` (юнит-тесты — чистая JVM, без
# эмулятора/устройства) ПРОТИВ ТЕКУЩЕГО рабочего дерева, в одноразовом
# контейнере (см. Containerfile рядом) — на хосте, где эта штука реально
# нужна (CI), нет ни нужного JDK17 (Gradle 7.5 не собирает build-скрипты на
# JDK21 — см. Containerfile), ни Android SDK. Тот же приём, что у
# wallet-api/tests_integration/run_local.sh: не Jenkinsfile
# переизобретает команды, а он зовёт этот скрипт — тестовый контур остаётся
# здесь, в проекте, а не в BuildPlatform (см. BuildPlatform/jobs/
# android-unit-tests/Jenkinsfile).
#
# Требования: только Docker/Podman — ни JDK, ни Android SDK на хосте не
# нужны (весь тулчейн — внутри образа, собирается этим же скриптом).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE=wallettransactions-android-ci

# Движок контейнеров — podman (изначальная разработка) или docker (эта
# машина) — тот же приём, что wallet-api/loadtest/mint_tokens.sh.
if command -v podman >/dev/null 2>&1; then
    ENGINE=podman
elif command -v docker >/dev/null 2>&1; then
    ENGINE=docker
else
    echo "Не нашёл ни podman, ни docker в PATH." >&2
    exit 1
fi

echo "==> Образ сборки (кэшируется — пересобирается, только если правился Containerfile)"
"$ENGINE" build -t "$IMAGE" -f "$SCRIPT_DIR/Containerfile" "$SCRIPT_DIR"

# Именованные тома, не bind-mount на ~/.gradle хоста — на CI-хосте (Jenkins,
# сам гоняющий этот скрипт от root, см. BuildPlatform/jenkins/README.md,
# "Зачем контейнеру Jenkins...") нет и не должно быть предположений о
# домашней директории; тома переживают отдельные прогоны И на CI, И локально
# (второй прогон не тянет заново дистрибутив Gradle ~120MB и Android SDK
# заново не качает — тот уже внутри образа, тут кэшируется только то, что
# gradlew докачивает сам при первом запуске, см. ../gradle/wrapper/
# gradle-wrapper.properties).
"$ENGINE" run --rm \
    -v "$ANDROID_DIR:/repo/Android" \
    -v wallettransactions-android-gradle-cache:/root/.gradle \
    "$IMAGE" \
    ./gradlew :app:testProdDebugUnitTest --console=plain --no-daemon
