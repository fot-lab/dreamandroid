# FILE-STRC-0001: 当前文件结构

| 属性 | 值 |
|------|-----|
| 分类 | File Structure |
| 对应章节 | §9 |
| 依赖 | ARCH-OVER-0001 |

## 目录结构

```
app/src/main/java/io/github/dreamandroid/local/
├── DreamAndroidApplication.kt   # Application 类，持有 BackendManager + BackendService + QueueRepository
├── core/
│   └── error/
│       └── AppError.kt          # 统一错误密封类
├── data/
│   ├── Model.kt                 # Model/UpscalerModel 数据类 + Repository
│   ├── QueueModels.kt           # GenerationTask/TaskStatus/BatchGroupDisplay
│   ├── HistoryManager.kt        # 历史记录 Room DB 管理
│   ├── GenerationPreferences.kt # 生成参数持久化
│   └── db/                      # Room Database Entity/DAO
├── service/
│   ├── backend/
│   │   ├── BackendManager.kt    # C++ 后端进程生命周期管理 (唯一进程管理者)
│   │   ├── BackendService.kt    # HTTP 中间件 (UI 层边界，薄代理)
│   │   └── RuntimeDirPreparer.kt # QNN 运行时目录准备
│   ├── queue/
│   │   ├── GenerationWorker.kt        # WorkManager CoroutineWorker (主路径)
│   │   ├── QueueController.kt         # WorkManager 生命周期控制
│   │   ├── QueueNotificationHelper.kt # 统一通知工具
│   │   ├── QueueProcessingService.kt  # Android Service 队列处理 (兼容路径)
│   │   └── SseStreamParser.kt         # SSE 流解析器
│   ├── http/
│   │   └── HttpClientProvider.kt      # OkHttpClient 单例工厂
│   ├── QueueRepository.kt             # 任务队列状态管理 (进程级单例)
│   └── ModelDownloadService.kt        # 模型下载服务
├── ui/
│   ├── viewmodel/
│   │   ├── GenerateViewModel.kt       # 生成参数管理 + tokenize (via BackendService)
│   │   ├── ModelsViewModel.kt         # 模型加载/卸载 (via BackendService)
│   │   ├── UpscaleViewModel.kt        # 超分辨率 (via BackendService)
│   │   ├── QueueViewModel.kt          # 队列状态管理
│   │   └── BrowseViewModel.kt         # 图库浏览
│   ├── screens/
│   │   ├── ModelRunScreen.kt          # 模型详情/操作 (via BackendService)
│   │   ├── UpscaleScreen.kt           # 超分辨率 (via BackendService)
│   │   └── run/
│   │       └── ModelRunGeneration.kt  # 模型运行子模块
│   ├── orchestrator/
│   │   └── AppContent.kt              # 编排层 (via ViewModel → BackendService)
│   ├── frontend/
│   │   └── GenerateSection.kt         # 生成参数 UI 组件
│   ├── backend/
│   │   └── BackendSection.kt          # 后端状态 UI 组件
│   ├── components/                    # 通用 Compose 组件
│   └── theme/                         # 主题配置
├── utils/
│   ├── ImageUtils.kt                  # performUpscale/saveImage (via BackendService)
│   └── LogCapture.kt                  # 日志捕获
└── cpp/
    └── src/main.cpp                   # C++ 后端 HTTP Server (cpp-httplib, port 8081)
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §9 内容，创建独立文件 |
| 2026-06-16 | 更新为当前实际结构: 删除 MainActivity.kt / BackgroundGenerationService.kt / UpscaleBackendManager.kt；新增 core/error/ / service/backend/ / service/http/ / ui/viewmodel/ / ui/orchestrator/ / ui/frontend/ / ui/backend/ 目录；更新 BackendService 描述为 HTTP 中间件 |
