# `dsh` 移动客户端（Android APK）

[English](README.md) | 中文

面向 [DeepSeek Harness](../../README.zh.md) Web UI 的原生 Android 外壳。它通过 HTTP(S) 登录 `dsh web` 服务器——可以在本机通过 Termux 运行，也可以在局域网的另一台电脑上运行——保留浏览器会话，并补上手机需要的原生能力：带本机与局域网发现的连接界面、无法连接/登录过期界面、文件上传、下载，以及语音输入的麦克风权限。

## 为什么把 harness 留在别处运行

`dsh` 是 Node.js 应用：它拥有 agent 循环、工具进程、文件系统和模型凭据。Android 没有可以随应用分发的 Node 运行时（除非自行内置一份），因此本项目做了退而求其次的事——让手机成为和桌面浏览器完全相同的客户端，而宿主继续运行在存放工作区的那台机器上。

```
 phone (this APK)                         computer (or server)
┌───────────────────────┐   HTTP + WS   ┌────────────────────────────┐
│ WebView  ── dsh Web UI │ ───────────▶ │ dsh web --host <lan-ip>    │
│ native setup / errors  │              │ agent · tools · workspace  │
└───────────────────────┘               └────────────────────────────┘
```

## 在手机上运行

Android 也能自己承载 `dsh`。APK 不内置运行时，因为 harness 需要 Node.js `^22.19 || >=24`，以及针对目标平台构建的原生模块：npm 上可用的打包版 Android 运行时（nodejs-mobile）是 Node 18，而 harness 的原生插件没有 bionic 预编译产物。Termux 同时提供这两者。

```sh
pkg install nodejs-lts
npx @deepseek-ai/dsh web --no-open
```

然后在应用中：

1. 点击 **在手机上运行** 卡片里的 **重新检查**。应用会探测 `127.0.0.1:3080` 并填入地址。
2. 粘贴 dsh 在 Termux 中打印的 URL 里的 token。
3. 使用期间让 Termux 保持在前台，并持有唤醒锁（`termux-wake-lock`）：Android 12 及以上会杀掉长时间运行的后台进程，除非同时把 Termux 排除在电池优化之外。

回环地址上的服务器属于安全的浏览器上下文，因此剪贴板、加密和工作线程相关 API 的表现与 `https://` 一致，不同于明文 HTTP 的局域网地址。当手机只应作为瘦客户端时，下文的 **连接到电脑** 就是替代方案。

## 安装

```sh
adb install -r apps/android/dist/dsh-mobile-0.2.1-rc.2-android.apk
```

也可以侧载：把 APK 复制到设备上直接打开（按提示允许文件管理器安装应用）。该 APK 面向 Android 8.0（API 26），使用构建时生成的开发密钥签名；它不是 Play Store 制品。

## 连接到电脑

在应当运行 agent 的电脑上执行：

```sh
npx @deepseek-ai/dsh web --host 192.168.1.20 --no-open
```

* 把 `192.168.1.20` 换成该机器的局域网地址（`hostname -I`、`ipconfig`，或 Wi-Fi 设置页）。
* `--host` 只接受一个具体地址——`dsh` 出于安全考虑拒绝通配绑定。
* 命令会打印形如 `http://192.168.1.20:3080/?token=8f3…` 的 URL。保持该进程运行。

在应用中：

1. 把打印出的 URL 粘贴到 **服务器地址**（应用会自动拆出 token），或者输入 `192.168.1.20:3080` 并单独粘贴 token。
2. 点击 **连接**。启动 token 会被兑换成会话 cookie，此后重连不再需要 token。
3. 如果不知道地址，点击 **扫描我的网络**：应用会在自身地址所在 /24 网段探测 3080 端口，并列出所有应答的主机。地址栏中填写的端口会被采用。

每次 `dsh` 重启都会打印新 token。旧 token 被拒绝时，应用会显示 **登录 token 被拒绝**，并提供填入新 URL 的入口。

## 应用做了什么

| 功能 | 说明 |
|---|---|
| 会话 | 使用 WebView cookie，Web UI 的表现与浏览器完全一致；仅在收到 401 后才重放 token |
| 本机承载 | “在手机上运行”会探测 `127.0.0.1:3080`，复用已保存的 token，并一键连接 |
| 局域网发现 | 并行 TCP 探测回环地址与本地 /24 网段的 3080 端口，结果按数值排序 |
| 最近服务器 | 最多八条记录，附带各自的 token；长按可删除 |
| 上传 | 为 `<input type=file>` 提供原生文件选择器，支持多选与相机/相册来源 |
| 下载 | 使用 Android `DownloadManager` 并附带 WebView cookie，保存到 `Downloads/` |
| 语音输入 | 仅当 Web UI 请求麦克风时才申请 `RECORD_AUDIO` |
| 导航 | 同一服务器的链接留在应用内，其他链接交给浏览器；返回键先走 WebView 历史，再双击退出 |
| 错误 | 超时、拒绝连接、DNS、渲染进程崩溃和 token 失效界面，均带恢复操作 |

原生插桩刻意保持轻薄：产品是 harness 的 Web UI，外壳只补齐 WebView 缺失的部分。

## 构建

无需 Gradle，也无需安装 Android SDK。脚本会从 npm 载体获取官方 Google 二进制（aapt2、`android.jar`、D8、apksigner），并用 ECJ 编译；`dist/` 中的 APK 正是这样产出的。

依赖：Node.js（用于获取工具）和 JDK 17+。

```sh
cd apps/android
scripts/fetch-toolchain.sh            # once: aapt2, android.jar, d8, apksigner, ECJ
JAVA=/path/to/java scripts/build-apk.sh
```

输出：`apps/android/dist/dsh-mobile-0.2.1-rc.2-android.apk`，构建结束时已完成签名与校验。

环境变量覆盖：

| 变量 | 默认值 | 用途 |
|---|---|---|
| `JAVA` | `PATH` 中的 `java` | 运行 ECJ、D8 和 apksigner 的 JVM |
| `DSH_ANDROID_TOOLS` | `apps/android/.tools` | 工具链与签名密钥目录 |
| `VERSION_CODE` / `VERSION_NAME` | `21` / `0.2.1-rc.2-android` | APK 版本元数据 |
| `DSH_KEYSTORE` | `.tools/signing/dsh-mobile.p12` | 签名密钥库；首次构建时创建 |
| `DSH_STORE_PASS` | `android` | 密钥库与密钥口令 |
| `DSH_KEY_ALIAS` | `dsh-mobile` | 签名密钥别名 |

如果要长期保留某个密钥，请自行传入 `DSH_KEYSTORE`/`DSH_STORE_PASS`/`DSH_KEY_ALIAS`：Android 拒绝更新签名已变化的已安装应用。

### 测试

`ServerTarget`——地址/token 解析器——只依赖 `java.net`，因此可以直接在 JVM 上运行：

```sh
cd apps/android
tests/run-tests.sh
```

测试覆盖裸主机名、默认端口、带 token 的启动 URL、挂载前缀、IPv6 字面量、百分号编码，以及被拒绝的协议。

## 目录结构

```
apps/android
├── app/src/main
│   ├── AndroidManifest.xml            cleartext HTTP is intentional (LAN servers)
│   ├── java/dev/dsh/harness
│   │   ├── MainActivity.java          WebView, setup/error screens, uploads, downloads
│   │   ├── ServerTarget.java          address + launch-token parsing
│   │   ├── ServerStore.java           recent servers in SharedPreferences
│   │   └── LanScanner.java            /24 TCP probe for local dsh servers
│   └── res                            layouts, dark theme, adaptive icon
├── scripts/fetch-toolchain.sh         aapt2 + platform jars via npm
├── scripts/build-apk.sh               aapt2 → ECJ → D8 → apksigner
├── tests/run-tests.sh         JVM tests for the parser
└── dist/                              build output (gitignored)
```

## 安全

* 打印出的 URL 是一份 **进程凭据**：拿到它的人就拥有完整的 agent 访问权，包括在宿主机上执行工具。只把它分享给正在配对的设备。
* 应用把基础 URL 和 token 存在私有的 `SharedPreferences` 中（未加密存储）；会话 cookie 位于 WebView cookie 存储中。
* 清单中启用了明文 HTTP，因为 `dsh` 在局域网绑定时默认提供明文 HTTP。受信任的网络中这没有问题；其他场合请在服务器上终止 TLS（`--tls-cert`/`--tls-key`），或通过 Tailscale 之类的 VPN 访问，再用 `https://` URL 连接。
* 绝不要把 `dsh` 直接暴露到互联网。它的远程访问方案是在回环监听器前放置一个终止 TLS 的反向代理——参见[公开部署](../../docs/user/guide/public-deployments.zh.md)。

## 限制

* **APK 不内置 Node 运行时。** harness 本身要么在手机的 Termux 中运行，要么在电脑上运行；本应用是它的客户端。内置 Node 意味着要为 Android 交叉构建运行时以及每一个原生插件。
* **Blob 下载**（页面创建的 `blob:` URL）无法交给 `DownloadManager`，只能捕获真正的 HTTP 下载。
* **没有后台推送。** 会话在宿主上持续运行，但手机只在应用打开时获知新输出。
* **WebView 版本很重要。** Web UI 是现代化的 React 应用；在旧设备上请保持 Android System WebView（Play Store）为最新版本。
* **每次安装只认一个签名密钥。** 换用不同密钥库重新构建时，需要先卸载旧应用。
