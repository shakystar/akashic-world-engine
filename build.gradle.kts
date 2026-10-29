plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories { mavenCentral() }

val ktorVersion = "3.6.0"
val flywayVersion = "11.20.3"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson-jvm:$ktorVersion")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")
    implementation("com.auth0:java-jwt:4.5.0")
    implementation("com.auth0:jwks-rsa:0.23.0")
    implementation("ch.qos.logback:logback-classic:1.5.20")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

kotlin { jvmToolchain(21) }
application { mainClass.set("app.akashic.MainKt") }
tasks.test { useJUnitPlatform() }
