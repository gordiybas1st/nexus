# Personal Assistant (Android)

Менеджер задач на Kotlin + Jetpack Compose + Material 3.

| Этап | Что входит | Статус |
|---|---|---|
| 1 | Задачи, заметки, теги, подзадачи, тема | ✅ |
| 2 | Вход через Google и синхронизация с Google Календарём | ✅ |
| 3 | Локальный ассистент-чат, голосовой ввод | — |
| 4 | Pomodoro, напоминания, вечерний обзор, виджет | — |
| 5 | Статистика, достижения, гео-напоминания, экспорт, онбординг | — |

## Что работает
- **Задачи:** список, поиск, фильтры (все, активные, выполненные, по тегу), приоритет, дедлайн, повтор (RRULE), теги, подзадачи. Свайп вправо отмечает задачу выполненной, свайп влево удаляет её (с «Отменить»).
- **Заметки:** сетка в 2 колонки, цвета, поиск, автосохранение (500 мс).
- **Google Календарь:** задача с включённым «Синхронизировать с Google Календарём» становится событием в основном календаре. Её изменение, выполнение (✅ в названии) и удаление переносятся туда же. Без сети изменения копятся в очереди и уходят, когда сеть появится.
- Значок облака в шапке списка задач показывает состояние синхронизации. Нажатие запускает синхронизацию, а при ошибке открывает настройки.
- Тема: светлая, тёмная или системная, Material You на Android 12+. Интерфейс на русском и английском.

## Сборка
### Через GitHub (без Android Studio)
Каждый push в `main`, затрагивающий `assistant/`, запускает workflow **Build Assistant APK**.
Готовый `app-debug.apk` лежит в **Artifacts** запуска (`assistant-apk`).

### В Android Studio
File → Open → папка `assistant`, дождитесь Gradle Sync (JDK 17), нажмите Run ▶.
Файлов `gradlew` и `gradle-wrapper.jar` в репозитории нет: Studio сама скачает Gradle 8.5.
Для сборки из консоли один раз выполните `gradle wrapper --gradle-version 8.5`.

---

## Настройка Google Cloud (нужна для синхронизации)

Без этой настройки приложение работает, но кнопка «Подключить Google Календарь» выдаст ошибку.

### 1. Проект и Calendar API
1. Откройте https://console.cloud.google.com/ и создайте проект (например, `personal-assistant`).
2. **APIs & Services → Library**, найдите **Google Calendar API** и нажмите **Enable**.

### 2. OAuth consent screen
1. **APIs & Services → OAuth consent screen** (в новом интерфейсе: **Google Auth Platform → Branding / Audience / Data access**).
2. User type: **External**. Заполните название приложения и email поддержки.
3. **Scopes / Data access → Add or remove scopes**, добавьте:
   - `https://www.googleapis.com/auth/calendar.events`
   - `openid`
   - `https://www.googleapis.com/auth/userinfo.email`
4. **Test users / Audience**: добавьте свой email и email друзей. Пока приложение в режиме *Testing*, войти могут только они (до 100 человек). Публикация и проверка Google не нужны.

### 3. OAuth Client ID №1: Android (обязателен)
**Credentials → Create credentials → OAuth client ID → Android**
- Package name: `com.example.assistant`
- SHA-1: `92:81:EC:C3:94:A3:A2:5B:68:DF:8F:25:DF:4E:A4:51:AC:3A:27:13`

Этот SHA-1 у ключа `app/debug.keystore`, который лежит в репозитории. Им подписываются все debug-сборки (и в GitHub, и в Studio), поэтому SHA-1 не меняется.
Проверить: `gradle signingReport` в папке `assistant` (вариант `debug`).

> Ключ `debug.keystore` годится только для личной debug-сборки. Для Google Play нужен отдельный секретный release-ключ и ещё один Android-клиент с его SHA-1.

### 4. OAuth Client ID №2: Web application (рекомендуется)
**Create credentials → OAuth client ID → Web application** (URI указывать не нужно).
Скопируйте **Client ID** вида `1234-abc.apps.googleusercontent.com`. Это `serverClientId` для Credential Manager.

Куда его вставить:
- **GitHub:** Settings репозитория → Secrets and variables → Actions → **New repository secret**, имя `GOOGLE_WEB_CLIENT_ID`.
- **Локально:** строка `GOOGLE_WEB_CLIENT_ID=1234-abc.apps.googleusercontent.com` в `assistant/local.properties`.

Без Web Client ID вход всё равно работает: шаг Credential Manager пропускается, и аккаунт выбирается на экране разрешений Google.

### 5. Подключение в приложении
Настройки → **Подключить Google Календарь** → выбрать аккаунт → разрешить доступ к календарю.
Предупреждение «Google hasn't verified this app» нормально для режима Testing: нажмите **Continue**.

---

## Как устроена синхронизация
- **Авторизация** (`data/remote/GoogleAuthManager.kt`). Credential Manager (`GetGoogleIdOption`) отвечает за вход и email. AuthorizationClient (`Identity.getAuthorizationClient`) выдаёт **access token** со scope `calendar.events`; ID-токен для Calendar API не подходит.
- **Обновление токена.** Access token живёт час. Приложение получает новый повторным `authorize()`: после первого согласия Google выдаёт его молча. Refresh-токен на телефоне не хранится, потому что для него нужен client secret на сервере. При 401 токен сбрасывается (`GoogleAuthUtil.clearToken`) и запрашивается заново. Если не помогло, в настройках просит «Выдать доступ снова».
- **Очередь** (`sync/SyncQueue.kt`, таблица `sync_queue`). Изменения сначала пишутся в Room и в очередь (CREATE/UPDATE/DELETE, FIFO), повторяющиеся операции схлопываются. Очередь разбирает `CalendarSyncWorker` (WorkManager, нужна сеть, экспоненциальный backoff от 30 с), а раз в 6 часов идёт полная сверка.
- **Конфликты:** всегда побеждает телефон. Если событие изменили или удалили в календаре, при сверке оно перезаписывается или создаётся заново, а запись попадает в «Журнал синхронизации» (таблица `sync_conflicts`). События приложения помечены приватным свойством `source=personal_assistant`, чужие события не трогаются.
- **API.** Calendar v3 REST вызывается напрямую (`CalendarApiService.kt`, `HttpURLConnection` + `org.json`), без google-api-client: тот же протокол, но без лишних зависимостей и конфликтов META-INF.

## Troubleshooting
| Симптом | Причина и что делать |
|---|---|
| «Не удалось войти» / `[28444] Developer console is not set up correctly` | Не совпадает package name или SHA-1 у Android-клиента, либо указан не тот Web Client ID. Проверьте шаги 3 и 4. |
| `ApiException 10` (DEVELOPER_ERROR) | То же: SHA-1 или package name. Изменения в Cloud Console применяются до 5–10 минут. |
| `ApiException 12501` / «Доступ не выдан» | Пользователь закрыл окно согласия. |
| «Access blocked: app has not completed verification» | Email не добавлен в Test users (шаг 2.4). |
| 401 повторяется | Доступ отозван в https://myaccount.google.com/permissions. Нажмите «Выдать доступ снова». |
| 403 Forbidden | Не включён Google Calendar API (шаг 1) или scope `calendar.events` не добавлен на consent screen. |
| 403 `rateLimitExceeded` / 429 | Лимит запросов. Приложение само повторит позже. |
| События не появляются | У задачи должен быть включён переключатель синхронизации. Проверьте значок облака: число означает, что изменения ждут сети. Событие без дедлайна ставится через час после создания задачи. Смотрите основной (primary) календарь. |
| 410 Gone | Событие уже удалено. Для удаления это считается успехом, при обновлении событие создаётся заново. |

## Структура
```
app/src/main/java/com/example/assistant/
├── AssistantApp.kt, MainActivity.kt
├── di/                 Hilt-модуль (БД, DAO, app scope)
├── data/local/         Room: AppDatabase (v2 + миграция), entity/, dao/, converter/
├── data/remote/        GoogleAuthManager, CalendarApiService
├── data/repository/    TaskRepository (пишет в очередь синхронизации), NoteRepository
├── data/prefs/         UserPreferences (DataStore: тема, аккаунт, статус синхронизации)
├── domain/model/       Task, Subtask, Tag, Note, Priority, RepeatOption, ThemeMode
├── sync/               CalendarSyncManager, SyncQueueWriter/Processor, CalendarSyncWorker, SyncScheduler
└── ui/                 theme/, navigation/, components/, screens/{home, task_edit, notes, settings}
```
