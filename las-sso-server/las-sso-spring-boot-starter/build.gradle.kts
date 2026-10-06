plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.spring.dependency.management)
}

group = "org.las"
version = providers.gradleProperty("lasSsoStarterVersion").getOrElse("0.1.0-SNAPSHOT")

publishing {
    publications {
        create<MavenPublication>("starter") {
            from(components["java"])
            artifactId = "las-sso-spring-boot-starter"
            versionMapping {
                usage("java-api") { fromResolutionOf("runtimeClasspath") }
                usage("java-runtime") { fromResolutionResult() }
            }
        }
    }
    providers.gradleProperty("lasSsoPublishRepository").orNull?.let { repository ->
        repositories {
            maven {
                name = "LasSso"
                url = uri(repository)
            }
        }
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}") {
            // Local dependency management; the exported native platform below carries this into POM/.module.
            bomProperty("spring-security.version", libs.versions.spring.security.get())
        }
        // Test HTTP/JWKS conversion uses Jackson; keep its supported 3.1 patch family aligned.
        mavenBom("tools.jackson:jackson-bom:${libs.versions.jackson.get()}")
    }
}

dependencies {
    // Export the patch alignment in Gradle module metadata as well as the Maven POM.
    api(platform(libs.spring.security.bom))
    // POM-only consumers do not inherit this library's dependency-management constraints.
    // Declare the Security APIs we use directly so their transitive patch graph cannot fall back to Boot 7.1.0.
    api(libs.spring.security.config)
    api(libs.spring.security.oauth2.client)
    api(libs.spring.security.oauth2.jose)
    api(libs.spring.boot.starter.oauth2.client)
    api(libs.spring.boot.starter.security)
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    // Exercise Spring's real OAuth2 HTTP clients; mock-only tests did not need a JSON converter.
    testRuntimeOnly("tools.jackson.core:jackson-databind")
    testRuntimeOnly(libs.junit.platform.launcher)
}
