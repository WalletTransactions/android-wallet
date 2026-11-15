## Why

Приложение входит на сервер только через Google (Credential Manager).
google-auth (change `add-password-auth`) получает вход и регистрацию по email и
паролю рядом с Google. Нужен способ войти и зарегистрироваться с телефона без
Google-аккаунта, а набор способов должен определяться сервером. Кроме того,
для работы по HTTPS со своим CA приложение должно доверять пользовательским
сертификатам.

## What Changes

- Экран входа (тот, что открывает строка «Связь с Google аккаунтом») запрашивает
  `GET /auth/methods` и показывает только включённые способы: форму «Email /
  Пароль», ссылку «Создать аккаунт» и кнопку «Войти через Google».
- Вход по паролю: `POST /auth/login` с `device_id` установки, именем устройства и
  платформой `android`; ответ — пара токенов, дальше всё как после входа через Google
  (хранение, обновление, смена аккаунта).
- Регистрация с телефона: `POST /auth/register`, затем вход теми же данными.
- Предупреждение на экране входа, если адрес сервера начинается с `http://`.
- Приложение доверяет системным **и пользовательским** CA и работает и по
  `http://`, и по `https://` (`network_security_config`), чтобы можно было
  установить свой корневой сертификат.
- Все новые тексты — в `strings` (ru и en), без хардкода.

## Capabilities

### New Capabilities
Нет.

### Modified Capabilities
- `server-account`: способы входа на экране, вход и регистрация по паролю,
  предупреждение о `http://`, доверие к пользовательским CA.

## Impact

- **android-wallet**: экран входа (Activity, макет), `sync/` (`AuthSession`/клиент
  входа), `AndroidManifest.xml` и `res/xml/network_security_config.xml`,
  `strings_sync.xml` (ru/en), `docs/ARCHITECTURE.md`.
- **google-auth** (`add-password-auth`): `GET /auth/methods`, `POST /auth/register`,
  `POST /auth/login`; контракт — `google-auth/openapi.yaml`.
- **wallet-gateway**: правок нет.
- **wallet-infra** (`add-tls-ingress`): TLS-прокси и выгрузка корня CA, который
  пользователь ставит на телефон вручную.
- Выкатывается после google-auth и TLS; старые клиенты не поддерживаются.
