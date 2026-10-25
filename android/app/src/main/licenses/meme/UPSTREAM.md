# 表情包搜索来源与实现边界

- 参考项目：https://github.com/o98k-ok/meme
- 核对版本：`eb9631a3858fb978d64ae801ec95dd726735411d`（2026-10-19 核查 main）
- 参考文件：`internal/sources/api.go` 的搜狗搜索字段与图片元数据结构。
- 上游 README 声明：`License: MIT`。该固定版本文件树没有独立 LICENSE 文件，因此未复制 Go 源码或引入其二进制，也不虚构版权人或许可文件。
- VoxFix 独立实现 Kotlin 网络查询、公开页面 JSON 解析、图片提交与前端面板。旧 `/napi/pc/searchList` 实测返回 `forbid`；改为读取正常公开 `/pics` 页面中的 `window.__INITIAL_STATE__`，只解析 JSON，不执行页面脚本。
- 图片来自搜狗图片索引，不属于 VoxFix 或该开源项目的 MIT 授权范围。保留“搜狗图片”来源显示；第三方页面、图片与接口条件可能变化。
- 固定分区是关键词检索；搜狗相关分类来自每次结果的 `tagList`。没有接入搜狗输入法内部栏目或热榜。
