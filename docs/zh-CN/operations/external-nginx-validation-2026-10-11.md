# 外部主机Nginx准备 — 2026-10-11

[English](../../operations/external-nginx-validation-2026-10-11.md) · [ADR-0017](../adr/0017-single-external-nginx-container-edge.md) · [N01–N06](../../../release/_source/docs/zh-CN/host-nginx-deployment.md) · [前次手册验证](operation-handbook-validation-2026-10-11.md)

## 已选范围与来源

续作：下方检查后用户明确授权分组本地提交及干净`0.1.0-rc.1` build/scan/package/验收。未提交描述保本记录先前验证范围，不代最终候选来源；未请求push或目标服务器操作。

用户确认同机单层外部Nginx，正式域名/publicHTTPS在目标正式上线再确定。软件准备继续用明确隔离fixture；实际目标DNS/CA/续期、转发、登录、监控、备份及容量不勾完成。用户回报服务器SSO/PG已启动，本记录仅本地验证，不是从该服务器采集的证据。

修改仍未提交，HEAD为`297cdfc0dcf06a9db421064d5328b7bb71f07859`，未新建候选。历史`.2`归档不可变；干净候选须明确本地提交授权、精确新版本及自己的build/scan/验收。

## 已实现准备

- 独立`compose.host-nginx.yml`：SSO/PG/offline维护，应用一项IPv4 loopback发布，management/PG不发布，保原project卷命名；不需内置proxy镜像/限额/TLS挂载。
- 独立host非秘密env、主机Nginx site、完整canonical转发include及双语N01–N06安装/切换/升级/指标步骤。内置模板保替代，helper分别选择。
- 验证器`--topology host-nginx`核service/project/env/image/resource、私有网络/权限/loopback端口，拒wildcard/管理/DB/维护暴露及非法kid；拒绝不输出实际输入。
- 基础设施可只构建PG，保旧两镜像用法，回执区别dirty fixture及可信来源。
- 补私密bcrypt生成、cost/前缀/原密码区别及kid/monitoring启动排障。正式输入留目标阶段，production门禁未放宽。

## 已完成验证

| 层级 | 实际结果 |
| --- | --- |
| Python工具测试 | 14项通过：旧9发行工具及5拓扑/基础设施测试，含多种负例 |
| 真实Docker Compose | 两源码模板解析，host无需proxy输入通过，同project保相同postgres_data卷名 |
| Nginx语法/TLS探针流量 | 隔离Nginx fixture按主机site及转发include对probe后端验证 |
| HTTP行为 | canonical308、本site错Host421、actuator404、OIDC完整路径/query保持 |
| 伪元信息 | Forwarded/prefix清除，host/HTTPS port/proto/client地址由入口覆盖 |
| 双语渲染手册 | 各18页/63流程，59 API operation覆盖、451本地links |
| 示例解析 | 66 Bash、16 PowerShell、2 JavaScript块通过，原10项失败停止stub仍通过 |

入口流量fixture为probe后端、显式信任的一天自签证书及容器测试socket，仅证明转发/模板行为，不代真实SSO OIDC、publicCA、Linux主机权限或续期。

## 独立Compose运行演练

使用既有当前Java制品及源码launcher构建另行标识的dirty工作区SNAPSHOT fixture，明确`unverifiedFixture=true`、`sourceCommit=null`、`productionApproved=false`，不冒充干净候选/正式新交付。

- JAR SHA256：`4f2f9f29cd56b2569f84eadb59a6a9485beb627754bfed861e7ad33b822121c1`。
- fixture image ID：`sha256:7520fa4913b66ce19e2b8bc183c90801b80384c4c1541c5e638c300c8da82571`。
- 新隔离project/DB、独立随机秘密/signing key、既有PG18.4 fixture镜像，原本地/用户数据未操作。
- 新独立Compose：offline V1–V16、migrator所有者/runtime权限调整、隐藏console bootstrap通过。
- Web readiness UP，主机loopback `/login`200；Docker inspect只见`9000/tcp`绑定`127.0.0.1:<fixture-port>`，9091/DB不发布。
- 指标无认证401、独立原密码经stdin curl配置200；SSO restart恢复readiness UP。
- fixture容器/网络/DB卷、生成key/config/secrets及image tag清理，受限count-only/诊断证据仓库外保留。

未在Windows构建机执行主机安装Nginx、未将入口probe fixture与真实SSO连接、未完成浏览器强改/恢复/OIDC/refresh/logout、填数据升级/恢复或LinuxACL证明。它们仍为精确候选/目标演练，旧843项JVM保日期工作区事实。

## 软件发行下一步

最终仓库检查589 UTF-8/text候选、1765本地链接、1声明兄弟链接、三锁/Wrapper及V1–V16通过。`git diff --check`仅既有CRLF转换warning，无空白错误。Gitleaks8.30.1/redact100扫描605跟踪/未忽略源码candidate含本轮：exit0/0发现，路径/hash清单及脱敏报告私有保留。不可变`.2` ZIP仍SHA256 `1e9d5782536cb0eec00f9175060b3ed4291e81a14be72f230e701c2a001b38eb`。源码检查不代最终artifact/history/image扫描，加入本结果段后再做最终扫描。

用户授权后审查/提交intended实现/文档/工具，选新候选，strict clean精确版本build/publication/当前scan，独立pack/extract/consume/build，再验支持的账号/OIDC/维护/升级/恢复全链。正式域名/publicHTTPS及真实服务器运维按要求留目标上线。这里未commit/push/远程发布/实际生产操作。
