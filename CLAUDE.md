# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 构建与打包

```bash
# 完整构建：编译 APK → apktool 反编译 → 提取 smali → 重新打包为 JAR
build.bat              # gradlew assembleRelease → jar/genJar.bat

# 构建不同 JAR 变体（需先完成 gradle assembleRelease）
jar/genJar.bat         # custom_spider.jar（含 Go 代理二进制可执行文件）
jar/genJar_so_only.bat # custom_spider.jar（仅含 .so，不含二进制可执行文件）
jar/danmu.bat          # danmu.jar（不含任何原生资源）
jar/proxy.bat          # proxy.jar（仅代理相关）
```

**构建流水线**：`gradlew assembleRelease` 产出 APK → apktool 反编译 APK → 将 `spider`、`js`、`net`、`slf4j` 包的 smali class 注入 `jar/spider.jar/` → apktool 重新打包为独立 JAR。原生 Go 代理资源（.so 或二进制可执行文件）在此过程中被打包进 JAR 的 `assets/` 目录。

工程名 `CatVodSpider`，namespace `com.github.catvod`，applicationId `com.github.catvod.demo`。Java 8，minSdk 21，targetSdk 36。AGP 8.12.0，OkHttp 5.3.2。

## 总体架构

这是一个 **TVBox (CatVod) spider 插件**，提供两大核心能力：(1) **弹幕系统**——对接外部弹幕 API，实现弹幕搜索、自动匹配与推送；(2) **视频代理加速**——在设备本地启动 HTTP 代理服务器，以多线程分块下载方式加速视频流。

### Spider 框架 (`com.github.catvod.crawler`)

`Spider` 是抽象基类，定义了 TVBox API 协议。所有内容源（spider）均继承它：
- `init(Context, String ext)` —— 初始化，ext 为 JSON 扩展配置
- `homeContent(boolean filter)` —— 首页数据
- `categoryContent(tid, pg, filter, extend)` —— 分类列表
- `detailContent(List<String> ids)` —— 媒资详情
- `searchContent(key, quick)` —— 搜索
- `playerContent(flag, id, vipFlags)` —— 播放地址
- `proxy(Map)` / `action(String)` / `liveContent(String)` —— 可选扩展

### 弹幕系统（核心功能）

```
DanmakuSpider (extends Spider)      ← TVBox 入口，站点配置，日志缓冲
├── DanmakuScanner                  ← Timer 轮询 Hook 播放器 UI，检测视频切换，
│                                     用正则提取剧集元数据（剧名、集数、季、年份），
│                                     触发自动/手动搜索与推送，注入"Leo弹幕"按钮
├── DanmakuManager                  ← 状态追踪：上次弹幕URL、集数ID、剧集缓存
│                                     (ConcurrentHashMap)、自动搜索标记
├── LeoDanmakuService               ← 搜索外部弹幕 API、推送弹幕数据到播放器
├── DanmakuConfigManager             ← SharedPreferences 持久化 DanmakuConfig
├── DanmakuConfig                    ← 配置 POJO：API 地址、交互样式、自动推送、代理类型等
├── DanmakuUIHelper                 ← 设置对话框（配置、日志、缓存管理、时间偏移）
├── DanmakuUtils                    ← 标题提取、日期/分段正则匹配、时间偏移处理
└── entity/DanmakuItem, Media       ← 数据模型
```

**启动流程**: `Init.init()` → `DanmakuSpider.doInitWork()`（加载 SharedPreferences 配置，启动 9810 端口 WebServer）→ `DanmakuScanner.startHookMonitor()`（启动 1 秒周期 Timer，检测播放器 Activity 是否可见，向播放器 UI 注入"Leo弹幕"按钮，轮询 `/media` 接口获取播放状态，提取剧集信息，触发弹幕搜索/推送）。

**自动推送逻辑**：开启自动推送且检测到新剧集时，DanmakuScanner 搜索弹幕 API → 安排延迟推送（等待视频真正开始播放）→ 调用 `LeoDanmakuService.pushDanmakuDirect()` 推送 → 异步校验弹幕内容是否有效 → 失败则回退到备选弹幕源。

### 内容爬虫 (`com.github.catvod.spider`)

除弹幕外，还实现了多种影视源的 Spider：`AList`、`Bili`、`Jable`、`Jianpian`、`Kanqiu`、`Local`、`Market`、`MQiTV`、`PTT`、`Samba`、`WebDAV`、`XtreamCode`、`YHDM`、`Push`。均遵循 Spider API 协议。

---

## 代理系统深度分析

代理系统是本工程除弹幕外的第二大核心模块，负责为 TVBox 视频播放提供本地 HTTP 代理加速。整个代理架构采用 **"固定前端 + 可切换后端"** 的设计，对外暴露固定端口 5575，对内可灵活切换 Java 或 Go 两种后端实现。

### 整体架构拓扑

```
TVBox 播放器请求
       │
       ▼
┌──────────────────────────┐
│  ProxyRelayServer :5575  │  ← 固定前门（唯一对外端口）
│  (TCP 透明转发)          │
└──────────┬───────────────┘
           │ TargetResolver 动态解析后端端口
           ▼
   ┌───────┴───────┐
   │               │
   ▼               ▼
┌──────────┐  ┌──────────────┐
│ GoProxy  │  │ JavaProxy    │
│ :5576    │  │ Server :5577 │
│ (JNI/.so │  │ (纯Java TCP) │
│  或二进制)│  │              │
└──────────┘  └──────────────┘
```

**端口分配**：

| 端口 | 组件 | 用途 |
|------|------|------|
| 5575 | ProxyRelayServer | 固定前端入口，外部唯一可见端口 |
| 5576 | GoProxy | Go 代理默认后端端口 |
| 5577 | JavaProxyServer | Java 代理后端端口 |

### Java 代理（JavaProxyServer）

**实现原理**：纯 Java 实现，基于 `ServerSocket` 的 HTTP 代理。核心逻辑是接收客户端的 Range 请求，向目标视频服务器发起多线程分块并发下载，再将数据流式写回客户端。

**请求处理流程**（[JavaProxyServer.java:86](app/src/main/java/com/github/catvod/spider/JavaProxyServer.java#L86)）：

1. 主线程 acceptLoop 接受客户端连接，交给 `downloadExecutor`（CachedThreadPool）处理
2. 解析 HTTP 请求，提取 `url`、`thread`（线程数）、`chunkSize`（块大小）参数
3. **首块下载**（[line 171](app/src/main/java/com/github/catvod/spider/JavaProxyServer.java#L171)）：先下载第一块，根据 `Content-Range` 头获取文件总大小
4. **分批并发下载**（[line 247](app/src/main/java/com/github/catvod/spider/JavaProxyServer.java#L247)）：按 `chunkSize * thread` 为一批，每批内 N 个线程各下载一块，`CountDownLatch` 等待整批完成后写入客户端
5. 每块最多重试 3 次，支持断点续传（Range 请求）

**关键实现细节**：
- 使用自己构建的 OkHttpClient（TLS 信任所有证书，超时 connect=10s, read=60s, write=0）
- 转发客户端的 User-Agent、Cookie、Referer 头
- 支持 206 Partial Content 状态码的完整处理
- 有详细的进度日志（每批次耗时、传输速度、总进度百分比）

### Go 代理（GoProxyManager + GoProxyLibrary）

**两种加载模式**，按优先级自动选择：

#### 模式一：JNI/SO 方式（优先）

1. **SO 文件加载**（[GoProxyLibrary.java:35](app/src/main/java/com/github/catvod/spider/GoProxyLibrary.java#L35)）：从 APK assets 中提取 `arm64-v8a/libgoproxy.so` 或 `armeabi-v7a/libgoproxy.so`，写入 app 私有目录（带 ClassLoader 标识的独立文件名），`System.load()` 加载
2. **JNI 接口**：
   ```java
   public static native int startProxy(int port);   // 返回0=成功, 1=已运行, 2=端口不可用
   public static native int stopProxy();             // 停止代理
   public static native int isProxyRunning();        // 查询运行状态
   public static native String getLastError();       // 获取错误信息
   ```
3. **端口扫描**（[GoProxyManager.java:117](app/src/main/java/com/github/catvod/spider/GoProxyManager.java#L117)）：从候选端口列表（当前端口 > 5576 > 5576+1...20）逐个尝试，先做健康检查探测是否已有运行中的实例，再调用 `GoProxyLibrary.startProxy(port)` 启动

#### 模式二：二进制可执行文件方式（降级方案）

1. 从 assets 提取 `goProxy-arm64` 或 `goProxy-arm` 到 cache 目录
2. 通过 `Runtime.exec("/system/bin/sh")` 执行：`chmod 777` → `kill` 旧进程 → `nohup <binary> &`
3. 等待 3 秒后做 HTTP 健康检查确认启动成功

### 代理管理层（ProxyManager）

**初始化流程**（[ProxyManager.java:53](app/src/main/java/com/github/catvod/spider/ProxyManager.java#L53)）：

```
Init.init(context)
  └── ProxyManager.initialize(context)
        ├── 1. ensureRelayServer()          // 启动 5575 中继前端
        ├── 2. 读取 DanmakuConfig 中的 proxyType 偏好
        ├── 3. 如果偏好 Go 且有 SO 资源 → startGoProxySync()
        │     ├── Go 成功 → 设置 activeProxyType = GO
        │     └── Go 失败 → killGoProxy → startJavaProxy()
        └── 4. 否则 → startJavaProxy()
```

**健康检查与自动恢复**（[ProxyManager.java:320](app/src/main/java/com/github/catvod/spider/ProxyManager.java#L320)）：
- 每 5 秒对当前活跃后端的 `/health` 接口做 HTTP 检查
- 连续失败超过 10 秒 → 自动触发重启，Go 重启失败则降级到 Java
- 健康检查响应格式：`"ok"`（纯文本）或 `{"status":"healthy","type":"go"/"java","port":xxxx}`（JSON）

**Go/Java 切换**（[ProxyManager.java:105](app/src/main/java/com/github/catvod/spider/ProxyManager.java#L105)）：
- 切换时有互斥锁保护（`isSwitching` AtomicBoolean）
- 切换步骤：停健康检查 → 停旧后端 → 启新后端 → 等端口释放 → 重新健康检查 → 保存偏好
- 切换触发方式：用户在 Leo 弹幕设置 UI 中点击按钮

### 中继服务器（ProxyRelayServer）

**为什么需要中继**：[ProxyRelayServer.java](app/src/main/java/com/github/catvod/spider/ProxyRelayServer.java) 解决了一个关键问题——TVBox 的代理配置指向固定地址 `127.0.0.1:5575`，不能随意改变。中继服务器绑定 5575，通过 `TargetResolver` 动态查询当前活跃后端端口，透明转发 TCP 流量。这样切换 Go/Java 后端对 TVBox 完全透明。

**转发机制**：简单的双向字节流转发（`pipeQuietly`），使用两个线程分别处理 client→backend 和 backend→client 的数据流。

### 代理如何被调用

TVBox 框架内部通过 `Proxy.getUrl()` 获取代理地址，然后配置播放器通过该代理请求视频。具体的代理 URL 格式由宿主 App（CatVod）的 `com.github.catvod.Proxy` 类生成，本插件通过反射调用之：

```java
// Proxy.java:43
public static String getUrl(boolean local) {
    // 反射调用宿主 App 的 com.github.catvod.Proxy.getUrl(local)
    // 降级为 http://127.0.0.1:{port}/proxy
}
```

如果反射失败，会扫描 8964-9999 端口寻找可用的代理端口。

### 代理系统移植指南

**可以移植**。整个代理系统与弹幕系统、Spider 框架解耦良好，核心依赖少。

**需要移植的核心文件**：

| 文件 | 用途 | 外部依赖 |
|------|------|----------|
| `ProxyRelayServer.java` | 固定前端中继（必需） | 仅 JDK Socket API |
| `JavaProxyServer.java` | Java 后端代理（必需） | OkHttp3（可替换为 HttpURLConnection） |
| `ProxyManager.java` | 代理总控（必需，可精简） | OkHttp3（仅健康检查用到） |
| `GoProxyManager.java` | Go 代理管理（可选） | 仅 JDK + .so 或二进制文件 |
| `GoProxyLibrary.java` | JNI 桥接（可选） | 仅 JDK + libgoproxy.so |
| `DanmakuConfig.java` | 持久化 proxyType 偏好（可精简） | Gson |

**不依赖本项目的部分**：
- `JavaProxyServer` 和 `ProxyRelayServer` 完全不依赖 Spider、Danmaku 等模块
- `ProxyManager` 中调用了 `DanmakuConfigManager` 保存偏好，移植时可以改为你自己的 SharedPreferences 或其他存储方式
- 健康检查使用 `OkHttp.string(url, timeout)` 做 HTTP GET，可以替换为 `HttpURLConnection`

**Go 代理的依赖**：
- 需要编译好的 `libgoproxy.so`（arm64-v8a / armeabi-v7a）或 Go 二进制可执行文件
- Go 代理源码位于独立仓库 [GoProxyAndroid](https://github.com/Silent1566/GoProxyAndroid)
- 如果不需要 Go 代理，可以完全去除 `GoProxyManager` 和 `GoProxyLibrary`，仅使用 Java 代理

**最小移植步骤**（仅 Java 代理）：
1. 复制 `JavaProxyServer.java`、`ProxyRelayServer.java` 到新工程
2. 创建简化版 `ProxyManager`（去除 Go 相关代码，仅保留 Java 启动/停止/健康检查）
3. 在 Application.onCreate() 或初始化入口调用 `ProxyManager.initialize(context)`
4. 端口可自定义，只需保持 relay 和 backend 端口一致即可
5. OkHttp3 依赖可替换为 HttpURLConnection（修改 `buildOkHttpClient()` 和 `downloadChunk()` 方法）

## 调试

```bash
# 查看 Leo 弹幕 + 代理日志
adb -s 127.0.0.1:5555 logcat -v time | findstr "TV-SpiderDebug"

# 查看特定 app 进程日志
adb -s 127.0.0.1:5555 logcat -v time --pid=$(adb shell pidof -s com.github.catvod)
```

日志标签为 `TV-SpiderDebug`（通过 `SpiderDebug.log()` 输出）。弹幕系统和代理系统各自维护 1000 行的环形日志缓冲区，可在 Leo 设置 UI 的"查看日志"中查看。

## 关键依赖

- OkHttp 5.3.2 —— HTTP 客户端
- Gson 2.13.2 —— JSON 序列化
- QuickJS wrapper 3.2.3 —— JS 引擎，用于运行时执行 JS 爬虫脚本
- NanoHTTPD 2.3.1 —— 嵌入式 HTTP 服务（WebServer 端口 9810，提供弹幕搜索 HTML 页面）
- Jsoup 1.21.2 —— HTML 解析
- Sardine (WebDAV)、SMBJ (SMB/CIFS) —— 文件协议爬虫
