# Релизы и установка

Подготовлены два канала: GitHub Releases для скачивания файлов и JitPack для подключения зависимости из Gradle/Maven. Maven Central не настроен. До публикации публичного репозитория и тега удалённой зависимости ещё нет.

## Версии

Версия разработки задаётся в gradle.properties: projectVersion=0.1.0-SNAPSHOT. Для стабильного релиза используйте тег vMAJOR.MINOR.PATCH. Workflow удаляет префикс v и передаёт версию в Gradle через -PreleaseVersion. Исходники Kotlin остаются в пакете com.grozniy.

На JitPack группа и версия берутся из его переменных GROUP и VERSION. Для репозитория GrozniyMax/concurrent-mockk и тега v0.1.0 координаты будут com.github.GrozniyMax:concurrent-mockk:v0.1.0. Локальные публикации сохраняют группу com.grozniy.

## Проверка до публикации

```sh
./gradlew clean build releaseArtifacts publishMavenJavaPublicationToLocalPreviewRepository -PreleaseVersion=0.1.0
./gradlew -p examples/basic clean test -PusePublishedArtifact -PreleaseVersion=0.1.0
```

В build/release появятся JAR, sources JAR, POM и SHA256SUMS. Локальный Maven-репозиторий находится в build/repository. Второй запуск подключает артефакт из этого репозитория без composite build и проверяет транзитивные зависимости.

## Выпуск

1. Опубликуйте этот проект в публичном GitHub-репозитории и включите GitHub Actions.
2. Обновите CHANGELOG.md и выполните проверки выше.
3. Закоммитьте изменения, отправьте ветку и создайте новый тег:

```sh
git tag -a v0.1.0 -m "Release 0.1.0"
git push origin v0.1.0
```

Workflow .github/workflows/release.yml проверяет тег, собирает библиотеку, выполняет её тесты и тесты потребителя, проверяет контрольные суммы и создаёт GitHub Release с артефактами. Используется стандартный GITHUB_TOKEN с contents: write; отдельный секрет для GitHub Releases не нужен. Workflow ожидает существующий тег (--verify-tag). Уже выпущенные теги не передвигайте: исправления выпускайте новой версией.

JitPack получает код по опубликованному тегу и выполняет jitpack.yml на JDK 17 при первом запросе зависимости. Внешняя сборка может занять время. Готовность проверяется на https://jitpack.io/#GrozniyMax/concurrent-mockk; лог версии — https://jitpack.io/com/github/GrozniyMax/concurrent-mockk/v0.1.0/build.log.

## Gradle Kotlin DSL

После публикации тега в указанном репозитории:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://jitpack.io")
        content { includeGroup("com.github.GrozniyMax") }
    }
}

dependencies {
    testImplementation("com.github.GrozniyMax:concurrent-mockk:v0.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }
```

Если репозитории централизованы, перенесите блок repositories в dependencyResolutionManagement в settings.gradle.kts.

## Maven

Добавьте репозиторий в pom.xml:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

Добавьте зависимость в существующий блок dependencies:

```xml
<dependency>
    <groupId>com.github.GrozniyMax</groupId>
    <artifactId>concurrent-mockk</artifactId>
    <version>v0.1.0</version>
    <scope>test</scope>
</dependency>
```

В Maven-проекте также должны быть настроены компиляция Kotlin-тестов и запуск JUnit 5. Зависимость подключает библиотеку; её импорт и использование одинаковы для Gradle и Maven.

Если репозиторий будет создан под другим владельцем или именем, замените координаты и ссылки в этой инструкции. Никогда не помещайте токены в build-файлы или README.

## Источники

- [JitPack: установка зависимостей](https://docs.jitpack.io/) — формат координат и сборка по тегу.
- [JitPack: сборка Gradle-проектов](https://docs.jitpack.io/building/) — maven-publish, переменные окружения и JDK.
- [GitHub CLI: создание релиза](https://cli.github.com/manual/gh_release_create).
