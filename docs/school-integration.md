# 学校系统接入

[返回项目入口](../README.md)

本文按 2026-11-24 的代码记录教务相关功能（课表、考试、成绩、作业，以及登录、空教室和后台轮询）怎样认证、怎样选择线路、怎样判断失败。代码变动后以源码为准，并同步更新本文。

## 接入范围与入口

项目自行实现学校 CAS／WebVPN 请求，没有引入 BIT-Login SDK，也不使用 BIT101 社区后端账号。历史参考范围见[决策记录](decisions.md#参考项目与验证边界)。

| 系统 | 用途 | 地址／入口 | 公网 DNS | 校园网 DNS |
| --- | --- | --- | --- | --- |
| CAS 统一认证 | 签发各服务的 ticket，保存登录态 Cookie（TGC） | `https://sso.bit.edu.cn/cas/login` | 能解析 | 能解析（内网地址） |
| WebVPN 网关 | 校外访问校内服务的代理 | `https://webvpn.bit.edu.cn/` | 能解析 | **不能解析** |
| 教学中心 jwapp | 课表、考试、空教室 | 数据 `jxzxehallapp.bit.edu.cn`；认证预检 `jxzxehall.bit.edu.cn` | 能解析 | 能解析 |
| 正方教务 jwms／jsxsd | 成绩列表与单课详情 | CAS 注册 service 为 `http://jwms.bit.edu.cn/`（明文 http） | **不能解析** | 能解析 |
| 乐学 Moodle | 日历订阅（作业截止时间） | `https://lexue.bit.edu.cn/` | **不能解析** | 能解析 |
| 新乐学（畅课 TronClass） | 作业与提交状态 | `https://zy-eclass.bit.edu.cn/`；认证经 `zy-identity.bit.edu.cn`（Keycloak）转到 CAS | 能解析 | 能解析 |

解析情况为 2026-11 在真机移动数据和校园网 Wi-Fi 下实测。各站除新乐学（HTTP/2）外都只用 HTTP/1.1。延河课堂门户 `aita.yanhekt.cn` 顶栏的“教学管理（新乐学）”就指向 zy-eclass，门户本身不放作业。

WebVPN 把目标 host 加密后编进路径：`https://webvpn.bit.edu.cn/<scheme>[-端口]/<iv><加密 host>/<原路径>`，`<iv>` 是固定密钥 `wrdvpnisthebest!` 的十六进制，host 用同一密钥以 AES-CFB 加密。编解码见 [WebVpnCodec](../app/src/main/java/com/example/personal_studio/data/network/bit/WebVpnCodec.kt)，各服务的代理前缀见 [BitUrlsConfig](../app/src/main/java/com/example/personal_studio/data/network/bit/BitUrlsConfig.kt)，测试核对了全部前缀。Retrofit 使用相对业务路径，不能用以 `/` 开头的路径冲掉代理前缀。

## 整体结构

- **共享客户端与会话锁。** [`BitApiClient`](../app/src/main/java/com/example/personal_studio/data/network/bit/BitApiClient.kt) 是单例。前台同步、Worker、网页验证都先 `acquireSession()`，从 `open(mode)` 一直持有到 `close()`，关闭与释放放在 `finally` 中。`open(mode)` 按直连或 WebVPN 为 jwapp、jwms、乐学建立 Retrofit 实例；新乐学始终用公网地址。
- **两类请求。**
  - 登录链请求走 [`BitAuthSession`](../app/src/main/java/com/example/personal_studio/data/network/bit/BitAuthSession.kt)：不自动跟随跳转，每一跳由代码检查；只访问可信学校地址（`BitAuthUrls.trusted`）；表单只提交到 CAS；请求带 `BitAuthFlow` 标记。
  - 业务请求走 Retrofit：自动跟随跳转，跟完后由 [`SchoolPageGuard`](../app/src/main/java/com/example/personal_studio/data/network/bit/SchoolPages.kt) 检查最终落在什么页面（见[响应检查与失败原因](#响应检查与失败原因)）。
- **拦截器顺序**（[BitNetworkModule](../app/src/main/java/com/example/personal_studio/core/di/BitNetworkModule.kt)）：统一浏览器头 → `BitTrace.finals`（记录每次调用的最终结果）→ `SchoolPageGuard` → OkHttp 跳转与连接 → 浏览器 Cookie 同步 → `BitTrace.hops`（记录每一跳）。连接、读取超时各 30 秒。
- **Cookie。** [`BitCookieJar`](../app/src/main/java/com/example/personal_studio/data/network/bit/BitCookieJar.kt) 是会话内的临时 Cookie。原生请求还通过 network interceptor 与 [CookieManager](../app/src/main/java/com/example/personal_studio/data/network/bit/BitBrowserSessions.kt) 双向同步，这份“浏览器 Cookie”与验证用 WebView 共用，保存 CAS 的 TGC、WebVPN 会话等，详见 [Cookie、连接与互斥](#cookie连接与互斥)。

## 线路选择与 WebVPN

### 每个功能先走哪条线路

[`ResolveNetworkModeUseCase`](../app/src/main/java/com/example/personal_studio/domain/bitimport/ResolveNetworkModeUseCase.kt) 按 `SchoolRoute` 决定首选线路（`NetworkMode.LOCAL` 直连，`NetworkMode.WEBVPN` 经网关）：

| SchoolRoute | 使用者 | 首选线路 |
| --- | --- | --- |
| `PUBLIC` | 课表、考试、空教室、登录页校验密码 | 直接 `LOCAL`，不探测（教学中心公网可达） |
| `CAMPUS` | 成绩、作业（乐学＋新乐学）、成绩和作业的后台轮询 | 探测 jwms：能直连则 `LOCAL`，否则 `WEBVPN` |

一键同步只探测一次，课表和考试用 `PUBLIC` 的结果，作业和成绩用 `CAMPUS` 的结果。历史 `lastMode` 只作为记录，不参与选择；登录界面不提供手动切换。Wi-Fi／蜂窝不能直接等同于校内／校外。

**校内探测**（[CampusReachabilityProbe](../app/src/main/java/com/example/personal_studio/data/network/bit/CampusReachabilityProbe.kt)）：对 `http://jwms.bit.edu.cn/` 发 GET，不跟随跳转，不带 Cookie，带 `Connection: close`，只读状态行，只认 2xx；连接、读取各 2 秒，整个调用 3 秒，可随调用方取消。

- 不跟随跳转、只认 2xx：校园网还没完成网页认证时会被跳到认证页，不能误判为在校内。
- 不能用 HEAD：jwms 对 HEAD 返回 403、对 GET 返回 200（2026-11 校园网实测）。用 HEAD 时校内探测总是失败，成绩和乐学被送去 WebVPN，而校园网解析不到 WebVPN。
- 不保留连接：留在池里的旧连接可能已经失效，碰上它会耗尽 3 秒预算，把校内判成校外。

### 换线路

[NetworkFallback](../app/src/main/java/com/example/personal_studio/data/network/bit/NetworkFallback.kt) 只在**连接级失败**（各功能的 `NetworkFail`，即 `IOException`）时换另一种线路再试一次，并在成功后回告生效线路（只作历史记录）。密码错误、锁定、需要验证、评教、学校返回的异常页面（`School(problem)`）、解析异常或真实空数据都不换线路，避免掩盖真因或重复提交密码。

作业一项特殊：乐学校内直连、校外必须经 WebVPN，新乐学两种线路都走公网。线路选错时只有乐学失败，作业整体仍算成功，原本不会触发换线路。现在**只有乐学连不上（新乐学成功）也算连接级失败**：

- 前台 `syncAuto`：`DdlSyncStep.unreachable()` 判断，第二次的结果作为最终结果。
- 后台 [DdlPollWorker](../app/src/main/java/com/example/personal_studio/core/workers/DdlPollWorker.kt)：用 `DdlSyncOutcome.then()` 按平台合并两次结果，后一次只是连不上时保留前一次已拿到的；`lexueUnreachable` 为真或全部连不上时才试第二条线路。
- 成绩的 [GradePollWorker](../app/src/main/java/com/example/personal_studio/core/workers/GradePollWorker.kt) 对 `Transient` 结果同样换线路一次；空教室用 `withSessionAutoFallback` 做同样的事。之后仍失败的交给 WorkManager 指数退避。

### 什么时候真正经过 WebVPN

一次会话以 `WEBVPN` 打开时，只有这些请求改走网关（`BitAuthUrls.route`，代理表 `proxyBases`）：教学中心（`jxzxehall`、`jxzxehallapp`）、jwms、乐学的业务请求，以及它们的 ticket 回调。**CAS 永远直连**，ticket 都向 `sso.bit.edu.cn` 直接申请；**新乐学永远直连**。

所以实际用到 WebVPN 的只有三种情况：

1. 成绩或乐学在校外（探测失败）。
2. 课表、考试、空教室、登录直连时遇到连接级失败，换到 WebVPN 重试；或成绩、乐学探测为校内却连不上，换到 WebVPN。
3. 学校网页验证：WebView 打开 `CAS?service=<网关回调>`，经网关门户完成，网关的设备校验脚本由 WebView 自己执行。

## 登录与会话

### 原生认证链

[`SsoLoginUseCase`](../app/src/main/java/com/example/personal_studio/domain/bitimport/SsoLoginUseCase.kt) 为教学中心（`invoke`）、成绩（`loginGrades`）、乐学（`loginLexue`）和新乐学（`loginEclass`）提供各自的入口。每次业务同步都会完整走一遍：

1. **准备账号。** `prepareAccount` 在换账号时清掉浏览器 Cookie 和设备标识。
2. **确定 service。**
   - 教学中心：不带 Cookie 访问 `jxzxehall` 的认证入口（公开预检），从 302 到 CAS 的地址里取带本次 nonce 的 service。
   - 成绩：`http://jwms.bit.edu.cn/`；乐学：`https://lexue.bit.edu.cn/login/index.php`。
   - 新乐学：从 `/user/courses` 顺着 zy-identity 的跳转走到 CAS，取其中带本次 `state` 的 broker service（地址写作 `/cas//login`，判断 CAS 时会合并重复斜杠）；若 zy-eclass 直接返回页面，说明会话仍有效，不再认证。
3. **直连 CAS 申请目标 ticket（先不兑换）。**
   - TGC 有效时 CAS 直接 302 带回 ticket，不提交密码。
   - 否则解析 `login-page-flowkey` 和 `login-croypto`，按 [CasAesCrypto](../app/src/main/java/com/example/personal_studio/core/util/CasAesCrypto.kt) 加密后提交表单。密钥需先 Base64 解码，算法是学校要求的 AES-ECB，不是旧计划中的 CBC。
   - 回到短信二次验证页：交互登录在 App 内完成（见下节），业务同步和后台任务直接以 `CaptchaRequired` 结束，显示“学校要求验证”。
   - 返回错误码：`1030027`／`1030031` 为密码错误，`1030028` 为锁定。
   - 要求响应带 ticket，且回调的 origin／path 与 service 一致。HTTP 200、空响应、没有错误提示，均不能单独证明登录成功。
4. **需要 WebVPN 时（新乐学除外）先登录网关**（`signInGateway`）：
   1. **设备校验**（2026-11 起）：新网关会话访问 `/login` 会先被送到 `/fingerprint`。该页用 FingerprintJS 算出访客标识，再请求 `/set-fingerprint?<本页查询参数>&fingerprint=<标识>`；之后 `/login` 才接受 CAS ticket。在此之前兑换的 ticket 会在跳转途中丢失，网关停在未登录状态。原生流程先访问 `/login`，若被送去 `/fingerprint`，就打开该页并按脚本的做法提交标识；已登录或已校验的网关不做处理。标识是每次安装随机生成的 32 位十六进制数，存于 `BitBrowserSessions`，随浏览器 Cookie 清除或换账号时重新生成。
   2. 直连 CAS 申请**网关自己的** ticket（TGC 有效时立即签发）。
   3. 访问 `webvpn.bit.edu.cn/login?cas_login=true&ticket=…`，**只检查这一跳**：302 且没有被送回 `/fingerprint`、网关 `/login` 或 CAS，就算登录成功；200（网关自己的登录页）或被送回这些页面算未登录。网关登录后会跳到它在会话里记着的上次目标页，那可能是早已失效的回调，最后落在 404／500，所以不再跟随。
   4. 未登录时重新做设备校验，换一张新 ticket 再试一次；仍不行就以“校外访问需要先验证”结束。
5. **成绩先初始化 jwms。** 访问 jwms 根入口（校外经网关）取得其会话 Cookie，再兑换 ticket。
6. **兑换目标 ticket。** 访问回调地址（校外改写为网关地址），在可信地址内最多跟 10 跳，每一跳都经 `route` 改写：
   - 落到网关的 `/fingerprint` 或 `/login`：以“校外访问需要先验证”结束。
   - 非 2xx：失败，5xx 记为“学校系统繁忙”。
   - 落到 CAS 或页面显示会话失效：按 `CaptchaRequired` 结束。
   - 原生 HTTP 不执行网页 JavaScript，只对独立、完整的字面量跳转脚本做受限跟随；函数体、点击事件和条件中的 `location` 赋值不能当成立即跳转。
7. **教学中心再打开课表入口** `wdkbby/*default/index.do` 完成初始化。

目标 ticket 先申请、后兑换：校外必须在网关登录之后经网关兑换，若先在不可达的校内地址上消费，就只能重新申请。解析与错误页识别在 [BitAuthPages](../app/src/main/java/com/example/personal_studio/data/network/bit/BitAuthPages.kt)。

### 交互验证

交互登录（登录页、同步面板的“去验证”／“重新登录”、校园页“重新验证”）时，[`BitLoginViewModel`](../app/src/main/java/com/example/personal_studio/feature/auth/BitLoginViewModel.kt) 用 `PUBLIC` 线路（教学中心直连，连接失败再换 WebVPN），把一个 [`CasPrompter`](../app/src/main/java/com/example/personal_studio/domain/bitimport/CasPrompter.kt) 交给登录链，学校要的额外步骤在 App 内的底部面板里完成（[CasVerifySheets](../app/src/main/java/com/example/personal_studio/feature/auth/ui/CasVerifySheets.kt)）：

- **短信二次验证**：密码提交后，CAS 返回带 `user-object-id` 和 `secondSmsLoginForm`／`second-auth-tip` 的页面（[`BitAuthPages.secondFactorPage`](../app/src/main/java/com/example/personal_studio/data/network/bit/BitAuthPages.kt)）。[`CasSmsChallenge`](../app/src/main/java/com/example/personal_studio/domain/bitimport/CasSmsChallenge.kt) 按学校页面的做法：`POST /cas/api/protected/sms/getPhoneNumberByUserId`（加密，取不透明的 `tel` 和脱敏 `maskTel`）→ `…/sms/publicNoToken/sendSmsCode` `{phone, businessNo: "0008"}` → 用户输入 → `…/sms/checkToken` `{phone, token, delete: false, trustDevice}`；通过后提交 `type=smsLogin` 表单（`password` 为验证码，带 `execution`、`trustDevice`），拿到 ticket 后照常兑换。412／8000068 表示上一条验证码仍有效，按返回的剩余秒数倒计时。
- **图形验证码**：交互登录在提交密码前先 `GET /cas/api/protected/user/findCaptchaCount/{学号}`；`captchaInvisible` 为真时显示 `captchaUrl`（data URL）里的图片，答案作为 `captcha_code`，`captchaPayload` 按 `croypto` 加密后作为 `captcha_payload`。这个检查失败或返回异常时照旧直接提交密码。
- **协议细节**（[`CasApi`](../app/src/main/java/com/example/personal_studio/data/network/bit/CasApi.kt)、[`CasCrypto`](../app/src/main/java/com/example/personal_studio/data/network/bit/CasCrypto.kt)，2026-11 对照学校 `cas-gateway` 前端脚本核实）：`protected` 路径带 `Csrf-Key`／`Csrf-Value`；需要加密的路径以服务端 `/gate/sso-extend/protected/api/dictconfig/get` 的 `URL_CRYPTO_DATA.SID` 列表为准（读不到时只加密查手机号这一项）。加密方式为随机 AES 密钥（Base64 文本）以 ECB 加密 JSON，密钥文本用学校脚本里的 RSA 公钥（PKCS#1）加密放进 `privateKey` 头，并带 `hasCrypto: true`；回复用同一密钥解密，可能嵌套两层。这些接口只在 CAS 源站调用，验证码不记日志、不保存。

原生流程处理不了的情况交给[学校官方网页弹窗](../app/src/main/java/com/example/personal_studio/feature/auth/ui/BitWebVerificationDialog.kt)：滑块等第三方验证码、首次修改密码、邮箱二次验证、查不到绑定手机、表单地址异常，以及用户在面板里点“改用学校网页验证”。这些都以 `CaptchaRequired` 结束原生登录，再自动打开一次网页。BIT-Login 只作接口参考（未声明开源许可，未复制代码）。

网页无主文档错误地到达干净的 HTTPS WebVPN 根入口后，触发原生再验证；其他落地页可使用“验证完成，继续”。**网页跳转或点击按钮本身不等于登录成功**，只有后续原生验证通过才保存凭据并回到目标功能。再次要求验证时停止自动弹窗，避免循环。

登录过程显示阶段、阻止重复提交并支持取消；同一次操作固定使用开始时的账号信息，只去掉学号首尾空白，保留密码字符。业务同步或后台任务没有 `CasPrompter`，遇到短信或验证码时返回“需要验证”，不会发送短信、不弹出界面，需要回到登录入口完成验证。

### Cookie、连接与互斥

仅让“一键同步”内部串行，不足以防止后台任务同时关闭客户端，所以所有入口都要遵守同一把会话锁。课表导入只在登录和读取期间持锁，读完即关闭并释放，再进入预览；用户停在预览页时不阻塞后台轮询和其他同步，确认后只写本地数据库。

[`BitCookieJar`](../app/src/main/java/com/example/personal_studio/data/network/bit/BitCookieJar.kt) 以 `(domain, path, name)` 为键，新 Cookie 覆盖旧值，匹配请求使用标准 `Cookie.matches()`。浏览器 Cookie 同步在 network interceptor 中进行，响应 Cookie 立即写入，下一跳就能使用；公开预检请求既不带也不保存 Cookie。

每次 `close()` 释放 Retrofit 实例、临时 Cookie 和**全部空闲连接**，**保留浏览器 Cookie**：

- 浏览器 Cookie 由学校有效期控制，切账号或退出登录才主动清除；没有“固定七天免短信”的客户端保证。因为它保留，后续同步通常不必再提交密码，也不触发短信。校园页的“账户已保存”表示本地凭据存在，不表示所有服务在线，“上次连接”也不表示当前网络位置。
- 清空闲连接是因为两次同步之间，运营商 NAT 或 Wi-Fi／移动数据切换会悄悄断开它们，复用时请求要等满 30 秒读超时。2026-11 真机日志里，每次同步的第一个统一认证请求都这样失败过（空闲 2～11 分钟后）；改为每个会话从新连接开始后，间隔 6～16 分钟的移动数据同步都没有再出现。

## 响应检查与失败原因

### 业务请求落在了哪里

`SchoolPageGuard` 检查每个业务请求（登录链请求除外）跟完跳转后的最终地址、状态码和响应开头：

| 情况 | 范围 | 结果 |
| --- | --- | --- |
| 最终落在网关的 `/fingerprint` 或 `/login` | 任何业务请求（含乐学订阅） | `WEBVPN_SIGN_IN`：校外访问需要先验证 |
| 5xx | 教学中心（路径含 `/jwapp/`）、教务（含 `/jsxsd/`）和乐学（直连或经网关） | `SERVER_BUSY`：学校系统繁忙或正在维护 |
| 最终落在 CAS 登录页（直连或经网关） | 同上 | `SESSION_EXPIRED`：登录已过期 |
| 应返回 JSON 的教学中心接口返回了 HTML | jwapp 中除 `index.do` 外的接口 | 是登录页 → `SESSION_EXPIRED`；是繁忙／网关错误页 → `SERVER_BUSY`；其他 → `UNEXPECTED_PAGE` |
| 其他 | — | 放行，交给各功能自己的检查（如成绩的评教、成绩表）。新乐学不在检查范围内：它被带到登录页只表示需要先认证，由自己的流程处理；它的非 2xx 按连接级失败处理 |

命中时抛出 `SchoolPageException`。它只因为 OkHttp 拦截器只能抛 `IOException` 才继承 `IOException`；各用例在网络错误分支**之前**捕获它，转为各自错误类型的 `School(problem)`（`ImportError`、`ExamSyncError`、`GradesSyncError`、`DdlSyncError`），所以**不会触发换线路**，也不会被报成“连不上”。

登录链自身的失败也带原因：`CasLoginDto.UnknownFailure` 的 `problem` 在 5xx 时为 `SERVER_BUSY`，网关登录不成功或兑换途中停在网关时为 `WEBVPN_SIGN_IN`。各功能把带 `problem` 的登录失败同样转为 `School(problem)`。

### 同步面板显示什么

[`SyncAllUseCase`](../app/src/main/java/com/example/personal_studio/domain/bitimport/SyncAllUseCase.kt) 的 `SyncFailures` 把各功能的错误映射为 [`SyncFailure`](../app/src/main/java/com/example/personal_studio/domain/bitimport/model/SyncAllModels.kt)，文案在 [SyncSheet 的 `SyncText`](../app/src/main/java/com/example/personal_studio/feature/bitimport/ui/SyncSheet.kt)：

| SyncFailure | 来源 | 文案 | 按钮 |
| --- | --- | --- | --- |
| `VERIFY` | CAS 要短信／验证码 | 学校要求验证，其余各项不受影响 | 去验证 |
| `GATEWAY` | `WEBVPN_SIGN_IN` | 校外访问需要先验证 | 去验证 |
| `PASSWORD` | 密码未被接受 | 学校没有接受保存的密码，请重新登录 | 重新登录 |
| `SESSION` | `SESSION_EXPIRED` | 登录已过期，请重新登录 | 重新登录 |
| `LOCKED` | 账户锁定 | 账户被学校暂时锁定，请稍后再试 | — |
| `REVIEW` | 需要先评教 | 需要先在教务系统完成评教 | — |
| `BUSY` | `SERVER_BUSY` | 学校系统繁忙或正在维护，请稍后再试 | — |
| `NETWORK` | 两条线路都连接失败 | 连不上学校服务器，请检查网络后重试 | — |
| `EMPTY` | 学校返回为空 | 学校返回为空，本机数据未改动 | — |
| `OTHER` | `UNEXPECTED_PAGE`、无法归类的解析失败或异常 | 学校返回了意外的内容，稍后再试 | — |

“去验证”和“重新登录”都打开交互登录。各功能页和课表导入向导用 `SchoolProblem.text` 或[导入错误横幅](../app/src/main/java/com/example/personal_studio/feature/bitimport/ui/components/ErrorBanner.kt)给出同样的原因。后台轮询见[一键同步与后台轮询](#一键同步与后台轮询)。

## 业务数据链路

### 课表

[`ImportCoursesUseCase`](../app/src/main/java/com/example/personal_studio/domain/bitimport/ImportCoursesUseCase.kt) 登录教学中心后调用 `getAppConfig`、`switchLang` 预热 `wdkbby`，再获取当前学期、第 1 周日期与课表。缺少预热曾导致登录成功后业务端点仍返回 403。

学期字典使用 `DM/MC`；课表行使用 `XNXQDM` 等另一组字段，不能照搬同名系统的 DTO。`cxzkbrq.do` 必须传 `ZC=1` 才获得第一周日期；课程按 `SKZC` 位图展开。预览确认、25 周覆盖范围与学期锚点副作用见[日程写入规则](timeline.md#学校数据写入规则)。

### 成绩与分析

[`SyncGradesUseCase`](../app/src/main/java/com/example/personal_studio/domain/bitgrades/SyncGradesUseCase.kt) 建立 jwms 会话后，POST `jsxsd/kscj/cjcx_list`，提交 `kksj/kcxz/kcmc/xsfs=all`。数据源是 HTML，不是早期假设的 ehall `cjcx` JSON 接口。

同步在解析前检查 HTTP 状态、登录／评教拦截页和 `table#dataList` 是否存在，再交给 [JsxsdGradeParser](../app/src/main/java/com/example/personal_studio/domain/bitgrades/JsxsdGradeParser.kt)。找不到表属于异常；真实空表也不执行前台全量覆盖。单课 `cjfx` 详情并发获取，为可选补充：详情路径要去掉前导 `/` 才能保留网关前缀；详情失败保留该课基本成绩，不阻断整个同步。

成绩列表没有直接提供全部绩点字段，由 [BitGpaConverter](../app/src/main/java/com/example/personal_studio/core/util/BitGpaConverter.kt) 换算，再经 [ComputeGpaUseCase](../app/src/main/java/com/example/personal_studio/domain/bitgrades/ComputeGpaUseCase.kt) 按学分加权。当前百分制算法为低于 60 分记 0，否则 `4 − 3 × (100 − 分数)² / 1600`；通过／免修等无法计入 GPA 的项目返回 null 并跳过。等级制和均分代表值以转换器为准。

界面还提供目标 GPA 反推、假设绩点预测、成绩分享卡和 AI 学情报告。AI 摘要不含姓名／学号，但会把课程及成绩信息发给用户配置的模型服务，不能称为完全离线分析。

“专业前 p%”中 p 越小越靠前；整体专业百分位和同学平均绩点是本地估计，不是学校发布的总排名。模型假设、非线性换算与区间含义见[成绩统计决策](decisions.md#成绩统计的解释边界)。

### 考试

当前考试入口为 `wdksapMobile/*default/index.do`，业务请求为 POST `wdksapMobile/modules/ksap/cxxsksap.do`，表单为 `XNXQDM` 和 `*order=-KSRQ`。旧 jsxsd 考试 HTML 与旧 `studentWdksapApp` 路径均不代表现状。

[`SyncExamsUseCase`](../app/src/main/java/com/example/personal_studio/domain/bitexam/SyncExamsUseCase.kt) 登录教学中心并预热 `wdkbby` 取当前学期，打开考试入口后取数；[ExamRowMapper](../app/src/main/java/com/example/personal_studio/domain/bitexam/ExamRowMapper.kt) 解析课程、时间、地点及座位，按 `+08:00` 换算时间并写入 Timeline 的 `EXAM`。仅有日期时退化到当天零点；不能解析的行会被跳过。

### 乐学作业

订阅地址保存为**乐学自己的地址**（`https://lexue.bit.edu.cn/calendar/export_execute.php?…`），存于 DdlSyncPrefs。这个 URL 本身带访问能力，不要放进日志或文档。

[`SyncAssignmentsUseCase`](../app/src/main/java/com/example/personal_studio/domain/bitddl/SyncAssignmentsUseCase.kt) 读取乐学的步骤：

1. 用保存的地址直接读，读取时用 `BitApiClient.routed()` 按当前线路改写（校外为网关地址）。是 VCALENDAR 就解析。
2. 读到的不是日历，或被页面检查拦下（网关未登录、登录过期、繁忙，都按“不是日历”处理）时，用 [`GenerateLexueIcalUrlUseCase`](../app/src/main/java/com/example/personal_studio/domain/bitddl/GenerateLexueIcalUrlUseCase.kt) 重新派生：`loginLexue`（校外先登录网关）→ 乐学首页取 `sesskey` → 提交日历导出表单 → 从页面取出订阅地址。经网关时页面上的地址可能是网关地址或只有路径：路径按导出页地址补全，网关地址用 `BitAuthUrls.unroute` 还原成乐学原址后再保存。
3. 用新地址再读一次，仍不是日历才报错。无需每次读取都重新认证。

[`LexueIcalParser`](../app/src/main/java/com/example/personal_studio/domain/bitddl/LexueIcalParser.kt) 处理折行、TEXT 转义、UTC 时间及本地／全天日期，取 UID、标题、描述、课程和 `DTSTART`；非 UTC 日期按 `+08:00`。这是面向乐学 feed 的有限解析器，不支持所有 RFC 5545 规则或任意时区。单条缺 UID／日期会跳过，合法日历但条目解析失败仍可能得到空列表，这与真实空订阅目前并未完整区分。

DDL 是日历元数据；“完成”仅在本地记录，没有查询 Moodle 的实际作业提交状态。

### 新乐学作业

新乐学是畅课（TronClass）系统，没有日历订阅地址，只能登录后读接口，且始终公网直连。[`FetchEclassAssignmentsUseCase`](../app/src/main/java/com/example/personal_studio/domain/bitddl/FetchEclassAssignmentsUseCase.kt) 只读三类接口：

1. `GET api/my-semesters`，取 `is_active` 的学期（都没有时取 `sort` 最大的）。
2. `POST api/my-courses`，`conditions.semester_id` 限定本学期，分页。
3. 每门课 `GET api/courses/{id}/homework-activities?page=&page_size=100`，并发、分页。课堂小测也在这里。

时间是 UTC ISO-8601；取 `end_time`（缺省用 `deadline`），跳过未发布或无截止的条目。说明是教师填写的 HTML，转成纯文本。uid 为 `eclass-homework-<id>`。`api/todos` 只列未交且开放的作业，交完就为空，不能作为完整来源。

先直接读：学校网页 Cookie 仍有效（或 CAS 能直接签票）时不必认证。拿到的不是 JSON（被带到登录页）或 401／403 才调 `loginEclass`，再读一次；仍不是 JSON 则报解析失败。

### 两个平台的合并

两个平台在同一次会话里依次拉取，结果是按平台的 `DdlSyncOutcome`：

- 落库按平台进行，一个平台失败时不改它已有的作业；手动刷新只要一个平台成功就算完成，并写明哪个平台没同步上。
- 只有乐学连不上时换线路再试一次，见[换线路](#换线路)。
- 密码错误／账号锁定无论哪个平台发现都按账号问题处理；后台轮询在另一平台成功时不因单个平台的其他失败停轮，只记入结果说明。
- 新作业通知按平台建基线：某平台第一次同步不通知；没同步上的平台保留上次的 UID，恢复后不会误报。

新乐学返回本人是否已提交（`submitted`）。新作业已提交就直接记为完成；已有作业在“刚提交”（上次同步时未提交）时自动打勾并取消提醒。用户之后手动取消，不会再被勾上；系统从不自动取消完成。上次已提交的 uid 存在 DdlSyncPrefs。

### 空教室

[`EmptyRoomRepository`](../app/src/main/java/com/example/personal_studio/domain/emptyroom/EmptyRoomRepository.kt) 登录教学中心（`PUBLIC` 线路）并预热 `wdkbby`，读取校区、教学楼和 `kxjasbyController/cxkxjasqk.do` 的占用数据；多个教学楼在同一个已打开会话内并发获取。

每次会话结束都会清空临时 Cookie，每打开一次会话就要重新走认证链（TGC 有效时不提交密码）。为此空教室把登录次数压到最少（[`EmptyRoomViewModel`](../app/src/main/java/com/example/personal_studio/feature/emptyroom/EmptyRoomViewModel.kt)）：
- 校区和教学楼作为“目录”存在本机（[`EmptyRoomPrefs`](../app/src/main/java/com/example/personal_studio/data/local/datastore/EmptyRoomPrefs.kt)），30 天后或在选择面板里手动重新获取。获取时校区一次请求，教学楼用不带 `XXXQDM` 的 `cxjxl.do` 一次拿全（每行带所属校区）；若有行缺校区代码，就在同一会话内逐个校区再取。打开页面、切换校区和教学楼都不联网。
- 占用数据按“楼 + 日期”缓存在内存，查询前先查缓存，全部命中就不登录；时间段筛选只在已取到的结果上过滤。目录需要更新且有记住的地点时，目录和占用在同一次会话里一起取。
- 记住上次的校区、教学楼、时间段和最近用过的楼，有记住的地点时打开页面即查询。

[`ComputeFreeSlotsUseCase`](../app/src/main/java/com/example/personal_studio/domain/emptyroom/ComputeFreeSlotsUseCase.kt) 把 `ZYJC` 忙节次转换为 1–13 节的空闲区间，结合用户作息表计算“现在空／空到何时”等状态。结果不是教室实时门禁或实际占座信息。

缓存只存原始占用行，查询时重新计算相对时间；当前键为“楼代码 + 日期”，没有旧设计承诺的到节次边界自动失效，也没有 TTL 或学期／账号隔离。部分缺失响应仍以空列表处理，不能把空教室结果当作所有学校接口已完成严格响应校验的例子。

## 一键同步与后台轮询

`SyncAllUseCase` 先决定线路（教学中心 `PUBLIC`，成绩／作业 `CAMPUS`，只探测一次），再按 **课表 → 作业 → 考试 → 成绩** 串行执行。每项各自开会话、登录、同步、关闭，单项失败继续后续项；课程预览在此自动确认。每个失败来源带一个原因，见[同步面板显示什么](#同步面板显示什么)。“只重试失败项”只重跑失败来源，其余来源沿用上次结果。一次同步全部会走 3～4 次认证链，TGC 有效时都不提交密码。

同步由单例 [`SyncCenter`](../app/src/main/java/com/example/personal_studio/domain/bitimport/SyncCenter.kt) 在应用级协程里运行，同步面板、今日胶囊和首次同步页共享同一份进度；关闭面板不会中断同步。完成或跳过会设置首次同步标记；至少一项成功时记录 `LoginPrefs.lastSyncAt`。

成绩和 DDL 轮询使用 WorkManager，默认关闭，可选 6／12／24 小时；成绩默认 6 小时，DDL 默认 12 小时。任务要求网络已连接，周期执行不保证精确时刻。应用启动和开机会按配置恢复调度。两个 Worker 都用 `CAMPUS` 线路，连接级失败换线路一次（作业按平台合并，见[换线路](#换线路)）。

| 行为 | 成绩轮询 | DDL 轮询 |
| --- | --- | --- |
| 变化识别 | 学期、课程、考试性质、成绩构成签名，改分也可成为新增变化 | UID 集合差异；改截止时间只更新，不算新作业 |
| 首次运行 | 建立通知基线，不全量写入成绩库、不通知 | 写入 Timeline 并建立基线，不通知 |
| 后续运行 | 只为新增变化补详情并 upsert，保留未变化课程的现有详情 | 每次按来源更新 Timeline，再通知新增 UID |

实现入口：[GradePollWorker](../app/src/main/java/com/example/personal_studio/core/workers/GradePollWorker.kt)、[DdlPollWorker](../app/src/main/java/com/example/personal_studio/core/workers/DdlPollWorker.kt)。

- 无凭据时停轮；密码错误／锁定会清凭据并停止该轮询。
- 需要验证、`School(problem)`（`SERVER_BUSY` 除外）或解析失败会停轮并通知。
- 未评教只记录状态，保持启用。
- 连接级失败和学校繁忙（`SERVER_BUSY`）交给 WorkManager 指数退避，保持启用，避免连续认证请求。作业判断用 `BackgroundDdlResult.temporary`：繁忙在结果里仍是 `Stop(School(SERVER_BUSY))`，所以手动刷新能显示“学校系统繁忙”，也不会被当成“乐学连不上”去换线路。

## 诊断日志

正式版也写脱敏轨迹（[BitTrace](../app/src/main/java/com/example/personal_studio/data/network/bit/BitTrace.kt)），设备连电脑后用 `adb logcat -s BitTrace` 读取：

| 行 | 含义 |
| --- | --- |
| `GET host/path?参数名 → 200 最终host/path [内容类型] 页面类型` | 一次业务或登录调用的最终结果；页面类型如 `JSON`、`CALENDAR`、`CAS_LOGIN`、`WEBVPN_LOGIN`、`ERROR_PAGE`、`HTML` |
| `  ↪ GET … → 302 → 目标` | 一次跳转 |
| `  ✗ GET … 异常类名`、`GET … ✗ 原因` | 连接或读取失败；被 `SchoolPageGuard` 拦下时写出 `SchoolProblem` |
| `  ⟲ GET …（复用空闲 Ns 的连接）` | 这一跳复用了空闲 10 秒以上的连接 |
| `sync: 教学中心 X，成绩/乐学 Y`、`sync: ▶／■ 来源 状态 原因`、`sync: switching to …`、`sync: failed with …` | 一键同步的线路、每项开始与结果、换线路和失败原因 |
| `login 目标: 结果 原因` | 登录链在哪一步、因何结束（如“需要短信二次验证”“WebVPN 设备校验未通过”） |

只记录方法、host 和路径、查询参数的**名字**、状态码、跳转目标和页面类型。WebVPN 的编码段缩写为 `…`，6 位以上的数字（如 CAS 路径里的学号）写成 `#`。不记录密码、Cookie、请求头、响应正文、查询参数值（ticket、订阅 token）或验证码。Debug 构建另有一条只含 host/path 和状态码的 `BitAuth` 日志。

## 当前边界

- 每个业务入口仍会重新走目标服务认证链；可复用 CAS 的 TGC，但没有独立的“服务会话仍有效就直接执行业务”入口。
- 统一认证偶尔在会话中途长时间不响应（2026-11 见过一次提交密码时等满 30 秒），只能靠超时和换线路兜底；换线路对它无效，因为两条线路都要直连 CAS。
- 依赖学校页面细节：CAS 表单标记和错误码、WebVPN 的 `/fingerprint` → `/set-fingerprint` 流程、jwms 对 GET 返回 2xx、教学中心接口返回 JSON。学校改版后会再失效，但失败会带具体原因，`BitTrace` 可定位到具体一跳。
- 新乐学的登录链只在浏览器里实际走通过（含 `/cas//login` 和 broker 回调）；App 内的原生认证链由本地模拟服务器测试覆盖。从未登录过新乐学的账号可能遇到 Keycloak 的首次登录页，目前会报“登录后仍未进入”。
- 乐学 token、业务缓存与已下载数据没有完整账号隔离；有效空结果与解析丢失的保护在不同功能之间也不一致。
- 原生短信验证的接口来自学校前端脚本，学校改版就可能失效；失效时退回网页弹窗。“信任这台手机”是否生效、生效多久由学校决定。
- 学校是否需要短信、多久免验证由服务端决定。不能从一次成功登录或单元测试通过推导出长期免验证、全服务可用。

## 排障顺序

先用 `BitTrace` 确定失败发生在“选线路／探测 → CAS ticket → WebVPN 设备校验与登录 → 服务回调 → 模块预热 → 业务取数 → 解析 → 写入”的哪一步，再修改对应实现。只记录脱敏后的阶段、状态码、host/path、响应类型和 ticket 是否存在，不记录密码、Cookie、完整查询参数、短信或订阅 URL。

对 200 响应先识别是业务数据、登录页还是错误页；对 Retrofit 非 2xx 同时检查 `errorBody()`，不要把 `body()==null` 当成功。判断线路问题时先看域名能否解析：校园网解析不到 WebVPN，公网解析不到 jwms 和乐学。用真实且脱敏的结构补测试，再在真实校园网／移动数据下验证；历史验收范围见[决策记录](decisions.md#参考项目与验证边界)。
