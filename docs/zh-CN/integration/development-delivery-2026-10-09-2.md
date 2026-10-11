# 开发交付 0.1.0-dev.20261009.2

[English](../../integration/development-delivery-2026-10-09-2.md) · [发行包入口](../../../release/README.md) · [验收记录](../../../release/verification/0.1.0-dev.20261009.2.json)

最新本地独立交付：`release/0.1.0-dev.20261009.2/` 与 `release/las-sso-0.1.0-dev.20261009.2.zip`。ZIP 共 81,241,365 字节、98 个文件、229 条包内手册链接。中英[Docker 教程模板](../../../release/_source/docs/zh-CN/docker-deployment.md)各有八个按任务组织的步骤，写明预期结果和失败处理；解压包首页直达部署、排障、运维和接入。本次是开发交付，不是生产批准。

## 来源与构建

干净已提交源码 `297cdfc0dcf06a9db421064d5328b7bb71f07859`（tree `a7b9bc6493083d1959d079158391ab6e77b45d59`）。JDK 21、Gradle 9.6.1，三模块强制 clean 与全量重跑，要求数据库测试：**服务端 588 + Starter 196 + 参考 BFF 50 = 834**，失败、错误、跳过均为 0；Starter 本地 Maven 发布通过。未来 `nbf` 拒绝测试数据给 JWT 秒精度和 JWKS 延迟留出余量，运行时校验未改。

Trivy 0.75.0 识别 241 条锁文件依赖、157 条嵌套 JAR 依赖，报告漏洞 0。漏洞库更新于 10 月 8 日且通过新鲜度检查；Java 包映射库于 10 月 9 日成功下载更新。Semgrep 1.179.0 扫描 488 个路径、错误 0、命中 6 条，与上一版已复核的规则、文件及行号一致。Gitleaks 8.30.1 当前源码和产物命中 0；历史上一条合成的测试 keystore 路径匹配保持原退出码 1，并明确记录复核。独立解压的完整包 depth-3 扫描命中 0。扫描数量与工具覆盖范围不能替代目标镜像安全验收。

## 独立解压验收

在 ZIP 新解压副本中，`scripts/verify-package.py` 核对文件清单、摘要、源码归档提交、产物坐标、内嵌 V1–V16 迁移及全部手册链接；篡改和额外文件均被拒绝，恢复原件后再次通过。Python/PowerShell 工具解析通过，维护脚本在启动 Java 前拒绝错误 JAR 摘要，空白 Docker 输入被拒绝。两套独立 Gradle 消费者分别使用 Starter 原生与仅 POM 元数据完成编译，并核对 Boot 4.1.1、Security 7.1.1、Framework 7.0.9、Log4j 2.25.5 对齐。Prometheus 3.6 配置、规则、规则 fixture 和 Alertmanager 0.28.1 配置通过。

无 Git 的独立解压目录利用包内工具和精确 JAR/manifest 构建镜像，外部保存回执并核对来源标签：`sha256:3a8a328bef7d06927a48ebb678534e9fa1da537d1b4d1ad50197322f8f16b143`。构建后包未变化。复制包内 Compose/properties，加私密随机测试秘密后，实际通过空库 V1–V16 离线迁移、migrator 对象所有权与 runtime 历史只读、迁移重复执行、隐藏 TTY 初始化管理员、浏览器首登强改、Secure host Cookie、规范代理与未知 Host/actuator 检查。SSO 重启后 key/JDBC 会话连续；DB 中断时 readiness 200→503→200、liveness 保持 200；私有监控凭据缺失/正确时返回 401/200。浏览器页面错误 0，测试秘密原值未出现在容器日志。隔离容器、网络、卷和外部秘密/key/config 已清理。

## 不可变 SHA-256

| 项目 | SHA-256 |
| --- | --- |
| ZIP | `1e9d5782536cb0eec00f9175060b3ed4291e81a14be72f230e701c2a001b38eb` |
| 源码 ZIP | `c792ee5037e44b8a5c2805f4b789c7a1c6ada69d10a1f4313e472369d3fcfce9` |
| 服务端 | `10c5755b481ec4bdd8461cc75907408b79bb32ce6650f55df613d819299d2310` |
| Starter | `1a916c9df33f2a0b5eab88795cdc49bce1115d4b3c6914f5d65f81608841f5ad` |
| 参考 BFF | `e6bdf55dbfdde06b6faa1947fb675e2e7754d3f099dc90f2a14fd0663fbfadd6` |

旧 `.20261009.1` 和 `.20261007.1` ZIP 摘要仍与各自记录一致。浏览器采用两天自签测试证书与 SPKI 例外；PostgreSQL/Nginx 复用以前已验的本地测试镜像，非本轮目标镜像验收。正式 DNS/公共 CA、Linux UID/ACL 保管、生产镜像/JDK/OS 扫描、异机恢复与 RPO/RTO、容量、通知及全部消费方联验仍是[目标环境门禁](../../implementation/production-launch-plan.md)。本版未重跑完整双 BFF OIDC/refresh/logout/restore/Grafana/on-call 流程；未 push、托管发布或操作生产数据库。
