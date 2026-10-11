# ADR-0017：容器账号站使用单层外部Nginx

- 状态：拓扑已接受，2026-10-11；目标执行待验
- [English](../../adr/0017-single-external-nginx-container-edge.md)
- 细化[ADR-0016](0016-single-host-compose-account-site-launch.md)

## 决策

用户选择Docker同机的单层外部Nginx。它终止公网HTTPS，将完整路径转发到只绑定主机IPv4 loopback的应用端口（例`127.0.0.1:19000`），目标为SSO容器9000。PG及management9091不发布；可信采集器可加入私有monitoring网络。此处是主机直接安装的Nginx，不是另一容器的localhost或异机代理。

提供独立的SSO/PG/离线maintenance Compose、非秘密输入和主机Nginx站点模板。原内置proxy保留为独立选择的替代模板；外部Nginx Compose不要求proxy镜像/TLS挂载。现有安装切换时保原project名、PG卷、signing key及外部秘密；先停原proxy再让主机Nginx接管公网端口。

主机入口清除客户端Forwarded/其他转发元信息，提供唯一canonical HTTPS host/port及真实客户端地址。账号/API/OIDC路径完整保留，公网actuator返回404。不把loopback upstream当issuer，不信任任意请求Host。主机配置/续期、权限、防火墙由运维负责。

## 发行边界

按用户要求，正式域名、publicCA及续期在正式目标上线时供应；软件准备继续使用明确标识的隔离TLS fixture，不推断生产输入。production仍要求合法HTTPS issuer/配置，fixture不等公共CA验收。软件交付、目标开放和实际消费方接入保持分开。

已有`0.1.0-dev.20261009.2`不可变，不补入新模板/工具。源码变更须新干净候选及对应验证。本决策授权实现/文档，不授权Git提交/push、托管发布或操作用户服务器。

## 后果

部署校验器须区分内置与主机代理的service/port/network边界，拒非loopback应用发布、management/DB暴露，并核signing kid语法。独立解析及fixture流量检查仅为模板证据；实际Linux listener可达性、证书信任/续期、转发、Cookie/登录、镜像安全、监控与恢复须目标开放前验证。
