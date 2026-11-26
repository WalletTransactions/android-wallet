# Proposal

## Why

Адрес сервера в «Интеграциях» вводится вручную, а переключаться приходится
между несколькими адресами: test и prod, LAN и Tailscale, HTTP и HTTPS. Каждый
раз набирать адрес на телефоне неудобно, и легко ошибиться.

## What Changes

- Телефон запоминает до трёх последних адресов сервера, на которых **вход
  прошёл успешно** (пароль, регистрация или Google). Новые идут первыми, один
  и тот же адрес в списке не повторяется.
- Диалог «Адрес сервера» показывает эти адреса под полем ввода. Нажатие на
  адрес подставляет его в поле, сохраняет по-прежнему кнопка «Сохранить».
- Список хранится только на устройстве, в настройках приложения, в синк не
  уходит.

## Capabilities

### New Capabilities

Нет.

### Modified Capabilities

- `server-account`: два новых требования: «Недавние адреса сервера» и «Выбор недавнего адреса».

## Impact

- `AppSettings.java` (`getRecentServerUrls`, `rememberSuccessfulServerUrl`,
  ключ `recentServerUrls` в prefs `settings`), `SyncLinkActivity.onLinked`
  (запись адреса), `IntegrationsActivity.onPickServerUrl` (список в диалоге),
  строка `integrations_server_url_recent` (en/ru).
- Юнит-тест `RecentServerUrlsTest`.
- Сервер и другие клиенты не меняются.
