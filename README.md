# CRACKCORE — Android prototype v0.1

Kotlin + Canvas (hardware canvas), без игровых движков и внешних библиотек.
Музыка синтезируется процедурно (AudioTrack), 180→222 BPM, 64 такта (~76 с).

## Загрузка в GitHub
1. Создай пустой репозиторий на github.com.
2. Загрузи ВСЁ содержимое этой папки в корень репозитория
   (включая скрытую папку `.github`). Через веб: Add file → Upload files.
   Лучше через git: `git init && git add . && git commit -m init && git remote add origin <URL> && git push -u origin main`.

## Сборка APK
- Push запускает workflow автоматически; либо вкладка **Actions → Android Build → Run workflow**.
- Готовый файл: **Actions → (запуск) → Artifacts → CrackCore-debug-apk** (внутри `app-debug.apk`).
- Путь внутри сборки: `app/build/outputs/apk/debug/app-debug.apk`.

Gradle берётся из workflow (gradle 8.7), `gradlew` в репозитории нет.
Для Android Studio выполни один раз `gradle wrapper`.
