# 参考持久撤销交付 — 2026-10-05

.20261005.4历史快照，后续[.20261006.1](development-delivery-2026-10-06-1.md)增SSO可执行restore屏障/真实backup与key更换，本manifest非current来源。

- ID **0.1.0-dev.20261005.4**，本地开发组合，.1–.3历史保留。
- [English](../../integration/development-delivery-2026-10-05-4.md)／[manifest](../../integration/development-delivery-2026-10-05-4.json)／[ADR-0009](../adr/0009-reference-bff-durable-token-revocation.md)／[profile契约](reference-durable-revocation.md)。

## 范围

用户选参考BFF可选PostgreSQL持久撤销。durable-revocation实现原sink，独立AES-256-GCM/key/AAD加密Token，submitcommit后返回，restart恢复lease/retry。DB无ClientSecret，当前confidential配置核issuer/client/endpoint快照/currentsecret。仅200/禁redirect/两分钟fencedlease/8claims随机retrydead/成功擦密文。local先退出不变，submitcommit前crash仍间隙。sample非Starter JDBC库/LASLOG表。

参考V1queue迁移独立SSO V1–V14。仅ref增加SpringJDBC7.0.8/Flyway12.4.0/PGdriver42.7.11及testcontainers2.0.5并重生其锁；server/starter行为锁不变，starter bytehash同.3正常。default不需DBkey仍memory，explicit才迁移。参考test现37非NO-SOURCE。

## 执行

Java21/Docker，las-sso-server/：

```powershell
.\gradlew.bat :las-sso-reference-bff:dependencies --write-locks --no-daemon --console=plain
.\gradlew.bat :las-sso-reference-bff:test --tests "org.las.sso.reference.revocation.JdbcRevocationIntegrationTest" --no-daemon --console=plain
.\gradlew.bat :las-sso-reference-bff:test --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261005.4" "-PlasSsoStarterVersion=0.1.0-dev.20261005.4" --rerun-tasks --no-daemon --console=plain
```

约 **22:03–22:11 Asia/Shanghai** 强制 **461server+193starter+37refBFF=691** 零失败错误跳过20tasks，PG18.4/Testcontainers实际运行。shutdownpool访问已停fixture日志同前、report0failerror skip。新增feature测试非捏旧code漏洞红。

34DB/AES/HTTP含commit/外rollback、insertclaimretryreceiptSQLfault、status14matrix/禁redirect/200、newstore及JVMrestart、lease/staleowner/concurrentclaim、8claimcrash、200回执rollback重复、断连、metadata/task替换/key错缺、endpointclientissuer变化/currentSecret、本地logout提交失败、安全input。3配置defaultnoDB/explicitonlysink/schema/missconfigfail；初context缺sample registrationid/JWKS fixture失败后修，非生产修复。

真实.4 SSO/BFF JAR、临时PG中独立identity/queueDB、local临时signkey/独立queuekey、Edge Playwright1.55.1默认/自有两模式：

1. ALTCHA注册与双browser同主体密码login。
2. pausedworker本地退出A，A匿名/B登录，加密taskcommitattempt0而SSO原auth仍活动。
3. 停BFF→新JVM同DBkey启动worker，taskattempt1完成/擦nonceciphertext。
4. SSO原ATRT metadata失效，旧RT实际refresh400invalid_grant，其他browserauth仍有效。
5. restart废local登录，SSObrowser仍可免密码顶层返回，再全局退出正确返回。

初harness误期望撤销删除授权行，实际RFC7009保存invalidatedmetadata；校准并加真实RT拒绝，非SSO缺陷。最终强制JAR重启两流程再次绿。不输出Tokenkeysecret，targetedlog检查独立于全扫描，外部临时helper非默认。

starter.4仅localfile发布，两独立native/POM-only旧新APIcompile/Security7.1.1无composite/inherit锁，非MavenCLI/托管。

10进程log针对fixture密码/key/clientsecret/BearerJWT恢复码Cookiepattern无命中；最终SSO/BFF进程、临时DB容器volume已清，外部credentials/key配置删除，非完整sourcehistoryproduction扫描。

## 来源

base8cee3ed62fd7c53664e8026b99664c73fae5f19d/branchcodex/fix-sso-quality-review **加未提交worktree**，278sourcebuildtestGradlescripts，fingerprint49fab61275a19c707429107710e109bd6c3f52579409f78773c815449b32e348，manifest9artifactscript参考migration/3locks，相对las-sso-server，排docsbuildexternalconfig。

| 制品 | SHA-256 |
| --- | --- |
| Server bootJAR | 2fac17b9ed79f62f82313e7a852f6f16b3c2d6ce32eda3605da9b47c20033912 |
| Reference BFF bootJAR | ec4bd1547922a245e90c7fa2822fdfb0a19aec3ee9e0e4fcd00c6a9ea42c888d |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

default仍SNAPSHOT，新内容.4不覆盖.3；hash非release签名/commit复现，无commitpush/productionDB/remote deploy。

## 剩余

仅参考已committaskrestartretry已验，B1整体不关闭：presubmitcrash/持久TokenSessionindexreplaymarker/customshared B2仍缺。无dead人工API/cleanup/multikeyrotation/productionTLSbackuprestorepoolcapacityfullscan或完整multiinstanceapp/业务接入。实际SSObackup/epochbarrier/成功admin交互/externalURI/RP15m/hostedsignedrelease独立；下一需明确跨存储接受恢复边界，不误称原submit与local删除原子。
