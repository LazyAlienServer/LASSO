# TLS代理、转发边界与Secure Cookie验收

- 2026-10-06实际Nginx/TLS/Java容器与Edge已验。[English](../../operations/tls-proxy-acceptance.md)／[交付.20261006.3](../integration/development-delivery-2026-10-06-3.md)。
- 仓库输入deploy/nginx/las-forwarding.conf与参考application-https.yml，显式example非production部署或可信代理filter。

## 实际转发缺口

旧guide重写XForwardedHostProtoPortFor，但未清客户端Forwarded/Prefix；真实Nginx→Springframework生成：

```text
正常redirect_uri：https://bff.las.test:9443/login/oauth2/code/las-sso
伪造redirect_uri：http://evil.example/forged/login/oauth2/code/las-sso
```

probe无真实grant，只印redirectUri不state/nonce/code；SSO精确URI仍拒evil，非已窃Code，但外部host/path被污染/登录中断。新include清Forwarded/Prefix/alternate，统一canonicalHost/schemeport/observedIP；同probe变正确HTTPS。

每proxyinglocation都include，Nginxchild自定义proxy_set_header后不继承parent整组。每TLS应用仅一literalcanonical server_name，unknownhostdefault拒；prefix部署另验，不还原任意clientprefix。

## 部署条件

- 仅受控TLSedge外可达，SSO/BFF/PG私有loopback或firewall；framework不能自行认证header来源。
- 单跳edge覆盖不appenduntrusted链；CDN/multiproxy先定trustIP规则，本include非所有链证明。
- unknownHost拒/HTTP固定HTTPS/cert真实/issuer callbacklogoutbackchannel精确登记。
- signingkey仅SSO、TLSkey仅edge、BFF仅自身clientsecretissuer。
- log独立检查，不记TokenCode凭据query/body，acceptance accesslog关闭，productionerrorlogaggregation仍需secretreview。

```nginx
location / {
    proxy_pass http://127.0.0.1:9000;
    proxy_http_version 1.1;
    include /etc/nginx/las-forwarding.conf;
}
```

复制仓库include到选定path；scheme/serverport取edge实际listener，前LB外port不同须显式canonical覆盖非client值，无workstation默认/domain/DB迁移依赖。

## 参考HTTPS profile

可选https要求外环境issuer/client，不再用localidentity默认，并让Cookie与Starterlogout一致：

```text
LAS_SSO_ISSUER=<精确外部HTTPS issuer>
LAS_SSO_CLIENT_ID=<confidential client>
LAS_SSO_CLIENT_SECRET=<外部Secret>
```

```powershell
.\gradlew.bat :las-sso-reference-bff:bootRun --args="--spring.profiles.active=https --spring.config.additional-location=$HttpsConfigUri"
```

frameworkforward、hostonly __Host-las_reference SecureHttpOnlySameSiteLaxPath/、同session-cookie-name，可组合application-security。无Domain，upstreamHTTP仅受控私网。defaultBFF仍localHTTP；profile不自动限制listener/签cert/secretprovision/clientregister。SSObase已有Secure __Host-las_sso。

## 真实证据

独立Docker网络/PG18.4/EclipseTemurin21.0.12.1/Nginx1.29.8/final.3JAR，持久RSA3072signkey、独立临时TLSCA/leaf auth.las.test/bff.las.test。仅proxy映射hostloopback，appsDB无hostport；finalproxy只mount Nginx/include/TLScertkey不SSOsigning/clientsecret，app仅必要file。非productionfirewall/hostadmin限制验收。

NodeperrequestCA/SNI/hostname验证，unknownCA/wronghostname拒；JVM fixturePKCS12 truststore非trustall。Edgeperprocesshostresolver及**唯一leaf SPKI例外**，非globalignoreHTTPS/全局hosts/CA。真实HTTPS/SecureCookie，但publicCA/OStrust/证书续期待生产；两域同registrablesite，不验异站SameSite。

default/appowned两模式：

| 项 | 实际断言 |
| --- | --- |
| edge | CAhostDiscovery/endpoints/固定issuer/伪造Forwarded与Prefix仍正确callback、HTTP308/noCookie、unknown421、actuator404/HSTS |
| private | SSO/BFF/PG PortBindings空，仅proxyloopback发布 |
| login | ALTCHA注册/CSRF/双密码、实际CodePKCE/TokenJWKSUserInfo |
| Cookie | __Host hostonly SecureHttpOnlyLax/Path、无Domain、两origin JS不可读、浏览器HTTP不发 |
| reauth | harness标准promptloginage0、别browser401/Origin403/wrong401证据不进/correctsignedtime新sid保持HTTPSissuer |
| logout | 缺CSRF403保登录/current仅owningCookie消/别browser保持/SSO免密码返回/globalstate/伪造return400/真实CAHTTPSBack-channel清其他 |
| 风控 | 伪IP仍同observednetwork；fixture2次CAPTCHA/真实proof消费/第3block，plain两encoded正密码仍429RetryAfter |

fixturethreshold2/3jitter0非prod20/50window/饱和，两mode仅一次性DB清预算。无新Javacore/schema/businesspermission/RP15m实现或依赖锁变化。

初工具/顺序：BFF早discovery502、BusyBox无CA参数、Pythonready不通而Nodehostnameprobe通、timeoutloop加界，非产品缺陷；旧Forwardedprefix红独立复现，新repoinclude真转绿/finalprofile两模式过。

## 剩余

officialDNS/domain/client/publiccertificate续期、host/containerhardening/firewall/CDN多级prefix/跨站域、真实window/refresh到期、certkeyrotation/staleJWKS灾备、capacitymonitorfullscan/业务消费独立。本轮补隔离singlehopTLSbrowserproxy边界/修example，不宣称productionready。
