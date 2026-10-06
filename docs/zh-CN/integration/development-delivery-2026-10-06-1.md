# 可执行恢复屏障交付 — 2026-10-06

.20261006.1历史快照，后续[.20261006.2](development-delivery-2026-10-06-2.md)修revokedbrowser OIDC续行并完成隔离管理员维护；旧manifest非current来源。

- ID **0.1.0-dev.20261006.1**，本地可辨识开发组合；旧.20261005.1–.4保留历史。
- [English](../../integration/development-delivery-2026-10-06-1.md)／[manifest](../../integration/development-delivery-2026-10-06-1.json)／[ADR-0010](../adr/0010-executable-restore-epoch-barrier.md)／[操作流程](../operations/restore-epoch-barrier.md)。

## 修改范围

SSO新增SecurityEpochRecoveryService、非Web命令rotate-security-epoch-after-restore及PowerShell。要求精确expected-restored-epoch/有界非秘密reason、关闭scheduling及交互确认；脚本精确JAR/可选trustedSHA256，不HTTP/自动恢复。

同一事务锁全局epoch并核验，换随机不透明epoch、撤销activeSSOmetadata、删SpringSession/Authorization、撤销RT history、清OIDC/recovery/CAPTCHA/rate/idempotency短事务、记安全事件。用户/凭据/恢复凭据/client/consent/deletion/audit/outbox payload和receipt保留，只释放pending非dead旧claim，schedule/attempt/dead不变。重复或并发旧expected拒绝。无schema/dependencylock变化，Starter/refBFF行为不变，starterbytehash同旧正常。

key更换/停止其他writer/proxydrain/consumer屏障归运维前置，服务不能证明writer已停或原子控制外库/key。非普通globallogout，不能恢复快照之后改密/账号/恢复码使用事实。

## 测试命令

Java21/Docker，las-sso-server/：

```powershell
.\gradlew.bat :test --tests "org.las.sso.maintenance.SecurityEpochRecoveryServiceIntegrationTest" --tests "org.las.sso.maintenance.SecurityEpochRecoveryCommandTest" --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.1" "-PlasSsoStarterVersion=0.1.0-dev.20261006.1" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.1" --no-daemon --console=plain
```

最终约 **02:08–02:14 Asia/Shanghai** 强制 **479server+193starter+37refBFF=709** 零fail/error/skip20tasks/两JARmetadata，实际PG测试。shutdownpool访问已停TC fixture日志同前，报告无失败掩盖。中间707是前16恢复测试阶段，outbox两用例后最终增18。

新增18：12真实PG（所有login/RT/shortfixture、永久facts保留、六SQLtrigger fault全状态rollback、缺/不符/重复epoch/reason/concurrentoperator、pendingclaim释放/processeddead保存）；6command（nonweb/scheduling/no console/cancel/非法重复参数/成功delegate），此层consolefixture。

开发初constructor/finaltransactional bean启动失败、Session缺user FK fixture、早challenge处理不符未完成约束均已修；最终删challenge不伪造completion，按恢复契约保consent/outbox。不称旧发行恢复命令漏洞。

## 真实隔离恢复

两PG18.4 source/emptytarget，真实pg_dump-Fc/pg_restore-exit-on-error，两RSA3072 PKCS#12独立kid，真实JavaJAR/EdgePlaywright1.55.1/WindowsPTY（pywinpty3.0.5），仅fixtureSecret。local只初始化clients，browser与recovery用持久key非ephemeral。

1. 真JVM bootstrap隐藏输入两次，唯一fixtureadmin建立且密码无回显；仅consolebootstrap，不算管理员首次改密/resetUI。
2. ALTCHA注册普通账号/双browser密码BFFlogin，实际queryepochversion1/ATRTID/未消费CodePKCE与callback内存保留。
3. 显式seed旧claimoutbox和near-due deletion schedulerfixture，停SSO备份再同旧key重启；备份后SQLfreeze/version2形成真实旧快照回退（非browseradmin验收）。
4. 停SSO/BFF→新target实际restore→**真实PowerShell/JAR/hash/PTY**交互确认→epoch事务清理，永久facts快照相同→独立新key/kid启动target，调度暂闭。
5. 新epochversion1ALLOWED不同于旧epochversion2；测试consumer generation屏障拒旧响应/旧request，不是LASLOG运行cache。
6. 旧SSO/BFFcookie不登录；ATUserInfo401；旧RT和旧Code/正确verifier400invalid_grant；旧BFFcallback不能消费旧pending。旧IDToken验旧publickey成功、验新key失败，JWKS仅新kid；不宣称已有productioncache自动忘旧key。
7. 以同新keyepoch开调度，保留outbox完成/near-due注销fixture执行；原UUID/password双browserfreshlogin，全局退出通过真实signedBack-channel清两Session。

最后构建hashJAR再跑完整恢复。首次restart Nodepool闭socket，transport-only一次retry/Connectionclose校准harness非SSO缺陷。stopweb后真no-console/cancel/repeatoldepoch命令nonzero，安全快照不变，无确认bypass。12进程logfixturepassword/keypassword/pepper/secret/Token恢复码Cookiepattern无命中，非fullscan。native.module/POM-only包外old/newAPIcompileSecurity7.1.1、本地file发布非MavenCLI/托管。

## 来源

base8cee3ed62fd7c53664e8026b99664c73fae5f19d/branchcodex/fix-sso-quality-review **加未提交worktree**，284sourcebuildtestGradlescripts，fingerprint29dcfbc13ef97b6128c7590993fcb80d209fc3d85cc5a90658beb4886efc7c57；manifest10artifactscriptmigration/3unchangedlocks，相对las-sso-server排docs/buildexternal。

| 制品 | SHA-256 |
| --- | --- |
| Server bootJAR | 277faab68d6d5df0fb45a337202ec19dde132302d04306d805da6b11237dee9f |
| Reference BFF bootJAR | 77e5f1f210d83bd1506e99a6dcec5d598075e00d12f8ed1862a2a5039fe50941 |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

defaultSNAPSHOT/hash非release签名/commit复现，无productionbackup/DB/commitpush/remote deploy；临时进程DBbackup/key/config清理记本交付。

最终已按身份核验停止SSO/BFF，删专用PG容器volume（含内部dump）、外部凭据pepper配置及两生成PKCS#12二进制fixturekey，无生产key/DB。

## 剩余

productionbackupstorage/RPORToPITR/key保管/proxyTLS/issuerdrain、真实LASLOG缓存Session屏障及已有JWKS旧kid拒绝、fullscan容量监控/adminfirstchange/reset完整UI独立。B1presubmitgap/持久Sessionindexreplay/custommultiinstance/referencequeuedeadkeyrotation仍开放，事件仍延期。本轮完成SSO可执行屏障和隔离真实backuprestore/key更换，不关闭全部灾备或consumer实施。
