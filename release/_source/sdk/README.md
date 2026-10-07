# Starter SDK — {{VERSION}}

Coordinate: `org.las:las-sso-spring-boot-starter:{{VERSION}}`. `maven/` is a versioned file Maven repository with JAR/POM/Gradle module metadata; no global Maven Local is required. Application repositories/dependency management must explicitly include this trusted path and review their final graph.

Gradle Kotlin DSL example, replace the repository URI with your extracted package path:

```kotlin
repositories {
    maven {
        url = uri("file:///absolute/path/to/package/sdk/maven")
        content { includeGroup("org.las") }
    }
    mavenCentral()
}
dependencies {
    implementation("org.las:las-sso-spring-boot-starter:{{VERSION}}")
}
```

Use Spring Boot Web/OIDC JSON converters and JDK21 in the consuming application. Module metadata/POM preserve alignment; consumer locks/overrides are authoritative. File distribution is not a hosted immutable/signature service. [接入手册](../docs/zh-CN/external-integration.md) / [Integration guide](../docs/en/external-integration.md) explain independent client registration, state/nonce/PKCE, endpoints, storage interfaces and single-instance limits.
