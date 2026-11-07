#!/usr/bin/env bash
# Зашитые тексты интерфейса (openspec android-i18n, design D5): кириллица в строковых
# литералах Java и в атрибутах text/hint/title/contentDescription layout и menu, не
# ссылающихся на ресурс (@string/…). Такой текст не переводится — его место в
# res/values/strings.xml.
#
# Не считаются: комментарии (строки, начинающиеся с //, /*, *), логи (Log.*), строки с
# пометкой `// not UI` (текст уходит на сервер как данные — design D2) и файлы из
# ci/hardcoded_text_allowlist.txt — ещё не вынесенные экраны. Список сокращается с каждым
# вынесенным экраном (задачи 2.x) и в конце пуст.
#
# Выход 1 — найдены зашитые тексты вне списка исключений.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ALLOWLIST="$ROOT/ci/hardcoded_text_allowlist.txt"
MAIN="app/src/main"
CYR='[А-Яа-яЁё]'

allowed() {
    [ -f "$ALLOWLIST" ] && grep -qxF "$1" <(grep -v '^\s*#' "$ALLOWLIST" | sed 's/[[:space:]]*$//')
}

found=0
report() {
    local file="$1" hits="$2"
    if allowed "$file"; then return; fi
    echo "$file:"
    echo "$hits" | sed 's/^/    /'
    found=1
}

cd "$ROOT"

while IFS= read -r file; do
    hits=$(grep -nP "\"[^\"]*${CYR}[^\"]*\"" "$file" \
        | grep -vP '^\d+:\s*(//|/\*|\*)' \
        | grep -vP '\bLog\.[vdiwe]\(' \
        | grep -vF '// not UI' || true)
    [ -n "$hits" ] && report "$file" "$hits"
done < <(find "$MAIN/java" -name '*.java' | sort)

while IFS= read -r file; do
    hits=$(grep -nP "android:(text|hint|title|contentDescription)=\"[^@\"][^\"]*${CYR}" "$file" || true)
    [ -n "$hits" ] && report "$file" "$hits"
done < <(find "$MAIN/res/layout" "$MAIN/res/menu" -name '*.xml' 2>/dev/null | sort)

if [ "$found" -ne 0 ]; then
    echo
    echo "Зашитые тексты интерфейса — вынесите их в res/values/strings.xml (openspec android-i18n)." >&2
    exit 1
fi
echo "Зашитых текстов вне ci/hardcoded_text_allowlist.txt нет."
