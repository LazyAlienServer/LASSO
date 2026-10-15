# LAS SSO Spring Boot Starter

Java21 Servlet BFF library. Browser receives local Cookie/safe session snapshot; backend owns Tokens. Public integration and storage hooks: [中文](../../docs/zh-CN/integration.md) / [English](../../docs/en/integration.md).

From the parent build:

```sh
./gradlew :las-sso-spring-boot-starter:test --no-daemon --console=plain
```

Exact-version publication is part of [release tooling](../../release/README.md), with JAR/POM/native metadata. Initial default session/index/replay/refresh is one JVM; restart ends business login. Consumer permissions, persistent adapters and eligibility/password gates remain consumer-owned. [Reference BFF](../las-sso-reference-bff/README.md) demonstrates default and application-owned chains.
