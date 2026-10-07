# 验收与证据

[目录](README.md) · [English](../en/acceptance.md)

## 当前包

解压根运行 `python scripts/verify-package.py`，验证精确文件清单/SHA-256、缺失/多余文件、安全相对路径、包内文档链接、程序/内嵌迁移摘要和版本一致。ZIP checksum只是传输完整性，不是签名/可信发行授权。source ZIP为所记录commit的git archive；SDK/JAR由同一干净commit显式server/Starter版本、三模块rerun构建。归档使用稳定排序/时间戳，但不宣称独立双构建器字节级可重现。

provenance/verification-summary.json记录实际命令/工具版本/时间/报告hash/模块测试数和扫描覆盖；测试fail/error/skip必须零。provenance/dependencies含三锁/catalog/Wrapper及实际nested JAR名称/hash。opaque JAR扫描可能识别零依赖，需解包。扫描不认证“绝无漏洞”，以实际工具/DB/rule范围、发现和复核限制为准，仅已执行检查算本轮证据。

历史验收：.7真实TLS双BFF/刷新后初始hint退出/Grafana Viewer桌面移动/两条5分钟混合运行，.6三类真实告警firing/resolved，.5管理员/恢复演练。45秒AT是fixture，一个RuntimeError采样gap未精确归因，短期内存不能排漏。不代表小时级/default-lifetime生产soak；未明确重跑不算新包真实进程验收。

## 部署环境需执行

| 范围 | 环境专项证据 |
| --- | --- |
| 输入/启动 | 已确认issuer/网络/客户端/负责人、独立秘密/持久key/无local默认；人工关入口至admin初始化，完整startup gate仍待做 |
| DB | 空库/代表性旧库升级V1–V15、备份恢复/checksum/权限；DB断连readiness200→503→200而liveness200 |
| TLS/浏览器 | 实际CA/域名/代理头/禁止直连、Secure Cookie/CSRF/Origin、双站登录/consent/改名稳定UUID |
| 生命周期/admin | 近期密码/admin门禁、冻结解冻/注销等待hold取消执行、人工恢复/交接，不绕last-admin |
| 消费安全 | 真实发起会话15分钟证据、refresh不推进auth_time、一分钟资格拒绝与epoch/在途屏障 |
| 刷新/退出 | 并发刷新合并/RTreuse拒绝、刷新后当前vs全部退出、重复/迟到回调/接收者故障及真实本地撤销 |
| 持久化 | 同意默认重启丢登录；已提交task重启/retry/dead/key保管，明确处理pre-submit gap |
| 可观察性 | Grafana真实数据/Viewer权限、真人firing/resolved；snapshot/outbox/reuse/admin/hash及责任 |
| 恢复 | 实际目标备份/key恢复、新epoch/kid、旧Cookie/code/AT/RT拒绝、每消费方清旧证据 |
| 容量/安全 | 目标JDK/image/proxy/DB扫描与DAST、小时级ramp/soak/history增长清理/RPO/RTO，以测量定阈值 |
| 上线 | 候选演练、不可变可信交付、限量开放/回退、响应负责人和首运维周期 |

每门禁记录版本/hash/日期/环境/操作者/结果/例外后续。开发包完成交付不豁免生产门禁；SSO不能替业务应用验收其实现。
