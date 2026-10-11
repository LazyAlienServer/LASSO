# LAS SSO 文档目录

本目录按文档用途分组。产品总纲仍位于仓库根目录的 [LAS_SSO_DESIGN.md](../LAS_SSO_DESIGN.md)。

当前软件发行进度见[API/发行准备](zh-CN/implementation/api-release-readiness-2026-10-11.md)（[English](implementation/api-release-readiness-2026-10-11.md)）；目标生产使用见[正式生产上线计划](zh-CN/implementation/production-launch-plan.md)（[English](implementation/production-launch-plan.md)）：区分最新`.2`/工作区/历史`.7`证据与19项部署任务，不把软件包发行等同公众开放。

## 架构与协议契约

- [身份与数据模型](architecture/identity-and-data-model.md)：身份边界、状态机、数据库约束和并发规则。
- [账号与管理 API](architecture/api-contract.md)：页面与管理接口、错误语义和安全门禁。
- [已实现 API 参考](../release/_source/docs/zh-CN/api-reference.md)（[English](../release/_source/docs/en/api-reference.md)、[OpenAPI3.1](../release/_source/api/openapi.json)）：实际路由/DTO/认证/响应例外；发行时替换精确版本，无Swagger运行端点。
- [OIDC 与 BFF 接入契约](architecture/oidc-and-bff-contract.md)：协议、Token、Claims、会话和退出契约。
- [安全、密码与恢复](architecture/security-and-recovery.md)：密码、恢复码、威胁控制和 Secret 规则。
- [SSO 架构与 SSO 专属交付计划](architecture/sso-architecture-and-roadmap.md)：只覆盖 SSO 服务端、starter 和参考 BFF 的架构、当前→预期矩阵、问题修复与后续交付。

服务访问资格的第一阶段只使用第一方机密客户端查询和单调版本；独立状态事件投递已确认延后。Back-channel Logout 不承担资格状态同步。

## 领域决策

- [ADR-0017：同机单层外部Nginx](zh-CN/adr/0017-single-external-nginx-container-edge.md)（[English](adr/0017-single-external-nginx-container-edge.md)）：主机Nginx直达loopback发布的SSO，PG/管理不发布，域名/HTTPS在正式目标上线供应。

- [ADR-0016：单机Compose与账号站首发](zh-CN/adr/0016-single-host-compose-account-site-launch.md)（[English](adr/0016-single-host-compose-account-site-launch.md)）：确认拓扑/负责人、私有端口/离线迁移及目标门禁。

- [ADR-0015：运行历史保留](zh-CN/adr/0015-runtime-history-retention.md)（[English](adr/0015-runtime-history-retention.md)）：用户确认90/30天、有效hint保留、审计事实不删，有限batch/锁序/超时与V16。

- [ADR-0014：人工恢复与一次性秘密界面](zh-CN/adr/0014-manual-recovery-verification-and-secret-ui.md)（[English](adr/0014-manual-recovery-verification-and-secret-ui.md)）：两项独立依据/最小工单，关闭及离页清理，管理员恢复路径保持。

- [ADR-0011：刷新后退出hint关联](zh-CN/adr/0011-preserve-id-token-logout-hint-association.md)（[English](adr/0011-preserve-id-token-logout-hint-association.md)）：实际refresh后global400修复，V15HMAChistory/显式IDtokenlookup/cascade拒已撤销hint，原权限与ATRT边界保持。
- [ADR-0010：可执行恢复epoch屏障](zh-CN/adr/0010-executable-restore-epoch-barrier.md)（[English](adr/0010-executable-restore-epoch-barrier.md)）：旧epoch匹配/交互确认/单事务安全重置，真实PG备份恢复与持久key更换已验；生产与消费屏障独立。

- [ADR-0009：参考持久加密撤销](zh-CN/adr/0009-reference-bff-durable-token-revocation.md)（[English](adr/0009-reference-bff-durable-token-revocation.md)）：用户选参考BFF profile验证、已commit任务重启可靠，非跨存储local退出原子。

- [ADR-0001：SSO 拥有第一方服务访问资格](adr/0001-sso-owns-service-access-eligibility.md)：资格查询与后续状态事件的责任边界。
- [ADR-0002：提升管理员前必须取消有效注销申请](zh-CN/adr/0002-require-deletion-cancellation-before-admin-promotion.md)（[English](adr/0002-require-deletion-cancellation-before-admin-promotion.md)）：S4门禁已实现并通过数据库/命令fixture验证，真实交互与生产待验收。
- [ADR-0003：退出重放执行回执](zh-CN/adr/0003-logout-replay-execution-receipts.md)（[English](adr/0003-logout-replay-execution-receipts.md)）：用户批准增量 process SPI；旧 accept-only adapter 编译兼容、接收 503 直到升级。
- [ADR-0004：返回目标绑定登录事务](zh-CN/adr/0004-bind-local-return-target-to-login-transaction.md)（[English](adr/0004-bind-local-return-target-to-login-transaction.md)）：用户确认自有成功处理器显式接入，目标与state/Session/十分钟单次事务绑定。
- [ADR-0005：内部 Basic 独立失败预算](zh-CN/adr/0005-independent-internal-basic-failure-budget.md)（[English](adr/0005-independent-internal-basic-failure-budget.md)）：用户确认独立10/20十五分钟，哈希前占用/成功归还，与OAuth失败桶隔离。
- [ADR-0006：注销用户优先锁与批次原子](zh-CN/adr/0006-user-first-deletion-batch-locking.md)（[English](adr/0006-user-first-deletion-batch-locking.md)）：用户确认保留整批回滚，预选user并集/稳定UUID顺序/busy跳过。
- [ADR-0007：服务资格版本限定于安全代次](zh-CN/adr/0007-scope-service-access-versions-to-security-epoch.md)（[English](adr/0007-scope-service-access-versions-to-security-epoch.md)）：用户确认stateEpoch复用security_epoch，恢复后旧cache/inflight不可直接比counter。
- [ADR-0008：登记前保留sid撤销标记](zh-CN/adr/0008-retain-sid-revocations-before-local-registration.md)（[English](adr/0008-retain-sid-revocations-before-local-registration.md)）：用户确认默认单实例期限与登记协调，自有持久Adapter边界独立。

## 业务系统接入

- [发行候选0.1.0-rc.1](zh-CN/integration/release-candidate-2026-10-11-rc1.md)（[English](integration/release-candidate-2026-10-11-rc1.md)、[验收JSON](../release/verification/0.1.0-rc.1.json)）：干净8db0b20、843全量0skip、113files/451links、独立SDK/账号/双链OIDC/维护/restore；SSO镜像pebble3High保正式镜像前后续，非stable/生产批准。

- [参考BFF持久撤销](zh-CN/integration/reference-durable-revocation.md)（[English](integration/reference-durable-revocation.md)）：显式profile/独立PG/key/加密task/lease重启恢复，消费schema与submit前crash边界独立。
- [当前独立开发包0.1.0-dev.20261009.2](zh-CN/integration/development-delivery-2026-10-09-2.md)（[English](integration/development-delivery-2026-10-09-2.md)，[验收摘要](../release/verification/0.1.0-dev.20261009.2.json)）：双语任务型手册/Docker八步教程、98文件/229包内链接、834全量0skip、精确扫描、独立ZIP/SDK/无Git镜像与Compose浏览器冒烟；目标门禁保留。
- [历史独立开发包0.1.0-dev.20261009.1](zh-CN/integration/development-delivery-2026-10-09-1.md)（[English](integration/development-delivery-2026-10-09-1.md)，[验收摘要](../release/verification/0.1.0-dev.20261009.1.json)）：P02–P06、90files/109links、834全量0skip、精确扫描、独立SDK/无Git包内镜像构建及真实Compose账号站冒烟，映射缓存/目标门禁限制保留。
- [历史独立开发包0.1.0-dev.20261007.1](zh-CN/integration/development-delivery-2026-10-07-1.md)（[English](integration/development-delivery-2026-10-07-1.md)，[验收摘要](../release/verification/0.1.0-dev.20261007.1.json)）：三模块程序/SDK/源码、独立中英手册、配置维护与来源摘要；735全量0skip、独立解压/双metadata消费及实际模板DBreadiness验证。
- [历史交付0.1.0-dev.20261006.7](zh-CN/integration/development-delivery-2026-10-06-7.md)（[English](integration/development-delivery-2026-10-06-7.md)，[manifest](integration/development-delivery-2026-10-06-7.json)）：V15修refresh后oldIDhint global400，735全量0skip；实际Grafana desktop/mobileViewer及两chain十分钟12,598混合操作/120refresh/无reuse，productionrampsoak另验。
- [历史交付0.1.0-dev.20261006.6](zh-CN/integration/development-delivery-2026-10-06-6.md)（[English](integration/development-delivery-2026-10-06-6.md)，[manifest](integration/development-delivery-2026-10-06-6.json)）：734全量0skip、低基数领域指标/独立管理端口认证/缓存DBsnapshot、Prometheus到Alertmanager实际3类firing/resolved与TLS双链；当时面板只query已验。
- [历史交付0.1.0-dev.20261006.5](zh-CN/integration/development-delivery-2026-10-06-5.md)（[English](integration/development-delivery-2026-10-06-5.md)，[manifest](integration/development-delivery-2026-10-06-5.json)）：修数据库中断readiness假200，717全量0skip；最终JAR重验TLS双链/管理员/restore、有界容量与JVMpool观察；当时领域指标/告警未实施。
- [历史交付0.1.0-dev.20261006.4](zh-CN/integration/development-delivery-2026-10-06-4.md)（[English](integration/development-delivery-2026-10-06-4.md)，[manifest](integration/development-delivery-2026-10-06-4.json)）：Boot4.1.1/Tomcat11.0.26/Jackson3.1.7等安全补丁、三锁及716全量通过；17不同CVE复扫不再报告，保留源码误报复核与秘密扫描覆盖边界。
- [历史交付0.1.0-dev.20261006.3](zh-CN/integration/development-delivery-2026-10-06-3.md)（[English](integration/development-delivery-2026-10-06-3.md)，[manifest](integration/development-delivery-2026-10-06-3.json)）：修代理Forwarded/Prefix入口、参考https profile，716全量及真实默认/自有链TLS/SecureCookie/HTTPSBack-channel/伪header风控验收。
- [历史交付0.1.0-dev.20261006.2](zh-CN/integration/development-delivery-2026-10-06-2.md)（[English](integration/development-delivery-2026-10-06-2.md)，[manifest](integration/development-delivery-2026-10-06-2.json)）：716全量、撤销Cookie OIDC继续请求修复，真实bootstrap/首次改密/恢复码download/离线reset与退出。
- [历史交付0.1.0-dev.20261006.1](zh-CN/integration/development-delivery-2026-10-06-1.md)（[English](integration/development-delivery-2026-10-06-1.md)，[manifest](integration/development-delivery-2026-10-06-1.json)）：709全量/恢复18新增，真实pg_dump/restore/交互脚本/持久新key/旧Token拒绝与后台任务恢复。
- [历史交付0.1.0-dev.20261005.4](zh-CN/integration/development-delivery-2026-10-05-4.md)（[English](integration/development-delivery-2026-10-05-4.md)，[manifest](integration/development-delivery-2026-10-05-4.json)）：691全量/参考37数据库测试、实际SSO重启撤销/旧RT拒绝。

- [通用业务网站接入指南](integration/business-system-sso-integration-guide.md)：合作网站及不同技术栈的完整接入流程。
- [LAS 第一方网站接入指南](integration/first-party-system-sso-integration-guide.md)：LAS 自营系统的客户端登记与实现要求。
- [Starter 安全链组合与生命周期](zh-CN/integration/starter-security-composition.md)（[English](integration/starter-security-composition.md)）：SSO-01 公开 configurer/成功回调/换号销毁/错误接点、B2 修复证据及分布式边界。
- [退出投递与撤销回执](zh-CN/integration/logout-delivery-acknowledgements.md)（[English](integration/logout-delivery-acknowledgements.md)）：B3 明确成功状态、禁止重定向、重试/耗尽与真实 HTTP/DB 验证边界。
- [Back-channel 验证与执行回执](zh-CN/integration/backchannel-logout-receipts.md)（[English](integration/backchannel-logout-receipts.md)）：B4 严格 Token、process adapter 升级、并发/失败重试与持久边界。
- [登录返回目标与本地授权事务](zh-CN/integration/login-return-target.md)（[English](integration/login-return-target.md)）：B5输入校验/事务期限/单次、默认与自有链用法、兼容和验收边界。
- [内部第一方 Basic 认证预算](zh-CN/integration/internal-basic-authentication-budget.md)（[English](integration/internal-basic-authentication-budget.md)）：A3配置/占用/归还、429/503、PostgreSQL哈希前并发与生产边界。
- [开发交付0.1.0-dev.20261005.1](zh-CN/integration/development-delivery-2026-10-05.md)（[English](integration/development-delivery-2026-10-05.md)，[manifest](integration/development-delivery-2026-10-05.json)）：SSO-03publiccustomizer/验证Token访问、SSO-06identifiedserverstarter/sourcehash/锁/制品消费、SSO-04epoch兼容。
- [历史开发交付0.1.0-dev.20261005.2](zh-CN/integration/development-delivery-2026-10-05-2.md)（[English](integration/development-delivery-2026-10-05-2.md)，[manifest](integration/development-delivery-2026-10-05-2.json)）：UserInfo修复与真实浏览器闭环。
- [历史开发交付0.1.0-dev.20261005.3](zh-CN/integration/development-delivery-2026-10-05-3.md)（[English](integration/development-delivery-2026-10-05-3.md)，[manifest](integration/development-delivery-2026-10-05-3.json)）：B2 sid迟到登记保护、精确维护JAR/hash、654全量及真实默认/自有BFF回归。

## 部署与运维

- [单层外部Nginx准备2026-10-11](zh-CN/operations/external-nginx-validation-2026-10-11.md)（[English](operations/external-nginx-validation-2026-10-11.md)）：独立host Compose/模板/验证器、14工具case、TLS探针转发及SNAPSHOT隔离运行，目标域名/HTTPS留正式上线。

- [操作手册验证2026-10-11](zh-CN/operations/operation-handbook-validation-2026-10-11.md)（[English](operations/operation-handbook-validation-2026-10-11.md)）：中英各17页/57流程、59 API operation覆盖，415渲染links、命令解析/失败停止及隔离SQL/backup/restore；实际部署/浏览器证据另验。

- [新版发行手册导航](zh-CN/operations/handbook-reading-guide.md)（[English](operations/handbook-reading-guide.md)）：Docker优先入口、步骤/结果/失败表、排障与历史清理/参考队列专项。

- [Docker部署/环境输入矩阵](zh-CN/operations/docker-compose-deployment.md)（[English](operations/docker-compose-deployment.md)）：单机/无CDN/账号站首发确认、待定值、P06–P10目标门禁；[容器步骤](../las-sso-server/deploy/docker/README.zh-CN.md)／[English](../las-sso-server/deploy/docker/README.md)。

- [SSO 部署指南](operations/sso-deployment-guide.md)：当前仓库可执行的 JAR、PostgreSQL、反向代理部署步骤。
- [部署与运维手册](operations/operations-runbook.md)：备份、恢复、轮换、监控和故障 Runbook。
- [人工恢复、凭据保管与注销争议](zh-CN/operations/manual-recovery-and-lifecycle.md)（[English](operations/manual-recovery-and-lifecycle.md)）：用户确认的核验流程、无秘密工单模板、管理员离线保管和hold/release边界。
- [历史保留与运维清理](zh-CN/operations/runtime-history-retention.md)（[English](operations/runtime-history-retention.md)）：逐表矩阵、history-status只读报告、清理吞吐/指标/忙及超大数据处理、vacuum与归档责任。
- [维护制品选择](zh-CN/operations/maintenance-artifact-selection.md)（[English](operations/maintenance-artifact-selection.md)）：精确JarPath/可选ExpectedSha256、旧默认兼容、PowerShell与实际Java无console拒绝证据。
- [管理员维护闭环验收](zh-CN/operations/administrator-maintenance-acceptance.md)（[English](operations/administrator-maintenance-acceptance.md)）：真实隐藏console/forcedchange/reset失败无变化及成功旧状态失效，OIDC续行修复/边界。
- [恢复epoch操作屏障](zh-CN/operations/restore-epoch-barrier.md)（[English](operations/restore-epoch-barrier.md)）：恢复到隔离库、expected epoch命令、新key/consumerbarrier及重新开放步骤。
- [TLS代理与SecureCookie验收](zh-CN/operations/tls-proxy-acceptance.md)（[English](operations/tls-proxy-acceptance.md)）：单跳边界/私有upstream/CA与SPKI信任范围、部署headerinclude/https profile及生产限制。
- [最终制品真实验收](zh-CN/operations/final-artifact-acceptance-2026-10-06.md)（[English](operations/final-artifact-acceptance-2026-10-06.md)）：依赖升级后.4及readiness修复后.5，实际TLS双链、隐藏维护与备份恢复重验，最终hash与清理核验。
- [有界容量、监控观察与readiness修复](zh-CN/operations/capacity-readiness-2026-10-06.md)（[English](operations/capacity-readiness-2026-10-06.md)）：真数据库down红绿/恢复、短query/login/Argon2/JVMpoolSQL样本、429预算边界，明确领域指标与告警尚未落地。
- [私有监控与告警](zh-CN/operations/monitoring-and-alerting.md)（[English](operations/monitoring-and-alerting.md)）：.6已实现指标与标签语义、profile专用凭据/端口、15s快照失效、7rules/8panel输入及3真通知路径，生产transport/权限与覆盖限制。
- [Grafana与混合负载验收](zh-CN/operations/grafana-mixed-load-2026-10-07.md)（[English](operations/grafana-mixed-load-2026-10-07.md)）：.7 actualimport/desktopmobilecanvasViewer403/十分钟5subject9Session混合及短Tokenfixture、延迟memsamplinggap和soak边界。

## 实施、验证与审查记录

- [API与软件发行准备](zh-CN/implementation/api-release-readiness-2026-10-11.md)（[English](implementation/api-release-readiness-2026-10-11.md)）：实现级OpenAPI/双语参考、正式/RC版本工具、已复现跨client RT重用撤销顺序修正，软件发行与目标部署门槛分开。

- [Compose本地验收](zh-CN/implementation/docker-compose-acceptance-2026-10-09.md)（[English](implementation/docker-compose-acceptance-2026-10-09.md)）：834全量、实际维护/TLS/权限/故障、镜像扫描范围与目标待验。

- [工程规范基线](zh-CN/implementation/engineering-baseline-2026-10-07.md)（[English](implementation/engineering-baseline-2026-10-07.md)）：功能/文档本地提交、共享构建/格式化/CI/校验规则与735全量结果；历史制品保留，生产发行另验。
- [项目状态与正式生产上线计划](zh-CN/implementation/production-launch-plan.md)（[English](implementation/production-launch-plan.md)）：P01–P19从当前基线到正式部署、有限开放和首轮生产运维；本地工作与运维/外部消费方任务分别注明责任。
- [生产配置与启动门禁实现](zh-CN/implementation/production-startup-gates-2026-10-07.md)（[English](implementation/production-startup-gates-2026-10-07.md)）：P02源码门禁、管理员/客户端/密钥/Secret/Flyway校验与聚焦验证；目标环境门禁仍需单独验收。
- [可靠撤销与操作恢复](zh-CN/implementation/revocation-recovery-2026-10-07.md)（[English](implementation/revocation-recovery-2026-10-07.md)）：P03参考V2死信恢复/保留/key排空轮换，提交失败结果及消费补偿边界。
- [核心 UI 与人工流程补验](zh-CN/implementation/ui-lifecycle-acceptance-2026-10-07.md)（[English](implementation/ui-lifecycle-acceptance-2026-10-07.md)）：P04复用历史证据，补当前HTTPS浏览器恢复/注销/一次性秘密/键盘移动端与晋升维护，人员/目标环境仍独立。
- [P05增长与清理测量](zh-CN/implementation/history-growth-2026-10-08.md)（[English](implementation/history-growth-2026-10-08.md)）：10万session/outbox及20万RT/hint合成SQL、actualJava清理/锁回滚/协议连续性，目标容量/审计archive独立。
- [状态地图与问题计划（文件名保留2026-10-02）](implementation/status-issues-and-plan-2026-10-02.md)：最新.7服务端505/starter193/refBFF37共735零失败错误跳过，刷新退出缺陷已修、实际Grafana/十分钟混合通过；生产transport/oncall/剩余指标/小时级soak独立。
- [刷新后退出hint连续性](zh-CN/implementation/refresh-logout-hint-history.md)（[English](implementation/refresh-logout-hint-history.md)）：真实400与PG回归redgreen、V15HMAC-only/CAScascade/显式idtokenlookup、preupgrade兼容及撤销拒绝边界。
- [活跃构建安全扫描与依赖修复](zh-CN/implementation/security-scan-2026-10-06.md)（[English](implementation/security-scan-2026-10-06.md)）：工具/DB/规则hash、17CVE与厂商级别差异、三锁/解包JAR真实覆盖、重定向误报及Secret零匹配限制。
- [实施准备度](implementation/implementation-readiness.md)：编码与生产上线门禁。
- [阶段 0 实施记录](implementation/phase-0-implementation.md)：工程、依赖、迁移和协议基础。
- [阶段 1 账号基础记录](implementation/phase-1-account-foundation.md)：账号、会话、恢复和管理员能力。
- [测试与验收计划](implementation/test-plan.md)：契约测试、集成测试和发布证据。
- [注销事务与并发契约](zh-CN/implementation/deletion-concurrency.md)（[English](implementation/deletion-concurrency.md)）：S3锁序/状态重检/批次回滚、24新增DBrace及容量/部署边界。
- [管理员晋升与有效注销申请](zh-CN/implementation/admin-promotion-deletion.md)（[English](implementation/admin-promotion-deletion.md)）：S4门禁/先取消重试、25数据库+3命令fixture、并发回滚与真实维护验收边界。
- [管理员墓碑详情](zh-CN/implementation/admin-tombstone-views.md)（[English](implementation/admin-tombstone-views.md)）：S5终态只读、明确null/无凭据展示、18数据库/MockMvc/模板回归与终态写拒绝。
- [恢复凭据熵与旧码兼容](zh-CN/implementation/recovery-credential-entropy.md)（[English](implementation/recovery-credential-entropy.md)）：S6新16随机字符80bit、旧HMAC/单次期限兼容、9JVM/33数据库MockMvc与轮换/审计边界。
- [Spring Security同意页补丁与交付](zh-CN/implementation/spring-security-consent-patch.md)（[English](implementation/spring-security-consent-patch.md)）：D1/CVE-2026-47877、Security7.1.1对齐、renderer/consent回归、三锁/bootJar与原生/POM-only制品解析。
- [Servlet HTTP路径安全验证](zh-CN/implementation/servlet-path-security.md)（[English](implementation/servlet-path-security.md)）：A1/A2直连TCP/Tomcat根与/passport各48回归、真实密码/JDBC会话/编码门禁与一次CAPTCHA，明确proxy/TLS/browser边界。
- [客户端Secret竞争与回滚](zh-CN/implementation/client-secret-concurrency.md)（[English](implementation/client-secret-concurrency.md)）：S2新增23DB/MockMvc双向mutation/SQLfault/幂等认证对照。
- [资格epoch与生命周期响应](zh-CN/implementation/service-access-epoch-contract.md)（[English](implementation/service-access-epoch-contract.md)）：七字段/同代次版本/恢复义务、17querycontract与80query小load记录，非productionSLA。
- [安全审查修复记录（2026-08-09）](implementation/security-review-remediation-2026-08-09.md)：已发现问题与修复记录。

## 界面规格

- [SSO UI 流程规格](ui/ui-flow-spec.md)：页面状态、跳转、危险操作和响应式要求。

## 阅读顺序

新参与者建议按以下顺序阅读：

1. [产品总纲](../LAS_SSO_DESIGN.md)；
2. [身份与数据模型](architecture/identity-and-data-model.md)；
3. [OIDC 与 BFF 接入契约](architecture/oidc-and-bff-contract.md)；
4. 根据任务选择[业务接入](integration/business-system-sso-integration-guide.md)或[SSO 部署](operations/sso-deployment-guide.md)；
5. 上线前检查[实施准备度](implementation/implementation-readiness.md)和[测试计划](implementation/test-plan.md)。
