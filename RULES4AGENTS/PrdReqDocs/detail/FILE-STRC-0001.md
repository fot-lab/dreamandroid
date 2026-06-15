# FILE-STRC-0001: 当前文件结构

| 属性 | 值 |
|------|-----|
| 分类 | File Structure |
| 对应章节 | §9 |
| 依赖 | ARCH-OVER-0001 |

## 目录结构

```
app/src/main/java/io/github/dreamandroid/local/
├── MainActivity.kt              # 主 Activity，状态管理和 WorkManager 编排
├── DreamAndroidApplication.kt   # Application 类，持有 QueueRepository 单例
├── navigation/
│   └── Navigation.kt            # BottomTab 枚举和路由
├── data/
│   ├── Model.kt                 # Model/UpscalerModel 数据类 + Repository
│   ├── QueueModels.kt           # GenerationTask/TaskStatus/BatchGroupDisplay
│   ├── HistoryManager.kt        # 历史记录 Room DB 管理
│   ├── GenerationPreferences.kt # 生成参数持久化
│   └── db/                      # Room Database Entity/DAO
├── service/
│   ├── BackendService.kt        # C++ 后端进程管理
│   ├── BackgroundGenerationService.kt  # 单次 HTTP 生成服务 (兼容路径)
│   ├── QueueRepository.kt       # 任务队列状态管理 (进程级单例)
│   ├── UpscaleBackendManager.kt # Upscale 后端管理
│   ├── ModelDownloadService.kt  # 模型下载服务
│   └── queue/
│       ├── GenerationWorker.kt        # WorkManager CoroutineWorker (主路径)
│       ├── QueueController.kt         # WorkManager 生命周期控制
│       ├── QueueNotificationHelper.kt # 统一通知工具
│       ├── QueueProcessingService.kt  # 前台服务队列处理 (兼容路径)
│       └── SseStreamParser.kt         # SSE 流解析器
├── ui/
│   ├── screens/
│   │   ├── ModelListScreen.kt   # 模型列表/下载页面
│   │   ├── ModelRunScreen.kt    # 模型详情/操作页面
│   │   ├── GenerateScreen.kt    # 生成参数组合页面
│   │   ├── QueueScreen.kt       # 任务队列页面
│   │   ├── UpscaleScreen.kt     # 超分辨率页面
│   │   └── BrowseScreen.kt      # 图库/画廊页面
│   ├── components/              # 通用 Compose 组件
│   └── theme/                   # 主题配置
├── utils/
│   ├── ImageUtils.kt            # performUpscale/saveImage
│   └── LogCapture.kt            # 日志捕获
└── cpp/
    └── src/main.cpp             # C++ 后端 HTTP Server
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §9 内容，创建独立文件 |
