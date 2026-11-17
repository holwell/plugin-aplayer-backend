# APlayer 音乐后端（plugin-aplayer-backend）

一个通用的 Halo 2.x 插件：解析网易云等平台的歌单，把**音源、封面、歌词下载并缓存到服务器本地**，再以 [APlayer](https://github.com/MoePlayer/APlayer) 可直接消费的 JSON 接口对外提供。它不绑定任何特定主题，任何前端只要能读这个接口就能播放。

## 为什么做这个插件

Handsome 等主题的播放器依赖 Typecho 的 Meting 插件路由，Halo 里没有对应实现。常见替代方案（前端直连公共 Meting API）依赖第三方服务、直链会过期、登录态无法保留。本插件的做法是：

- 解析在**服务端**完成，前端只拿本地资源；
- 缓存的是**文件字节**而不是第三方 URL，音频直链过期对本插件无影响；
- 支持传入平台 **Cookie**，可解析私人歌单与会员音质；
- 解析次数可控（手动 / 定时 / 增量），降低风控风险。

## 工作原理

```
配置(歌单ID + Cookie) ──▶ 同步(手动/定时)
                              │
                   Java 用 ProcessBuilder 拉起一次性 node 进程
                              │
                   node 调用 @meting/core 解析 playlist/url/lyric/pic
                              │
                   返回 JSON → Java 下载音频/封面/歌词字节到本地缓存
                              │
                   写 manifest.json（播放清单）
                              │
                   公开只读 API：/playlist + /files/**
                              │
                   前端 APlayer 读取并播放本地资源
```

`@meting/core`（[metowolf/Meting](https://github.com/metowolf/Meting) 的 Node.js 实现）作为类库随插件打包在 `src/main/resources/node/`，运行时解压到插件工作目录执行。插件本身是 Java/Spring 程序，通过外部 `node` 进程调用它——因此**运行 Halo 的机器需要预装 Node.js**（详见「运行环境」）。

## 功能

- 支持平台：网易云 `netease`、QQ 音乐 `tencent`、酷狗 `kugou`、百度 `baidu`、酷我 `kuwo`
- 资源类型：歌单 `playlist`、单曲 `song`、专辑 `album`
- 音质：`128 / 192 / 320` kbps（会员音质取决于 Cookie 与账号权限；以接口实际返回码率为准）
- 缓存：音频 / 封面 / LRC 歌词字节全部本地化
- 同步：控制台「立即同步」按钮、cron 定时、增量（只下载未缓存歌曲）、自动清理已移除歌曲、失败重试、防任务重叠
- 鉴权：Cookie 存入 Halo Secret（配置中只保存 Secret 名称，不明文）；API 通过 Halo RBAC 授权匿名或登录访问
- 兼容：音频接口支持 HTTP Range，播放器可拖动进度条

## 配置项

在 Halo 控制台 → 插件 → APlayer 音乐后端 中设置。

| 分组 | 配置项 | 说明 | 默认值 |
|---|---|---|---|
| 歌单源 | `server` | 平台：`netease` / `tencent` / `kugou` / `baidu` / `kuwo` | `netease` |
| 歌单源 | `type` | 资源类型：`playlist` / `song` / `album` | `playlist` |
| 歌单源 | `id` | 纯数字资源 ID（**不是分享链接**），如 `60198` | 空（必填） |
| 歌单源 | `cookieSecretName` | 存放 Cookie 的 Halo Secret 名称，键为 `cookie`；留空则匿名解析 | 空 |
| 音质与缓存 | `bitrate` | 音质码率 `128` / `192` / `320` | `320` |
| 音质与缓存 | `picSize` | 封面尺寸（像素） | `300` |
| 音质与缓存 | `downloadLyrics` | 是否缓存歌词 | `true` |
| 音质与缓存 | `downloadCover` | 是否缓存封面 | `true` |
| 同步策略 | `syncMode` | `manual`（仅手动）/ `cron`（定时） | `manual` |
| 同步策略 | `cron` | Spring cron，秒 分 时 日 月 周，如 `0 30 3 * * *` | `0 30 3 * * *` |
| 同步策略 | `incremental` | 增量同步：跳过已缓存歌曲 | `true` |
| 同步策略 | `cleanupRemoved` | 同步后清理已不在歌单中的音频/封面/歌词缓存，避免缓存膨胀 | `true` |

「同步策略」分组里的 **立即同步** 按钮可随时手动触发一次同步（无需等待定时任务）。修改任意设置后请先点击页面底部的「保存」，再点此按钮；同步结果会以提示（Toast）反馈。若解析结果为空（如歌单 ID 无效或 Cookie 失效），本次不会清理也不会覆盖已有播放列表，避免误删缓存。
| 访问与鉴权 | `publicRead` | 允许匿名读取播放列表 | `true` |
| 访问与鉴权 | `allowOrigins` | 预留：跨域来源白名单（逗号分隔） | 空 |
| 运行环境 | `nodePath` | Node 可执行文件路径；留空自动探测 PATH、nvm、mise | 空 |

### 获取资源 ID

以网易云歌单为例，打开歌单页，地址栏 `https://music.163.com/#/playlist?id=60198` 中的 `60198` 就是 ID。QQ / 酷狗 / 酷我同理取对应链接里的数字 ID。

### 配置 Cookie（可选）

需要私人歌单或会员音质时：

1. 在 Halo 控制台创建一个 Secret（名称记为 `aplayer-cookie`），新增一个键 `cookie`，值为从音乐平台网页复制的完整 Cookie 字符串。
2. 在插件设置的 `cookieSecretName` 填入该 Secret 名称。

Cookie 会过期或触发风控；失效时同步会失败，更新 Secret 即可。明文 Cookie 不会写进 ConfigMap 或日志。

## API

所有接口前缀为 `/apis/api.aplayer-backend/v1alpha1/`。

### `GET /playlist`

返回 APlayer 可直接使用的格式：

```json
{
  "title": "APlayer Backend",
  "author": "aplayer-backend",
  "syncedAt": "2026-11-17T12:40:40.918630289Z",
  "audio": [
    {
      "name": "歌曲名",
      "artist": "歌手",
      "url": "/apis/api.aplayer-backend/v1alpha1/files/audio/<id>.mp3",
      "cover": "/apis/api.aplayer-backend/v1alpha1/files/cover/<id>.jpg",
      "lrc": "/apis/api.aplayer-backend/v1alpha1/files/lrc/<id>.lrc"
    }
  ]
}
```

所有资源 URL 都指向本地缓存，不含任何第三方链接。

### `GET /status`

同步状态摘要：`server`、`type`、`sourceId`、`syncedAt`、`count`。

### `GET /files/{kind}/{fileName}`

提供缓存文件（`kind` 为 `audio` / `cover` / `lrc`）。音频支持 HTTP Range（206 Partial Content）。

### `POST /sync`

手动触发同步（需要相应权限）。控制台「立即同步」按钮调用的就是此接口。返回同步报告：`success`、`message`、`total`、`downloaded`、`skipped`、`failed`、`cleaned`（`cleaned` 为本次清理的过期缓存文件数）。同步失败时返回 HTTP 409。

### 前端接入示例

```html
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/aplayer/dist/APlayer.min.css">
<script src="https://cdn.jsdelivr.net/npm/aplayer/dist/APlayer.min.js"></script>
<div id="player"></div>
<script>
fetch('/apis/api.aplayer-backend/v1alpha1/playlist')
  .then(r => r.json())
  .then(data => new APlayer({
    container: document.getElementById('player'),
    audio: data.audio
  }));
</script>
```

## 安装

1. 从 [Releases](https://github.com/holwell/plugin-aplayer-backend/releases) 下载 `plugin-aplayer-backend-*.jar`。
2. Halo 控制台 → 插件 → 安装插件 → 上传该 jar 并启用。
3. 在插件设置中填入歌单 `id`（必要时配置 Cookie 与 `nodePath`）。
4. 保存设置后，点击「同步策略」中的「立即同步」（或等待定时任务），完成后即可读取 `/playlist`。

> 注意：直接把 jar 拷进 `plugins/` 目录**不会**被 Halo 加载——Halo 通过数据库中的 Plugin 记录管理插件，必须走正式安装流程。

> 升级插件后请到设置页复核一遍配置：Halo 在重新注册 Setting 时可能把部分字段重置为 schema 默认值（实测 `id`、`nodePath` 会被清空）。重新填好并保存后，再点「立即同步」即可。

## 运行环境

- Halo `>= 2.23.0`（开发与验证环境为 2.24.0 / JDK 21）
- **Node.js >= 12**：用于运行内置 `@meting/core`。`nodePath` 留空时会自动探测 PATH，以及常见的 `~/.nvm/versions/node/*/bin/node`、`~/.local/share/mise/installs/node/*/bin/node`。找不到时同步接口会返回明确错误提示，不会静默失败。
- 磁盘空间：按歌单大小而定，128kbps 一般每首 3–5MB，另加封面与歌词。
- 出站网络：需要能访问对应音乐平台接口。

## 数据与安全

- Cookie 用 Halo `Secret` 保存，配置只保存 Secret 名称；不写入日志、ConfigMap 或公开 API。
- 匿名访问通过插件内置的 RBAC 角色模板（`role-template-anonymous.yaml`）聚合到 Halo 的 `anonymous` 角色实现，只授予本插件 apiGroup 的 `get`/`list`。如需改为仅登录可访问，把设置里的 `publicRead` 关掉即可。
- 缓存文件位于 Halo 工作目录下的 `plugins/aplayer-backend/cache/`，可安全删除后重新同步。

## 从源码构建

```bash
git clone git@github.com:holwell/plugin-aplayer-backend.git
cd plugin-aplayer-backend
./gradlew build          # 产物：build/libs/plugin-aplayer-backend-*.jar
```

开发联调（需要 Docker）：`./gradlew haloServer`，改代码后用 `./gradlew reloadPlugin` 热重载。

项目结构：

```
src/main/
  java/run/halo/aplayerbackend/    Java 端（同步、缓存、API、调度、配置）
  resources/
    plugin.yaml                    插件清单
    extensions/settings.yaml       设置表单
    extensions/role-template-anonymous.yaml  匿名只读 RBAC 模板
    node/parse.js                  Node 解析入口
    node/meting/meting.esm.js      内置 @meting/core
```

## 已知限制

- 当前为单歌单/单资源；多歌单可在 `parse.js` 与清单结构上扩展。
- 音质以平台接口实际返回为准：请求 320 但账号或歌曲不支持时，会返回较低码率。
- 音乐资源涉及版权，请仅在你有权使用的范围内缓存和分发。
- `@meting/core` 依赖平台接口，平台策略变化可能导致解析失败，需更新内置库。

## License

[MIT](LICENSE)
