plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

val ktor = "3.0.3"

application { mainClass.set("ru.tpu.ml.ApplicationKt") }
kotlin { jvmToolchain(21) }
repositories { mavenCentral() }

dependencies {
    // сервер
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
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