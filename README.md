# EnChat — 加密聊天应用

原生安卓风格的加密聊天 App：Kotlin + Jetpack Compose + Material3 客户端，Kotlin + Ktor + PostgreSQL 服务端，两端共享 `crypto-core` 密码学与协议模块。

> **本轮交付范围**：架构设计 + 核心加密模块 + 协议约定 + **Ktor 服务端** + **Android 客户端** + 构建与 CI。
> 口令策略已放宽到**字母/数字/符号**（不再限定纯数字）。
>
> ⚠️ `app/` 为 Android 模块，**本轮只做了语法级检查**（环境无 Android SDK）。
> 纯逻辑抽在 `client-core/`，已实际跑通 13 项测试。细节见 `docs/04-客户端实现.md`。

---

## 1. 目录结构

```
.
├── docs/
│   ├── 01-架构设计.md        # 总体架构、加密策略、数据模型、威胁模型、连接状态机
│   ├── 02-协议约定.md        # REST/WebSocket 接口、帧格式、错误码、限速、已知限制
│   ├── 03-服务端实现.md      # 服务端模块结构、运行方式、安全设计、测试覆盖与未覆盖
│   ├── 04-客户端实现.md      # 客户端模块结构、需求落点、已知未完成项、构建踩坑记录
│   └── 05-构建踩坑与修复.md  # 同上（单独抽出，便于检索 CI/Gradle 报错）
├── crypto-core/              # 共享密码学 + 协议模块（Android 与服务端复用）
│   └── src/main/kotlin/com/enchat/
│       ├── crypto/
│       │   ├── Encoding.kt        # 字节工具、常数时间比较、擦除、大端读写
│       │   ├── Primitives.kt      # X25519 / Ed25519 / HKDF-SHA256 / HMAC / AES-256-GCM
│       │   ├── Keys.kt            # 身份密钥、签名预密钥、一次性预密钥、公钥包
│       │   ├── X3dh.kt            # X3DH-lite 协商 + Safety Number
│       │   ├── Ratchet.kt         # Hash 链棘轮 + 乱序/重放处理
│       │   ├── E2eeSession.kt     # 1:1 端到端会话（含序列化）
│       │   ├── TransportCrypto.kt # 客户端↔服务器传输层信封加密
│       │   ├── GroupCrypto.kt     # 群聊 Sender-Key
│       │   └── PasswordHash.kt    # Argon2id + pepper 口令哈希
│       └── protocol/Protocol.kt   # 协议版本、帧类型、错误码、连接状态、加密模式
│   └── src/test/kotlin/...        # 73 项单元测试（含 RFC/参考向量校验）
├── server/                   # Ktor 服务端（WebSocket + REST，H2/PG 双 DDL）
│   └── src/main/kotlin/com/enchat/server/  # 装配、配置、数据层、HTTP、WebSocket
│   └── src/test/kotlin/...        # 15 项端到端集成测试（真实启动 Netty）
├── client-core/              # 纯 Kotlin 客户端逻辑（连接状态机、会话列表解析）→ 13 项单测
├── app/                      # Android 客户端（Compose + Material3，原生安卓风格）
│   └── src/main/java/com/enchat/app/  # DI、Keystore、Room、OkHttp/WS、Repository、UI
├── .github/workflows/ci.yml  # GitHub Actions：编译 + 测试 + 上传测试报告
├── gradle/libs.versions.toml # 依赖版本目录
├── settings.gradle.kts / build.gradle.kts / crypto-core/build.gradle.kts
└── build.sh                  # 离线构建脚本（无 Gradle 环境时使用）
```

---

## 2. 加密方案速览

| 维度 | 方案 |
|---|---|
| 默认加密（客户端↔服务器） | TLS 1.3 **+** 应用层信封加密（X25519 握手 + HKDF 双向密钥 + AES-256-GCM，seq 严格递增防重放）；服务器解密后明文落库 |
| 高级加密（端到端） | X3DH-lite（长期身份 DH + 临时密钥 DH + SPK/OPK + 双向签名）→ Hash 链棘轮 → 每消息一次性 AES-256-GCM 密钥 |
| 群聊 | Sender-Key：每发送者每群一条链，链密钥经双边会话加密分发；成员变动必须 `epoch+1` 并轮换 |
| 口令 | 字母/数字/符号 8–64 位；Argon2id（64MiB/t=3/p=1）+ 服务端 pepper（HMAC-SHA256，存 KMS） |
| 前向保密 | ✅ 消息密钥用过即擦除，链单向前进 |
| 后向保密 | ❌ 无 DH 棘轮；需重建会话恢复（取舍原因见架构文档 §3.2） |

---

## 3. 构建与测试

### 方式 A：Gradle（推荐）

要求 JDK 17 + Gradle 8.9（仓库未提交 `gradle-wrapper.jar`，可自行 `gradle wrapper --gradle-version 8.9` 后改用 `./gradlew`）。

```bash
gradle :crypto-core:build --no-daemon   # 核心加密模块（73 项单测）
gradle :server:test --no-daemon         # 服务端（15 项集成测试，真实启动 Netty + H2）
gradle :server:installDist --no-daemon  # 构建可执行包
gradle :client-core:build  --no-daemon  # 客户端纯逻辑（13 项单测）
gradle :app:assembleDebug  --no-daemon  # Android APK（需 Android SDK 34）
```

构建 Android 前**必须**把服务端启动时打印的长期公钥填入 `app/build.gradle.kts`
的 `SERVER_ED25519_PUB_B64` / `SERVER_X25519_PUB_B64`，否则 App 会拒绝连接（这是刻意的）。

启动前建议先设置（生产必填）：

```bash
export ENCHAT_JDBC_URL='jdbc:postgresql://localhost:5432/enchat'
export ENCHAT_DB_USER=enchat
export ENCHAT_DB_PASSWORD='...'
export ENCHAT_PEPPER_B64='...'          # KMS 提供，32 字节 base64
export ENCHAT_PUBLIC_BASE_URL='https://chat.example.com'
```

启动后会打印服务器长期公钥，**必须** pinning 进客户端（详见 `docs/03-服务端实现.md`）。

### 方式 B：离线脚本（无 Gradle / 无外网 Maven 时）

`build.sh` 用预下载的 jar 直接调用 Kotlin 编译器与 JUnit Console Launcher：

```bash
# 1) 准备依赖到 libs/（JDK 11+ 即可，jar 可从任意 Maven 镜像获取）
#    kotlin-compiler-1.9.24、kotlin-stdlib、kotlin-reflect、kotlin-script-runtime、
#    trove4j-1.0.20200330、annotations-24.1.0、bcprov-jdk18on-1.78.1、
#    junit-platform-console-standalone-1.10.2
# 2）
./build.sh
```

### 测试结果（本轮实际执行）

```
70 tests found / 70 started / 70 successful / 0 failed
```

单测覆盖：RFC 7748（X25519）、RFC 8032（Ed25519）、RFC 5869（HKDF）、AES-GCM 与 JCE 互操作、
Argon2id 参考向量、X3DH 协商一致性与伪造拒绝、棘轮 1000 条往返、乱序/重放/超限窗口、
传输层握手与 MITM 拒绝、群 Sender-Key 与 epoch 轮换、口令策略、协议枚举。

---

## 4. 验证状态（如实标注）

**已验证（实际运行）**
- 测试环境 JDK 11 + Kotlin 1.9.24 + BouncyCastle 1.78.1 + JUnit 5.10.2
- 全部 73 项单元测试通过；底层原语与 RFC 官方向量逐字节一致，AES-GCM 与 JDK JCE 双向互操作
- X3DH 双方派生密钥一致；伪造 SPK/签名/预密钥 id 不匹配均被拒绝
- 传输层中间人（用假服务器密钥签名）被客户端拒绝
- 前向保密：持有"当前会话状态"无法解密已消费的历史消息

**客户端纯逻辑（真实执行）**
- `client-core` 13 项单元测试通过：连接状态机文案（连接中…/正在等待网络…）、重连退避封顶与抖动、
  开启高级加密后会话条目由 1 变 2、弹窗文案逐字比对、同一好友只弹一次

**服务端（真实执行）**
- `server` 15 项端到端集成测试全绿：真实启动 Ktor/Netty + H2 内存库 + 真实 WebSocket
- 覆盖：标准模式明文投递与落库、端到端双向消息且服务端只见密文、明文降级被拒、乱序投递、
  群聊 Sender-Key 分发与 `epoch+1` 轮换、离线消息同步、好友增删、登录限速、未鉴权帧被拒、
  客户端持有错误服务器公钥时握手失败
- 口令策略放宽后的注册、登录与存储校验

**未验证（本轮未做，需后续补齐）**
- **Gradle 脚本与 GitHub Actions 工作流未实际执行**（当前环境无 Gradle、外网 Maven 受限），按 Gradle 8.9 / Kotlin 1.9.24 标准写法静态编写
- PostgreSQL 实机未跑（DDL 分两套维护，H2 版已验证）
- **`app/` 模块未编译、未真机验证**：环境无 Android SDK 与 Google Maven，仅做语法级检查（纯语法错误 0，1238 处符号未解析均为缺失依赖所致）
- 群聊 UI、头像图片选择器、推送通知、本地库加密（SQLCipher）未完成
- 未做并发压测、多实例部署验证
- 未做第三方密码学审计

**已知风险**
1. **服务器长期公钥必须 pinning**：否则传输层加密可被中间人绕过（`TransportCrypto.ServerStaticKeys`）
2. **服务器可整包替换用户公钥包**实施主动 MITM，只能靠 Safety Number 带外核验发现
3. **弱口令仍可能存在**：口令已放宽到字母/数字/符号（8–64 位），熵显著高于纯数字；但内置黑名单只覆盖几十条，生产建议接入 HIBP k-anonymity 查询，并配套登录限速与风控
4. 无后向保密；无多设备密钥同步；E2EE 不隐藏元数据
5. `bcprov` 约 8MB，Android 端必须开 R8 裁剪
6. 群成员移除后若忘记 `epoch+1`，离开者仍可解密后续消息（代码已强制校验 epoch，但服务端分发流程需严格落实）

---

## 5. 下一步

1. 用 Android SDK 首次构建 `app/`，修正可能的 API 名/版本不匹配（详见 `docs/05-构建踩坑与修复.md` §6.7）
2. 客户端 ↔ 服务端真机联调：传输层握手、端到端会话、群聊 epoch 轮换、弱网与断线重连
3. 群聊 UI、头像图片选择器（Photo Picker）、推送通知、SQLCipher 加密本地库
4. PostgreSQL 实机验证、Flyway 迁移、多实例限速（Redis）、TLS 终止、发布签名配置
