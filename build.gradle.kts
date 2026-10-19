plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
    // Сборка в GraalVM Native Image: ./gradlew nativeCompile (нужно 6–8 ГБ памяти — собираем на сервере, см. README-native.md)
    id("org.graalvm.buildtools.native") version "1.1.12"
}

val ktor = "3.5.2"

application { mainClass.set("ru.tpu.ml.ApplicationKt") }
kotlin { jvmToolchain(21) }
repositories { mavenCentral() }

dependencies {
    // сервер: движок CIO (без Netty) — проще для Native Image и легче по памяти
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-server-auth:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-call-logging:$ktor")
    implementation("io.ktor:ktor-server-call-id:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    // клиент — чтобы ходить во внешнее API модели
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    // логи
    implementation("ch.qos.logback:logback-classic:1.5.12")
    // тесты
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("io.ktor:ktor-client-mock:$ktor")
    testImplementation(kotlin("test"))
}

graalvmNative {
    // native-image берётся из GRAALVM_HOME / JAVA_HOME (образ сборки — GraalVM), а не из toolchain Kotlin (Temurin 21)
    toolchainDetection.set(false)
    // Готовые метаданные достижимости для популярных библиотек (logback и др.) из репозитория GraalVM
    metadataRepository { enabled.set(true) }
    binaries {
        named("main") {
            imageName.set("ml-server")
            mainClass.set("ru.tpu.ml.ApplicationKt")
            fallback.set(false)
            // Свои метаданные (рефлексия kotlinx.serialization, ресурсы) — в src/main/resources/META-INF/native-image,
            // собраны агентом трассировки: см. README-native.md
            buildArgs.addAll(
                "--initialize-at-build-time=ch.qos.logback,org.slf4j,io.ktor,kotlin,kotlinx.coroutines,kotlinx.serialization,kotlinx.io",
                "--enable-https",
                "-H:+InstallExitHandlers",
                "-H:+ReportExceptionStackTraces",
                // Переносимый бинарник: процессор сервера заранее неизвестен
                "-march=compatibility",
                // Потолок кучи по умолчанию под лимит контейнера 128 МБ; переопределяется -Xmx при запуске
                "-R:MaxHeapSize=80m",
            )
        }
    }
}
