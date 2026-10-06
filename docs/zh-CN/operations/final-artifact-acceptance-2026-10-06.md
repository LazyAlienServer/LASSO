# 最终制品真实验收 — 2026-10-06

- [English](../../operations/final-artifact-acceptance-2026-10-06.md)／[当前.5交付](../integration/development-delivery-2026-10-06-5.md)／[manifest](../../integration/development-delivery-2026-10-06-5.json)／[容量与readiness](capacity-readiness-2026-10-06.md)。
- 范围：隔离单实例SSO、默认/自有参考BFF链及本机维护恢复，不操作生产库、不实施业务consumer。

## 1. 精确制品与重验

依赖升级后的`.20261006.4`首先通过TLS双链browser、管理员维护和真实restore；随后数据库中断观察发现readiness错误保持200，形成`.20261006.5`。**三套外部演练均使用.5重跑**，不是直接沿用.4推断。

| 最终制品 | SHA-256 |
| --- | --- |
| SSO bootJAR | f845e54b7dcde555894a66d9a52f458c48baeb5cb95e4db3c5a7461461f3ae65 |
| Reference BFF bootJAR | 34af02ba84a1026c20171a35349bad49568ccd6b3985d57d5a3b92c6c2c8e78e |
| Starter普通JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

编排启动前逐次核验manifest hash，维护脚本传精确JarPath/ExpectedSha256。后续完善回归fixture再强制构建，三个runtime字节hash均不变；最终源码fingerprint包含最终test，因此运行证据和源码/测试来源对应同一组合。旧ID/JAR保历史。

## 2. TLS与两BFF链

2026-10-06最终browser **19:33:24–19:34:07 Asia/Shanghai**。复用[TLS流程](tls-proxy-acceptance.md)：privateDocker网络/PG18.4/Java21容器/Nginx、独立短期CAleaf和持久RSA signingkey，appDB端口不发布、proxy只loopback。实际repo forwardinginclude和打包httpsprofile。Node验证CA/SNI/hostname，JVMtruststore验证服务端HTTPS，Edge仅processresolver和单leafSPKI例外，无globalCA或ignoreallHTTPS。

默认/`application-security`均通过：

- Discovery同origin、恶意Forwarded/Prefix/Host/Proto/For/Port下正确HTTPScallback、固定HTTPredirect、unknownHost421/HSTS/actuator404；
- 实际ALTCHA注册、同主体两个独立密码login、Code/PKCE/JWKS/Token/UserInfo；
- hostonly Secure/HttpOnly/Lax __HostCookie/Path=/无Domain，JS不可读、HTTP不发送；
- browser/request绑定密码重输：别browser401、foreignOrigin403、错密码401不增evidence、正确后签名auth_time推进且sid稳定；
- 缺CSRF退出403且登录保留，current只清本BFFCookie/其他browser与SSO保持，SSO重入/global/伪returnstate400/真正signedHTTPSBack-channel清另browser；
- observedproxyIP CAPTCHA/budget、plain/encodedlogin在伪alternateheaders下仍拒。

fixture阈值2/3、两mode间budgetclear、harnessreauth参数、同sitehost/privateCA保原限制，不代正式cert续期/CDN多跳crosssite、prod20/50窗口、consumerpending/15m或全部UI。八finalcontainerlog定向secret/JWT/recovery/Cookie无命中。

## 3. 管理员维护

最终 **19:52:45–19:55:07**，实际PowerShell入口/WindowsPTY隐藏输入/Edge/finalJava21JAR/一次性PG：

- 空库bootstrap错确认nonzero无变，成功唯一admin/强改状态；
- 两临时密码browser，management/account/OIDC/recovery门禁，缺CSRF/外Origin/错密码/confirm错不改；
- 强改成功废旧临时密码/别Session，新login通过；
- 十个新80bit恢复码一次展示，实际download内容匹配且删除，reload不显明文；
- 最终密码双BFF，停SSO做resetcancel/mismatch/reuse/short无变，hidden成功废recovery/session/auth；
- samekeySSO重启真实Back-channel，旧Cookie/AT/RT/password拒，新密码login/global清双BFF。

一次.5与强制重建并行的attempt在browser前bootstrap transcript断言失败，随后隔离重跑通过，未确立持续产品缺陷或密码泄露，失败记录保留。已有用户晋升/注销取消、live24h、operator保管打印/完整adminUIaccessibility仍独立。四finalprocesslog定向秘密无命中。

## 4. 备份恢复

最终browser **19:41:17–19:42:32**，guards至 **19:43:03**。真正PG `pg_dump -Fc`/`pg_restore --exit-on-error`、两持久PKCS12、精确hash PowerShell expectedepoch/真PTY确认：

- 保UUID/account/credential/client/admin/recoveryfacts，释放pendingoutboxclaim；
- newepoch/newkeykid，oldSSO/BFFCookie/pendingCodecallback/ATRT拒，恢复低counter属newopaqueepoch；
- 原密码fresh双browserCodeTokenUserInfo，开调度后pendingoutbox/due-deletion fixture运行，globalBack-channel；
- no-console/cancel/repeatoldepoch nonzero且securitysnapshot不变。

generationbarrier、备份后freeze/deletion时间为明确fixture，不是业务cache屏障/完整browser注销验收。不恢复备份后facts，不代生产备份介质RPORTo、也不会自动阻止RP缓存旧kid。二十finalrestore进程/编排log定向秘密无命中。

## 5. 构建、扫描与清理

最终强制 **19:43:45–19:49:43**，**487server193starter37reference=717**，0失败/错误/跳过、20tasks全执行，实际PG运行。新增HTTP/PGtest验readiness200→503→200、liveness始终200及仅粗状态。独立native.module/POM-only .5编译并解析Boot4.1.1/Framework7.0.9/Security7.1.1/Log4j2.25.5，只localfilepublication。

final三锁/解包JARSCA234/150包记录0报告漏洞，Semgrep221path/保1当前调用图redirect误报/0error，Gitleaks source/JAR/allhistory各0match。扫描288含Compose，既定fingerprint287不含；同[扫描记录](../implementation/security-scan-2026-10-06.md)DB/rules与覆盖限制，原报告private临时保。

已核专用TLS/admin/restore/capacity容器与DBvolume、TLSnetwork、detachapp及生成key/trust/cert/secretconfig/state均清；9000/8088/9080/9443端口free，无globalCA/hosts修改。旧新非秘密logs/scannercache/inventory保本机临时证据，无长期保留保证。定向运行日志覆盖8TLS+4admin+20restore+2capacity，不称任意所有日志完整审计。

下一仍需实际production域名cert续期/firewallCDNdeploy、productionimageOSJDKscan、完整runtime log/DAST/UIaccessibility、持续容量monitoralerts、生产backup/keycustody、实际consumer与committedsignedimmutable发行。完整B1/customdurableSession/index/replay与明确延期能力保独立范围。
