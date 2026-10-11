# 发行手册阅读入口

[English](../../operations/handbook-reading-guide.md) · [手册源码目录](../../../release/_source/docs/zh-CN/README.md)

新版发行包在首页和手册目录优先显示Docker部署。教程按执行位置、命令、预期结果和失败处理组织。

| 要做什么 | 源码模板 / 包内位置 |
| --- | --- |
| 已选单层外部主机Nginx | [host-nginx-deployment.md](../../../release/_source/docs/zh-CN/host-nginx-deployment.md)、N01–N06及独立主机入口Compose |
| 第一次Docker安装 | [docker-deployment.md](../../../release/_source/docs/zh-CN/docker-deployment.md) → `docs/zh-CN/docker-deployment.md` |
| 启动/登录失败 | [排障速查](../../../release/_source/docs/zh-CN/troubleshooting.md) |
| 重启/重置/升级 | [运维操作](../../../release/_source/docs/zh-CN/operations.md) |
| 旧快照恢复 | [备份恢复](../../../release/_source/docs/zh-CN/backup-and-recovery.md) |
| 历史表增长 | [清理专项](../../../release/_source/docs/zh-CN/history-retention.md) |
| 参考队列死信/key | [参考维护](../../../release/_source/docs/zh-CN/reference-revocation.md) |
| 各操作详细步骤 | [操作总目录](../../../release/_source/docs/zh-CN/operation-index.md)、[用户](../../../release/_source/docs/zh-CN/user-guide.md)、[管理员](../../../release/_source/docs/zh-CN/administrator-guide.md)、[API调用](../../../release/_source/docs/zh-CN/api-operations.md) |

Docker模板目录README改为入口索引，不再重复完整安装流程。打包将源码链接改成包内手册链接，并去掉源码专用构建段。

[2026-10-11验证](operation-handbook-validation-2026-10-11.md)：各17页/57流程、全部59 API operation、415渲染链接，命令解析/失败停止及隔离SQL/备份还原通过；记录区分这些检查与实际部署、本版本制品浏览器验收。

`.20261009.1`及已完成`.20261009.2`包保持不可变，后续流程/API源码等待新版本，不补写旧字节。目标域名/资源/保管/备份/通知仍由运维填写。流程按实际按钮、操作者/位置、编号动作、read-back预期与失败处理组织，一次性明文及远端结果未知边界明确。
