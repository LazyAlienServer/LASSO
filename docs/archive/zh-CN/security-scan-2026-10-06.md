# 历史安全扫描与依赖修复 — 2026-10-06

- [English](../en/security-scan-2026-10-06.md)／[交付.20261006.4](https://github.com/LazyAlienServer/LASSO/blob/8390243505b781c25447fa4e125532f421f56b76/docs/zh-CN/integration/development-delivery-2026-10-06-4.md)／[制品与源码manifest](../releases/development-delivery-2026-10-06-4.json)。
- 范围：活跃SSO服务端、starter与参考BFF。基线为已辨识`.20261006.3`，修复形成新`.20261006.4`组合；原D1同意页补丁不能证明所有依赖安全。

## 1. 覆盖与工具

| 检查 | 方法与实际覆盖 | 基线→修复后 |
| --- | --- | --- |
| 依赖锁 | Trivy0.75.0 `fs --scanners vuln --list-all-pkgs`，三个实际锁，包含runtime/test条目 | 232→234包记录；33→0告警记录，17→0不同CVE |
| 打包依赖 | 从两个hash确定bootJAR提取精确`BOOT-INF/lib`并纳入普通starter；Trivy `rootfs --scanners vuln --list-all-pkgs` | 148→150包记录；32→0告警记录，同样17→0不同CVE |
| 源码 | Semgrep1.179.0本地扫描，`p/java`/`p/javascript`/`p/security-audit`，禁metrics/versioncheck | 219→221扫描路径；保留1warning，0扫描错误 |
| 秘密模式 | Gitleaks8.30.1 `--redact=100`，活跃源码快照／三JAR（`--max-archive-depth 2`）／Git历史`--all` | 两阶段三路径各0匹配 |

直接Trivy `fs`扫描bootJAR返回**0target/0package**，不能算有效依赖通过。显式解包补足：新server84个nestedlibrary、BFF63个；扫描器识别150包记录，不能与147个库文件混为同一指标。starter不打包依赖图，需要同时看锁及独立解析的发布metadata。

最终活跃源码快照清单287文件：三模块源码/资源/tests、gradle、scripts、deploy、build/settings/wrappers/locks及Compose。源码来源仍使用既定286文件范围，不含Compose。Semgrep扫描路径数是语言/规则覆盖，不等于所有清单文件，不能当PowerShell/SQL/Thymeleaf模板/bytecode或完整跨函数安全验证。Gitleaks零匹配不证明local-only示例凭据、任意秘密格式、ignored外部文件没有秘密；历史覆盖Git记录，不等于LAS全工作区每个ignored/untracked文件。本文不保留匹配源码、凭据、Cookie、Token或私钥。

Trivy DB schema2：`UpdatedAt=2026-10-06T01:08:27.80517672Z`、`DownloadedAt=2026-10-06T05:19:56.75879896Z`；前后相同DB（`--skip-db-update`），无严重级别过滤、ignorefile或`--ignore-unfixed`。数据库完整性与未来公告仍独立。

Gitleaks Windows archive SHA-256已核验：`d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e`。Semgrep规则包SHA-256：

- java：`5e652fa9ac09c9fac36bbadc3562a0c8eed1a47438a9d1f545e021ae3c5648b1`
- javascript：`e65e8449157ef5d587f2c2a0c17ed388910ca90336e514d27941ccd25543cf4e`
- security-audit：`b109a039df712f30c6d3e25e1e8358053fd0f1c91b92d0e8d2871cd141fe602f`

## 2. 依赖告警与处置

下表级别为**扫描器级别**，不表示LAS暴露对应功能。Spring对CVE-2026-47884标MEDIUM且需XsltView/映射条件，本DB标CRITICAL；Tomcat DIGEST/FORM项同样有较低厂商评级和特定容器认证条件。通过升级移除受影响打包版本，不虚构应用可利用复现或笼统RCE结论。

| 依赖 | 基线不同公告 | 扫描级别 | 实际解析处置 |
| --- | --- | --- | --- |
| Log4j API | CVE-2026-49844 | MEDIUM | 2.25.4→2.25.5，Boot BOM |
| Tomcat embed core | CVE-2026-65182、CVE-2026-65905、CVE-2026-68525 | CRITICAL | 11.0.22→11.0.26，应用显式override |
| Bouncy Castle provider | CVE-2026-8763；CVE-2026-13506 | CRITICAL；HIGH | 1.84→1.86 |
| pgJDBC | CVE-2026-54291 | HIGH | 42.7.11→42.7.13，Boot BOM |
| Spring MVC | CVE-2026-47884 | CRITICAL | Framework7.0.8→7.0.9，Boot BOM |
| Jackson core | CVE-2026-89407、CVE-2026-89425 | HIGH | 3.1.4→3.1.7 |
| Jackson databind | CVE-2026-68497、CVE-2026-91776、CVE-2026-91777 | HIGH | 3.1.4→3.1.7 |
| Jackson databind | CVE-2026-19032、CVE-2026-59889、CVE-2026-77310、CVE-2026-83557 | MEDIUM | 3.1.4→3.1.7 |

Boot4.1.0→**4.1.1**，保留minor基线、Security/AS **7.1.1**和Nimbus JOSE10.9.1/OIDC SDK11.38.2。Boot自带Tomcat11.0.24早于后续厂商修复（含11.0.26 HTTP/2回归补丁），两个可执行应用统一embed模块11.0.26。Jackson BOM3.1.7保现有3.1补丁系列（annotations2.21），core/databind不混patch。Bouncy Castle1.86纳入晚于扫描DB最小1.85的厂商加固。相关BOM还更新Spring Session4.1.1、Logback1.5.38、Micrometer1.17.1和测试库；三锁重新生成、实际JAR条目已核验。

核对的一手版本/公告：

- [Boot4.1.1 dependency BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom)
- [Tomcat11厂商公告](https://tomcat.apache.org/security-11.html)
- [Spring CVE-2026-47884](https://spring.io/security/cve-2026-47884)
- [pgJDBC/channel-binding修复](https://jdbc.postgresql.org/security/)
- [Bouncy Castle1.86发布](https://www.bouncycastle.org/download/bouncy-castle-java/)
- [Log4j API CVE-2026-49844](https://logging.apache.org/security.html)
- [Jackson core3.1.7](https://github.com/FasterXML/jackson-core/releases/tag/jackson-core-3.1.7)、[databind3.1.7](https://github.com/FasterXML/jackson-databind/releases/tag/jackson-databind-3.1.7)

## 3. 保留的源码告警

规则`out.java.lang.security.audit.unvalidated-redirect.unvalidated-redirect`，WARNING，`src/main/java/org/las/sso/security/LoginFailureResponseJitter.java:45`。立即与调度两分支都重定向方法参数；唯一生产调用`LasAuthenticationFailureHandler.java:68`来自容器contextpath加固定`/login?error`，不是浏览器参数、savedrequest、headerURL或任意returntarget。另一调用为固定字符串测试。

结论：**当前调用图误报**，人工复核，原报告保留warning。没有屏蔽规则/虚构allowlist修复/增加镜像测试凑数量。容器context与转发信任仍需受控部署；`.3`转发缺陷及修复有独立记录。新增传用户输入的调用须重新复核。

## 4. 验证与证据处理

Java21/Docker强制全量**2026-10-06 13:41:00–13:45:20 Asia/Shanghai**，20tasks全执行：**486server+193starter+37referenceBFF=716**，0失败/错误/跳过，实际PostgreSQL fixture运行。现有renderer/consent、持久化序列化、root/context实际Tomcat HTTP、OIDC/reauth、callback/logout及durable JDBC在新图通过。两bootJAR/metadata生成；隔离native.module与POM-only消费项目编译新旧API并解析Boot4.1.1/Framework7.0.9/Security7.1.1/Log4j2.25.5。本轮依赖处置无需修改生产Java或migration。

基线/最终原报告、清单、build执行日志分别留在获准本地临时证据目录`sso-security-scan`与`sso-security-scan-20261006-4`。它们不是已提交制品，无长期保管保证，SAST原报告可能包含源码，应保持私有。远程请求只获取rules/DB/artifact，local源码scan禁metrics。扫描容器`--rm`，最终Docker没有运行中的fixture。一次准备重写EXE与Gitleaks竞争导致占用错误；脚本现先核验相同字节才写，最终清单重新生成并重跑secret；这是工具错误非产品告警。已知Testcontainers/Hikari停止警告不改变0skip XML，也不能作生产容量证据。

最终报告SHA-256锚点：

| 报告 | SHA-256 |
| --- | --- |
| 基线三锁SCA | 8661ba72ba528cea1202eec37cd411db39a9a4d3b93793ebf7db178a4776a461 |
| 基线解包JARSCA | bcb38e95c8135d5366aeeebdfdb62ef13ca9dff39b9a25400b352cc49d279ffa |
| 最终三锁SCA | 2b6ac4408300a1dde277b6431074a9f29c449cea1e52be8027361e8c21a9134c |
| 最终解包JARSCA | 261184b31e217cac3253ded98b51fff8bb61c79516b012a0c85f915c1d54d7ab |
| 最终Semgrep | 0a230f68ecc24ee02b9547e8aafee176b540e3f7bce9cec1c05346580e272054 |
| 三份0finding Gitleaks JSON各自 | 37517e5f3dc66819f61f5a7bb8ace1921282415f10551d2defa5c3eb0985b570 |

本次辨识活跃构建扫描/处置完成，不等于全部安全验收。生产image/OS/JDK/proxy扫描、完整runtime日志/ignored外配置检查、认证DAST/全部UI/可访问性、发布时新公告复核、容量monitor、签名已提交发行与实际consumer仍开。原`.3`TLS/browser/维护/恢复证据保历史；本轮未用`.4`JAR重复这些外部演练。
