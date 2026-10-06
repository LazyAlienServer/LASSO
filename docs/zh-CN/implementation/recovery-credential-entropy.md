# 恢复凭据熵与已签发旧码兼容

- 状态：2026-10-04 S6修复并通过JVM/PostgreSQL/MockMvc验证，真实浏览器/运维待验收。
- 英文对应：[Recovery credential entropy and persisted-code compatibility](../../implementation/recovery-credential-entropy.md)。
- 跟踪：[状态地图§3.12](../../implementation/status-issues-and-plan-2026-10-02.md)。

## 1. 新生成

现有字符表`23456789ABCDEFGHJKLMNPQRSTUVWXYZ`共32个；`SecureRandom.nextInt(32)`逐随机字符均匀选择。旧三组×四字符为12×log2(32)=60bit；两个生成入口现四组×四字符，**每枚新生成凭据16×5=80bit**，符合原规范。固定前缀/分隔符无熵。

| 凭据 | 新显示格式 | 含前缀/分隔符长度 |
| --- | --- | --- |
| 普通恢复码 | LAS-XXXX-XXXX-XXXX-XXXX | 23 |
| 人工恢复凭据 | LAS-ADMIN-XXXX-XXXX-XXXX-XXXX | 29 |

普通批次仍十枚，注册/显式轮换/人工签发走同生成器/owning services。HMAC-SHA-256/pepper、32byte摘要、防御性array copy、移除横杠/Locale.ROOT大写规范化保持。恢复页placeholder改四组，页面/API上限仍64；JS按规范化前缀识别类型，不加精确新长度pattern。无schema/依赖/因素/权限/端点/公开方法变化。

熵结论由32字符CSPRNG均匀选择源码核对＋确定性格式/随机字符预算断言组成，不是平台随机源统计认证，也不以“输出无重复”独立证明密码学强度。

## 2. 旧摘要与替换

升级不自动重写凭据、不批量强制撤销、不改pepper。已签三组码仍为**60bit**，只有原摘要匹配同当前用户名/身份/type且未用/未撤销才可用；人工旧码不延长原期限。兼容不恢复旧明文、不把旧码强度提升为80bit。

- 两格式均在恢复start原子消费，建立原十分钟受限Session；code/已完成或到期Session重放失败。
- 普通恢复完成保留同批其他未用码，包括旧码，沿原政策。
- 显式普通rotation同事务废除旧批次/新十枚四组码，响应丢失仍需重新轮换，不能找回明文。
- 人工reissue废除以前未用凭据，新四组仍三十分钟；恢复完成/改密/维护的既有失效保持。
- 已用/撤销/过期旧码不会复活，错用户/type、CAPTCHA、用户/网段/全局预算仍同路径。

审计action/result不变，保存凭据ID/requestId/类别而非明文。校验仍对输入按旧规范hash，无需format/version迁移列。滚动部署旧实例仍可能生成60bit；要宣称所有新签发80bit，运维须更新全部签发实例。未演练真实混合版本部署。

## 3. 证据

修复前RecoveryCodeServiceTest两熵回归均因60<80失败，原一项生成/HMAC通过。最终JVM**9项（新增8）**验两个熵门禁/四组格式、三个独立计算旧新HMAC固定向量/规范化、前缀区别、array copy；原十枚格式断言更新四组。

RecoveryCredentialCompatibilityIntegrationTest新增**33项**真实PostgreSQL18.4/Spring/MockMvc。旧明文为固定测试值，用JCA/test profile pepper独立计算摘要，再仅一次性库替换owning service已建行的hash；不通过新生成器制造“旧码”。新码由注册/人工签发service。service人工issuer为已注册actor fixture，非浏览器role/近期认证验收；原admin套件另验保护签发API与明示role/Session fixture。

覆盖：

- 旧/新×普通/人工完整恢复、完成前后code/Session重放，原ALREADY_USED/成功request关联审计；
- 旧大小写/横杠、撤销/过期拒绝且行不变，过期拒绝审计无固定明文；
- 普通rotation/人工reissue，新格式摘要/期限；外层事务明示异常回滚还原旧有效性、不提交新行/成功审计；
- 普通完成保留同批其他旧未用码；
- 改密废除未用旧码/消费已建Session，原十分钟Session到期不改密码；
- 错身份/type拒绝且usage不变，正确旧码也不能越过用户CAPTCHA/尝试预算；
- 四组旧新HTTP经原Controller，204/no-store/token仅Secure/HttpOnly/Strict十分钟Cookie，实际密码完成；
- 同旧码并发一次成功一次拒绝/仅一Session，原PostgreSQL用户锁，barrier/Future控制同时调用；
- 实际Thymeleaf恢复页新placeholder与原长度宽容。

初兼容扩大2项fixture失败来自JdbcClient单值used_at为null；改nullable行record helper解决，非生产缺陷。生成/兼容/admin随后绿，再**164相关**全绿。红XML后续覆盖，命令输出/状态§3.12保留阶段。

server目录Java21/Docker命令：

```powershell
.\gradlew.bat :test --tests "org.las.sso.account.RecoveryCodeServiceTest" --no-daemon --console=plain

.\gradlew.bat :test --tests "org.las.sso.account.RecoveryCodeServiceTest" --tests "org.las.sso.account.RecoveryCredentialCompatibilityIntegrationTest" --tests "org.las.sso.account.PasswordRecoveryInvalidationIntegrationTest" --tests "org.las.sso.admin.AdminRecoveryCredentialServiceTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.maintenance.AdminPromotionDeletionIntegrationTest" --tests "org.las.sso.account.DeletionRequestServiceTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

约21:25–21:26Asia/Shanghai最终专项**164=9+33+28+6+53+25+10**零失败/错误/跳过；约21:27–21:30强制全量**314 server/176 starter**同零失败/错误/跳过，12actionable全执行、BFF重编译/NO-SOURCE。JVM9项XML13:27:56.325UTC、兼容33项13:27:56.343UTC，相对S5的273新增41。

## 4. 边界与剩余

MockMvc/controller/模板、真实PostgreSQL/JCA非浏览器TCP/TLS/proxy、JS可用性/可访问性/打印下载/剪贴板验收。外层异常为rollback fixture非crash/kill/生产迁移。定向拒绝审计明文检查非全应用/代理/遥测Secret扫描。原S1竞争维护/注册rotation一次性API按文档层级重跑，不扩成live多实例保证。

D1指定补丁随后升级验证，该阶段323/176见[Security记录](spring-security-consent-patch.md)/§3.13；A1/A2随后直连Servlet及最新419/176见[HTTP记录](servlet-path-security.md)/§3.14。上方314/176保留S6。完整扫描/productionproxyTLSbrowser/S2更广竞争/B1消费版本独立，S6无commit/release/deploy/用户库操作；两生成入口现在均有熵预算断言。
