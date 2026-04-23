# ScreenStreamHub

> Android 屏幕录制流媒体应用 - 支持 SRT / RTMP / WebSocket 多协议推流

---

## 📋 项目概述

**ScreenStreamHub** 是一款基于 Android 平台的高性能屏幕录制流媒体应用，能够将手机屏幕内容实时推送到远程服务器。项目基于 **StreamPack** 流媒体库构建，实现了 H.264 硬件编码和WebSocket传输支持。

### ✨ 核心特性

| 特性                   | 描述                         | 状态    |
| ---------------------- | ---------------------------- | ------- |
| **多协议支持**   | SRT / RTMP / WebSocket       | ✅ 完整 |
| **H.264 硬编码** | Android MediaCodec 硬件加速  | ✅ 完整 |
| **屏幕录制**     | MediaProjection API 全屏捕获 | ✅ 完整 |
| **音频采集**     | 麦克风 / 系统音频            | ✅ 完整 |
| **实时预览**     | Python 服务器实时显示        | ✅ 完整 |
| **MP4 录制**     | 直接保存为 MP4 格式          | ✅ 完整 |
| **内网穿透**     | cloudflared 公网访问支持     | ✅ 完整 |
| **配置灵活**     | 分辨率、码率、帧率可配置     | ✅ 完整 |

---

## 🏗️ 技术架构

### 整体架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Android 应用层                                  │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐    │
│  │  MainActivity   │    │ SettingsActivity│    │   资源文件      │    │
│  │  (主界面控制)   │    │  (配置管理)     │    │  (布局/配置)    │    │
│  └────────┬────────┘    └────────┬────────┘    └─────────────────┘    │
│           │                      │                                     │
├───────────┼──────────────────────┼────────────────────────────────────┤
│                        业务逻辑层                                      │
│  ┌──────────────────────────────────────────────────────────────┐      │
│  │       DemoMediaProjectionService (屏幕录制服务)               │      │
│  │  ┌────────────────────────────────────────────────────────┐  │      │
│  │  │              SingleStreamer (StreamPack)               │  │      │
│  │  │  ┌───────────┐  ┌───────────┐  ┌─────────────────────┐ │  │      │
│  │  │  │ VideoSrc  │→│ Encoder   │→│   CombinedEndpoint   │ │  │      │
│  │  │  │(屏幕采集)  │  │(H.264硬编)│  │  (端点分发器)       │ │  │      │
│  │  │  └───────────┘  └───────────┘  ├─────────────────────┤ │  │      │
│  │  │                                │ SRT Endpoint        │ │  │      │
│  │  │                                │ RTMP Endpoint       │ │  │      │
│  │  │                                │ WebSocket Endpoint  │ │  │      │
│  │  │                                └─────────────────────┘ │  │      │
│  │  └────────────────────────────────────────────────────────┘  │      │
│  └──────────────────────────────────────────────────────────────┘      │
├─────────────────────────────────────────────────────────────────────────┤
│                        协议层                                          │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐         │
│  │   SRT    │    │   RTMP   │    │ WebSocket│    │  HTTP    │         │
│  └────┬─────┘    └────┬─────┘    └────┬─────┘    └────┬─────┘         │
│       │               │               │               │                │
└───────┼───────────────┼───────────────┼───────────────┼────────────────┘
        │               │               │               │
        ▼               ▼               ▼               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        Python 服务器端                              │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  websocket_h264_live.py  (实时显示)                        │   │
│  │  websocket_h264_mp4.py   (MP4录制)                         │   │
│  │  websocket_server.py     (基础接收)                         │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 核心模块说明

| 模块               | 职责           | 关键文件                                                 |
| ------------------ | -------------- | -------------------------------------------------------- |
| **端点模块** | 协议抽象与分发 | `WebSocketEndpoint.kt`, `CombinedEndpointFactory.kt` |
| **流服务**   | 屏幕录制与编码 | `DemoMediaProjectionService.kt`                        |
| **配置管理** | 参数持久化     | `Configuration.kt`, `root_preferences.xml`           |
| **界面控制** | 用户交互       | `MainActivity.kt`, `SettingsActivity.kt`             |

---

## 📁 项目结构

```
mcbtm/                                    # 项目根目录
├── app/                                  # Android 应用模块
│   ├── src/main/
│   │   ├── java/com/mcbcc/mcbtm/
│   │   │   ├── endpoints/                # 端点实现（核心）
│   │   │   │   ├── WebSocketEndpoint.kt      # WebSocket 端点实现
│   │   │   │   ├── CombinedEndpointFactory.kt # 端点工厂
│   │   │   │   └── WebSocketMediaDescriptor.kt # 媒体描述符
│   │   │   ├── services/                 # 服务模块
│   │   │   │   └── DemoMediaProjectionService.kt # 屏幕录制服务
│   │   │   ├── models/                   # 数据模型
│   │   │   │   ├── EndpointType.kt           # 端点类型枚举
│   │   │   │   ├── Endpoint.kt               # 端点配置模型
│   │   │   │   └── Actions.kt                # 动作常量定义
│   │   │   ├── settings/                 # 设置界面
│   │   │   │   ├── SettingsActivity.kt       # 设置页面
│   │   │   │   └── SettingsFragment.kt       # 设置碎片
│   │   │   ├── utils/                    # 工具类
│   │   │   │   └── LocaleHelper.kt           # 语言本地化
│   │   │   ├── MainActivity.kt            # 主界面
│   │   │   └── Configuration.kt           # 配置管理类
│   │   ├── res/
│   │   │   ├── layout/                    # 布局文件
│   │   │   ├── menu/                      # 菜单资源
│   │   │   ├── values/                    # 字符串/样式
│   │   │   └── xml/root_preferences.xml   # 设置项定义
│   │   └── AndroidManifest.xml            # 应用清单
│   └── build.gradle.kts                   # 模块构建配置
├── websocket_h264_live.py                 # Python 实时显示服务器
├── websocket_h264_mp4.py                  # Python MP4 录制服务器
├── websocket_server.py                    # Python 基础接收服务器
├── build.gradle.kts                       # 项目构建配置
└── gradle.properties                      # Gradle 属性

```

---

## 🔧 环境要求

### Android 端

| 依赖        | 版本    | 说明                 |
| ----------- | ------- | -------------------- |
| Android SDK | API 24+ | 最低支持 Android 7.0 |
| StreamPack  | 2.14.0  | 流媒体核心库         |
| OkHttp      | 4.12.0  | WebSocket 客户端     |
| AndroidX    | 1.0+    | Jetpack 组件         |

### Python 端

| 依赖          | 版本  | 用途             |
| ------------- | ----- | ---------------- |
| Python        | 3.8+  | 运行环境         |
| websockets    | 11.0+ | WebSocket 服务端 |
| opencv-python | 4.5+  | 实时显示         |
| numpy         | 1.21+ | 图像处理         |
| FFmpeg        | 4.0+  | MP4 封装（可选） |

---

## 🚀 快速开始

### 1. 构建 Android 应用

```bash
# 进入项目目录
cd mcbtm

# 构建 Debug 版本
./gradlew assembleDebug

# 构建 Release 版本
./gradlew assembleRelease
```

构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`

### 2. 安装依赖（Python 服务器）

```bash
# 安装基础依赖
pip install websockets opencv-python numpy

# 如果使用 MP4 录制版本，需安装 FFmpeg
# Windows: 下载 ffplay.exe 并添加到 PATH
# Linux: sudo apt install ffmpeg
# macOS: brew install ffmpeg
```

### 3. 启动 Python 服务器

#### 实时显示版本（推荐）

```bash
python websocket_h264_live.py --port 8765
```

**功能：**

- ✅ 实时解码显示画面
- ✅ FPS 实时统计
- ✅ 帧计数与码率显示
- ✅ 自动保存录制文件

#### MP4 录制版本

```bash
python websocket_h264_mp4.py --port 8765
```

**功能：**

- ✅ 直接保存为 MP4 格式
- ✅ 包含时间戳信息
- ✅ 流畅播放无减速问题

#### 基础接收版本

```bash
python websocket_server.py --port 8765
```

**功能：**

- ✅ 接收 H.264 裸流
- ✅ 保存为 .h264 文件
- ✅ 支持内网穿透

### 4. 配置应用

1. 安装 APK 到 Android 设备
2. 打开应用，点击右上角设置图标
3. 在「端点类型」中选择 **WebSocket**
4. 配置服务器地址：`ws://localhost:8765/stream`
5. （可选）调整视频参数：
   - 分辨率：默认 1280x720
   - 码率：默认 2000 kbps
   - 帧率：自动匹配屏幕刷新率

### 5. 开始推流

1. 返回主界面
2. 点击 **Live** 按钮（红色圆形按钮）
3. 授权屏幕录制权限
4. 选择录制范围（全屏或应用窗口）
5. 开始推送视频流

---

## 🌐 内网穿透配置

### 使用 cloudflared（推荐）

```bash
# 下载 cloudflared（Windows）
# https://github.com/cloudflare/cloudflared/releases

# 启动内网穿透
cloudflared.exe tunnel --url http://localhost:8765

# 输出示例：
# 2024-01-01T12:00:00Z INF Requesting new quick Tunnel on trycloudflare.com...
# 2024-01-01T12:00:01Z INF +--------------------------------------------------------------------------------------------+
# 2024-01-01T12:00:01Z INF |  Your quick Tunnel has been created! Visit it at (it may take some time to be available):  |
# 2024-01-01T12:00:01Z INF |  https://abc123.trycloudflare.com                                                          |
# 2024-01-01T12:00:01Z INF +--------------------------------------------------------------------------------------------+
```

在手机端配置 WebSocket URL：

```
wss://abc123.trycloudflare.com/stream
```

### 调试实例

运行服务器后的输出示例：

```bash
D:\program\miniconda\envs\flask_test\python.exe D:\muchengbai\wenjian\py\serverapp\app_test.py 
============================================================
WebSocket H.264 服务器已启动
============================================================
本地地址: ws://localhost:8765/stream
网络地址: ws://0.0.0.0:8765/stream
============================================================
使用 cloudflared 内网穿透:
  cloudflared.exe tunnel --url http://localhost:8765
============================================================
等待客户端连接...
------------------------------------------------------------

[+] 客户端已连接: ('127.0.0.1', 65375)
[*] 连接路径: /stream
[*] 当前在线客户端: 1
[+] 开始写入文件: h264_stream_20260423_111541.h264
[S] SPS 帧, 帧: 1, 大小: 23 bytes
[P] PPS 帧, 帧: 2, 大小: 9 bytes
[I] 关键帧, 帧: 3, 大小: 3534 bytes
[*] 已接收 100 帧, 总大小: 0.56 MB
[S] SPS 帧, 帧: 165, 大小: 23 bytes
[P] PPS 帧, 帧: 166, 大小: 9 bytes
[I] 关键帧, 帧: 167, 大小: 173592 bytes
[*] 已接收 200 帧, 总大小: 1.14 MB
[*] 已接收 300 帧, 总大小: 1.49 MB
```

**输出说明：**

| 标识    | 含义                         |
| ------- | ---------------------------- |
| `[+]` | 重要事件（连接、文件操作）   |
| `[*]` | 状态信息（客户端数量、统计） |
| `[S]` | SPS 帧（序列参数集）         |
| `[P]` | PPS 帧（图像参数集）         |
| `[I]` | I帧（关键帧）                |

---

## 🎛️ 配置项说明

### 视频配置

| 参数   | 键名                 | 默认值        | 说明       |
| ------ | -------------------- | ------------- | ---------- |
| 编码器 | `video_encoder`    | `video/avc` | H.264 编码 |
| 分辨率 | `video_resolution` | `1280x720`  | 录制分辨率 |
| 码率   | `video_bitrate`    | `2000`      | kbps       |

### 音频配置

| 参数   | 键名                         | 默认值              | 说明     |
| ------ | ---------------------------- | ------------------- | -------- |
| 编码器 | `audio_encoder`            | `audio/mp4a-latm` | AAC 编码 |
| 采样率 | `audio_sample_rate`        | `48000`           | Hz       |
| 声道数 | `audio_number_of_channels` | `2`               | 立体声   |
| 码率   | `audio_bitrate`            | `128000`          | bps      |

### 端点配置

#### SRT 配置

| 参数      | 键名                  | 默认值   | 说明           |
| --------- | --------------------- | -------- | -------------- |
| 服务器 IP | `server_ip`         | -        | SRT 服务器地址 |
| 端口      | `server_port`       | `9998` | SRT 端口       |
| Stream ID | `server_stream_id`  | -        | 流标识         |
| 密码      | `server_passphrase` | -        | 加密密码       |

#### RTMP 配置

| 参数       | 键名                | 默认值                           | 说明          |
| ---------- | ------------------- | -------------------------------- | ------------- |
| 服务器 URL | `rtmp_server_url` | `rtmp://localhost/live/stream` | RTMP 推流地址 |

#### WebSocket 配置

| 参数       | 键名                     | 默认值                         | 说明           |
| ---------- | ------------------------ | ------------------------------ | -------------- |
| 服务器 URL | `websocket_server_url` | `ws://localhost:8765/stream` | WebSocket 地址 |

---

## 🧠 WebSocket H.264 推流技术实现

### 核心流程

```
=Android 端发送流程：
┌─────────────────────────────────────────────────────────────────┐
│ 1. MediaCodec 编码产生 H.264 帧                                │
│    └─→ 包含：SPS(7)、PPS(8)、I帧(5)、P帧(1)                   │
│                                                                 │
│ 2. StreamPack 封装为 FrameWithCloseable                        │
│    └─→ Frame.extra 包含 SPS/PPS                                │
│    └─→ Frame.rawBuffer 包含帧数据                               │
│                                                                 │
│ 3. WebSocketEndpoint 处理                                      │
│    ├─→ 检测关键帧(I帧)                                          │
│    ├─→ 提取 SPS/PPS (从 MediaFormat.csd-0/csd-1)               │
│    ├─→ 添加 Annex B 起始码 (0x00000001)                        │
│    └─→ 发送顺序：SPS → PPS → I帧 → P帧...                      │
│                                                                 │
│ 4. WebSocket 二进制发送                                        │
│    └─→ 通过 OkHttp WebSocket 发送 byte[]                        │
└─────────────────────────────────────────────────────────────────┘
```

### SPS/PPS 提取机制

**为什么需要 SPS/PPS？**

H.264 解码器需要序列参数集（SPS）和图像参数集（PPS）来正确解码视频流。缺少这些信息会导致：

- `non-existing PPS 0 referenced` 错误
- `decode_slice_header error` 错误
- 无法解码任何帧

**提取来源优先级：**

1. **Frame.extra** - StreamPack 提供的额外数据
2. **MediaFormat.csd-0/csd-1** - MediaCodec 配置数据

```kotlin
// WebSocketEndpoint.kt - 关键代码
private fun extractSpsPpsFromMediaFormat(format: MediaFormat) {
    val csd0 = format.getByteBuffer("csd-0")  // SPS
    val csd1 = format.getByteBuffer("csd-1")  // PPS
  
    if (csd0 != null) {
        val data = ByteArray(csd0.remaining())
        csd0.get(data)
        spsData = createAnnexBFrame(data)  // 添加起始码
    }
  
    if (csd1 != null) {
        val data = ByteArray(csd1.remaining())
        csd1.get(data)
        ppsData = createAnnexBFrame(data)  // 添加起始码
    }
}

private fun createAnnexBFrame(data: ByteArray): ByteArray {
    val annexbData = ByteArray(data.size + 4)
    annexbData[0] = 0
    annexbData[1] = 0
    annexbData[2] = 0
    annexbData[3] = 1  // Annex B 起始码
    System.arraycopy(data, 0, annexbData, 4, data.size)
    return annexbData
}
```

### 端点分发机制

**CombinedEndpointFactory** 根据 URI scheme 自动选择端点：

```kotlin
class CombinedEndpoint(...) : IEndpointInternal {
    private fun isWebSocketDescriptor(descriptor: MediaDescriptor): Boolean {
        return descriptor.uri.scheme == "ws" || descriptor.uri.scheme == "wss"
    }

    override suspend fun open(descriptor: MediaDescriptor) {
        val factory = if (isWebSocketDescriptor(descriptor)) {
            webSocketEndpointFactory      // WebSocket
        } else {
            dynamicEndpointFactory        // SRT/RTMP
        }
        currentEndpoint = factory.create(context, dispatcherProvider)
        currentEndpoint?.open(descriptor)
  
        // 等待端点打开
        while (!currentEndpoint?.isOpenFlow?.value!!) {
            kotlinx.coroutines.delay(50)
        }
    }
}
```

---

## 📊 协议对比

| 协议                | 延迟           | 可靠性 | 带宽开销 | 适用场景             |
| ------------------- | -------------- | ------ | -------- | -------------------- |
| **SRT**       | 低 (<100ms)    | 高     | 中       | 专业直播、远程监控   |
| **RTMP**      | 中 (200-500ms) | 中     | 中       | 传统直播平台         |
| **WebSocket** | 低 (<100ms)    | 中     | 低       | Web 集成、轻量级场景 |

### 选择建议

- **低延迟要求** → SRT 或 WebSocket
- **兼容传统平台** → RTMP
- **Web 应用集成** → WebSocket
- **高可靠性要求** → SRT

---

## 🐛 常见问题

### Q1: 播放 H.264 文件时速度变慢

**原因**：H.264 裸流文件没有时间戳信息，ffplay 无法确定播放速度。

**解决方案**：

```bash
# 方法1：指定帧率播放
ffplay -framerate 30 output.h264

# 方法2：转换为 MP4
ffmpeg -i output.h264 -c:v copy output.mp4

# 方法3：使用 MP4 录制服务器
python websocket_h264_mp4.py --port 8765
```

### Q2: 解码器报错 `non-existing PPS 0 referenced`

**原因**：缺少 SPS/PPS 数据，解码器无法初始化。

**解决方案**：确保 Android 端正确发送 SPS/PPS。检查 `WebSocketEndpoint.kt` 中的提取逻辑。

### Q3: 实时显示卡顿

**原因**：解码线程与网络线程竞争资源，缓冲区管理不当。

**解决方案**：

```bash
# 确保安装优化版服务器
python websocket_h264_live.py --port 8765
```

### Q4: 内网穿透无法连接

**原因**：cloudflared 配置问题或防火墙限制。

**解决方案**：

```bash
# 确保使用 wss 协议
wss://<your-domain>.trycloudflare.com/stream

# 检查防火墙是否放行 8765 端口
```

### Q5: ffmpeg使用问题

**原因**：请下载 ffmpeg 工具包,并保证在同一目录下。

**解决方案**：

```bash
# 推荐使用方法:
.\ffplay.exe output/your_file.h264
# 仅提供windows使用方法
```

---

## 📝 API 说明

### Android 端 API

#### EndpointType 枚举

```kotlin
enum class EndpointType(val id: Int) {
    SRT(0),           // SRT 协议
    RTMP(1),          // RTMP 协议
    WEBSOCKET(2)      // WebSocket 协议
}
```

#### WebSocketMediaDescriptor

```kotlin
class WebSocketMediaDescriptor(val websocketUrl: String) : MediaDescriptor(
    type = Type(MediaContainerType.FLV, MediaSinkType.CONTENT),
    customData = listOf(WebSocketCustomData(websocketUrl))
) {
    override val uri: Uri = Uri.parse("ws://websocket/stream?url=${Uri.encode(websocketUrl)}")
}
```

### Python 端 API

#### websocket_h264_live.py 参数

| 参数   | 类型 | 默认值 | 说明         |
| ------ | ---- | ------ | ------------ |
| --port | int  | 8765   | 监听端口     |
| --save | bool | True   | 是否保存文件 |

#### 快捷键

| 按键 | 功能      |
| ---- | --------- |
| q    | 退出程序  |
| f    | 切换全屏  |
| p    | 暂停/继续 |

---

## 🤝 贡献指南

### 代码规范

- **Kotlin**：遵循 Android 官方代码风格
- **Python**：遵循 PEP 8 规范
- **提交信息**：使用语义化提交格式

### 开发流程

1. Fork 项目
2. 创建特性分支
3. 提交代码
4. 创建 Pull Request

### 测试建议

- 测试所有三种协议的推流功能
- 测试不同分辨率和码率配置
- 测试内网穿透场景
- 测试客户端断开重连

---

## 📄 许可证

```
ScreenStreamHub License

Copyright (c) 2026 ScreenStreamHub

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software for **non-commercial, personal, educational or research purposes only**,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

**Commercial use, including but not limited to selling, licensing, monetizing,
or using the Software in any commercial product or service, is strictly prohibited
without prior written permission from the copyright holder.**

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

```
This project incorporates software from StreamPack by Thibault B.,
licensed under the Apache License, Version 2.0.

Copyright 2021 Thibault B.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0
```

---

## 📞 联系方式

- **项目地址**：https://github.com/muchengbai2005/ScreenStreamHub
- **Issue 提交**：https://github.com/muchengbai2005/ScreenStreamHub/issues
- **邮箱地址 提交**：2601065607@qq.com

---

## 📚 参考资料

1. **StreamPack 官方文档**：https://github.com/ThibaultBee/StreamPack
2. **H.264 编码标准**：ITU-T H.264
3. **WebSocket 协议**：RFC 6455
4. **SRT 协议**：https://www.srtalliance.org/
5. **cloudflared**：https://developers.cloudflare.com/cloudflare-one/connections/connect-apps/

---

*Built with ❤️ for Android streaming*
