plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    java
}

group = "org.las"
version = providers.gradleProperty("lasSsoServerVersion").getOrElse("0.1.0-SNAPSHOT")
// Security scan remediation: Boot 4.1.1's Tomcat 11.0.24 still predates later vendor fixes.
extra["tomcat.version"] = libs.versions.tomcat.get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencyManagement {
    imports {
        // D1 / CVE-2026-47877: align Security modules on the fixed patch while retaining Boot's baseline.
        mavenBom("org.springframework.security:spring-security-bom:${libs.versions.spring.security.get()}")
        mavenBom("tools.jackson:jackson-bom:${libs.versions.jackson.get()}")
    }
}

dependencies {
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.registry.prometheus)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.session.jdbc)
    implementation(libs.spring.boot.starter.oauth2.authorization.server)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.flyway.postgresql)
    implementation(libs.bouncycastle.provider)
    implementation(libs.altcha)
    implementation(libs.altcha.widget)
    runtimeOnly(libs.json)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}
