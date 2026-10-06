# 有界容量、监控观察与readiness修复 — 2026-10-06

- [English](../../operations/capacity-readiness-2026-10-06.md)／[最终制品验收](final-artifact-acceptance-2026-10-06.md)／[.5交付](../integration/development-delivery-2026-10-06-5.md)。
- 本次是**短时工作站观察**，不是持续容量/一分钟传播SLA/生产sizing；未为通过而调整生产阈值或Argon2参数。

## 1. 真缺陷与回归

精确`.4`JAR在PG停止后readiness仍200，内部firstparty query正确503、liveness200。application.yml开probe但未纳`db`，Boot只有availability的readiness没体现数据库依赖。

修复前新增`DatabaseReadinessIntegrationTest`，数据库down503断言先失败；加入`management.endpoint.health.group.readiness.include: readinessState,db`转绿。最终真实TomcatHTTP/PG18.4，固定loopback端口同容器stop/start，readiness200→503→200、liveness始终200、精确粗粒度`{"status":"DOWN"}`不含组件详情。保disabledWithoutDocker，最终全量0skip。

恢复fixture另有两偏差：镜像初始化socket暂时就绪但finalpostmaster尚重启；Docker动态发布端口restart可改变。改fixture TCP就绪和显式选出的临时固定loopbackbinding，不据此改应用pool/JDBC。未增加schema/key/adminbootstrap/凭据policy/生产config的启动验证器；本healthgroup检查DB与应用ready，不等于runbook所有预期startupgate实现。

## 2. 测量拓扑

最终`.5`观察截至 **20:05:11 Asia/Shanghai**约一分钟。精确hashJAR/Zulu21Windows/20logicalCPU/PG18.4fixedloopback/localHTTP无forward/BFF/TLS。fixture JVMheap256MiB→1GiB、Hikari max10/min2/timeout2s，外部测试配置不写生产sizing。local用ephemeral signingkey，持久key/TLS另套最终演练。

hash采用最终JAR实际库及Argon2id salt16/hash32/parallelism1/memory19,456KiB/iterations2，warmup8/每worker16check/一预编码credential，独立hash p95在1/4/8worker为30.62/45.00/72.13ms；非全HTTPlogin或参数强度认证。

仅一次性DB建立fixture user/client，internalBasic secret显式Argon2id而非local示例便宜hash。query经过实际budgetreserve/refund/密码hash/JDBC资格/Tomcattransport：一knownactive subject，每phase96request/warmup8/closedconnection/boundedworkers。200验ALLOWED/version1/no-store，429验RATE_LIMITED/RetryAfter，输出不含TokenCookiepassword及subject/IPmetriclabel。

## 3. 最终有界结果

| query并发 | 响应 | 总观察req/s | p50 / p95 / p99 ms |
| --- | --- | --- | --- |
| 1 | 96×200 | 13.84 | 65.17 / 118.25 / 258.83 |
| 4 | 96×200 | 46.31 | 83.76 / 108.93 / 133.84 |
| 8 | 96×200 | 60.93 | 123.70 / 187.02 / 212.14 |
| 16 | 91×200、5×429 | 60.65，含拒绝 | 196.91 / 551.15 / 878.47 |

默认client10/network20含在途reservation，16worker即使正确凭据也可能拒。各phase结束FIRST_PARTY_AUTH两scope0row：successrefund/拒绝rollback。不能据此自动升阈值或承诺并发admission，16worker总速率包括拒绝，不是成功query吞吐。

| passwordlogin并发 | POST响应 | 整体workflow req/s | POST p50 / p95 / p99 ms |
| --- | --- | --- | --- |
| 1 | 32×302成功 | 3.70 | 83.80 / 131.20 / 175.50 |
| 4 | 32×302成功 | 5.36 | 160.75 / 239.09 / 386.28 |
| 8 | 32×302成功 | 5.51 | 181.40 / 343.44 / 347.64 |

每login独立cookiejar先/account建savedrequest、真CSRF/密码POST，整体耗时含prepare、percentile只POST，验302目标account/观察96activeSSOSession。单subject竞争，非多独立user/暴力/注册/OIDCrefreshBack-channel混合或长期expiry。首探针未建savedrequest却期待account，是fixture修正非产品repair。前次受hostload不同，本表报最后一轮，不挑最快。

## 4. 监控与故障恢复

31个1秒local同userJMX采样，无remoteJMX flag/listener或publicmetrics新增：

- processCPU fraction峰0.2647按工作站logicalCPU汇总；
- sampledheap峰438.50MiB/38thread/GC累计增831ms；
- Hikari峰10active/10total/1awaiting，1秒采样可能漏更短峰；
- SQLsnapshot96activesession/0pendingdeadoutbox/11DBconn含observer/0lockwaiter，idleoutbox非投递容量验收。

实际stop/start：readiness200→503→200，liveness始终200，querydown503/恢复200。fixturetimeout2s及有界retry非default30s/productionnetwork时延保证，详情never。

匿名`/actuator/metrics`不跟redirect时302login，早自动跟随200实际loginpage非metrics。base暴露仍health/info，未找到/未实施/未验证custom领域MeterRegistrycountertimerGauge、Prometheusscrape/exporter/dashboard/alertdelivery；runbook指标名是目标要求非已有endpoint。JVM/pool/SQL是观察基线，监控告警仍pending。

## 5. 证据、隐私和后续

finalprivate `capacity-verified/observation.json` SHA-256 `18555db1b65acae5b2af1b48f27b0f4a86f7ccc135ae2e5ca6da594bd9c357eb`，script/Java probe外部临时诊断。app/DBvolume/settings已清，两finalprocesslog定向credentialJWTcookie无命中，runtimehash匹配manifest。早失败/修正observation独立保留，不隐成passedbenchmark。

下一需持续ramp/soak、实际多user/OIDCrefreshlogout混合、productionCPUmemDBnetworkproxy sizing、账号IP窗口边界、secretfree低基数字段指标、认证privateexport/scrape、dashboard与实际alert/recovery投递。不能推productioncapacity/SLA/monitor完成。
