# Android unit-тесты вне Android Studio

`./gradlew :app:testProdDebugUnitTest` — чистая JVM (не Robolectric/эмулятор),
но и ей нужны JDK17 (Gradle 7.5 не собирает build-скрипты на JDK21 — см.
`Containerfile`) и Android SDK (`compileSdk 34`, `android.jar`), которых на
произвольной машине/CI-хосте может не быть. `run_unit_tests.sh` гоняет их в
одноразовом контейнере — весь тулчейн внутри образа, на хосте нужен только
Docker/Podman:

```bash
bash ci/run_unit_tests.sh
```

Первый запуск дольше (собирает образ — скачивает commandline-tools и
`platforms;android-34`/`build-tools;30.0.3`); повторные быстрее (слой
образа кэшируется, Gradle-кэш — в именованном томе
`wallettransactions-android-gradle-cache`, переживает отдельные прогоны).

Результаты (JUnit XML) — `app/build/test-results/testProdDebugUnitTest/`.
Тесты от варианта сборки не зависят, поэтому гоняется только `prod`
(openspec `android-flavors`); `qa` — `testQaDebugUnitTest`, если нужно.

## Файлы

```
ci/
  README.md            — этот файл
  Containerfile         — JDK17 + Android SDK (cmdline-tools, platform-34, build-tools 30.0.3)
  run_unit_tests.sh     — сборка образа (кэшируется) + testProdDebugUnitTest против ТЕКУЩЕГО рабочего дерева
```

## Jenkins

`BuildPlatform/jobs/android-unit-tests/Jenkinsfile` — тонкая обёртка вокруг
`run_unit_tests.sh`, публикует JUnit-отчёт (Test Result Trend на странице
job'а). Сам Jenkins и его настройка — целиком в BuildPlatform, не здесь:
`BuildPlatform/README.md` и `BuildPlatform/jenkins/README.md`.
