# P04 核心 UI 与生命周期补验

[English](../../implementation/ui-lifecycle-acceptance-2026-10-07.md) · [ADR-0014](../adr/0014-manual-recovery-verification-and-secret-ui.md) · [人工流程](../operations/manual-recovery-and-lifecycle.md)

## 复用已有工作

P04补证据，不重新实现UI。账号/恢复/注销/admin页和API已存在。历史[管理员维护](../operations/administrator-maintenance-acceptance.md)已验hidden bootstrap/首改/download/reset/旧sessionToken拒；[旧码兼容](recovery-credential-entropy.md)、[注销竞争](deletion-concurrency.md)、[晋升](admin-promotion-deletion.md)、[墓碑](admin-tombstone-views.md)已有DB/MockMvc/template证据。旧OIDC/TLS/双BFF仍绑定原artifact，不自动算本轮。

## 当前浏览器与维护层

含P02/P03的当前SNAPSHOT server JAR、JDK21、隔离PG18.4、真实PowerShell/Windows PTY、Edge154 headless/Playwright1.55.1/axe4.14.0。production持久fixture签名key、loopback直连HTTPS；浏览器仅生成证书SPKI定向例外，Node负例验fixtureCA/SNI/host。无生产库/key/公共CA/deploy。独立情形间清一次性fixture限流桶；恢复/人工凭据/注销日期加速、短job轮询为setup，不改生产默认。

| 情形 | 已验结果 |
| --- | --- |
| bootstrap/首改 | 真实隐藏console、production Web、首改清字段/新密码普通登录 |
| 注册 | 真ALTCHA widget/request/create、十80bit码/save确认、真clipboard反馈读取（WindowsCRLF归一）后清空、download匹配、再访无旧码 |
| 普通恢复 |375px/字段错误焦点、短密码拒、Secure/HttpOnly/Strict受限Cookie、完成废旧登录browser/使用后码拒 |
| 旧码恢复 | 明确三组旧HMAC fixture走真browser，不借新generator造旧码，新密码有效 |
| 到期/人工恢复 | 十分钟会话过期回第一步、真实admin issue/reissue、旧/过期凭据拒、当前人工码完整恢复，30分钟加速 |
| 敏感写 | 缺CSRF/foreignOrigin无目标mutation、错误请求内密码不能注销 |
| 注销 | 申请/受限页、hold私有reason不泄/公开摘要可见、本人密码取消HELD、due fixture真scheduler执行、admin墓碑无写按钮 |
| release/冻结/下线 | hold→release保原execute_after后本人cancel，freeze废旧登录/unfreeze新登录，admin强下线废browser访问 |
| 一次性输出 | save/close/Escape清人工凭据/clientSecret，真create/rotate；两次真实同幂等issue模拟响应不确定，无明文错误保持可见 |
| 键盘/布局 | native modal焦点在dialog或浏览器chrome、Escape、审计PageDown；375×812/812×375/768×1024/1440×1000及320/720px重排无横溢 |
| 既有用户晋升 | 停Web、独立fixture克隆，SQL去admin仅no-admin setup；真hiddenconsole拒有效注销且credential/audit不变；克隆显式取消可唯一晋升；browser验临时过期拒/有效首改后新admin登录 |

本人取消在主fixture真UI已验；晋升克隆取消为SQL setup，非允许运维代取消。临时到期为日期加速，不等24小时实等。业务权限/资格/consumer十五分钟门禁和真人QQ核验不由fixture验。

## 修复与回归

- 关闭一次性dialog后隐藏DOM仍有人工凭据/Secret：save/close/Escape同步擦，close/pagehide补清理。
- register复制await后event.currentTarget失效：提前保button，success/failure无JS错。
- 幂等无秘密结果的错误写到已关闭dialog：可用明文/成功前保持confirmation可见。
- 成功隐藏步骤/原生dialog关后密码残留：成功reset、close/cancel/pagehide清，reveal字段还原password。
- 轮换恢复码加保存确认/未保存离页提示/pagehide擦，history不复明文；clipboard/download外副本用户保管。
- 管理确认显示稳定user/request/client目标；dialog名称描述/login标题引用/brand可见name修。
- ALTCHA对比度修；audit/deletion/client滚动region可键盘聚焦。

跟踪[浏览器回归工具](../../../scripts/browser/README.md)，源DOM/JS/native/clipboard/history用stub协议响应，无SSO真实凭据，另于真实JAR/PG。npm ci/test固定Playwright/已安装Edge，不称CI浏览器已跑。

## 可访问性及剩余

主24/补17渲染状态修后axe A/AA/2.1 A/AA无报告违规、无横溢/脚本错。装饰背景导致自动contrast incomplete；五代表桌面/手机/横屏solid-background复查零contrast违规，一disabled rotate control未自动判断。是有界证据，非整站WCAG认证/真人读屏/所有文字控件合格；小密集元信息/真设备与读屏仍目标复查。启用reduced-motion。720等效重排/320px替换不适用body CSS zoom specimen，不称browser-level zoom认证。

用户确认两项独立人工核验/最小无秘密工单；手册模板完成，真实负责人/培训、key/恢复码离线保管、真人核验/争议仍运维。partner consent/browser在合作首发前条件验，旧release不改。

## 最终验证

严格强制JDK21/Docker构建 **562server+196 Starter+50 reference=808**，失败/错误/跳过零。scripts/browser的npm ci --ignore-scripts及npm test四组交互通过，与JUnit计数独立。未改应用Java/依赖/SSO迁移。最终server SNAPSHOT SHA-256 `2d11bc988aa77cecb653480dbc1f3d9d913c7e3e8d9c65803916eb88405288f7`，基线ae27c3a加本轮P04工作区；主/补/lifecycle在该JAR通过。晋升/临时到期本轮也已演练，no-admin/cancelled克隆setup明确；审查后源码commit标识改动，不是替换发行manifest。

初fixture偏差（download标题/错误HTTP期待/异步等待/DB日期约束/Node测试域DNS/Windows clipboard CRLF）修工具不改policy。原生dialog Tab可至browser chrome（document.body），不能到背景控件，原过严断言已修。body CSS zoom不等viewport media-query重排，不将溢出结果当browser zoom证据。上列真实UI缺陷另修并重验。截图/原始日志/private fixture在源码外，人工手册无真实凭据。0.1.0-dev.20261007.1仍历史，不含P02–P04。

最终源码524文件Gitleaks8.30.1/redact100零发现；运行日志对已知fixture秘密/JWT模式零匹配（定向非全环境）。仓库502text/1215本地链接/一个声明兄弟链接、三锁/Wrapper checksum/15迁移通过。最终复验后停删fixture JVM/PG及外部private账号/config/PKCS12文件，未production操作/push；加载稳定状态截图再查，contrast仍上述有界结果。不称本轮最终依赖/image/DAST扫描或整站可访问性认证。
