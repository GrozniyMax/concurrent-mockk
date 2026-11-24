# concurrent-mockk

[![CI](https://github.com/GrozniyMax/concurrent-mockk/actions/workflows/ci.yml/badge.svg)](https://github.com/GrozniyMax/concurrent-mockk/actions/workflows/ci.yml)

Изоляция MockK для параллельных тестов JUnit 5. Пакет библиотеки — `com.grozniy`; в тестах используются стандартные импорты `io.mockk` и стандартный `MockKExtension`.

**Новый режим: `0.2.0-SNAPSHOT` в main.** Опубликованный [v0.1.0](https://github.com/GrozniyMax/concurrent-mockk/releases/tag/v0.1.0) содержит прежний API с `com.grozniy.mockk` и собственным расширением. Для описанной ниже интеграции используйте текущий исходный код или JitPack `main-SNAPSHOT`. Лицензия — [MIT](LICENSE).

## Что делает библиотека

- Автоматически открывает контекст до создания экземпляра теста: работают обычные поля `val x = mockk<T>()`, `@MockK`, `@RelaxedMockK`, `@SpyK` и `@InjectMockKs`.
- Учитывает обычные, дочерние и статические моки каждого теста. Настройка, история вызовов и массовые проверки не смешиваются с соседними тестами.
- Ограничивает `clearAllMocks()`, `confirmVerified()` и `checkUnnecessaryStub()` текущим тестом, включая вызовы из стандартного `MockKExtension`.
- Изолирует внутренний кэш внедрения `MockKExtension`, чтобы `@InjectMockKs` не получал параметр соседнего параллельного теста.
- Поддерживает параллельное мокирование одного Java static-метода или Kotlin top-level-функции с разными ответами. `unmockkStatic()` и `unmockkAll()` отменяют только моки текущего теста.

## Быстрый старт

Нужен JDK 17. В папке проекта:

```sh
./gradlew clean build
./gradlew -p examples/basic test
```

На Windows используйте `gradlew.bat`. Gradle Wrapper загружает Gradle 8.12.1.

## Подключение без изменения тестовых аннотаций

Для локальной разработки добавьте в `settings.gradle.kts` потребителя:

```kotlin
includeBuild("../concurrent-mockk")
```

И в его `build.gradle.kts`:

```kotlin
repositories { mavenCentral() }
dependencies {
    testImplementation("com.grozniy:concurrent-mockk:0.2.0-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}
tasks.test { useJUnitPlatform() }
```

В `src/test/resources/junit-platform.properties` включите автообнаружение и параллельный запуск:

```properties
junit.jupiter.extensions.autodetection.enabled=true
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=concurrent
junit.jupiter.execution.parallel.mode.classes.default=concurrent
junit.jupiter.execution.parallel.config.strategy=fixed
junit.jupiter.execution.parallel.config.fixed.parallelism=4
```

JAR уже содержит ServiceLoader-регистрацию. Добавлять `@ExtendWith(MockKIsolationExtension::class)` или менять импорты `mockk` не нужно. Существующий стандартный `@ExtendWith(MockKExtension::class)` сохраняется, если используются аннотации MockK.

```kotlin
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

interface Repository { fun find(): String }

@ExtendWith(MockKExtension::class)
class RepositoryTest {
    @MockK lateinit var repository: Repository
    private val local = mockk<Repository>()

    @Test fun example() {
        every { repository.find() } returns "annotated"
        every { local.find() } returns "local"
        assertEquals("annotated", repository.find())
        assertEquals("local", local.find())
        verify(exactly = 1) { repository.find(); local.find() }
    }
}
```

Рабочий [пример](examples/basic/src/test/kotlin/com/grozniy/example/GreetingServiceTest.kt) дополнительно мокирует top-level-функцию через стандартный `mockkStatic`.

## Статические моки

Используйте обычные `mockkStatic(JavaClass::class)` или `mockkStatic(::topLevelFunction)`, затем `every` и `verify`. Класс инструментируется один раз, а вызов направляется к ответам текущего теста. Настройки, проверки, `clearStaticMockk`, `unmockkStatic` и блоковая форма `mockkStatic { ... }` работают с его состоянием.

После освобождения последнего контекста статический прокси снимается. Поток без контекста видит оригинальную реализацию даже тогда, когда другие тесты мокируют метод. Уже активный статический мок, созданный вне изолированного теста, нельзя одновременно использовать для того же класса в изолированном режиме: создание завершается понятной ошибкой.

## Совместимость и границы

Интеграция инструментирует внутренние классы MockK через Byte Buddy. Поддерживаемая версия **MockK 1.14.6** закреплена строгим ограничением Gradle; JUnit **5.12.2**, JDK **17+**. В Maven не переопределяйте MockK на другую версию. Если JVM запрещает динамическое подключение агента, его необходимо разрешить; на новых JVM может потребоваться `-XX:+EnableDynamicAgentLoading`.

- Требуется `PER_METHOD`. `PER_CLASS` отклоняется до создания общих моков.
- Один тест и его lifecycle работают в одном потоке. Передача контекста в корутины/Executor и preemptive-таймауты не поддерживаются.
- `@TestFactory` с динамическими тестами отклоняется: JUnit не предоставляет отдельный lifecycle каждого такого теста.
- `mockkObject` и `mockkConstructor` отклоняются в изолированном контексте. Изоляция этих глобальных механизмов пока не реализована.
- Моки из `@BeforeAll`, общие моки между тестами и конкурентное смешивание изолированного и обычного static mocking не поддерживаются.
- За пределами контекста сохраняется обычное поведение MockK. Глобальная очистка вне интеграции может затронуть обычные моки работающего теста.

Прежние `MockContext`, `com.grozniy.mockk` и `IsolatedMockKExtension` сохранены для совместимости. В новом режиме они не требуются.

## Установка через JitPack

Текущая ветка доступна как изменяемый snapshot; для воспроизводимых сборок используйте SHA коммита вместо `main-SNAPSHOT`:

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
dependencies {
    testImplementation("com.github.GrozniyMax:concurrent-mockk:main-SNAPSHOT")
}
```

Автообнаружение JUnit включается так же, как показано выше. Инструкция для [Gradle, Maven и релизов](docs/releases.md). Версия `v0.1.0` новый режим не содержит.

## Проверки и устройство

Интеграционные тесты запускают настоящий JUnit Launcher с двумя потоками. Барьеры и latch подтверждают перекрытие выполнения, разные ответы одного static-метода и сохранность соседнего теста после очистки. Проверяются constructor/parameter/field injection, дочерние моки, массовые проверки, ошибки конструктора/setup/теста и вложенный запуск JUnit. `Thread.sleep` не используется.

- [Архитектура](docs/architecture.md).
- [Разработка](CONTRIBUTING.md).
- [История изменений](CHANGELOG.md).
- [Пример-потребитель](examples/basic).

Отчёт: `build/reports/tests/test/index.html`. CI проверяет сборку, пример и получение Maven-артефакта без composite build.
