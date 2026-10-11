# 操作流程总目录 — {{VERSION}}

[手册首页](README.md) · [English](../en/operation-index.md) · [接口字段](api-reference.md) · [API调用](api-operations.md)

**怎么用：** 先找任务/操作者，再进入对应页的流程编号。每个流程按前提/位置→输入→编号步骤→预期→失败处理执行；一个阶段成功不代表后续成功。路径和外配置示例按目标替换，密码用私密文件/隐藏输入，例子不是待执行的生产命令。

## 用户与管理员

| 操作项 | 位置/身份 | 完整步骤 |
| --- | --- | --- |
| 注册并保存首次恢复码 | SSO浏览器、匿名 | [用户 U01](user-guide.md) |
| 登录及风险提示 | SSO浏览器、本人 | [U02](user-guide.md) |
| 临时密码首次改密 | SSO受限会话 | [U03](user-guide.md) |
| 显示名/用户名修改 | SSO本人正常会话 | [U04–U05](user-guide.md) |
| 正常改密/恢复码轮换 | SSO本人及当前密码 | [U06–U07](user-guide.md) |
| 恢复码/人工凭据找回 | SSO浏览器及有效凭据 | [U08](user-guide.md) |
| 设备撤销/退出 | SSO本人；先核全局范围 | [U09](user-guide.md) |
| 应用授权撤销 | SSO本人、目标client | [U10](user-guide.md) |
| 注销申请/查看/取消 | 普通本人账号 | [U11](user-guide.md) |
| 用户/墓碑查询 | SSO管理员读 | [管理员 A01](administrator-guide.md) |
| freeze/unfreeze/强制下线 | SSO管理员、自己密码/原因 | [A02–A03](administrator-guide.md) |
| 人工核验/签发/交付/重发 | SSO管理员及核验记录 | [A04](administrator-guide.md) |
| client创建/配置/secret交付 | SSO管理员+对接负责人 | [A05](administrator-guide.md) |
| client编辑/disable/enable | SSO管理员+变更窗口 | [A06–A07](administrator-guide.md) |
| client secret轮换 | SSO管理员+后端私密配置 | [A08](administrator-guide.md) |
| 注销hold/release | SSO管理员+精确requestId | [A09](administrator-guide.md) |
| 审计/安全事件过滤分页 | SSO管理员读 | [A10](administrator-guide.md) |

## 安装、运行与恢复

| 操作项 | 执行位置/前提 | 完整步骤 |
| --- | --- | --- |
| 已选容器安装+单层外部主机Nginx | 包根/主机Linux/loopback入口 | [N01–N06](host-nginx-deployment.md) |
| 验包/三镜像/外配置/秘密/权限/部署输入 | 包根、Linux/Docker/受控外目录 | [Docker 1–4](docker-deployment.md) |
| 空卷DB/迁移/唯一admin/首次登录 | 包根、精确project及TTY | [Docker 5–8](docker-deployment.md) |
| 已选JAR/systemd方式 | 包根/目标Linux/service账号 | [J01](jar-deployment.md) |
| 日检/停止/恢复/restart | 包根、已有安装 | [运维 O01](operations.md) |
| 唯一管理员offline reset | 包根、停writer/真实TTY | [O02](operations.md) |
| 更新软件/兼容回退 | 新包根、原project/volume/key、backup | [O03](operations.md) |
| CA证书续期/reload | 外TLS目录+proxy | [O04](operations.md) |
| 创建/核dump/加密异机备份 | 包根、backup角色、已选备份存放 | [B01](backup-and-recovery.md) |
| 隔离空库restore/epoch/key/consumer屏障 | 包根、独立restore.env | [B02及§2](backup-and-recovery.md) |
| 采集/规则/真实通知安装 | collector/AM实际配置目录 | [监控 M01](monitoring.md) |
| Grafana数据/Viewer验证 | 已有Grafana/Prometheus | [M02](monitoring.md) |
| 告警firing/resolved演练 | 隔离或授权窗口 | [M03](monitoring.md) |
| collector密码轮换 | SSO+collector协调窗口 | [M04](monitoring.md) |
| history报告/增长分类/调参 | 包根、只读backup角色 | [H01–H02](history-retention.md) |
| reference队列准备/死信/purge/AES轮换 | 包根/真实PowerShell、独立queue DB | [R01–R04](reference-revocation.md) |
| 故障分层定位/修复复验 | 包根、私有bounded logs | [T01](troubleshooting.md) |
| 软件包/目标开放验收 | 独立测试、真实负责人 | [V01–V02](acceptance.md) |

## API与业务接入

| 操作项 | 流程 |
| --- | --- |
| 导入OpenAPI/选择认证/写结果判定 | [API F01–F02](api-operations.md) |
| 注册/两步恢复调用 | [F03](api-operations.md) |
| 每个账号/管理员operation的操作流程 | [F04–F05](api-operations.md)及对应U/A编号 |
| 内部资格/分页 | [F06](api-operations.md) |
| OIDC code/表单/重认证/UserInfo | [F07](api-operations.md) |
| Refresh/revoke/introspect/local-global logout | [F08](api-operations.md) |
| private health/metrics | [F09](api-operations.md) |
| 操作验证记录 | [F10](api-operations.md) |
| 登记/固定SDK/唯一链 | [接入 I01–I02](external-integration.md) |
| 双浏览器/重启/刷新故障/退出验收 | [I03–I04](external-integration.md) |
| 一分钟资格/15分钟密码证据 | [I05](external-integration.md) |
| 对接完成/升级维护 | [I06](external-integration.md) |

## 执行到哪一步就核哪一步

1. 用流程编号+精确目标/version开始，不跳过同源/身份/backup条件。
2. 每条维护命令核exit/status，失败停在该步骤，不把命令块全部粘贴后只看最后成功。
3. 成功需read-back/audit/新登录或实际通知等相应证据，文档存在/语法通过不等实际执行。
4. 结果未知先查状态，不盲重试一次性秘密/refresh；200协议响应不代表后继family清理。
5. 记录非秘密结果/未执行平台边界。软件发布、目标上线及消费方联验分开，不自动扩展首版功能。
