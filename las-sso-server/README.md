# LAS SSO build

[中文](../docs/zh-CN/README.md) / [English](../docs/en/README.md) · [Contributing](../CONTRIBUTING.md)

This directory is the only Java21 Gradle build. Root project is the SSO application; `las-sso-spring-boot-starter` is the library; `las-sso-reference-bff` is a runnable sample. Dependencies use the catalog/three locks, schema uses embedded Flyway migrations.

```sh
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
./gradlew build -PrequireDatabaseTests=true --no-daemon --console=plain
```

Windows uses gradlew.bat. Local Compose starts development PG only; production uses [deployment](../docs/en/deployment.md). Runtime routes/guards are documented in [API](../docs/en/api.md). Administrator maintenance requires exact artifacts and private inputs; see [operations](../docs/en/operations.md).
