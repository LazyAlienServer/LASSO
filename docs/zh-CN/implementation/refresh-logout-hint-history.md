# 刷新后退出 hint 连续性

- 2026-10-07在[交付.20261006.7](../integration/development-delivery-2026-10-06-7.md)验证；[English](../../implementation/refresh-logout-hint-history.md)／[ADR-0011](../adr/0011-preserve-id-token-logout-hint-association.md)。
- 修正既有RP-Initiated全局退出实现，不新增认证/授权协议。

## 缺陷与证据

精确.6组合短browser流程通过，但较长shortAT混合负载发现另一缺陷：Spring refresh签发新IDToken并替换授权表当前值，BFF OidcUser保留首次IDToken用于id_token_hint，OP只查当前值，未过期的已签发hint返回400。本地BFF已退出，SSO/另一BFF仍登录。

单worker实际TLS最小复现refresh后400/hint未过期但不在currentcolumn。随后PG/MockMvc `originalIdTokenHintStillLogsOutAfterRealRefreshReplacesTheStoredIdToken`先red预期redirect断言；初始IDToken为fixture，实际refreshprovider签发替换，非browser签名证明。最终hashedJAR两套五分钟TLSbrowser重复refresh/global退出通过。

## 持久化与查找

V15创建`oauth2_id_token_hint_history(token_hash, authorization_id, created_at)`，原transient-security key-pepper/namespace `oidc-id-token-hint`做HMAC-SHA256，hash主键、authorization索引FK ON DELETE CASCADE。不存rawIDToken/principal/clientsecret/Cookie/新Claim。

原save事务先保previouscurrenthint关联，再写newauthorization/security/RT并记newhint，重复save不重复hash。升级前当前hint在首次后续save被捕获，升级前已丢失的更早hint不可重建，不从log捞Token/保removedauth。

`findByToken`先原repo，只有显式`id_token`类型fallbackHMAC→加载仍存在owningauthorization。null/general/AT/RT/code不扩大；成熟Springlogoutprovider继续核当前authorization/client/token状态、audience、principal、Session关联与精确postlogoutURI。digest只logout关联，不是login凭据/Bearer。

logout/revoke/recoveryreset/accountlife删除authorization时cascadehistory，不复活已删auth，重复旧hint拒。新增回归验actualrefresh后两32byteassociation/preupgradecurrent捕获、未登记returnURI拒且auth保持、ATRT不查、success后cascade/旧hint拒。RTfamily期限/reuse/securityversion保持。HMACpepperrotation可令historical查找不可用，当前repo仍原路径，轮换结合既有Session/Token reset流程。

distinctIDToken数量随仍存auth增长，未加独立TTL/周期压缩，只跟owningauth删除；生产长期authcleanup/history容量仍需测。不等durable跨实例BFFindex或绕过RP验证。

## 交付与限制

2026-10-07 **00:07:51–00:12:17 Asia/Shanghai** Java21Docker全量**505server193starter37reference=735**零失败错误跳过/20tasks/actualPG。依赖/三锁保.6，SSOschema V1–V15，starterAPI/JARbytes保持，新.7ID。

最终真实验收还导入精确Grafana/desktopmobile展示Viewer拒，两套五分钟TLS多subject。详[Grafana混合负载](../operations/grafana-mixed-load-2026-10-07.md)。mobile缺panel是探针未滚动lazy挂载、退出后refresh撞revocation401 SESSION_EXPIRED为允许终态，与实际400hint缺陷分开保记录。
