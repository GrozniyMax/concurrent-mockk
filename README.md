# concurrent-mockk

[![CI](https://github.com/GrozniyMax/concurrent-mockk/actions/workflows/ci.yml/badge.svg)](https://github.com/GrozniyMax/concurrent-mockk/actions/workflows/ci.yml)

Небольшая Kotlin/JVM-библиотека для изоляции моков при параллельном запуске тестов JUnit 5. Пакет: `com.grozniy`.

Тесты используют `mockk<T>()`, `every` и `verify`. Расширение самостоятельно создаёт контекст теста, регистрирует моки и очищает их после завершения. Передавать контекст параметром или закрывать его вручную не требуется.

**Релиз:** [v0.1.0](https://github.com/GrozniyMax/concurrent-mockk/releases/tag/v0.1.0). Версия разработки: `0.1.0-SNAPSHOT`. Артефакт пока не опубликован в Maven Central. **Лицензия:** [MIT](LICENSE).

## Задача проекта

При параллельном запуске тестов глобальная очистка моков может затронуть ещё выполняющийся тест. Проект исследует управление жизненным циклом моков: каждый тест владеет своими экземплярами, а очистка работает только с ними.

Внутри используются JUnit ExtensionContext.Store, ThreadLocal для доступа к текущему контексту и синхронизация реестра. Это обёртка над MockK; она не меняет его внутренности и не изолирует глобальные object/static-моки.

## Быстрый старт

Нужен установленный JDK 17. Gradle 8.12.1 загружается Wrapper-скриптом. Из корня проекта:

```sh
./gradlew clean build
./gradlew -p examples/basic test
```

На Windows используйте `gradlew.bat`. Библиотека и исходники появятся в `build/libs/`, отчёт тестов — в `build/reports/tests/test/index.html`.

## Релизы и подключение через Gradle/Maven

Добавлен workflow релизов по тегам `vMAJOR.MINOR.PATCH`: JAR, исходники, POM и SHA-256. Для удалённого подключения подготовлен JitPack; [публичный репозиторий](https://github.com/GrozniyMax/concurrent-mockk) создан, релизные файлы доступны в [GitHub Releases](https://github.com/GrozniyMax/concurrent-mockk/releases). Примеры Gradle и Maven, команды выпуска и проверки описаны в [инструкции по релизам](docs/releases.md).

## Локальное подключение

Разместите библиотеку рядом со своим проектом и добавьте в его `settings.gradle.kts`:

```kotlin
includeBuild("../concurrent-mockk")
```

В `build.gradle.kts` проекта-потребителя:

```kotlin
repositories { mavenCentral() }

dependencies {
    testImplementation("com.grozniy:concurrent-mockk:0.1.0-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }
```

Рабочий пример с composite build: [examples/basic](examples/basic). Альтернатива — выполнить `./gradlew publishToMavenLocal` и использовать `mavenLocal()` в проекте-потребителе.

## Использование без явного контекста

```kotlin
import com.grozniy.IsolatedMockKExtension
import com.grozniy.mockk
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

interface Repository { fun find(): String }

@ExtendWith(IsolatedMockKExtension::class)
class RepositoryTest {
    @Test
    fun example() {
        val repository = mockk<Repository>(name = "repository") {
            every { find() } returns "result"
        }
        assertEquals("result", repository.find())
        verify(exactly = 1) { repository.find() }
    }
}
```

Используйте **`com.grozniy.mockk` вместо `io.mockk.mockk`**: оригинальная функция не регистрирует моки в этом расширении. `every` и `verify` остаются из MockK. Поддерживаются параметры `name`, `relaxed`, `relaxUnitFun` и блок настройки.

Для полей объявляйте `lateinit var` и создавайте моки в `@BeforeEach`, как в [примере](examples/basic/src/test/kotlin/com/grozniy/example/GreetingServiceTest.kt). Стандартный `MockKExtension` одновременно подключать не нужно. Явный API `MockContext` сохранён для ручного управления, но в обычном тесте не нужен.

## Параллельный запуск

В проекте-потребителе создайте `src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=concurrent
junit.jupiter.execution.parallel.mode.classes.default=concurrent
junit.jupiter.execution.parallel.config.strategy=fixed
junit.jupiter.execution.parallel.config.fixed.parallelism=4
```

Используйте стандартный жизненный цикл `PER_METHOD` и отдельные моки каждого теста. Разные тесты могут работать одновременно; каждый отдельный тест и его callbacks должны оставаться в одном потоке.

## Гарантии и ограничения

| Поддерживается | Ограничение |
| --- | --- |
| Отдельный контекст каждого запуска теста | Общие моки между тестами не изолируются |
| Создание в тесте, `@BeforeEach`, `@AfterEach` | В инициализаторе поля, конструкторе, `@BeforeAll` и вне теста будет `IllegalStateException` |
| Очистка после ошибки теста или настройки мока | Глобальная очистка из стороннего кода может затронуть соседние тесты |
| Повторное использование потока и вложенный запуск JUnit | Перенос в корутины и дочерние потоки не поддерживается |
| Обычные экземпляры моков через `com.grozniy.mockk` | Нет автоматической обработки `@MockK`, object/static/constructor mocking и spy API |

Очистка сбрасывает ответы и историю вызовов через `clearMocks` только у собственных моков; `clearAllMocks` и `unmockkAll` не вызываются. Она освобождает ссылки реестра проекта, но не обещает удаления внутренних ссылок или отмены инструментации MockK. Ошибка очистки не скрывает исходную ошибку теста.

Таймауты с переключением потока (`assertTimeoutPreemptively`, `@Timeout` с `SEPARATE_THREAD`) не поддерживаются.

## Проверки и устройство проекта

Интеграционные тесты запускают настоящий JUnit Launcher с двумя рабочими потоками. Барьер подтверждает перекрытие выполнения, а второй тест проверяет свои ответы и историю после завершения и очистки первого. Все ожидания ограничены таймаутами; `Thread.sleep` не используется.

Также проверяются ошибки настройки и lifecycle, очистка после падения теста, повторное закрытие, повторные запуски, переиспользование потока и восстановление контекста после вложенного запуска JUnit.

- `src/main/kotlin/com/grozniy` — библиотека.
- `src/test/kotlin/com/grozniy` — модульные и интеграционные проверки.
- `examples/basic` — отдельный проект-потребитель с параллельными тестами.
- `.github/workflows/ci.yml` — сборка, тесты библиотеки и примера, проверка публикации.
- [Архитектура](docs/architecture.md) — жизненный цикл и принятые решения.
- [Разработка](CONTRIBUTING.md) — команды проверки и требования к изменениям.
- [История изменений](CHANGELOG.md).

## Следующие шаги

- Рассмотреть поддержку аннотированных полей `@MockK`.
- Отдельно исследовать перенос контекста между потоками и корутинами.

Эти возможности пока не реализованы. Проект использует Kotlin 2.1.20, MockK 1.14.6 и JUnit 5.12.2.
