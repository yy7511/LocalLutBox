# LocalLutBox

完全离线的 LUT 调色工具，面向 Android 7.0+ 与鸿蒙 4.x（APK 兼容层）。不需要网络、不需要账号、不依赖 Google 服务，所有照片和 LUT 只留在本机。

## 功能

- **多图导入**：从任意文件夹一次导入多张 PNG / JPG，编辑页左右翻页
- **LUT 库**：支持 `.cube`（1D / 3D，含 DOMAIN_MIN/MAX）、`.xmp`（ACR 预设参数 + 色调曲线烘焙成 33³）、`.3dl`（蓝快序自动重映射）
- **LUT 预览**：列表缩略图按内置色卡或你指定的图片渲染；编辑器内**短按套用、长按查看参数与全屏效果**（cube 尺寸/输入域，xmp 的曝光、对比度、高光、阴影、饱和度、自然饱和度、清晰度、色温、色调）；「预览大小」按钮可在小/中/大三档间切换，缩略图外框只包裹图片与名称
- **每图独立参数**：曝光、对比度、高光、阴影、饱和度、自然饱和度、色温、色调、清晰度
  - 拖动滑块调整；点击滑钮左侧/右侧微调一档；双击滑钮复位该参数
  - 按住图片对比原图；单击图片进入全屏，再点退出
  - 底部仅「调整」「LUT」两个标签，切换更快
- **分析与实时预览**：RGB 直方图（对数缩放）+ Cb/Cr 矢量示波器，固定显示在编辑页图片上方；点右上角「显示」可切换只显示直方图 / 只显示矢量图 / 两者 / 关闭，选择会被记住
- **文件夹自动处理**：指定输入/输出文件夹，新文件约 6 秒内自动套用固定 LUT 与参数，输出 `原名_LUT.jpg`；可选择「立即处理已有图片」、查看处理日志
- **色彩空间**：读取图片内嵌 ICC（JPEG APP2 / PNG iCCP），识别 sRGB / Adobe RGB / Display P3 / ProPhoto RGB / Rec.2020，先转换到 LUT 目标空间再调色，输出统一 sRGB
- **EXIF 与画质**：输出复制原文件 EXIF（机型、镜头、拍摄时间、GPS 等），方向标记归正；默认原图尺寸 + JPEG 质量 100，不做压缩（可手动选择长边 3000 / 2000）
- **主题**：深色 / 浅色一键切换

## 安装

从 [Releases](https://github.com/yy7511/LocalLutBox/releases) 下载最新 `LocalLutBox-x.y.z.apk`，允许「安装未知来源应用」后安装。

- 包名：`io.github.yy7511.locallutbox`
- 最低 Android 7.0（API 24），targetSdk 33
- 只申请前台服务、通知、忽略电池优化三项权限；图片与文件夹通过系统文件选择器（SAF）授权，不申请存储权限，也不申请联网权限

鸿蒙 4.x 额外一步：设置 → 应用和服务 → 应用启动管理 → LocalLutBox → 关闭自动管理，允许自启动 / 关联启动 / 后台活动，息屏后才能稳定自动处理。

## 使用要点

1. 首页「导入图片」选择照片；「LUT 库」导入 cube/xmp/3dl；「文件夹自动处理」设置输入/输出文件夹、固定 LUT 与参数后开启
2. 自动处理首次开启只记录现有文件，之后新增的才处理；输出文件以 `_LUT.jpg` 结尾并被忽略，避免循环处理
3. LUT 库长按条目可设置目标色彩空间、删除；「更换预览图」可用自己的照片作为预览底图
4. 编辑页保存：默认写入相册 `Pictures/LocalLutBox`；若已配置自动输出文件夹则优先写入该文件夹

## 构建

不需要 Gradle / Android Studio，使用 Android SDK 命令行工具直接产出签名 APK：

```bash
SDK=/path/to/android-sdk ./build.sh
```

流程：`aapt2 compile/link` → `javac --release 8` → `R8`（压缩、优化、混淆）→ `aapt2 optimize`（资源名折叠）→ 打包 dex → `zipalign` → `apksigner`。产物为 `LocalLutBox-<version>.apk`。

- 需要 Android SDK Platform 34 与 Build-Tools（34+）
- 签名密钥不随仓库分发，构建时读取 `$KS`（默认 `~/.lutbox/locallutbox.keystore`），可用环境变量 `KS` / `KS_PASS` / `KEY_ALIAS` 覆盖
- `build.sh` 结束时会调用 `tools/publish.sh`：推送到 `main` 并把 APK 上传到对应 tag 的 Release

## 目录结构

```
app/src/main/
  AndroidManifest.xml
  java/io/github/yy7511/locallutbox/
    App.java                  Application，初始化全局 Context
    MainActivity.java         首页：导入、图片网格、入口
    EditorActivity.java       编辑器：参数、LUT、直方图/矢量图、保存
    LutLibraryActivity.java   LUT 库：导入、预览图、目标色彩空间、删除
    AutoActivity.java         文件夹自动处理设置与日志
    AutoService.java          前台服务：监听目录、自动处理
    core/                     解析、管线、色彩空间、EXIF、存储
    ui/                       自定义控件（直方图、矢量图、滑块）、LUT 详情
  res/                        布局、矢量图标、主题与颜色（含 values-night）
tools/keep.pro                R8 保留规则
tools/make_icons.py           图标生成脚本
build.sh                      手工构建脚本
```

## 实现说明

- 纯 Android Framework API，无 AndroidX、无 GMS、无第三方依赖；Java 8 语法
- 调色在 `int[]` 上进行，多线程按行并行，三线性插值采样 3D LUT；清晰度用可分离盒式模糊
- LUT 缓存以 AES-256-GCM 加密存储，密钥由 APK 签名证书派生，缓存拷出应用沙箱无法读取
- 发布包经 R8 混淆（类/成员重命名、组件与自定义 View 按规则保留）、资源名折叠、去除调试信息
- 目录监听使用 SAF + ContentObserver + 6 秒轮询（SAF 目录无法使用 FileObserver）

## 已知限制

- 不支持 HEIC / AVIF 解码
- PNG 的 ICC 依赖 profile 名称文本识别；未内嵌 ICC 的图片按 sRGB 处理
- 宽色域转 sRGB 存在超色域裁剪（矩阵管线固有限制）
- 超大图（如 50MP 以上）在内存不足时会自动降级到长边 3000（编辑器 4096）
- 界面交互未经真机自动化测试覆盖

## 许可

[GNU General Public License v3.0](LICENSE) © 2026 yy7511

本说明由ai撰写，请注意甄别
