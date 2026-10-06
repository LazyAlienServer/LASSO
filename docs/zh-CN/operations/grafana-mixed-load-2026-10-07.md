# Grafana导入与混合负载验收 — 2026-10-07

- [English](../../operations/grafana-mixed-load-2026-10-07.md)／[.7交付](../integration/development-delivery-2026-10-06-7.md)／[refresh/logout修复](../implementation/refresh-logout-hint-history.md)。
- 最终hash.7本机真实验收；受控十分钟，不是饱和/生产sizing/长期内存/multiinstance认证。

## Grafana

Grafana12.2.0 digest `sha256:74144189b38447facf737dfd0f3906e42e0776212bf575dc3334c3609183adf7`独立一次性containernetwork/loopback3300，anon/signup禁，randomexternaladmin只provisionAPI。导入原repo SHA256`f4e735ddd45c666fbe950e3585ea2544db34dcf67bfc23aea3a80866bf713141`不改dashboard，server端privatePrometheusdataSource，另Viewer。

EdgePlaywrightViewer在1440×1000/390×844，八heading/panel真数据/canvaspixelvariation，无querypageerror/横向overflow；mobile实际scroll挂lazyPanel。Viewerread可，APIsaveoverwrite/datasourcecreate403，canEditSavefalse。desktop/top/mobilelower截图private临时保无tokencredential，不仅query语法验。非productionGrafanaSSO/所有权限plugin/升级兼容认证。

## 拓扑与负载

最终**2026-10-07 00:36:27–00:48:02 Asia/Shanghai**含setupbrowser，default301.154s/appowned302.178s。privateDockerPG18.4/Temurin21/NginxTLSinclude/fixtureCA-SNI客户端/JVMtruststore/browserresolver单leafSPKI，appBFFDB不publish，仅proxy/Grafana/Prome loopback。SSOheap1GiB/BFF512MiB/pool10，显式DBseed五独立Argon2identity是fixture非生产default/注册验收。每mode4subject双browser+无关sentinel=9Session。

closedloop800mspacing，protected/session并发、periodicBasicquery、80s current退出SSOreentry、140s global退出/freshpasswordCodePKCETokenUserInfo。fixtureclientAT45s/BFFbefore10s强制实际refresh，没等production15m，不改生产policyTTL。sentinel始终login，twin只deliberateglobal失效。

deliberaterevocation撞inflightrefresh可401SESSION_EXPIRED而非200anonymous，仅postlogoutterminal检查允此精确category，正常流量仍200auth/no其他subject失败。早探针不接401为fixture校准，和.6实际400IDhint缺陷分开。mobiletimeout是lazyprobe，无产品改面板；早attempt/protocoldiagnostic和final分开保。

## 结果

两mode**12,598成功operation观察**含主duration前initiallogin时延，非rawHTTPrequest数；browser操作多个redirectrequest。48passwordOIDC/24current/24SSOreentry/16global，5subject9browser每mode/sentinel保持，无意外workloadfail。Prome120successrefresh/0reuse/40successBack-channel；finalDB40processed/0pendingdead/0reuseevent/0internalbucket。保activeSSO/RTrows为current保SSO/sentinel预期，非零resource声明。

| 观察 | default count / p95ms | appowned count / p95ms |
| --- | --- | --- |
| password/OIDC | 24 / 1204.78 | 24 / 639.77 |
| Session含postlogoutterminal | 3198 / 24.11 | 3197 / 28.32 |
| protected | 2774 / 21.91 | 2760 / 25.55 |
| eligibility | 279 / 65.96 | 278 / 71.67 |
| currentlogout | 12 / 345.04 | 12 / 411.57 |
| SSOreentry | 12 / 303.56 | 12 / 268.38 |
| global至观察twin失效 | 8 / 9819.47 | 8 / 9021.69 |

global含redirect/workerpoll/终态，不是单HTTPdelivery或propagationSLA；8/12小样p95约上界。.7history让actualrefresh后重复global/freshlogin完成。

89processSQLsample：SSOmem406.9–926.2MiB(first406.9last637.4)/BFF309.3–402.8(first309.3last393.6)，DockerCPUpeak85%/40.35%/PID96/73。containermem含native非heap，DockerCPU非logicalJMX归一。一genericRuntimeError采样gap保，本次mode间也重启BFF但不足精确归因；lockwaiter0/DBconn≤11。无OOMcrash但warmup上升/短时不能排leak/headroom，未ramp/largeDB/multiinstance验收。

## 证据与清理

private runtimeSHA256`ef41bee420b87e2fa7d1eb405b2ef0b614bf99b67dfa01959e2625427595f37a`；default`fbd67f0a55c0c39ac833137ea2111b134fba40d50ddf8af5f48d1d0b92286a0d`、app`6b41b1889fe5b271f0e93223964bcfa59df0eecb6ca503f3fc4a06679f5e05fb`、browser`726445836ff13520c80bafa91bb2210c8e1dcad538d7d6a61f4eb9e1dcc86720`。[manifest](../../integration/development-delivery-2026-10-06-7.json)绑定artifactsource/tests，旧.6JAR保。

专用GrafanaPromeTLSapp/proxyDB/containerDBvolume/network、accountpasswordcollectoradminsecret/signingTLStrustkey均清，9443/9080/9092/3300free。rawlog/screenshots/report私有临时无长期保证。finaltargeted无fixturepasswordpepperclientadminsecret/JWTBearerCookie模式，非全log审计。无globalCAhosts/prodDBdeploy/commitpush。

下一productionGrafanaauthretentionbackupsecurity/采集privateTLSrotationoncall、真实硬件DBvolume/network小时级mixedrampsoak与metricsoverhead/historysizing、剩余UIdomainexternalprobe与正式发行，不称productionready。
