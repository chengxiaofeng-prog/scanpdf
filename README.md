# 扫描成文（ScanPdf）

连续拍照 → 自动裁边 + 透视校正 → 去底灰 / 对比度 / 阈值 → OCR → 合成 PDF → 微信/QQ 分享。

Android 原生实现：Kotlin + Jetpack Compose + CameraX + ML Kit，**不依赖 OpenCV**（裁边与透视校正为纯 Kotlin 实现）。

## 一、功能

| 模块   | 能力                                                    |
| ---- | ----------------------------------------------------- |
| 连续拍照 | 快门后**不退出相机**，页数持续累加；"连拍"每 0.8–5s 自动拍；缩略图条回看任意页        |
| 自动裁边 | 拍照后台自动检测纸张四角（Hough + Otsu），检测到就默认裁切；可一键关掉回到整页         |
| 透视校正 | 四角求交后做单应变换（Heckbert + 双线性逆向采样），斜拍也能拉正                 |
| 去底灰  | 0–100%，大半径背景估计 + 平场校正，灰纸拉成白底                          |
| 对比度  | 0–100%，绕中灰 128 线性拉伸（斜率 1–3），256 项 LUT                 |
| 阈值   | 开关 + 0–255，二值化为纯黑白                                    |
| OCR  | 离线中文识别（ML Kit），可查看 / 复制全文；导出时自动叠加**隐形文字层**，PDF 可搜索可复制 |
| 页序重排 | "页序"入口，长按拖动，松手落位                                      |
| PDF  | A4 版式（1240×1754 ≈150dpi），多页合成单个文件                     |
| 分享   | 微信 / QQ 直达，未安装置灰；另有"更多应用"走系统面板                        |

## 二、上传到 GitHub 并编译

仓库已带 `.github/workflows/android.yml`：推上去即自动出 APK，无需本地装 Android SDK。

### 方式 A：Windows 双击（推荐，不需要 PAT）

1. 浏览器打开 <https://github.com/new>，仓库名填 `scanpdf`，**不要**勾 "Add a README file"
2. 双击 `tools\push_to_github.bat`，一路回车
   - 自动探测本机代理端口（Watt Toolkit 26561 / Clash 7890 / v2ray 10809 …），只对当前仓库生效，推完自动清掉
   - 首次推送会弹浏览器登录 GitHub（Git Credential Manager），登录一次就记住
   - 推送成功后自动打开 Actions 页
3. 5–15 分钟后，在该次运行页底部 **Artifacts → ScanPdf-debug-apk** 下载，解压即得 `app-debug.apk`

> **git 连不上但浏览器能打开 GitHub**：右键管理器运行 `tools\fix_github_hosts.bat`，写入 GitHub IP 映射并刷新 DNS，然后重跑第 2 步。

### 方式 B：命令行 + PAT

```bash
cd ScanPdf

# 1) 生成 PAT：https://github.com/settings/tokens  → 勾选 repo
export GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxx

# 2) 建仓 + 推送 + 触发编译（自动探测本机代理）
bash push-to-github.sh scanpdf
```

脚本会：调用 REST API 在你的账号下创建 `scanpdf` 仓库 → 推送 `main` → 控制台打印 Actions 地址。  
编译成功后到 **Actions → 对应运行 → 页面底部 Artifacts → ScanPdf-debug-apk** 下载，解压即得 `app-debug.apk`。

> workflow 用 `gradle/actions/setup-gradle@v4` 固定 Gradle 8.7 构建，**不依赖 `gradle-wrapper.jar`**（二进制文件不便随源码携带）。  
> 首次构建成功后，Actions 里会额外产出一个 `gradle-wrapper` artifact，下载解压到工程根目录后即可用 `./gradlew` 在本地构建。

## 三、本地编译

**Android Studio（Koala 2024.1+）**：`File > Open` 选本目录 → 首次 Sync 联网拉依赖 → `Run ▶`。  
出包：`Build > Build Bundle(s)/APK(s) > Build APK(s)`。

**命令行**（本机需 JDK 17 + Gradle 8.7）：

```bash
gradle assembleDebug
# 或补一次 wrapper 后用 ./gradlew
gradle wrapper --gradle-version 8.7 && ./gradlew assembleDebug
```

**必须真机**：模拟器没有真实摄像头，相机相关功能无法验证。

## 四、目录结构

```
ScanPdf/
├─ .github/workflows/android.yml   GitHub Actions：自动编译 APK
├─ push-to-github.sh               bash 一键建仓 + 推送（需 PAT）
├─ tools/push_to_github.bat        Windows 双击推送（免 PAT，走浏览器登录）
├─ tools/fix_github_hosts.bat      GitHub 打不开时写 hosts（管理员运行）
├─ app/src/main/java/com/scanpdf/app/
│  ├─ MainActivity.kt              入口 + 两页导航
│  ├─ ui/CameraScreen.kt           CameraX 预览、单拍、连拍、拍照后自动裁边
│  ├─ ui/EditScreen.kt             参数面板、裁边开关、OCR、导出、分享
│  ├─ ui/PageOrderScreen.kt        长按拖拽重排页序
│  ├─ ui/theme/Theme.kt            配色
│  ├─ doc/Quad.kt                  四角点模型 + 合理性校验
│  ├─ doc/QuadDetector.kt          ★ 边缘检测：Sobel + Otsu + Hough + 交点
│  ├─ doc/PerspectiveWarp.kt       ★ 透视校正：Heckbert 单应 + 双线性采样
│  ├─ doc/PageRenderer.kt          校正 → 三参数的统一管线
│  ├─ img/ImageProcessor.kt        去底灰 → 对比度 → 阈值
│  ├─ ocr/OcrEngine.kt             ML Kit 离线识别
│  ├─ io/ImageIO.kt                降采样解码 + EXIF 方向校正
│  ├─ pdf/PdfExporter.kt           多页 A4 + 隐形文字层
│  ├─ share/ShareHelper.kt         FileProvider + ACTION_SEND
│  └─ data/ScanStore.kt            页列表 / 页序 / 裁边调度
```

## 五、算法要点

### 1. 自动裁边（`QuadDetector.kt`）

- 缩到长边 640 → 灰度 → 3×3 盒式模糊 → Sobel（|gx|+|gy|）
- Otsu 自适应阈值取边缘，阈值下限取最大梯度的 28%（防止整图噪声）
- Hough 变换（θ 1°、ρ 1px）累加，取峰值做 4°/8px 非极大值抑制
- 峰值按法线角度分成"近垂直 / 近水平"两组，各取最外两条
- 两两求交得四角 → 校验凸性、面积（15%–99.5%）、越界容差 15%
- 任一环节不满足即返回 null，界面保持整页，不会乱裁

### 2. 透视校正（`PerspectiveWarp.kt`）

- 输出尺寸由四边形边长推得（对边取长边）
- 单位正方形 → 四边形用 Heckbert 闭式解求 8 个系数，得到**目标→源**的逆向映射
- 逐像素双线性采样，无空洞；输出长边按需限制（预览 1400 / 导出 2600）

### 3. 三参数（`ImageProcessor.kt`）

顺序固定：去底灰 → 对比度 → 阈值（颠倒会把底灰"焊死"）。

- 去底灰：局部均值估背景，`f = 255/bg`，按强度与原图混合
- 对比度：`k = 1 + 2×强度`，LUT 一次映射
- 阈值：`≥t → 255，否则 0`

### 4. OCR 与文字层

- ML Kit 中文模型（`text-recognition-chinese`），离线、随包
- 识别在**处理后的预览图**上做，行框归一化保存
- 导出时按图片在 A4 上的实际绘制矩形换算，用 `alpha=3` 的画笔逐行绘制
- 视觉无感，但 PDF 阅读器可选中、可全文搜索

## 六、首次构建常见报错对照

| 报错                                                                | 原因                        | 处理                                                                                    |
| ----------------------------------------------------------------- | ------------------------- | ------------------------------------------------------------------------------------- |
| `Could not find com.google.mlkit:text-recognition-chinese:16.0.0` | 依赖版本在镜像里缺失                | 改为 `16.0.0-beta6`，或换回 `text-recognition:16.0.0`（纯拉丁）                                  |
| `Failed to find platform android-34` / `licenses not accepted`    | runner 缺 SDK 平台           | workflow 已加 `android-actions/setup-android@v3`；本地则用 SDK Manager 装 Android 14 (API 34) |
| `Unresolved reference: kotlinOptions`                             | Kotlin 插件未生效              | 确认根 `build.gradle.kts` 里 `org.jetbrains.kotlin.android` 版本与 compose 插件版本一致（均 2.0.21）  |
| `Inconsistent JVM-target compatibility (17 vs 1.8)`               | Kotlin/Java 目标不一致         | 已同时设 `compileOptions` 与 `kotlinOptions.jvmTarget = "17"`，不要再单改一处                      |
| `GradleWrapperMain not found`                                     | 仓库没有 `gradle-wrapper.jar` | CI 里不用 `./gradlew`；本地从 Actions 的 `gradle-wrapper` artifact 取回 jar                     |
| 编译期 OOM / `Java heap space`                                       | 内存不足                      | `gradle.properties` 已设 3G；机器仍不足则降到 `-Xmx2048m` 并关 `org.gradle.parallel`               |
| `Duplicate class com.google.android.gms...`                       | ML Kit 与其他 GMS 依赖版本冲突     | 在 `dependencies` 里统一 `play-services-*` 版本，或只用 bundled 版 ML Kit                        |

## 七、已知限制

- 裁边为全自动，**不做手动拉角**；极端光照/低对比纸面可能检测失败（此时保持整页）
- OCR 只处理当前页的预览分辨率图；裁边开关切换后需重新识别（行框会变）
- PDF 主层仍是图片，文字层仅用于检索；扫描件本身不做矢量化
- 页序拖拽为"松手落位"，没有实时插入动画
- 微信/QQ 走 `ACTION_SEND`，会跳到"选择聊天"，不能跳过选人

## 八、验收清单

- [ ] 连拍 10 页不崩、不丢页，缩略图顺序与拍摄一致
- [ ] 斜拍 A4 纸能自动裁正，四角不越界；无纸张时不误裁
- [ ] 三个滑块拖动不卡顿，预览与最终 PDF 一致
- [ ] OCR 能识别中文，导出的 PDF 可搜索（微信里打开也能选中文字）
- [ ] 长按拖动改页序后，页码、导出顺序同步
- [ ] 微信 / QQ 能收到 PDF 并正常打开
