# 历史发行候选0.1.0-rc.1 — 2026-10-11

[English](../en/release-candidate-2026-10-11-rc1.md) · [证据](../../../release/verification/0.1.0-rc.1.json) · [发行入口](../../../release/README.md)

## 交付与来源

用户授权分组本地提交及clean RC build/scan/package/验收。四提交：`ea7ef4a`刷新归属、`ba81a2b`版本/API工具、`b626c88`外部Nginx、`8db0b20`双语流程/证据。RC精确干净源码**`8db0b20e6c5898064ef59f657a15d5758b5dd166`**，tree `21ce6cf76e392a5a962c05603e59c6d03747155e`；后续验收文档提交不改该来源/字节。

- 目录：`release/0.1.0-rc.1/`。
- ZIP：`release/las-sso-0.1.0-rc.1.zip`，**81,512,271 bytes**，113文件/451包内链接。
- ZIP SHA256：**`6c8be5bbfee723d529217430fde8515ef5f64648e4355590fb184a2eb5682c04`**。
- source ZIP：`554bec04720c242329b8af4f6103f47040e7ade2074eeee1cdd5cdba9d1fa7e0`。
- server：`c0e35f22b941f0b5cf7fe421f977ddc59467a1bb6e06f8b0aab4d75db96d2319`。
- Starter：`1a916c9df33f2a0b5eab88795cdc49bce1115d4b3c6914f5d65f81608841f5ad`。
- reference：`d2d33254b9ff3281b2a4cea5d7541e9a4ee3d6cb5387c3f41fec595e263f820c`。

Starter实现未变，因此内容hash与`.2`相同；精确RC坐标/POM/native metadata已发布并独立消费。server/reference manifest为精确RC版本；`.2`及更早发行保持不可变。

## 构建、扫描与包

strict clean/重跑/database-required：**server597+Starter196+reference50=843**，fail/error/skip全0，UTC07:26:35–07:33:40；精确Starter发布成功。14Python工具case、双语18页/63流程、OpenAPI49path/59operation/54schema及包内API/version/ref校验通过。

Trivy0.75.0 advisoryDB UpdatedAt2026-10-11，241锁/157nestedJAR依赖，报告依赖漏洞0。Java mapping更新检查exit0，保October9种子且October12前在新鲜期，无失败回退。Semgrep1.179.0扫描514路径、6条现有rule/path身份复核、0error。Gitleaks8.30.1源码/产物0；同一历史合成keystore路径保exit1/原report及复核，无suppress。独立完整包depth3秘密扫描0。

ZIP独立解压核inventory/source/artifact/migration/API/links；tamper/extra/错误维护hash/空拓扑输入拒绝。native/POM-only SDK编译及依赖对齐通过。Python/PowerShell、Prometheus config/rules/fixture及Alertmanager config通过。无Git的包内工具构建精确镜像`sha256:4357c1e441bfe1e6e16dbb4978f350e2a5695a125693865dafa40d711f6ee8be`，source/JAR标签正确、unverifiedFixture=false；包构建后仍原字节。

## 精确制品运行验收

独立host-edge Compose、私密随机secret/key和PG18.4 fixture；单跳TLS用隔离Nginx容器按包site/forwarding模板表示，Windows未执行Linux主机安装Nginx。Java/Node显式信任短期私有CA，Edge为fixture SPKI例外，非publicCA验收。

空库offline V1–V16、migrator/runtime grants、隐藏bootstrap、浏览器首登强改/新admin登录、ALTCHA注册/10码、profile、手机恢复、冻结解冻、受限注销登录/取消、Secure hostCookie及SSO restart同key/JDBC session通过。伪代理头canonical metadata、Host421、公网actuator404通过。app仅loopback，DB/管理不发布。

两admin真实登记client及精确参考JAR的默认/自有链均通过：Code/PKCE/UserInfo/稳定subject、自动refresh、固定family/不推进auth_time、foreign ROTATED RT拒绝且owner无撤销、错密码再有效prompt=login challenge、local保SSO退出、global/真实HTTPS Back-channel及伪return-state拒绝。fixture BFF refresh-before-expiry设15分钟以立即触发轮换，OP AT仍默认15分钟。重认证保原BFF state/nonce/verifier事务，仅harness加标准参数，不代LASLOG待动作适配。

已填数据同V16 `.2`→RC binary切换、offline验证及事实/epoch保留通过，不宣称新DDL/代表旧schema升级。实际隐藏reset产生ACTIVE密码、旧admin session结束及新admin登录通过。backup-role custom dump/list、独立空目标migrator restore、还原后Flyway/grants通过。无console epoch拒绝，真实交互epoch/key轮换保用户事实、五安全状态计数归零；旧Cookie/AT/RT/code拒、新密码登录通过。readiness200→503→200、DB停时liveness200、私有metrics401/200通过；容器日志无fixture秘密值。

包内reference维护另验V1/V2、无console/取消拒、认证加密合成deadtask重放/stale generation拒、completed purge/audit、排空keyrotation/旧进程fence/新key restart。未声称真实worker远端撤销回执。生成secret/key/browser私有状态、隔离容器/网络/卷已清理；用户/原本地数据未操作。

仅harness失败已修：刷新配置需restart BFF、重认证需原pending request、async outbox需等待、恢复页面导航用path/DOM判定。早期assert将合成RT写私有errorlog，该日志已删且错误改stage/type/locations；无此类运行时源码修复。

## 镜像发现及正式版后续

**RC镜像不能称安全验收全通过。** 精确SSO image及effective rootfs报告3High/45Medium/5Low/4Unknown。3High均继承`/usr/bin/pebble`的Go stdlibv1.26.7：CVE-2026-78667/78669/97031，修复版本1.26.9/1.27.2。固定SSO入口不调用该工具/原CA入口，但文件仍存在。正式镜像前移除/升级不使用工具，对新辨识image输入复扫/重验；RC字节不改。

PG archive1Critical/24High/27Medium/3Low/5Unknown为已移除gosu元信息；有效regular-rootfs54包/0报告漏洞，gosu实际symlink到su-exec、旧regular binary不存在，两report保留。复用未补丁Bookworm edge fixture有10Critical/150High/268Medium/208Low/23Unknown，不推荐生产；已选主机Nginx须独立scan目标OS/包。

正式域名/publicCA/续期、Linux权限/防火墙、真实采集/Grafana/收件人、加密异机恢复/RPO/RTO、真实容量和已上线consumer仍目标阶段。RC可受控评估，**不是stable0.1.0或生产批准**。无push/托管发布/用户服务器操作；post-package证据刻意在不可变包外。
