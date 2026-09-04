# xq-assist Android

中国象棋辅助 App：悬浮窗 + 屏幕识别 + 皮卡鱼引擎分析 + 云库开局。

## 功能
- 悬浮窗：前台服务 + Overlay，显示当前局面与最优解，可拖动。
- 屏幕识别：MediaProjection 抓屏，识别棋盘网格与棋子，生成 FEN。
- 引擎分析：内置 Pikafish(arm64) 走 UCCI，返回最优着法。
- 云库开局：查询公共开局库 chessdb.cn，按胜率推荐。

## 目录
- `app/src/main/java/com/xqassist/core/` 局面 / 记谱纯逻辑（FEN、ICCS、中文记谱）
- `app/src/main/java/com/xqassist/engine/` UCCI 引擎封装、云库客户端
- `app/src/main/java/com/xqassist/capture/` MediaProjection 抓屏服务
- `app/src/main/java/com/xqassist/vision/` 棋盘识别接口与网格
- `app/src/main/java/com/xqassist/overlay/` 悬浮窗服务
- `app/src/main/assets/engine/` 打包进来的 Pikafish 二进制与 NNUE

## 本机 / 服务器构建
前置：JDK 17 + Android SDK(platform 34, build-tools 34) + Gradle(用 wrapper 自动下载)。

Debian/Ubuntu 服务器一次性准备：

    sudo apt update && sudo apt install -y openjdk-17-jdk unzip
    mkdir -p $HOME/Android/Sdk
    cd $HOME/Android && curl -O https://dl.google.com/android/repository/commandlinetools-linux-latest.zip
    unzip commandlinetools-linux-latest.zip -d cmdline-tools
    mv cmdline-tools/cmdline-tools cmdline-tools/latest
    export ANDROID_HOME=$HOME/Android/Sdk
    cmdline-tools/latest/bin/sdkmanager --licenses
    cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

构建：

    cd android
    ./gradlew assembleDebug
    # 产物: app/build/outputs/apk/debug/app-debug.apk

## 说明/注意
- 引擎是 arm64 ELF，APK 限制 `arm64-v8a`。
- Android 9+ 对从私有目录 exec 二进制有 W^X 限制，真机若启动失败，需改走 JNI/ndk 方式把引擎编为 so
  由 dlopen + 消息循环调用，而不是 ProcessBuilder exec。当前代码先按 exec 方式实现，作为第一版原型。
- 云库走 `http`，Android 9+ 默认禁明文流量，需在 network_security_config 放行该域名，
  或改用 https 网关。当前为原型默认仅本地/调试使用。
- 识别精度依赖目标 App 的棋盘皮肤；建议在设置里让用户圈定棋盘区域（BoardRect）。