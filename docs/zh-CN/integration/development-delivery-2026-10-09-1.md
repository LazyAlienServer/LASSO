# 开发交付 0.1.0-dev.20261009.1

[English](../../integration/development-delivery-2026-10-09-1.md) · [打包入口](../../../release/README.md) · [包后验收](../../../release/verification/0.1.0-dev.20261009.1.json)

最新独立本地交付：`release/0.1.0-dev.20261009.1/`、`release/las-sso-0.1.0-dev.20261009.1.zip`。ZIP81217917字节（约77.5MiB），90files/109包内links。旧`.20261007.1`字节不变。本次开发交付，不等生产上线批准。

## 来源与内容

- 干净commit `b84748640c26fc835f336cdbb24b38cf96dc0da5`，tree `75f69287d810dd687144ccda777e447a4e99ad35`；后续验收提交不替换该快照。
- P02启动/key/config/admin门禁，P03参考V2恢复/保留/轮换和提交结果，P04生命周期/恢复/secretUI及人工流程，P05 V16有界历史清理，P06单机Compose/离线迁移/辨识镜像工具。
- 精确server/参考BFF可执行JAR，Starter JAR/POM/native/checksums/file Maven，sourcegitarchive、SSO内嵌V1–V16/参考V1–V2分离、三锁/catalog/Wrapper/nestedlibraries来源。
- 中英独立手册/发行说明，Docker/Nginx/systemd/monitoring/config模板、console维护、只读status/smoke和完整性工具。ZIP无实际key/secret/backup/log或容器镜像。

## 本轮检查

干净JDK21/Gradle9.6.1、显式server/Starter版本、全任务rerun、数据库必需：**server588+starter196+reference50=834**，失败/错误/跳过0；Starter本地发布成功。Trivy0.75.0识别241lock/157nestedJAR包，0报告漏洞；漏洞库更新2026-10-08，Java映射下载失败后明确用2026-10-06缓存，记录日期/hash/限制，不称最终目标全部刷新扫描。

Semgrep1.179.0扫475paths/6findings/0errors：固定context重定向、两处operator-local/固定ruleURL、三可信本地XML元信息经复核，无新productionpath缺陷。Gitleaks8.30.1当前source/artifact0，history1固定MockEnvironment密钥库路径标签复核，保exit1、不刊秘密/源码、不ignore；独立解压完整包depth3扫0。数量不证明无所有缺陷。

独立解压核inventory/version/source/migration/link/hash，改文件/多文件负例拒、恢复后再通过；PowerShell/Python解析/错误维护hash拒Java前。独立Starter native/POM消费编译publicAPI，核Boot4.1.1/Security7.1.1/Framework7.0.9/Log4j2.25.5。Prometheus3.6 config/七rules/fixtures、Alertmanager0.28.1config通过，systemd仍模板。

无Git独立解压目录里Docker工具核包来源/精确JAR/外部staging/非fixturecommitlabel。image `sha256:08867282620512116e4920d26e8079196069c86b556fa2178ef1e7c98489522e`，构建后包未改。复制Compose/properties，私密随机secret/PKCS12、旧已验修补PG/Nginx和loopback限额，真实通过：

- 空库V1–V16 nonWeb迁移、migrator拥有对象、runtime Flyway只读SQL、重复exit0；
- 真实TTY hiddenbootstrap/TLS首登强改、Secure/HttpOnly/Lax hostonlyCookie；
- canonical伪头不改endpoint、HTTP308/未知Host421/actuator404、app/DB/metrics无host口、app/proxy非root只读；
- SSO重启同JWKS/JDBC登录、私有metrics401/200、无TTY维护拒；
- DB故障readiness200→503→200/liveness200、account恢复、browserpageerror0、fixture精确secret无日志。

TLS两天 `auth.las.test` 自签、HTTP指定证书/hostname、browser SPKI例外，不等正式DNS/公共CA/续期/firewall。基础设施复用已验修补本地镜像，不等当前目标新构建扫描。容器/网络/卷、外部key/secret/config/private-state均删除。

## 不可变 SHA-256

| 项目 | SHA-256 |
| --- | --- |
| ZIP | `3009f5d8b748d677a845b25fa304a9d0353f5fb29fa184850573ae4f8aede11c` |
| Source ZIP | `3c23b83321a5e5382c78078a38587ddb695a28fa86276e302d7ae4d678049031` |
| Server | `fb8b702d8719b45b6daa845c57decaf14d85e58dd7dce4770c9f4f70ce3df2a0` |
| Starter | `1a916c9df33f2a0b5eab88795cdc49bce1115d4b3c6914f5d65f81608841f5ad` |
| Reference BFF | `95eb7204fd57eaaa492ae844dce6cb1df7a50805e1179254a21a19e52064c6fe` |

解压根执行 `python scripts/verify-package.py`，读README/RELEASE-NOTES两语及对应手册。SHA256是完整性，不是发行签名；包后结果外部记录，不改已交付字节。

目标域名/硬件流量/key保管/backup/RPO/RTO/通知/安全/soak/最终上线仍[计划](../implementation/production-launch-plan.md)；本版本完整双BFF OIDC/refresh/logout/restore/Grafana/oncall未重跑，未来各消费方P11/P12独立，账号站首发仍需目标运维门禁。无push/托管发行/productionDB操作。
