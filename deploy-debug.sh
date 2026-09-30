#!/usr/bin/env bash
# Собирает debug-сборку и ставит на
# конкретное устройство через adb/gradlew, без Android Studio (gradlew уже
# умеет и собрать, и поставить сам).
#
# Сериал ниже — Pixel 8 Pro, подключенный по USB на момент написания этого
# скрипта (машина с
# двойной загрузкой, устройство одно на обе ОС). ANDROID_SERIAL — переменная,
# которую понимают И adb, И Android Gradle Plugin внутри gradlew
# installDebug — так они оба выбирают нужное устройство, если подключено
# несколько (второй телефон, эмулятор), без ручного "adb -s <serial> install".
#
# Если сериал Pixel сменится (переподключили как другое USB-устройство,
# другой телефон) или нужно поставить на другое устройство — редактировать
# файл не нужно, просто передать сериал первым аргументом:
#   ./deploy-debug.sh <serial>
# Список подключенных устройств и их сериалов: adb devices -l
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

DEFAULT_SERIAL=46281FDJG0015B
export ANDROID_SERIAL="${1:-$DEFAULT_SERIAL}"

# Android SDK — на этой машине НЕ из Android Studio/apt (их тут нет вовсе),
# поставлен вручную sdkmanager'ом в ~/Android/Sdk (2026-09-14), теми же
# версиями, что ci/Containerfile (cmdline-tools, platforms;android-34,
# build-tools;30.0.3, platform-tools) — local.properties уже указывает
# сюда же. ANDROID_HOME/ANDROID_SDK_ROOT можно переопределить снаружи —
# значения по умолчанию только для этой машины.
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${ANDROID_SDK_ROOT:=$ANDROID_HOME}"
export ANDROID_HOME ANDROID_SDK_ROOT
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$ADB" ] || ADB=adb   # fallback, если на будущей машине adb уже в PATH

# JDK17, не системный — Gradle 7.5 (см. gradle/wrapper/gradle-wrapper.properties)
# не собирает build-скрипты на JDK21 ("Unsupported class file major version
# 65", проверено вживую 2026-09-14) — 17 достаточно портативного Temurin в
# ~/.jdks/temurin-17.
: "${JAVA_HOME:=$HOME/.jdks/temurin-17}"
export JAVA_HOME

echo "Проверяю устройство $ANDROID_SERIAL ..."
if ! "$ADB" -s "$ANDROID_SERIAL" get-state >/dev/null 2>&1; then
    echo
    echo "Устройство $ANDROID_SERIAL не найдено adb (не подключено, не" >&2
    echo "авторизовано, или сменился сериал). Подключенные устройства:" >&2
    "$ADB" devices -l >&2
    exit 1
fi

# Debug-подпись должна совпадать с тем, что уже стоит на устройстве —
# иначе adb откажет: INSTALL_FAILED_UPDATE_INCOMPATIBLE (реальный случай
# 2026-09-14: на этой машине ~/.android/debug.keystore изначально
# генерировался Windows-стороной двойной загрузки, поэтому — если файла
# нет вовсе или он не тот — см. /run/media/ivan/OS/Users/<user>/.android/
# debug.keystore на смонтированном разделе Windows, тот же alias/пароль
# по умолчанию: androiddebugkey/android).
echo "Собираю debug и ставлю на $ANDROID_SERIAL ..."
# "sh gradlew", а не "./gradlew" — этот каталог на NTFS-диске (ntfs-3g/fuseblk),
# который не хранит бит на выполнение: chmod +x тут молча не сохраняется, и
# "./gradlew" всегда падает с Permission denied. gradlew сам начинается с
# "#!/bin/sh" — просто передаём его sh явным образом, бит на выполнение не нужен.
sh gradlew installDebug --console=plain

echo
echo "Готово: app-debug.apk установлен на $ANDROID_SERIAL."
