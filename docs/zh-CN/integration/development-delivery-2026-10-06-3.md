# TLS代理与Secure Cookie开发交付 — 2026-10-06

- ID **0.1.0-dev.20261006.3**，本地可辨识组合，旧ID历史保留。
- [English](../../integration/development-delivery-2026-10-06-3.md)／[manifest](../../integration/development-delivery-2026-10-06-3.json)／[TLS操作验收](../operations/tls-proxy-acceptance.md)。

## 范围修复

旧Nginx示例重写XForwardedHostProtoFor但保clientForwarded/Prefix，实际Nginx1.29.8/Boot4.1/Security7.1.1生成HTTPevilhostprefixcallback非registeredHTTPS。SSO精确URI仍拒非登记，非Code已窃，而是originpath扰乱。

新deploy/nginx/las-forwarding.conf清alternate metadata并canonicalserver_name/schemeport/observedIP，各proxylocation用全组，guide public/actuator均修；unknownhostdefault拒/HTTP固定HTTPS。framework非peertrustfilter，apps必须private/firewall；CDNmultihop/prefix另设计。

refBFF可选https profile外供issuerclientsecret、hostonly __Host-las_reference SecureHttpOnlyLaxPath/、startercleanupname一致，可appsecurity。不provisioncert/registerclient/restrictlistener，defaultlocalHTTP。SSO Secure__Hostbase不变。

无Javacore/API/dependencylock/migration/factors/businesspolicy改；仅deploy/profile输入，旧header红/新repoinclude绿，probe不印TokenstateNonce。

## 构建制品

Java21/Docker，las-sso-server/：

```powershell
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.3" "-PlasSsoStarterVersion=0.1.0-dev.20261006.3" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.3" --no-daemon --console=plain
```

约 **10:39–10:43 Asia/Shanghai** 强制 **486server+193starter+37reference=716** 零failerror skip20tasks/两JARmetadata/真PG。shutdownfixturepool日志不掩失败。count不变因为TLS是外部真实harness，非新增mock配置suite。仅localfilepub，两native.module/POM-only包外oldnewAPIcompile/Security7.1.1无compositeinheritlock；starterbyte不变，refJAR含真httpsprofile，无MavenCLIhosteddeploy。

## 真实topology/trust

finalJAR/EclipseTemurin21.0.12.1/PG18.4/Nginx1.29.8独立network，appsDB无hostport，仅proxyhostloopbackHTTPHTTPS。containerDNSalias/browserprocessresolver不修改hosts。persistentRSA3072signkey/独立短TLSCAleaf，finalproxy只config/include/TLScertkey不signing/clientsecret，app必要file，fixture非hardenedprodmanifest。

NodeperrequestCA/SNI/hostname、unknownCAwronghost拒；JVM显式truststore非trustall。Edgeprocessresolver/唯一leaf-SPKI私有CA例外非globalignoreHTTPS/OStrust。真实HTTPS/SecureCookie，publicCA/OS信任续期仍prod，两hostname同site，不验异站SameSite。

finalhashJAR/repo include/httpsprofile default/appowned两模式：

- 固定HTTPS discoveryissuer/endpoints/callback，伪ForwardedPrefixForProtoHostPort不变，HTTP308noCookie/unknown421HSTSactuator404；
- ALTCHA注册/CSRF/双密码、真CodePKCEJWKSTokenUserInfo；
- __Hosthostonly SecureHttpOnlyLaxPath/无Domain、两originJS不可读、browserHTTP不发；
- harnessreauthpromptage0别browser401/foreignOrigin403/wrong401证据不进/correctsignedtime/sid保持/HTTPSissuer；
- 缺CSRF403保登录，current清owncookie/别browserSSO保持/免密码返回；
- globalHTTPSstate/forged400、CAtrustedHTTPSBack-channel清另一BFF与SSOSession；
- fixture2CAPTCHA/widgetproof/3block依据observedIP，变ForwardedXForRealIP不绕，plain两encoded正密码仍429RetryAfter。

仅隔离DB两mode清预算，threshold2/3jitter0非prod20/50window容量；maincookies由browser管理非手工HTTP，Origin等反例用内存privatecookie/CSRF。reauth非RPpending/15m，无businesspermission。初startup502/BusyBoxCAoption/Pythonreadiness/timeoutloop为环境fixture，header红绿独立；八finalcontainerlogfixturepasswordkeysecret与Tokenrecoverycookiepattern无命中，非fullscan。

## 来源

base8cee3ed62fd7c53664e8026b99664c73fae5f19d/branchcodex/fix-sso-quality-review **加未commitworktree**，286sourcefingerprint cdd5763e10b60ee8aa909d8861901c61316825ce7c15e1143a76b01af445b7e6，新含deployscope/profile，非直接旧284scope比较。12artifactscriptmigrationconfig/3unchangedlocks，相对las-sso-server排docs/buildexternalSecret。

| 制品 | SHA-256 |
| --- | --- |
| Server bootJAR | 0f7027c217b284ef80fe1f4fe647d6d2792f9aba90b94989de4ff84352db269c |
| Reference BFF bootJAR | 387bb87aca5709f4250d5fe61c502d06a91a5c09d4d5796f934aab9e8c8283d8 |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

fixtureimage Nginxsha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de、Temurinsha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5，非prodscanclaim。defaultSNAPSHOT新.3不覆盖旧/hash非signaturecommittedrepro，无commitpush/productionDB/officialcert/remote/globaltrusthosts更改。清理containernetwork/keysecret另记录。

最终四专用app/proxy/DB容器及volume/private网络已删，外issuerclientpasswordpepper配置、TLS cert/privatekey/signingtrust二进制store清理，无globalCA/hosts安装；测试端口关闭，保checked非秘密fixture日志。

## 剩余

officialDNSdomaincert续期exactURI、realFirewallhost/containerhardening/CDNprefixcrosssite、prodthresholdrefreshwindowcapacitymonitorfullscan与consumeradoption独立。B1presubmitgap/持久Sessionindexreplaymultiinstance/queuedeadkeyrotation/backupRPORTo/staleJWKS/真实consumer15mepoch/hostedsignedcommit仍open。本轮完成隔离singlehopTLSproxySecureCookie与deployinputs修复，非productionready。
