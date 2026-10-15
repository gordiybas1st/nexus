# Personal Assistant (Android)

Менеджер задач на Kotlin + Jetpack Compose + Material 3.
Сейчас готов **Stage 1**: локальное приложение без синхронизации и ИИ.

## Что работает в Stage 1
- **Задачи:** список, поиск, фильтры (все, активные, выполненные, по тегу), приоритет, дедлайн (дата и время), повтор (RRULE), теги с цветом, подзадачи.
- **Жесты:** свайп вправо отмечает задачу выполненной, свайп влево удаляет (с «Отменить»).
- **Заметки:** сетка в 2 колонки, цвета, поиск, автосохранение (500 мс), долгое нажатие открывает цвет и удаление.
- **Оформление:** светлая, тёмная или системная тема, Material You на Android 12+.
- Русский и английский интерфейс.

Переключатель «Синхронизировать с Google Календарём» уже сохраняется в задаче, а заработает в Stage 2.

## Стек
Kotlin 1.9.22, AGP 8.2.2, Gradle 8.5, Compose BOM 2024.02.00, Room 2.6.1 (KSP), Hilt 2.50 (KSP), DataStore, Navigation Compose. minSdk 26, targetSdk 34.

## Сборка
### Через GitHub (без Android Studio)
Каждый push в `main`, затрагивающий папку `assistant/`, запускает workflow **Build Assistant APK**.
Готовый `app-debug.apk` лежит в разделе **Artifacts** запуска (`assistant-apk`).

### В Android Studio
1. File → Open → папка `assistant`.
2. Дождитесь Gradle Sync (JDK 17; Studio ставит его сама).
3. Run ▶ на телефоне или эмуляторе.

Файлов `gradlew` и `gradle-wrapper.jar` в репозитории нет: Android Studio скачивает Gradle 8.5 сама по `gradle/wrapper/gradle-wrapper.properties`.
Чтобы собирать из консоли, один раз выполните `gradle wrapper --gradle-version 8.5` в папке `assistant`.

## Структура
```
app/src/main/java/com/example/assistant/
├── AssistantApp.kt, MainActivity.kt
├── di/                 Hilt-модуль (БД, DAO, app scope)
├── data/local/         Room: AppDatabase, entity/, dao/, converter/
├── data/repository/    TaskRepository, NoteRepository
├── data/prefs/         UserPreferences (DataStore)
├── domain/model/       Task, Subtask, Tag, Note, Priority, RepeatOption, ThemeMode
└── ui/                 theme/, navigation/, components/, screens/{home, task_edit, notes, settings}
```

## Дальше
- Stage 2: вход через Google и синхронизация с Календарём (с инструкцией по Google Cloud Console).
- Stage 3: локальный ассистент-чат и голосовой ввод.
- Stage 4: Pomodoro, напоминания, вечерний обзор, виджет.
- Stage 5: статистика, достижения, гео-напоминания, экспорт/импорт, онбординг.
