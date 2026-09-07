# xq-assist

中国象棋助手 APK：本地人机对练、皮卡鱼引擎分析、最优着法连线提示、云库开局查询。

## 下载 APK

1. 打开 [Releases](https://github.com/xiaofeididi/xiangqi/releases)。
2. 选择 `xq-assist 最新测试版` 下载最新测试版；有正式版本时优先下载正式版本。
3. 下载 `app-debug.apk` 并安装。

也可以在 [Actions](https://github.com/xiaofeididi/xiangqi/actions) 中选择最近一次成功的 `Build APK`，从 Artifacts 下载 `xq-assist-debug`。

## 当前功能

- 本地人机对练：可切换红/黑执子与 AI 回招。
- 皮卡鱼提示：引擎计算最优着法，并在棋盘上显示绿色连线箭头。
- 云库查询：访问 chessdb.cn 开局库，按胜率展示推荐。
- 对局控制：新局、悔棋、思考时间设置。
- 棋盘绘制：完整象棋棋盘、棋子贴图、楚河汉界与最近着法高亮。

## 后续规划

- 悬浮窗 + 屏幕识别的实战辅助模式。
- 更精细的引擎强度、开局库与残局分析设置。
- UI 打磨、历史记录与复盘。

## Android 项目

源码和构建说明在 [`android/`](android) 目录。构建产物通过 GitHub Actions 自动生成。
