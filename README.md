# 校园社团活动发布与报名 App

西南交通大学校园社团活动的一站式发布与报名系统。学生浏览并报名活动，社团组织者发起活动，管理老师负责审核，全流程带二维码扫码报名、图片上传与报名提醒。

> Kotlin + Ktor 单体后端，同一个端口同时托管 REST API、上传资源和原生 JS 前端单页应用，零前端构建步骤，开箱即跑。

## 功能特性

**学生**
- 浏览已发布活动，查看剩余名额与报名截止时间
- 一键报名 / 取消报名，查看「我的报名」
- 上传自定义头像，设置活动提醒
- 扫描二维码直接报名

**社团组织者**
- 创建活动（名称、地点、容量、报名截止、起止时间、封面图）
- 修改活动信息、延长截止时间、增加名额、取消活动
- 查看某活动的报名名单
- 生成活动报名二维码

**社团管理老师**
- 审核活动（通过 / 驳回）
- 查看全局统计

**通用**
- 头像与活动封面图片上传（本地存储，限 3MB，支持 png/jpg/gif/webp/bmp/svg）
- 基于角色的接口鉴权（STUDENT / ORGANIZER / TEACHER）
- 统一错误响应与请求日志

## 技术栈

| 层次 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.1.0（JVM Toolchain 17） |
| 后端框架 | Ktor 2.3.12（Netty 引擎） |
| 序列化 | kotlinx.serialization JSON |
| Ktor 插件 | ContentNegotiation / CORS / StatusPages / CallLogging / DefaultHeaders / staticResources |
| 二维码 | Google ZXing 3.5.3 |
| 日志 | Logback 1.5.6 |
| 前端 | 原生 HTML + CSS + ES6 JS，hash 路由 SPA，无框架无构建 |
| 数据存储 | 本地 CSV 文件（`data/` 目录，首次启动自动写入种子数据） |
| 构建 | Gradle 8.10.2（wrapper 走腾讯云镜像） |

## 快速开始

### 环境要求

- JDK 17（项目配置了 `jvmToolchain(17)`）

### 启动

Windows 下直接双击 `run.bat`，或命令行：

```powershell
.\gradlew.bat run
```

脚本会自动把 `GRADLE_USER_HOME` 指向项目内的 `.gradle-home`，无需单独安装 Gradle。

启动后访问 <http://localhost:8080>。

### 其他命令

```powershell
.\gradlew.bat build   # 编译 + 打包（对应 build.bat）
.\gradlew.bat test    # 运行测试
```

### 端口配置

端口与监听地址由环境变量控制，默认 `0.0.0.0:8080`：

```powershell
$env:PORT=9000; $env:HOST="0.0.0.0"; .\gradlew.bat run
```

局域网访问时需在防火墙放行 TCP 8080，并把网络配置文件类别设为「专用」。

## 演示账号

首次启动会自动写入以下种子数据（密码均为 `123456`）：

| 角色 | 用户名 | 姓名 |
| --- | --- | --- |
| 社团管理老师 | `teacher01` | 王老师 |
| 组织者 | `guitarclub` | 李思远（吉他社） |
| 组织者 | `acmclub` | 张一鸣（ACM 协会） |
| 组织者 | `basketballclub` | 王强（篮球社） |
| 组织者 | `litclub` | 赵文博（文学社） |
| 学生 | `20210001` | 陈晓明 |
| 学生 | `20210002` | 刘雨欣 |

内置活动编号 `A-1001` ~ `A-1006`。

## 目录结构

```
src/main/kotlin/edu/swjtu/campus/
├── Application.kt              # 入口：端口、插件安装、路由装配
├── auth/AuthService.kt         # 内存 Token 鉴权、角色校验
├── data/
│   ├── CsvTable.kt             # 通用 CSV 读写
│   ├── Repositories.kt         # 各实体仓储
│   └── Store.kt                # 数据容器 + 种子数据
├── model/                      # 实体与 DTO
├── routes/ApiRoutes.kt         # 全部 REST 接口
└── service/QrService.kt        # 二维码生成

src/main/resources/
├── static/                     # 前端 SPA（index.html / css / js / images）
└── logback.xml

src/test/kotlin/.../CampusAppTest.kt   # 集成测试
```

## API 一览

所有接口以 `/api` 为前缀，除登录注册外均需 `Authorization: Bearer <token>`。

| 方法 | 路径 | 说明 | 角色 |
| --- | --- | --- | --- |
| POST | `/auth/login` | 登录 | 公开 |
| POST | `/auth/register` | 注册 | 公开 |
| POST | `/auth/logout` | 登出 | 登录 |
| GET | `/auth/me` | 当前用户 | 登录 |
| POST | `/auth/avatar` | 上传头像 | 登录 |
| POST | `/uploads` | 上传图片 | 登录 |
| GET | `/activities` | 活动列表 | 公开 |
| GET | `/activities/{id}` | 活动详情 | 公开 |
| PUT | `/activities/{id}` | 修改活动 | 组织者 |
| POST | `/activities/{id}/cancel` | 取消活动 | 组织者 |
| POST | `/activities/{id}/review` | 审核活动 | 老师 |
| GET | `/activities/{id}/registrations` | 报名名单 | 组织者 |
| GET | `/activities/{id}/qrcode.png` | 二维码图片 | 公开 |
| DELETE | `/registrations/{id}` | 取消报名 | 学生 |
| GET | `/registrations/mine` | 我的报名 | 学生 |
| GET | `/reminders/mine` | 我的提醒 | 登录 |
| DELETE | `/reminders/{id}` | 删除提醒 | 登录 |
| GET | `/stats` | 统计 | 老师 |
| GET | `/scan/{token}` | 扫码报名 | 公开 |

上传资源通过 `/uploads/{文件名}` 访问。

## 部署

项目为单体应用，前端与 API 同源，无需跨域配置即可运行。

- **局域网演示**：`HOST=0.0.0.0` 后同网段设备访问 `http://<本机IP>:8080`
- **公网访问**：使用 Cloudflare Tunnel 内网穿透，无需公网 IP 与入站端口
  ```powershell
  cloudflared tunnel --url http://localhost:8080
  ```
- **前后端分离**：`src/main/resources/static/config.js` 中的 `backend` 留空为同源模式；填后端地址则前端跨域直连（Ktor 已通过 `install(CORS)` 放开许可）

## 测试

```powershell
.\gradlew.bat test
```

13 个集成测试，覆盖登录鉴权、角色权限、活动发布审核全流程、满员拦截、扫码报名、二维码生成、活动变更、提醒、我的报名、CSV 持久化、图片上传与格式校验等场景。

## 说明

本项目为课程实践项目，采用本地 CSV 存储与内存 Token 鉴权，适合教学演示与小规模使用。若需生产部署，建议将存储迁移至 PostgreSQL/MySQL，并把鉴权替换为 JWT 或 OAuth2，图片改为对象存储。
