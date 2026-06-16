# FILE-TARG-0001: 目标文件结构

| 属性 | 值 |
|------|-----|
| 分类 | Target File Structure |
| 对应章节 | §13 |
| 依赖 | ARCH-TARG-0001 |

## 目标目录结构

```
app/src/main/java/io/github/dreamandroid/local/
├── DreamAndroidApplication.kt          # DI 容器，持有全部 Service 引用
│
├── core/                               # 核心接口与类型
│   ├── error/
│   │   └── AppError.kt                 # 统一错误密封类
│   └── model/
│       ├── GenerateParams.kt           # 生成参数 DTO
│       ├── QueueModels.kt              # GenerationTask/BatchGroupDisplay
│       └── Constants.kt                # 全局常量 (端口、路径等)
│
├── service/                            # 服务层（业务逻辑）
│   ├── backend/
│   │   ├── BackendManager.kt           # 统一后端进程管理器 (C++ 进程唯一管理者)
│   │   ├── BackendService.kt           # HTTP 中间件 (UI 层边界，薄代理)
│   │   └── RuntimeDirPreparer.kt       # QNN 运行时准备
│   ├── queue/
│   │   ├── QueueProcessingService.kt   # 队列处理前台服务
│   │   ├── QueueRepository.kt          # 队列状态管理
│   │   └── SseStreamParser.kt          # SSE 流解析器
│   ├── http/
│   │   └── HttpClientProvider.kt       # OkHttpClient 单例工厂
│   └── download/
│       └── ModelDownloadService.kt     # 模型下载服务
│
├── data/                               # 数据层
│   ├── db/
│   │   ├── AppDatabase.kt             # Room Database
│   │   ├── HistoryDao.kt              # 历史记录 DAO
│   │   ├── QueueDao.kt                # 队列持久化 DAO
│   │   └── ModelDao.kt                # 模型元数据 DAO
│   ├── repository/
│   │   ├── HistoryRepository.kt       # 历史记录仓库
│   │   ├── ModelRepository.kt         # 模型仓库 (SSOT: Room)
│   │   └── PreferencesManager.kt      # DataStore 统一管理
│   └── entity/
│       ├── HistoryEntity.kt
│       ├── QueueEntity.kt
│       └── ModelEntity.kt
│
├── ui/                                 # 表现层
│   ├── MainActivity.kt                 # 入口 Activity（轻量，仅导航）
│   ├── navigation/
│   │   └── Navigation.kt              # 路由定义
│   ├── viewmodel/
│   │   ├── MainViewModel.kt
│   │   ├── ModelsViewModel.kt
│   │   ├── GenerateViewModel.kt
│   │   ├── QueueViewModel.kt
│   │   ├── UpscaleViewModel.kt
│   │   └── BrowseViewModel.kt
│   ├── screens/
│   │   ├── models/ / generate/ / queue/ / upscale/ / browse/
│   ├── components/                     # 通用 Compose 组件
│   └── theme/                          # Material 3 主题
│
└── utils/
    ├── ImageUtils.kt                   # Bitmap 处理工具
    └── LogCapture.kt                   # 日志捕获
```

## 变更历史

| 日期 | 描述 |
|------|------|
| 2026-06-15 | 从 PrdReqDoc.md 提取 §13 内容，创建独立文件 |
| 2026-06-16 | service/backend/ 新增 BackendService.kt (HTTP 中间件) |
