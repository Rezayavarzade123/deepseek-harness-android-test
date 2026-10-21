# `dsh` 移动客户端（Android APK）

[English](README.md) | 中文

面向 [DeepSeek Harness](../../README.zh.md) Web UI 的原生 Android 外壳，而且把 harness 本身也装了进去。APK 内置 Node 运行时、harness 安装内容，以及 Node 依赖的库，因此一台新手机可以直接从桌面图标运行 agent——不需要 Termux、不需要包管理器、也不需要 root。当你希望工作区放在别处时，它依然可以连接电脑上的 `dsh web` 服务器。

## 一切都在 APK 里

`dsh` 是 Node.js 应用：它拥有 agent 循环、工具进程、文件系统和模型凭据。Android 没有系统自带的 Node，npm 上的打包运行时要么是 Node 18（低于 harness 的下限），要么缺少 harness 原生插件的 bionic 构建。因此本项目把它们全部内置。

```
 phone (this APK)                                    optional: a computer
┌──────────────────────────────────────┐   HTTP    ┌────────────────────────┐
│ WebView ── dsh Web UI                │ ────────▶ │ dsh web --host <lan-ip>│
│ native setup / runtime / error screens│          │ agent · tools · files  │
│                                      │           └────────────────────────┘
│ 127.0.0.1:308x  ◀── dsh web          │
│   ▲                                  │
│   └── Node 24 (bundled) + node_modules│
└──────────────────────────────────────┘
```

| 内置内容 | 大小 | 说明 |
|---|---|---|
| Android arm64 版 Node 24.18.0 | 43 MiB | Termux 的 bionic 构建，ELF runpath 已改写为 `$ORIGIN` |
| `@deepseek-ai/dsh` 0.2.1-rc.2 的 `node_modules` | 52 MiB（zip） | 已剔除手机无法运行的原生/WASM 包 |
| 共享库 | 44 MiB | ICU、OpenSSL、SQLite、c-ares、libc++ |
| 根证书 | 0.2 MiB | 供出站 HTTPS 使用的 `SSL_CERT_FILE` |

下载体积：**84 MiB**。首次启动时应用会向私有存储解压约 300 MiB 内容。

## 在手机上运行

打开应用。首次启动会显示 **这台手机** 卡片；点击 **在这台手机上启动 harness** 后会解压运行时（显示进度，失败时显示进程日志），在回环地址上启动 `dsh web`，并加载其 Web UI。之后启动会复用已解压的副本，直接进入界面；被系统在后台回收的运行时会在回到前台时重新启动。

harness 的行为与本地安装一致，而不是远程客户端：

* 工作区、会话历史和凭据都保存在应用的私有目录（`/data/data/dev.dsh.harness/files/runtime`）。卸载应用会删除它们。
* 工具以应用自身的 UID 在手机上执行。边界就是 Android 的应用沙箱：`dsh` 的 Landlock 启动器只支持 Linux，因此应用设置 `DSH_PERMISSION_MODE=danger-full-access`，agent 可以访问应用能访问的一切。
* 服务器只绑定 `127.0.0.1`，端口从 3080 起取空闲值，网络中的其他设备无法访问。
* 工具命令交给 Android 的 `mksh` 执行：harness 会调用 `bash -c`，`PATH` 上的垫片把它转给 `/system/bin/sh`。POSIX shell 语法可用，但 bash 独有语法（`shopt`、进程替换）和 `git` 不可用，因为两者都未内置。
* 模型提供商照常在 Web UI 中配置；API 密钥只在向提供商发起请求时离开设备。

菜单项：**重启 harness**、**运行时日志**（最近 400 行进程输出）、**在这台手机上运行 harness**、**连接到电脑**。

由于运行时需要从应用存储中执行，清单把 targetSdk 固定为 Android 9（API 28）——这也是 Termux 长期所处的上限，因为 Android 10 起，更新的 target 不允许从可写的应用数据目录 `exec`。在 Android 13 及以上，安装器可能提示该应用是为旧版系统构建的；这是预期行为。运行时的段对齐为 16 KB 页，因此也能在采用 16 KB 页的新设备上运行。

## 下载与安装

预编译的 APK 已收录在 [`releases/dsh-mobile-0.2.1-rc.2-android.apk`](releases/dsh-mobile-0.2.1-rc.2-android.apk)，可直接从仓库页面下载，无需自行构建。

```sh
adb install -r releases/dsh-mobile-0.2.1-rc.2-android.apk
```

也可以侧载：把 APK 复制到设备上直接打开（按提示允许文件管理器安装应用）。要求：**arm64** 设备、Android 8.0（API 26）或更新版本，安装期间约需 400 MiB 可用空间。该 APK 使用构建时生成的开发密钥签名；它不是 Play Store 制品。

## 连接到电脑

在应当运行 agent 的电脑上：

```sh
npx @deepseek-ai/dsh web --host 192.168.1.20 --no-open
```

* 把 `192.168.1.20` 换成该机器的局域网地址（`hostname -I`、`ipconfig`，或 Wi-Fi 设置页面）。
* `--host` 只接受一个具体地址——`dsh` 按设计拒绝通配绑定。
* 命令会打印形如 `http://192.168.1.20:3080/?token=8f3…` 的 URL。保持该进程运行。

在应用中：

1. 点击 **连接到电脑**（或同名菜单项）。
2. 把打印出的 URL 粘贴到 **服务器地址**（应用会自动拆出 token），或者输入 `192.168.1.20:3080` 并单独粘贴 token。
3. 点击 **连接**。启动 token 会换成会话 cookie，此后应用无需 token 即可重连。
4. 如果不知道地址，点击 **扫描我的网络**：应用会探测自身地址所在 /24 网段的 3080 端口，并列出所有响应的主机。

选择电脑会停止内置运行时，手机上不会同时保留第二个 agent。每次重启 `dsh` 都会打印新 token；旧 token 被拒绝时，应用会显示 **登录 token 已失效** 并提供接收新 URL 的操作。

## 应用做了什么

| 功能 | 说明 |
|---|---|
| 内置运行时 | 首次启动解压内置的 Node + harness，在回环地址启动 `dsh web`，被系统回收后自动重启，并展示进程日志 |
| 会话 | 使用 WebView cookie，Web UI 的表现与浏览器完全一致；仅在收到 401 后才重放 token |
| 电脑宿主 | 任意可达的 `dsh web` 服务器，支持局域网扫描与最多八条记住的服务器（长按删除） |
| 局域网发现 | 同时探测回环地址与本机 /24 网段的 3080 端口，结果按数值排序 |
| 上传 | 为 `<input type=file>` 提供原生文件选择器，支持多选与相机/相册来源 |
| 下载 | 使用 Android `DownloadManager` 并携带 WebView cookie，保存到 `Downloads/` |
| 语音输入 | 仅当 Web UI 请求麦克风时才申请 `RECORD_AUDIO` |
| 导航 | 同服务器链接留在应用内，其他链接交给浏览器；返回键先回退 WebView 历史，再双击退出 |
| 错误处理 | 超时、拒绝连接、DNS、渲染进程崩溃、token 被拒、运行时启动失败等界面均提供恢复操作 |

原生代码刻意保持精简：产品是 harness 的 Web UI，外壳只补上 WebView 缺少的能力，以及让它自给自足的运行时。

## 构建

不需要 Gradle，也不需要安装 Android SDK。脚本从 npm 分发的载体获取 Google 官方二进制（aapt2、`android.jar`、D8、apksigner），并用 ECJ 编译，`dist/` 中的 APK 正是这样产出的。

依赖：Node.js、JDK 17+、`zip`，以及 Python 3（用于改写 ELF runpath）。

```sh
cd apps/android
scripts/fetch-toolchain.sh     # once: aapt2, android.jar, d8, apksigner, ECJ
scripts/prepare-runtime.sh     # Node tarball + npm install + prune + patch, ~10 min
JAVA=/path/to/java scripts/build-apk.sh
```

`prepare-runtime.sh` 会把所有内容放到 `app/src/main/assets/runtime` 下：下载 Node、从 npm 安装 `@deepseek-ai/dsh`、剔除手机无法运行的包（WASM office 引擎、语音模型、`sharp`、`node-pty`、遥测 SDK）以及其他系统的原生构建、用等价的 JavaScript 实现替换 harness 需要的两个原生插件、把结果打包成不压缩的 zip 资源，并把每个 ELF 的 runpath 改写为 `$ORIGIN`，让动态链接器在应用被安装到的任何位置都能找到内置库。

输出：`apps/android/dist/dsh-mobile-0.2.1-rc.2-android.apk`，构建结束时自动签名并校验。把它以同名复制到 `releases/` 即可发布新的下载版本。

环境变量覆盖：

| 变量 | 默认值 | 用途 |
|---|---|---|
| `JAVA` | `PATH` 中的 `java` | 运行 ECJ、D8 和 apksigner 的 JVM |
| `DSH_ANDROID_TOOLS` | `apps/android/.tools` | 工具链与签名密钥目录 |
| `DSH_VERSION` | `0.2.1-rc.2` | 装入 payload 的 harness 版本 |
| `NODE_DISTRO_URL` | 固定的 GitHub tarball | 要内置的 Android Node 构建 |
| `WORK` | `apps/android/.runtime-build` | payload 暂存目录 |
| `VERSION_CODE` / `VERSION_NAME` | `23` / `0.2.1-rc.2-android` | APK 版本信息 |
| `DSH_KEYSTORE` | `.tools/signing/dsh-mobile.p12` | 签名 keystore；首次构建时生成 |
| `DSH_STORE_PASS` | `android` | keystore 与密钥口令 |
| `DSH_KEY_ALIAS` | `dsh-mobile` | 签名密钥别名 |

若要长期保留签名，请自行传入 `DSH_KEYSTORE`/`DSH_STORE_PASS`/`DSH_KEY_ALIAS`：签名不同时 Android 会拒绝覆盖安装。

### 测试

`ServerTarget`（地址/token 解析器）只用到 `java.net`，因此可以在 JVM 上运行；打包规则则直接针对已暂存的资源做检查：

```sh
cd apps/android
tests/run-tests.sh
```

解析器测试覆盖裸主机、默认端口、带 token 的启动 URL、挂载前缀、IPv6 字面量、百分号编码和被拒绝的协议。打包测试则检查内置 ELF 是否为 arm64、是否还有 runpath 指向 Termux、payload 是否包含打过补丁的 harness 且不含被剔除的原生包，以及清单是否保留了运行时所需的 target SDK。

## 目录结构

```
apps/android
├── app/src/main
│   ├── AndroidManifest.xml            targetSdk 28 so the bundled ELF can exec
│   ├── java/dev/dsh/harness
│   │   ├── MainActivity.java          WebView, setup/runtime/error screens, uploads, downloads
│   │   ├── EmbeddedHarness.java       unpack, spawn, watch, and stop the bundled runtime
│   │   ├── ServerTarget.java          address + launch-token parsing
│   │   ├── ServerStore.java           recent servers in SharedPreferences
│   │   └── LanScanner.java            /24 TCP probe for local dsh servers
│   ├── assets/runtime                 Node, shared libraries, payload.zip (staged, gitignored)
│   └── res                            layouts, dark theme, adaptive icon
├── scripts/fetch-toolchain.sh         aapt2 + platform jars via npm
├── scripts/prepare-runtime.sh         stage Node + harness into assets/runtime
├── scripts/patch-payload.mjs          prune packages, patch the two native addons
├── scripts/patch-elf-rpath.py         rewrite DT_RUNPATH to $ORIGIN
├── scripts/build-apk.sh               aapt2 → ECJ → D8 → apksigner
├── tests/run-tests.sh                 JVM parser tests + packaging checks
├── releases/                          published APK(s), downloadable from the repo page
└── dist/                              build output (gitignored)
```

## 安全

* 内置运行时只监听 `127.0.0.1`，其启动 token 不会离开设备。**连接到电脑** 路径会把 token 发到局域网，而打印出的 URL 是一份 **进程凭据**：拿到它的人可以获得完整的 agent 权限，包括在该主机上执行工具。
* 内置 agent 以 `DSH_PERMISSION_MODE=danger-full-access` 运行，因为 Landlock 启动器没有 Android 构建。工具调用的边界只有 Android 的应用沙箱和用户授予的权限；请把手机存储视为 agent 可以访问。
* 在 Web UI 中填写的凭据（提供商密钥、会话）保存在应用私有存储中，未加密落盘，卸载应用时一并删除。
* 清单允许明文 HTTP，因为 `dsh` 在局域网绑定时默认使用明文 HTTP。在可信网络上没有问题；其他场景请在服务器上终止 TLS（`--tls-cert`/`--tls-key`），或通过 Tailscale 之类的 VPN 访问，再用 `https://` URL 连接。
* 永远不要把 `dsh` 直接暴露到互联网。它的远程访问方案是在回环监听器前面放一个终止 TLS 的反向代理——参见 [public deployments](../../docs/user/guide/public-deployments.zh.md)。

## 限制

* **仅支持 arm64 与 Android 8.0 及以上。** 内置 Node 是 aarch64 bionic 构建；32 位与 x86 设备请改用电脑连接方式。
* **下载 84 MiB，占用约 400 MiB。** 运行时在首次启动时解压到应用存储，不存在系统级共享副本。
* **没有 bash、git 与 PTY。** 工具命令运行在 Android 的 `mksh` 下，因此 bash 独有语法和依赖 `git` 的功能（worktree、部分评审流程）不可用。终端面板需要 `node-pty`，它没有 Android 构建，因此保持不可用。
* **没有 Landlock 沙箱。** 缺少该启动器时，agent 的文件访问只能由应用沙箱约束，而不是由策略约束，因此应用以完全访问模式运行它。
* **Blob 下载**（页面创建的 `blob:` URL）无法交给 `DownloadManager`；只有真正的 HTTP 下载会被捕获。
* **没有后台推送。** Android 回收应用时 harness 进程会被终止；重新打开应用后会话继续。
* **WebView 版本很重要。** Web UI 是现代的 React 应用；在较旧设备上请保持 Android System WebView（Play Store）为最新版本。
* **每次安装只对应一个签名密钥。** 用不同 keystore 重新构建后需要先卸载旧应用。
