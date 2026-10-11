import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    java
    alias(libs.plugins.spotless)
}

group = "org.las"
version = providers.gradleProperty("lasSsoServerVersion").getOrElse("0.1.0-SNAPSHOT")
// Security scan remediation: Boot 4.1.1's Tomcat 11.0.24 still predates later vendor fixes.
extra["tomcat.version"] = libs.versions.tomcat.get()
val javaFormatterVersion = libs.versions.google.java.format.get()

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
    apply(plugin = "com.diffplug.spotless")

    dependencyLocking {
        lockAllConfigurations()
    }

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(21)
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release = 21
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            val databaseRequired = providers.gradleProperty("requireDatabaseTests")
                .map(String::toBoolean).getOrElse(false)
            inputs.property("requireDatabaseTests", databaseRequired)
            // Only the canonical CI/release run requires database coverage. Local focused runs may skip Docker tests.
            if (databaseRequired) {
                doLast {
                    val parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    }
                    val skipped = reports.junitXml.outputLocation.get().asFile
                        .walkTopDown().filter { it.name.startsWith("TEST-") && it.extension == "xml" }
                        .sumOf { report ->
                            parser.newDocumentBuilder().parse(report).documentElement
                                .getAttribute("skipped").toLong()
                        }
                    if (skipped > 0) {
                        throw GradleException("${project.path}: database-required verification has $skipped skipped tests")
                    }
                }
            }
        }
    }

    extensions.configure<SpotlessExtension> {
        java {
            target("src/*/java/**/*.java")
            googleJavaFormat(javaFormatterVersion).aosp().reflowLongStrings(false)
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

tasks.named<Test>("test") {
    // The API test reads the shipping document, so documentation edits invalidate cached tests.
    inputs.file(layout.projectDirectory.file("../release/_source/api/openapi.json"))
}
