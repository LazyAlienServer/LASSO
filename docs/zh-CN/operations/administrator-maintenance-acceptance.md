# 管理员维护与凭据转换验收

- 验证范围：2026-10-06隔离Windows PowerShell/Java21隐藏console、Edge浏览器演练。
- [English](../../operations/administrator-maintenance-acceptance.md)／[交付.20261006.2](../integration/development-delivery-2026-10-06-2.md)／[精确制品](maintenance-artifact-selection.md)。

## 流程语义

维护窗口停止SSO Web，原scripts选精确JAR/hash。密码仅JVM隐藏控制台，不命令参数/env/脚本参数，不在BFF收集。

```powershell
.\scripts\bootstrap-admin.ps1 -Username InitialAdmin -Profile production -JarPath $ServerJar -ExpectedSha256 $ServerJarSha256
.\scripts\reset-admin-password.ps1 -Profile production -JarPath $ServerJar -ExpectedSha256 $ServerJarSha256
```

空库bootstrap建立唯一admin、24h临时密码/PASSWORD_CHANGE_REQUIRED；已有人则原UUID晋升模式/ADR-0002先取消有效注销。本演练为空库创建，非live晋升取消。

临时密码仅强制改密/退出，不管理/普通account/恢复码/OIDC grant。首次改密验证临时密码及CSRFOrigin，ACTIVE并结束旧SessionAuth，必须新密码重登后生成恢复码，运维offline保存；reload不重显。reset显示immutable管理员身份，YES确认/两隐藏密码，cancel/mismatch/reuse/invalid nonzero不改凭据审计安全outbox；成功ACTIVE（非再临时）version推进、废恢复材料/SSOSessionAuth、auditsecurity/outbox。SSO同key重启，旧passwordCookieToken拒；旧恢复码已撤销，需要新batch。

## 实际证据

最终.20261006.2 JAR/hash由两真实PowerShell入口、WindowsPTY执行，随机fixture密码/独立PG18.4/RSA3072 key/loopbackHTTP/defaultrefBFF，Edge headless/Playwright1.55.1双独立Context，无生产库/key。

| 场景 | 验证 |
| --- | --- |
| bootstrap | 两密码不一致nonzero/快照不变；成功隐藏输入两次，唯一admin/version1/temporary24h，transcript无密码 |
| 强制登录 | 两browser到password-change-required，管理/account/OIDC跳强改，recoveryAPI403 |
| 首次改密反例 | 缺CSRF/外Origin403，确认不符不发送，错临时密码401/version不变 |
| 首改成功 | UI204/成功面板，ACTIVE2/tempNULL；其他旧SSOsession拒/临时密码login?error |
| 新登录恢复码 | 新密码管理页；十个四组80bit可见，download内容匹配并删除，reload无plaintext，DB仅digest |
| OIDC | admin双BFF独立CodePKCE/Token/UserInfo成功 |
| 离线reset反例 | cancel/mismatch/reuse/short均nonzero/securitysnapshot不变 |
| reset成功 | 隐藏输入、新audit、ACTIVE3，旧recovery撤销/SSOSessionAuth失效/旧family结束 |
| 投递旧凭据 | 同持久key重启，真实Back-channel清两个BFF，oldCookie/ATuserinfo401/RT400invalid_grant/旧passwordlogin?error |
| 新reset密码 | retained staleSSOCookie仍能fresh密码回BFF、管理页、global退出Back-channel清双Session |

download/password/Token仅harness内存断言不打印，恢复码下载文件删除，不代表实际operatoroffline/copyprint保管。reset恢复材料验证DB revoked，非旧码完整browser恢复演练；populated晋升/24h到期/其他终端/productionTLSproxySecureCookie/全部可访问性独立。

## 撤销Cookie的OIDC续行修复

reset后旧SSOCookie暴露真实偏差：会话Filter结束旧认证后、Spring尚未验证保存Authorization就redirectlogin，密码通过但到account而非BFF。

现仅canonicalGET授权请求在旧session/context清理后anonymous继续原Spring校clientredirectscopePKCEprompt，由原OIDCfilter在验证后创建newbrowsertransaction。不复制旧savedrequest/challenge/auth_time，不保存未验证redirect。普通API/browser过期保持拒绝，必须真密码/new sid。

两回归旧先红后绿，新7actualPGMockMvc覆盖revokedsid/credentialversion普通forced、旧密码拒/newstate nonce sid auth_time、promptnone login_required、evilredirect/oldchallenge拒、badmax_age。真实login/CodeS256/signToken，非RP15m门禁。一次fixture网络两wrongattempt触发CAPTCHA，隔离network修test非放宽生产limit。
