# ---------- 构建阶段 ----------
# 用 Gradle 官方镜像，避免本地 JDK/Gradle 版本差异。
FROM gradle:8.9-jdk17 AS build

WORKDIR /build

# 先只复制构建脚本，利用 Docker 层缓存：改源码不会重新下载依赖
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# ⚠️ :app 在 settings.gradle.kts 里是「检测到 Android SDK 才 include」。
# 这里没有 ANDROID_HOME，因此会被自动跳过，不会拖累构建。
COPY crypto-core ./crypto-core
COPY client-core ./client-core
COPY server ./server

# 只构建服务端分发包（启动脚本 + 依赖 jar）
RUN gradle :server:installDist --no-daemon --console=plain \
    && ls -l /build/server/build/install/server/bin

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r enchat \
    && useradd -r -g enchat -d /opt/enchat -s /usr/sbin/nologin enchat

WORKDIR /opt/enchat
COPY --from=build /build/server/build/install/server/ /opt/enchat/

# 密钥与头像目录必须持久化到卷：
#   - server-identity.key 丢了/重建 = 所有已安装客户端永久连不上（pin 校验失败）
#   - uploads 丢了只是头像没了，不影响聊天
RUN mkdir -p /var/lib/enchat/keys /var/lib/enchat/uploads \
    && chown -R enchat:enchat /var/lib/enchat

USER enchat

EXPOSE 8080

# Argon2id 参数为 64 MiB / t=3，每次口令派生都要一块 64 MiB 堆内存。
# 并发注册/登录数 N 大致需要 N×64MiB 的额外堆，故 Xmx 给到 1.5g，容器内存建议 ≥2g。
ENV ENCHAT_HOST=0.0.0.0 \
    ENCHAT_PORT=8080 \
    ENCHAT_SERVER_KEY_FILE=/var/lib/enchat/keys/server-identity.key \
    ENCHAT_UPLOAD_DIR=/var/lib/enchat/uploads \
    JAVA_OPTS="-Xms512m -Xmx1500m -XX:+UseG1GC -Dfile.encoding=UTF-8"

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["/opt/enchat/bin/server"]
