# Разработка

Нужен JDK 17. Используйте Gradle Wrapper из репозитория.

## Проверка изменений

```sh
./gradlew clean build
./gradlew -p examples/basic test
./gradlew publishMavenJavaPublicationToLocalPreviewRepository
```

Последняя команда создаёт Maven-структуру в `build/repository/` без отправки во внешний репозиторий. Там можно проверить JAR, sources JAR, POM и Gradle metadata. Для использования из другого локального проекта доступна команда `./gradlew publishToMavenLocal`.

Отчёт библиотеки: `build/reports/tests/test/index.html`. Отчёт примера: `examples/basic/build/reports/tests/test/index.html`.

## Соглашения

- Публичный пакет — `com.grozniy`; отступы задаёт `.editorconfig`.
- Изменение поведения сопровождайте тестом сценария и записью в `CHANGELOG.md`.
- Параллельные проверки координируйте барьерами или latch с таймаутом. Не используйте ожидания через `Thread.sleep`.
- Сохраняйте изоляцию очистки: глобальные операции MockK не должны попадать в lifecycle расширения.
- Держите пример и README в соответствии с реальным API.

Для сообщения об ошибке приложите минимальный тест, версии Java/MockK/JUnit, настройки параллельности и вывод Gradle. В описании изменения объясните наблюдаемое поведение и выполненные проверки.

## Релиз

Версия разработки хранится в gradle.properties. Релизный workflow получает стабильную версию из тега vMAJOR.MINOR.PATCH; вручную менять версию сборки перед каждым тегом не нужно. Используйте -PreleaseVersion=0.1.0 для локальной проверки. Пример принимает ту же версию через Gradle property.

Порядок выпуска, состав файлов и подключение через Gradle/Maven: [docs/releases.md](docs/releases.md). В CI отдельно проверяется получение опубликованного Maven-артефакта без includeBuild.
