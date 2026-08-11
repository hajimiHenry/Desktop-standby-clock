# Desktop Standby Clock

[English](README.md) | [简体中文](README.zh-CN.md)

一款原生 Android 全屏时钟，用一台闲置的横屏手机打造长期运行的桌面待机显示器。

<p align="center">
  <img src="docs/images/phosphor-dial.png" width="49%" alt="P3 琥珀色荧光表盘">
  <img src="docs/images/calligraphy.png" width="49%" alt="书法数字时钟">
</p>

## 主要功能

- 两种表盘：温暖的 P3 琥珀色荧光表盘与克制的书法数字表盘
- 每小时自动轮换表盘，也可以通过手势随时切换
- 环境光自动黑屏：房间变暗时 OLED 显示纯黑，环境恢复明亮后持续显示时钟
- 每五分钟轻微移动画面，降低 OLED 烧屏风险
- 带简短 CRT 风格提示音的就寝提醒
- 在本地局域网控制 Yeelight 吸顶灯，并通过 Wake-on-LAN 唤醒电脑
- 在已 root 的 MIUI 手机上支持开机恢复和任务移除恢复

## 操作

- 上下滑动：切换表盘
- 左右滑动：打开或关闭设备控制
- 长按：打开时钟设置

## 构建

需要 JDK 17 和 Android SDK Platform 35。

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 技术文档

架构、环境光阈值、MIUI 常驻、安装、验证和故障排查统一记录在
[docs/MAINTENANCE.md](docs/MAINTENANCE.md)。
