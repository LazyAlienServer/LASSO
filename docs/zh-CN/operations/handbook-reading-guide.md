# 发行手册阅读入口

[English](../../operations/handbook-reading-guide.md) · [手册源码目录](../../../release/_source/docs/zh-CN/README.md)

新版发行包在首页和手册目录优先显示Docker部署。教程按执行位置、命令、预期结果和失败处理组织。

| 要做什么 | 源码模板 / 包内位置 |
| --- | --- |
| 第一次Docker安装 | [docker-deployment.md](../../../release/_source/docs/zh-CN/docker-deployment.md) → `docs/zh-CN/docker-deployment.md` |
| 启动/登录失败 | [排障速查](../../../release/_source/docs/zh-CN/troubleshooting.md) |
| 重启/重置/升级 | [运维操作](../../../release/_source/docs/zh-CN/operations.md) |
| 旧快照恢复 | [备份恢复](../../../release/_source/docs/zh-CN/backup-and-recovery.md) |
| 历史表增长 | [清理专项](../../../release/_source/docs/zh-CN/history-retention.md) |
| 参考队列死信/key | [参考维护](../../../release/_source/docs/zh-CN/reference-revocation.md) |

Docker模板目录README改为入口索引，不再重复完整安装流程。打包将源码链接改成包内手册链接，并去掉源码专用构建段。

`.20261009.1`包字节不变；新手册用`.20261009.2`交付。认证代码、依赖锁、迁移未改，目标域名/资源/保管/备份/通知仍由运维填写。
