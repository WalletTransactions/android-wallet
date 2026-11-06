# Tasks

## 1. Исправление

- [x] 1.1 `TransactionActivity.onCreate`: убрать безусловное `mBtnSave.setEnabled(false)` и после снимка исходных значений правки вызвать `updateSaveButton()` — проверено `TransactionSaveButtonTest` (до правки на origin/main `ai_prefilled_new_operation_can_be_saved_at_once` падал, после — зелёный)

## 2. Тесты

- [x] 2.1 Robolectric `Activities/TransactionSaveButtonTest`: окно с extras черновика ИИ — «Сохранить» активна; черновик без суммы — погашена; правка без изменений — погашена — `bash gradlew :app:testProdDebugUnitTest` зелёный локально и в CI
- [x] 2.2 Проверка на устройстве: фраза на экране «ИИ» → окно с заполненными полями → «Сохранить» активна сразу, операция записывается одним нажатием — проверено пользователем на Pixel 8 Pro 2026-11-06 (QA 1.0.108-qa из main c418bd0, test-контур): работает
