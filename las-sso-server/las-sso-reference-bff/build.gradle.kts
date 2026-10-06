plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    java
}

group = "org.las"
version = providers.gradleProperty("lasSsoServerVersion").getOrElse("0.1.0-SNAPSHOT")
extra["tomcat.version"] = libs.versions.tomcat.get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.security:spring-security-bom:${libs.versions.spring.security.get()}")
        mavenBom("tools.jackson:jackson-bom:${libs.versions.jackson.get()}")
    }
}

dependencies {
    implementation(project(":las-sso-spring-boot-starter"))
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.web)
    // Optional reference-only durable-revocation profile; no JDBC/Flyway Boot auto-configuration.
    implementation("org.springframework:spring-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}
