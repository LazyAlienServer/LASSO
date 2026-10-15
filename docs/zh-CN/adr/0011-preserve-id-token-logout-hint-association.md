# ADR-0011：保留已签发 ID Token 退出 hint 关联

- 状态：接受既有契约实现修正，2026-10-07
- [English](../../adr/0011-preserve-id-token-logout-hint-association.md)

## 背景

既有RP-Initiated Logout用当前/最近签发hint，refresh替换server当前IDToken而defaultBFF保initialOidcUser。actualTLS混合与PGred证成功refresh后400/logout失败；原global退出policy保持，无新credential/businessentitlement。

## 决策

V15存HMAC-only issuedIDToken→auth/FKcascade，原save事务先保previouscurrent含upgrade首次save，再记newhint。仅显式ID-tokenlookup使用history，ATRTcodegeneral保原。成熟logoutclient/aud/principal/session/exactreturn验证与撤销仍权威，不保rawhistory、不复活removedauth。

## 影响

需迁移/history sizing/cleanup，旧hint不可补造，pepper rotation可需新登录；不改BFF principal/造auth_time。原回归/双链结果见[记录](https://github.com/LazyAlienServer/LASSO/blob/8390243/docs/zh-CN/implementation/refresh-logout-hint-history.md)。
