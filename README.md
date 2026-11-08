# PenPad — 为第三方 ROM 补上手写笔的批注工具

在一台 **刷了 ColorOS 17 的联想小新 Pad Pro GT（TB710FU）** 上，把被砍掉的手写笔功能一点点补回来的记录与工具。

手写笔按键控制目前作者没有在其他的平板上测试过，而且在作者本人的平板上运行也会时不时被杀进程导致用不了，有时会觉得鸡肋，有时会觉得好用
关于自己做的手写笔，先说一些我自己使用的感受写了以下几点，读完后再决定下不下载
槽点：1.在断开手写笔蓝牙后，按键启动功能100%被杀，系统重启后大概率被杀。每次被杀也只能从abd启动进程（我也改了，但尽力了）
      2.apk中的手写笔笔迹渲染很拉跨，但这也是我从开源库中找到的
      3.在功能菜单中虽然有保存图片，但保存之后只有一个纯色图片，不能保存
爽点：1.解放双手刷视频，虽然这一点在原装系统是最基础的，但越狱过来还能时不时解放双手也算是一个爽点
      2.对比该项目中调起的原装全局批注，我还是觉得本作品中的全局批注更好（对比：ColorOS中的全局批注是把屏幕截图放小之后再批注，我的全局批注更像是之前在联想系统的时候的全局批注）

感兴趣可加入交流群：923879464

> 本项目包含两部分：一个能用的**全屏批注 App**，和几份**踩坑文档**。
> 如果你也在折腾「非原厂 ROM + 手写笔」，`docs/` 里的结论可能比代码更有用。
> 本项目依赖酷安大佬@时空神的ColorOS17.0.104 for TB710fu基于ZUI_17.5.04.428底包原包OPPOpad5Proc17移植
原帖https://www.coolapk.com/feed/74223090?s=NjEwMGNhYTYyN2Y4Mjg5ZzZhZWZlMzZkega1664 以及@gaoshan667的ColorOS17手写笔修复模块，原帖链接：https://www.coolapk.com/feed/74563523?s=NzkwNDc3MzAyN2Y4Mjg5ZzZhZWZlNGQwega1664 使用本项目前请确保以上均已安装调试
---

## 为什么会有这个项目

平板的原厂系统被换成了 ColorOS 之后，笔的状态是这样的：

| 问题 | 现象 |
|---|---|
| 笔完全写不出字 | 设置里笔的开关是灰的，任何 App 都收不到压感 |
| 笔键没反应 | 按键事件在系统手势层就被吃掉，App 和无障碍服务都收不到 |
| 系统全局批注没有入口 | 笔从屏幕底部上滑无反应 |
| 重启后一切归零 | 上面几个设置会被系统重置 |

这些问题最后都被定位到了具体的系统键值和声明，见 [docs/](docs/)。

---

## 三个关键发现（最有价值的部分）

### 1. `settings_enable_oppo_pencil` —— 笔不工作的总开关

```bash
settings put global settings_enable_oppo_pencil 1
```

在 **Settings.Global**（不是 Secure）。设为 1 之后，数字化仪才会上报真实接触、压力和悬停。

会被系统不定时重置为 `0`，所以需要一个常驻进程守着。

### 2. `ipe_pencil_swipe_function` —— 解锁系统全局批注

```bash
settings put global ipe_pencil_swipe_function 1
```

生效后日志会从 `mode=SWITCH_CLOSE` 变成 `mode=SCREENSHOT_OPEN, activeStrategy=A_GLOBAL_ANNOTATION`，
此时**用笔从屏幕底边快速上滑**会唤起系统原生的截图 / 批注界面。

### 3. 无障碍服务必须声明 `directBootAware`

```xml
<service android:name=".PenKeyService" android:directBootAware="true" ... />
```

否则开机后系统日志会打：

```
AccessibilityManagerService: Ignoring non-encryption-aware service
```

服务永远不会被绑定 —— 这是「重启后无障碍失效、笔键永远不生效」的真正原因。

---

## 仓库结构

```
app/                     批注 App 源码（Java，无 Gradle）
  src/                   18 个类
  res/                   图标与无障碍配置
  build.ps1              纯命令行构建（aapt2 + javac + d8 + apksigner）
  fetch-deps.ps1         拉取 Jetpack Ink / Kotlin / Shizuku API 依赖
tools/
  penkeyd.sh             笔键守护：直接读 /dev/input 并转发广播
  PenKey_Monitor.bat     Windows 端监测：守护失效时自动补一次
  privapp-permissions-*.xml  装成系统特权应用用的权限白名单
docs/
  01-笔为什么写不出字.md
  02-笔键为什么收不到.md
  03-系统全局批注怎么打开.md
```

---

## 它依赖什么

| 组件 | 用途 | 许可 |
|---|---|---|
| [Jetpack Ink](https://developer.android.com/jetpack/androidx/releases/ink) 1.0.0 | 笔迹渲染（原生 `libink.so`） | Apache-2.0 |
| Kotlin stdlib 2.0.21 | Ink 的运行时 | Apache-2.0 |
| [Shizuku API](https://github.com/RikkaApps/Shizuku-API) 13.1.5 | 借 shell 身份启动守护（兼容官方 Shizuku 与 ShizukuX 等改包名分支） | Apache-2.0 |

全部通过 `fetch-deps.ps1` 从 Google Maven / Maven Central 下载，仓库里**不含任何二进制依赖**。

---

## 构建

```powershell
cd app
powershell -ExecutionPolicy Bypass -File fetch-deps.ps1
powershell -ExecutionPolicy Bypass -File build.ps1
# 产物：app\PenPad.apk
```

需要 JDK 17 和 Android SDK（build-tools + platform 33）。

---

## 安装

### 普通安装（只能用批注，不能改系统设置）

```bash
adb install -r PenPad.apk
adb shell settings put secure enabled_accessibility_services com.aclaniakea.penpad/com.aclaniakea.penpad.PenKeyService
adb shell settings put secure accessibility_enabled 1
```

首次使用要在系统设置里给「显示在其他应用上层」权限。

### 特权安装（推荐，能自愈系统设置）

把 `PenPad.apk` 放到 `/system/priv-app/PenPad/`，并把
`tools/privapp-permissions-com.aclaniakea.penpad.xml` 放到 `/system/etc/permissions/`，
重启后应用会自动获得 `WRITE_SECURE_SETTINGS`，就能自己维护笔开关、不用电脑。

> 改 `/system` 需要解锁 bootloader + 自定义 recovery，自行评估风险。
> 注意那个 XML **不能带 UTF-8 BOM**，否则会被系统静默跳过（这个坑排查了很久）。

---

## 笔键（可选）

笔键在多数 ColorOS 机型上会被系统手势层消费，App 侧无法获取。想用的话：

```bash
adb push tools/penkeyd.sh /data/local/tmp/penkeyd.sh
adb shell chmod 755 /data/local/tmp/penkeyd.sh
adb shell setsid sh /data/local/tmp/penkeyd.sh
```

Windows 上直接跑 `tools\PenKey_Monitor.bat`，它会每 20 秒检查一次，守护失效且屏幕亮着就自动补上。

### 笔键免电脑：接入 Shizuku

上面那条命令依赖电脑。想彻底摆脱电脑，就用 **Shizuku** 给 App 一个 shell 身份：

1. 安装 [Shizuku](https://github.com/RikkaApps/Shizuku)（或改包名分支，如 **ShizukuX**）
2. 通过无线调试启动它，并在设置里打开「开机自启」
3. 打开 PenPad，在 Shizuku 里授权本应用
4. 之后 `DaemonKeeper` 会自己启动守护，每 5 分钟检查一次，进程死了自动重启

```java
// ShizukuShell.java 里的候选 authority，改包名分支也认
"moe.shizuku.privileged.api.shizuku"   // 官方
"xyz.shizuku.extra.api.shizuku"        // ShizukuX
"af.shizuku.privileged.api.shizuku"    // 早期分支
```

Binder 键名同样做多候选匹配，所以只要分支保留协议就无需改代码。

> 注意：Shizuku 本身在**未 root 的设备上重启后也会停**，
> 靠的是它的「开机自启」（依赖无线调试）。如果那一步失效，仍需手动重开 Shizuku。

---

## 已知限制

- 只在 **TB710FU + ColorOS 17** 上验证过；其他机型可能键名不同
- 系统批注界面**没有压感**（它本身就没实现，不是本项目的问题）
- 笔键守护依赖 adb / Shizuku 提供的 shell 身份，重启后需要重新拉起
- 息屏速记不可用（需要驱动层配合）

---

## 许可

代码：MIT。文档：CC BY 4.0。
本项目**不包含**任何厂商固件或系统应用的二进制文件。
