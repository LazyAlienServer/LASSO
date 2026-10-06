# LAS SSO 监控与告警

- 2026-10-06在[交付.20261006.6](../integration/development-delivery-2026-10-06-6.md)实现/验证；[English](../../operations/monitoring-and-alerting.md)。
- 仅SSO，可选采集；starter/参考BFF不增加监控凭据、端口、数据库表或业务权限Claim。

## 1. 启用采集

default保health/info且不启监控listener；给目标profile加`monitoring`，如`production,monitoring`，并不会代替完整生产身份配置。

| 设置 | 行为 |
| --- | --- |
| `LAS_SSO_MONITORING_PORT` | 独立管理端口，默认9091；enabled拒缺失/非正数/与app相同 |
| `LAS_SSO_MONITORING_ADDRESS` | 默认127.0.0.1，仅显式private/firewall采集网络可改 |
| `LAS_SSO_MONITORING_PASSWORD_HASH` | 必须独立`{bcrypt}`摘要/cost10–14；固定采集用户名`las-sso-metrics` |
| `las.sso.monitoring.snapshot-interval` | 默认15s，范围1s–5m；示例alerts按15s，>60s判stale |

外部生成高熵随机collectorpassword，不进repo/log/CLIhistory；app只获兼容bcrypt摘要，collector raw由私有secretfile供给。hash仍敏感，不是公开示例default；轮换同步改apphash/collectorfile，app重启后核scrape恢复，非multikey/dynamicreload协议。property rendering redacts，enabled缺失/raw/弱hash拒绝。

独立managementlistener **GET `/actuator/prometheus`**要求专用Basic，不建Cookie/持久SecurityContext/savedrequest。humanSession/OIDCclientsecret/错或匿名均不能scrape；collector身份不能queryeligibility或人/admin业务。正确凭据在appport或nonGET仍403。未enabled时即便手工暴露Prometheus仍明确deny。order0链只匹配Prometheus，既有OIDC/browser/internal授权保留。

Spring独立管理listener同时迁移health，liveness/readiness改用managementport；details coarse/never，readiness`readinessState,db`、liveness不含DB。不要从publicNginx代理private管理端口，app仍受原controlledproxy保护。firewall/凭据失败throttle/transport由deploy负责；samehostloopback示例HTTP，跨host Basic/webhook必须validatedHTTPS/mTLS或受保护transport。本功能不启remoteJMX。

## 2. 实际指标契约

Micrometer映射Prometheus underscore/`_total`/`_seconds`。counter/timer进程内restartreset，用rate/increase，调查关联受限持久audit，非授权来源/exactlyonce账本。

| Prometheus指标 | 语义与有界标签 |
| --- | --- |
| `las_sso_login_attempts_total{result,reason}` | formlogin success在Session/evidence完成后，failurehandler拒绝、preauth网段/CAPTCHA；result固定success/rejected/limited/captcha_required，reason none/credentials/locked/disabled/expired/network/captcha/other。CSRF和专用reauth非全部计为login |
| `las_sso_admin_login_failures_total` | knownadmin密码失败记录事务commit后增，无identitylabel |
| `las_sso_password_hash_seconds_{bucket,count,sum}{operation,algorithm,result}` | 原appencoder耗时，hashformat/upgrade/exception不变；operation encode/match、algorithm argon2id/bcrypt/other、result success/mismatch/error。含human/client/maintenance，非peruser；collector刻意独立encoder |
| `las_sso_internal_authentication_total{result}` | internalBasicgate success/invalid_credentials/limited/unavailable；authsuccess不等eligibilityALLOWED |
| `las_sso_service_access_total{decision}` | controllerquery allowed/denied/unavailable；controller前拒绝不产生决策 |
| `las_sso_token_refresh_total{result}` | refreshprovidertransaction success/rejected/error；此前clientauth失败不纳 |
| `las_sso_refresh_reuse_total` | 已commit ROTATEDtokenreuse；rollback不增，revoked重复拒非newreuse |
| `las_sso_registration_success_total` | 新HTTP注册servicecommit后，不含replay/directservicefixture/internalcall和全部rejectreason |
| `las_sso_backchannel_delivery_total{result}` | guard一行receipt转success/retry/dead；update0不计，GLOBALplaceholder不计HTTPsuccess |
| `las_sso_sessions_active` | 缓存DBcount ACTIVE且idle/absolute未来，无SessionId/subject |
| `las_sso_outbox_pending/dead/expired_claims` | 缓存Back-channel/global退出count，pending排processeddead，claim超2m |
| `las_sso_outbox_oldest_age_seconds` | 最旧pending年龄含scheduledretry/leased，空0 |
| `las_sso_snapshot_available/last_success_seconds/failures_total` | 有效性/成功Unix秒/失败poll数；DB失败保lastknown但available0，stale失败数据不能当当前 |

Boot/Micrometer另有JVMGC/processHTTPpool。领域标签无username/UUID/IP/TokenCookie/sid/requestID/clientID/URI/异常文本；HTTP使用normalizedroutetemplate，无rawqueryheader标签。collectorjob/instance只部署有界身份，不能放业务主体。预创建零counterseries不表示事件。

DBsnapshot不依赖业务scheduling，webready后单daemon启动/nonwebmaintenance不运行/context关闭。singleSQL timeout2s，acquisition仍按pooltimeout，scrape只读immutable缓存不countSQL。聚合全表count可能需生产cardinality/queryplan/timeout调优，短小fixture不证largeDBcost；DBoutage health/export仍可返回缓存unavailable。

## 3. Collector、规则与面板

`las-sso-server/deploy/monitoring/`交付：

- `prometheus.yml`：samehost15s采集/5stimeout/password_file/privateAlertmanager示例；
- `las-sso-alerts.yml`：7rule含scrape2m、snapshot stale/down1m、logoutage>300s1m、dead1m、reuse、adminfail、Argon2p95>1s5m；
- `las-sso-alerts.test.yml`：promtool规则验scrape/pollfailure recovery/stale抑制/backlogthreshold；
- `alertmanager.yml`：privatebearercredentials_file/send_resolved/groupalertnamejobinstance；
- `grafana-dashboard.json`：8panel/10PromQL，datasourceinstance selector/availabilityfreshness/sessionloginhashqueuepool，sessionqueue抑制staleunavailable。

samehostexample无凭据；容器需各自private地址mountallowlist，不能抄containerloopback连接别服务。Prometheus/Alertmanager/Grafana/webhookAPI与retention各自限制；部署供webhookURLtoken，不代表QQ/email/oncall生产operator已收，不投Token/privateauditpayload。

选受支持安装，在配置目录：

```text
promtool check config prometheus.yml
promtool test rules las-sso-alerts.test.yml
amtool check-config alertmanager.yml
```

外部credentialfile缺失应startupfail，syntaxonly不是credential/transport验收。alerts不包productiondisk/backupfreshness/clockskew/所有流程，不自动restart/replaydead；queue/session需看snapshotvalid，沿示例60s规则应snapshotinterval≤60s。

## 4. 验证

Java21/Docker全量 **21:42:33–21:48:35 Asia/Shanghai**，**504server193starter37reference=734**零失败错误跳过/20tasks。17new=config6/transactionencoder3/disabledexposure1/actualdualportHTTPPG7，原refresh/backchanneltest加实际rotationreuse/deadreceiptmetric断言。default/appownedTLS/Edge final.6 **22:11–22:12**原Cookie/reauthCSRF/exit/forwardIP边界通过。starterAPI/schema/policy不改；Prometheusadapter1.17.1及7包row只加serverlock。

真实private进程 **22:01:03–22:06:30** finalhashSSO+PG18.4+Prometheus3.6.0+Alertmanager0.28.1+bearerwebhook，独立network/hostloopback，无collectorsecret进日志。local身份ephemeralkey+monitoringprofile，productionpersistentkeyTLS由另一BFF演练保。fixture scrape5s/eval2s/snapshot1s/notify1s2s，**表达式与for1m2m精确repo不缩短**。

验独立auth/port/method/Session、真loginhashinternalquerysession、JVMPoolseries、10dashboardquery。fixture6moldoutbox满1mbacklogfiring/processed后resolved；DBstoprestart snapshotunavailable/readiness503/liveness200及firingresolved；collectorfile错满2mscrapedown/恢复resolved。六通知真webhook只alertname/job/instance/severity。Grafana file/query已验非liveimport/render；reuse/admin/hash/deadletter有source/config/regression，非每条独立endtoend firing。

早appporthealth探针未迁移、fixture2sinterval/5stimeout无效为工具错误已修保日志；Semgreprules远程TLS EOF，用既定SHA256相同localrules禁metrics/source不外传。finalSCA241lock157JAR0reportedvuln、Semgrep2321redirect误报0error、Gitleaks三路径0match，沿旧coverage限制。5monitoring+8TLSfinalcontainerlog定向secret无命中。

private runtimesummary SHA256 `f3c72c6ff14a1b074d53ed67260331a40fccf7ff725ae346d6e573b86e940983`，[manifest](../../integration/development-delivery-2026-10-06-6.json)辨302source303scan18artifactconfig三锁。专用容器networkDBvolume/apphashcollectorwebhooksecretfile清，acceptanceportfree，reportcachelog保private临时。无commitpush/prodDB/productionmonitor部署/globalcerthosts变。

## 5. 剩余

随后.7 actualGrafana12.2.0 import/privateDatasource/Viewer desktopmobile渲染savewrite403及两套5minTLS多subjectmixed通过，见[配对记录](grafana-mixed-load-2026-10-07.md)，替换该隔离层queryonly限制，不代生产Grafanaauthretentionsoak。发现refresh→global400独立修；上.6alert证据保历史。

生产privateTLS/credentialrotation失败throttle、实际Grafana datasourceimportrender/权限retention、oncallretryfailureescalation、registrationreject/recovery/tokenissue/transientcleanupmetrics、externaldiskbackupclockprobes仍开。多user OIDCrefreshlogout持续混合负载/largeDBpoll与监控overheadsoak仍开。本次完成指标子集和3真alert/recovery，不是runbook全部或productionmonitorready。
