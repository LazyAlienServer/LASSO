rootProject.name = "las-sso-server"

include("las-sso-spring-boot-starter")
include("las-sso-reference-bff")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
