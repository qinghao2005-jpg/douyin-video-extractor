# 抖音视频提取（Android）

一个极简的安卓小工具：把抖音分享链接粘进去，解析出**无水印视频**或**图集（图片 / 动图）**，逐项保存到相册。

- 单文件 APK，约 40 KB，无需登录、无需 Root
- 支持普通视频、图文作品（多图 + 动图混合），逐项单独保存
- 保存位置：视频 → `Movies/Douyin`，图片 → `Pictures/Douyin`

## 工作原理

抖音分享页的数据由页面自己的脚本签名后请求，直接调接口会返回 403。所以本项目：

1. 在 App 内的 WebView 里加载分享页，让页面用自己的脚本取数据
2. 拦截页面发出的接口响应，并解析页面里 `images` 数组（图集）
3. 从作品数据里取 `play_addr`，替换成无水印的 `play` 接口地址
4. 自己用 HttpURLConnection 下载（带 UA / Referer / Cookie），按文件头判断真实类型，写入 MediaStore

**不做的事情**：不伪造 `a_bogus` / `msToken` 等签名参数，不批量爬取，不绕过登录。

## 使用

1. 在抖音里点「分享 → 复制链接」
2. 打开 App，粘贴到输入框，点「解析」
3. 普通视频：点「保存视频」；图文作品：弹窗里逐项点「保存」

> 分享页结构说改就改，失效时提 issue 即可。

## 构建

不需要 Android Studio / Gradle，一台 Linux（aarch64 也可以）+ JDK 17 就够：

```bash
# 依赖
sudo apt-get install -y openjdk-17-jdk-headless aapt apksigner zipalign android-sdk-platform-23

# 可选：更新 compileSdk 用的 android.jar 与 d8
#   tools/platform36/android-36/android.jar
#   tools/r8.jar (com.android.tools:r8)

SDK_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar ./build.sh
# 产物：build/douyin-dl.apk
```

## 免责声明

仅供个人学习与备份使用。请遵守抖音用户协议与相关法律法规，不要用于批量抓取、二次分发或商业用途。版权归原作者所有。

## License

MIT
