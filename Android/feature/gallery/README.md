# Gallery module

`feature:gallery` 是独立的相册功能模块，采用 MVVM 和分层结构：

- `data`：通过 `MediaStore.Files` 查询设备图片和视频，并在 IO 调度器执行。
- `domain`：定义统一的 `GalleryMedia`、媒体类型、仓库接口和用例，不依赖 Compose。
- `presentation`：`GalleryViewModel` 以 `StateFlow` 暴露单一 UI 状态，Compose 只渲染状态并转发事件。

应用层只需依赖模块并调用：

```kotlin
GalleryRoute(modifier = Modifier.fillMaxSize())
```

模块自行处理 Android 12 及以下的存储权限、Android 13 图片/视频权限，以及 Android 14
的部分媒体访问权限。当前能力包括：

- 静态图片以及平台支持的 HEIF、RAW 等图片格式；
- GIF 和动态 WebP 播放；
- 视频缩略图、时长标识及基于 Media3 的应用内播放；
- 媒体网格、刷新、空态、错误重试和全屏预览。

## Chinese-CLIP 图片搜索

模块依赖 `:core:clip` 完成图片编码和文本检索。gallery 只负责选择最近 100 张图片，
使用媒体 URI 作为跨模块 ID，并将排序结果映射回 `GalleryMedia`；视频不会进入 CLIP 索引。
