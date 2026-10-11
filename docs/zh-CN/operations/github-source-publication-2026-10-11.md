# GitHub源码提交范围检查 — 2026-10-11

[English](../../operations/github-source-publication-2026-10-11.md) · [仓库](../../../README.md) · [工程规则](../../../CONTRIBUTING.md) · [RC证据](../integration/release-candidate-2026-10-11-rc1.md)

## 检查快照

仅检查独立`LASSSO/`仓库，不含父级LAS工作区、LASLOG或Velocity兄弟仓库。审查HEAD为`7f2b905e7d6b4161d1bf7d380558ead8e8415f2b`，开始时工作区干净，**608跟踪文件/14,313,583 bytes**；最大文件为1,295,102-byte概念PNG，历史无超过50MiB的可达blob。尚无Git remote。

当前分支`codex/fix-sso-quality-review`，本地`main`仍`5252e51`，落后当前24提交。只推当前main会遗漏近期实现、CI、打包、部署与发行证据。需选如何将已验分支纳入GitHub默认分支，本检查不重命名/合并/改写分支。

## 应保留的源码

| 路径 | 原因 |
| --- | --- |
| `las-sso-server/src/`及两个子模块源码/测试 | 真实server、Starter、参考BFF，含测试/template/static |
| Gradle build/settings/catalog、三模块锁、Wrapper脚本/properties/JAR | 构建bootstrap及依赖定位；Wrapper JAR是应提交的二进制例外 |
| `las-sso-server/src/main/resources/db/migration/` | append-only schema历史，不能全局忽略SQL |
| `las-sso-server/deploy/`、其scripts及根`scripts/` | 已审查Docker/Nginx/监控/维护模板和验证工具 |
| `release/_source/` | 独立包手册/OpenAPI/工具源码，不是生成发行目录 |
| `release/verification/` | 脱敏版本/test/hash/scan范围，不含raw秘密发现/认证抓包 |
| `.github/workflows/verify.yml` | 固定Actions、Java/PG/Python验证，只上传数量类测试摘要 |
| README/CONTRIBUTING/CONTEXT/LAS_SSO_DESIGN/docs及editorconfig/gitattributes/gitignore | 产品/工程/API/ADR/运维上下文和追溯 |

显式local/test fixture凭据和local-only client配置可留源码，非生产材料，production门禁拒示例值。环境占位符及password/pepper属性名本身不是秘密。不要因格式可能含秘密就删测试、全局忽略properties/YAML/JSON/SQL或整个release目录。

## 不应进源码库的本地文件

`.idea/`、`.gradle/`、build/bin/target、node_modules、Python cache、`.build-docker/`、`.build-release/`、日志/heap dump本地保留。历史`SAS-Test/`已忽略，不属当前构建。

实际deployment.env/restore.env、server/monitoring/queue配置副本、secrets、PKCS12/privatekey/证书、密码文件、恢复码、Cookie/Token/browser storage、HAR、raw验收/安全日志及DB dump不进源码。优先外部保护位置或`.local/`；ignore仅便利，不能代暂存审查。

生成应用JAR、镜像tar、版本目录/ZIP不作为Git源码。已验RC ZIP后续可作为**GitHub Release附件**，附对应checksum及候选限制。RC镜像继承pebble High仍保待办；源码上GitHub不修复它，也不等生产批准。

本轮补deployment副本/secret目录、backup/capture/archive、应用binary的定向ignore，保`gradle/wrapper/gradle-wrapper.jar`。`.example`模板、迁移SQL及版本证据可跟踪。没有移除既有跟踪文件，也未删除本地部署数据。

## 可选资料及需你决定的项

- `design/`：生成的概念稿/品牌素材。已查看概览/会话/恢复页，包含示意身份/设备、掩码，不是真认证抓包。可保设计历史；想精简代码库可明确移到设计仓库，不自动删除。
- `research/transactional-email-pricing-2026-07-29.md`：基于公开来源的历史调研，提邮件/MFA假设不属已接受V1。可明确历史保留或另归档，不当当前需求。
- `AGENTS.md`：已有一行工具说明stub，公开阅读可选，不是秘密。
- 根目录未找到`LICENSE`。仓库public/private及预期许可证由你选择，不凭公开托管推断复用授权，也不擅自添加许可证。
- Git作者/提交者包含一个非noreply邮箱身份，public push会公开提交元信息。改未来Git身份不改旧提交；匿名化/历史改写需另行明确决策，且影响发行来源。

## 当前及历史检查

Gitleaks **8.30.1** `--redact=100`扫描当前608 candidate：**exit0/0发现**。全refs的**36个可达提交**有一条历史generic-api-key：`ProductionConfigurationGateTest.java`、f5048d2、206行，是既有复核的合成MockEnvironment keystore路径；保原exit1及身份，无suppress，不是真key/凭据。

历史含已删`docs.zip`（92,670 bytes/21文档项），SHA256 `f279a48b87db295e6743cbe7949e3f4f699e73ffbe8c9b84c96141c3f86eca88`。depth3扫描exit0/0发现，无敏感entry路径。普通push也带可达已删文件，gitignore不清历史；当前未发现需清历史的秘密。raw扫描及path/hash清单仓库外私有保管。

结果只覆盖检查快照，不保证未来文件；提交前核精确暂存路径，修改后按仓库验证/秘密检查复验。

## 上传边界

初次检查仅准备ignore/文档，未提交上传。用户随后要求发布到`https://github.com/LazyAlienServer/LASSO.git`，官方GitHub CLI2.102.0确认public空仓库及ADMIN权限。将已审查最新源码通过本地fast-forward纳入main，普通push；本次不改写历史或上传生成Release附件，不添加/推断许可证。GitHub CI需push后核实际执行，不以本地结果代称。
