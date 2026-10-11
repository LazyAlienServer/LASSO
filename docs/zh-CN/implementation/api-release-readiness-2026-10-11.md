# 已实现 API 与软件发行准备 — 2026-10-11

[English](../../implementation/api-release-readiness-2026-10-11.md) · [API参考](../../../release/_source/docs/zh-CN/api-reference.md) · [OpenAPI3.1](../../../release/_source/api/openapi.json) · [打包](../../../release/README.md)

## 范围

2026-10-11用户已授权继续：审查分组本地提交，再clean build/scan/package/验收`0.1.0-rc.1`。下方未提交工作区描述是当时验证快照，保历史；本地续作不授权push/托管发布/目标生产操作。最终候选独立记录真实提交及制品。

推进首个软件版本，与公众生产部署分开。已有`.20261009.2`及旧交付字节不变，来源297cdfc；本轮源码不修改正在运行的本地镜像，也不追认旧证据。工作不自动push、托管发布、操作生产库或批准部署。

## API文档

旧docs/architecture/api-contract.md是实施规格，部分描述与当前行为不一致。release/_source下新增独立中英参考及OpenAPI3.1 JSON，描述MVC账号/管理/内部路径、请求及Java record响应字段、认证、CSRF/同源、幂等、状态/content-type例外、浏览器表单/协议/私有管理接口；不引入Swagger依赖或运行时公开文档路由。

已记录差异：恢复改密PUT，登录rememberDevice，改密局部错误及session/restriction filter可用JSON code，client创建200，授权列表返回显示标签，撤销当前sid调用全用户退出，注册Location不代表存在GET用户详情。这些描述不默默改变实现或承诺设计目标。

OpenApiContractTest核全部/api/v1及/internal/v1 MVC路径、标记Java record字段、恢复enum及改密错误结构；独立openapi-spec-validator0.7.2检查OpenAPI3.1。防漂移不代全部协议一致性或生成client联验。

## 版本工具

共享版本策略接受有效日期/正序号dev、正序号RC及无前导零的stable语义版本，分别标development-delivery/release-candidate/software-release及双语标签。拒不安全输入/不存在dev日期；保留干净已提交来源、精确artifact/source/test/publication一致及禁止覆盖。所有软件包productionApproved=false，stable版本不等部署批准。

独立Python fixture验stable/RC/dev、hash/inventory及重封摘要后的kind/API版本/外部ref/部署声明/skip负例。synthetic JAR仅测工具，不是应用发行证据。Linux/Windows普通CI增加工具fixture，未执行远端完整发行流水线。

## 已复现的refresh跨client缺陷及修正

真实PG/MockMvc专项用另一个已认证client提交owner的CURRENT或ROTATED RT。CURRENT拒绝且无副作用；ROTATED返回400却先删除owner Authorization，标准provider之后才检查client归属。首次3case/1failure/0skip，UTC2026-10-10T17:47。前提是持有owner真实RT及另一个有效client凭据；影响是越权撤销，不是把owner身份签发给其他client。

wrapper现在先要求已认证client，把内部registered-client ID传给history校验；持有hash锁后核Authorization/family/client归属，才做reuse撤销、event/outbox。legacy CURRENT导入也限定client。归属读取沿既有history事务，不在跨history更新前新增parent锁；CURRENT既有锁与合法reuse行为保持。无schema/依赖锁/版本SQL变化，未新增历史RT撤销。

测试涵盖CURRENT/ROTATED跨client无变更、缺/错secret、owner仍可refresh、合法reuse、并发刷新、legacy导入、3次真实轮换不推进created/absolute时间、ROTATED重用与CURRENT刷新竞争。缺auth fixture未声明JSON Accept而得到browser302，补Accept修正测试假定，不改变运行时响应。

## 刷新不确定性与发行范围

未知B不能由本地CAS恢复。当前/oauth2/revoke不通过消费A撤后继，RFC7009的200不证明family清理；这是已知限制，不自动升级协议缺陷或V1强制功能。消费方基线是本地安全失效/重启废登录、已接受已知Token工作持久化、未知状态如实记录，核family/AT/资源边界并明确接受残留风险。不得故意reuse A触发高危处置；稳定ID/历史RT补偿仍可选、另行决策。

LASLOG适配/replay/worker/browser验收由LASLOG推进，账号站SSO首发不等待全部消费方。正式CA/续期、Linux秘密/UID、实际镜像安全、备份恢复/通知/容量是部署验收，不由本地包测试代替。

## 验证状态

后续[主机入口准备](../operations/external-nginx-validation-2026-10-11.md)记录已选单层外部Nginx、14工具case、中英各18页/63流程、真实Compose解析/TLS探针流量及新独立SNAPSHOT运行fixture。正式域名/HTTPS按用户要求留目标上线；精确RC来源/验收仍待。

后续[操作手册验证](../operations/operation-handbook-validation-2026-10-11.md)记录双语流程覆盖、命令解析/失败停止及隔离SQL/dump/restore演练。下方旧源码扫描/仓库数量为当时快照，不是最终手册输入集。

JDK21/Docker，spotlessApply build -PrequireDatabaseTests=true --rerun-tasks --no-daemon --console=plain：**server597+Starter196+reference50=843项，失败/错误/跳过0**，7m2s，UTC2026-10-10T18:00–18:01。包含3项API drift及6项新增refresh归属/期限/竞争。是dirty workspace SNAPSHOT强制全量，不是干净不可变发行来源；既有Hikari停止/容器连接拒绝仍测试fixture输出，不等生产健康/容量证据。

9项Python工具测试通过，包括真实stable/RC模板复制、中英页/本地链接、独立子进程使用包内版本策略、重封API/kind/部署/skip及tamper/extra负例；JAR manifest版本精确比较拒绝重新封摘要后把RC冒充stable；synthetic JAR仅工具证据。实际模板测试发现导入Python cache字节，copy已排__pycache__/.pyc/.pyo。最终OpenAPI49路径/59操作/54 schema，独立OpenAPI3.1格式校验成功；未运行remote CI或生成真实新RC。旧`.2`/`.1`/`.20261007.1`ZIP摘要与原记录一致。

独立校验器导入包内版本策略前关闭cache写入，测试使用普通Python命令、不靠-B。Gradle增加API文档input追踪后3项API测试再次通过；聚焦重跑替换服务端当前XML为仅3项，前843全量是日期记录，不把当前focused报告称全量。

Gitleaks8.30.1/redact100精确跟踪/未忽略源码快照587文件、0命中。首次整目录扫5.82GB含ignored build/历史验收，674匹配均不在源码candidate集合；原报告私有保存，不默默抑制或称源码命中。此检查不代最终精确artifact/history/dependency/image扫描。仓库571文本候选/1515本地links/三锁/Wrapper/V1–V16通过。

软件交付仍需：明确授权后审查/提交 intended源码、确定精确RC/release ID及存放/身份可信方式，执行对应strict clean build/publish/scan/package，再独立解压/消费/镜像及最终支持范围OIDC/browser/维护/升级演练。公开hosting/CD/OpenID认证不自动增加门槛，来源可追溯的验证必需；目标生产开放另按P06–P10/P13–P19。
