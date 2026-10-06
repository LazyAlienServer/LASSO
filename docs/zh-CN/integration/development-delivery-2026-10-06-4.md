# 安全依赖修复开发交付 — 2026-10-06

- ID **0.1.0-dev.20261006.4**；[English](../../integration/development-delivery-2026-10-06-4.md)／[manifest](../../integration/development-delivery-2026-10-06-4.json)／[扫描方法与告警记录](../implementation/security-scan-2026-10-06.md)。
- 旧交付ID/JAR/manifest保历史；新本地辨识组合在`.3`上更新依赖/build/三锁。

## 变更与验证范围

| 组件 | 实际解析版本 |
| --- | --- |
| Boot / Framework | 4.1.1 / 7.0.9 |
| Security / Authorization Server | 7.1.1，不变 |
| Tomcat embed模块 | 11.0.26 |
| Jackson core / databind | 3.1.7 / 3.1.7，annotations2.21 |
| Bouncy Castle provider | 1.86 |
| pgJDBC / Log4j API+bridge | 42.7.13 / 2.25.5 |

新应用实际打包上述版本，三锁重生成覆盖runtime/test，并纳入Boot管理的相关更新。公开starter API、SSO V1–V14及参考撤销migration保持；依赖处置无需修改生产Java。

基线17不同CVE（三锁33条、解包JAR32条告警）；最终Trivy0.75.0识别234锁包记录和150解包JAR包记录，使用同日已记录DB，0报告漏洞，无忽略级别/公告。直接opaqueJAR `fs`未识别包，明确不计有效通过。Semgrep1.179.0扫描221路径、保留一条当前调用图误报redirect warning、0error。Gitleaks8.30.1活跃source/JAR/allhistory三路径100%redact各0匹配。它们是记录覆盖内工具结果，不是普遍“无漏洞/无秘密”保证。

## 构建与制品消费

从`las-sso-server/`，Java21/Docker运行：

```powershell
.\gradlew.bat dependencies --write-locks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:dependencies :las-sso-reference-bff:dependencies --write-locks --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.4" "-PlasSsoStarterVersion=0.1.0-dev.20261006.4" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.4" --no-daemon --console=plain
```

实际强制**13:41:00–13:45:20 Asia/Shanghai**：**486server+193starter+37referenceBFF=716**，0失败/错误/跳过，20tasks全执行。PostgreSQL实际运行，含序列化/授权和durable撤销；root/context HTTP使用升级Tomcat。两可执行JAR生成；已知fixture停止连接池警告保原范围。

starter只发布到隔离本地file库，独立native`.module`与POM-only Gradle项目无composite/继承锁编译新旧API，核验Boot4.1.1/Framework7.0.9/Security7.1.1/Log4j2.25.5。starter Java/resources不变，JAR字节hash相同是预期，版本和依赖metadata已改变。库metadata不保证消费应用自有BOM/锁/override选择已修Tomcat/Jackson；须核最终应用图，本次两个可执行样例已核。

## 来源

Base `8cee3ed62fd7c53664e8026b99664c73fae5f19d`、branch `codex/fix-sso-quality-review`，**加未提交工作区**；286source fingerprint `de9428231e852bd3c77453ec303018a2b9bd70ef0ba96ebbc209a29d89de3e8b`与`.3`同范围，manifest12artifact/script/migration/config摘要与3新锁摘要。单HEAD不能复现，路径相对`las-sso-server/`，不含docs/build/external秘密fixture。

| 制品 | SHA-256 |
| --- | --- |
| Server bootJAR | 2d6fdb9a86eeb54859ec031bfe417a8ab0ae7b3a2992eb4777b1fee5d49da94b |
| Reference BFF bootJAR | 7a49d0ff00cb105e7f4f43f42160a66fb4735c8b8fab8a3bf176fed19b0f8ad0 |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

默认SNAPSHOT，未重发旧ID。原TLS/browser/管理员/恢复验收保对应历史JAR，本轮验依赖/build/tests/scans/metadata，未用`.4`重复外部演练。扫描容器一次性且已无运行中fixture；私有临时report/cache/inventory保本机证据。无commit/push/托管签名发行/production deployDB/业务consumer实施。

## 后续

发布时公告refresh/最终图核验、生产image/OS/JDK/proxy扫描、完整runtime日志/外配置检查、认证DAST/全部UI/可访问性、正式DNS/cert续期/firewallCDN信任、容量monitor、实际consumer15m/epoch/revocation验收独立。签名已提交immutable发行与完整B1/customdurableSession/index/replay/keyrotation仍开。本交付只完成记录的活跃构建安全处置。
