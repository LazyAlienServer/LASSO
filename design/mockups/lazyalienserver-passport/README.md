# LazyAlienServer 通行证设计稿

## 品牌命名

- 对外产品名：`LazyAlienServer 通行证`
- 英文辅助标识：`LAS PASSPORT`
- `SSO` 仅表示内部技术能力，不作为产品品牌名称展示。

## 页面

| 文件 | 对应页面 | 设计重点 |
|---|---|---|
| `login.png` | `/login` | OIDC 登录上下文、用户名与密码、保持登录 |
| `account-overview.png` | `/account` | 通行证资料、安全状态、快速操作 |
| `active-sessions.png` | `/account/sessions` | 当前会话、其他会话撤销、退出所有设备 |
| `recovery-codes.png` | `/account/recovery-codes` | 可用数量、最近生成、重新生成说明；普通改密在此页弹层完成，不再进入独立安全页；未重新生成时不展示真实恢复码 |
| `authorized-apps.png` | `/account/authorizations` | LAS 服务、OIDC scopes、最近使用、撤销授权 |
| `account-deletion-v3.png` | `/account/delete` | 30 天等待期、影响范围、当前密码确认；统一父子容器内边距并消除重叠框线 |

`account-deletion.png` 和 `account-deletion-v2.png` 为历史概念稿，保留用于对比；后续实现应以 `account-deletion-v3.png` 为准。

## 统一视觉提示词

这些概念稿使用内置图像生成模式制作。各页面共享以下提示词约束：

- 以已有 LASLog 页面和登录概念稿作为配色、网格、卡片密度和工业编辑排版参考；
- 浅色方格背景、白色实体卡片、细紫色边框、非对称 12 栏网格；
- 品牌紫 `#746AEB`、操作紫 `#6254D8`、深紫黑 `#2C1C46`；
- 顶部统一展示 `LazyAlienServer 通行证` 与 `LAS PASSPORT`；
- 左侧导航统一包含账户概览、活动会话、恢复码、已授权应用、注销账号；
- 不显示邮箱、MFA、Minecraft、QQ、白名单、业务角色、Token 或 Authorization Code；
- 不使用玻璃拟态、游戏角色、霓虹渐变、巨型圆角或概念艺术式装饰。

页面专用提示词分别约束了账号资料、会话撤销、恢复码一次性秘密、OIDC 授权关系以及 30 天注销等待期。

## 使用边界

这些 PNG 是高保真方向稿，不是可直接上线的 UI。实现时仍需依据 API 契约补齐 loading、empty、validation error、rate limited、state conflict、service unavailable、键盘焦点和窄屏状态。
