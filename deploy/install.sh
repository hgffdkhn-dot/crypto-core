#!/usr/bin/env bash
#
# EnChat 服务端「从分发包安装」脚本 —— 在没有 Docker 的服务器上直接装成 systemd 服务。
#
# 适用：已经把 CI 产出的 enchat-server-dist.zip（或 tar.gz）上传到服务器之后。
#
# 用法：
#   sudo ./install.sh /path/to/enchat-server-dist.zip
#   sudo ./install.sh /path/to/enchat-server-main.tar.gz
#   sudo ./install.sh /path/to/server/build/install/server   # 已解压的目录
#
# 会依次做：
#   1. 解压分发包到 /opt/enchat
#   2. 检测/安装 Java 17
#   3. 检测/安装 PostgreSQL，建库建角色（口令自动生成）
#   4. 生成 Argon2 pepper（已存在则复用，绝不覆盖）
#   5. 创建 enchat 系统用户、密钥与上传目录
#   6. 写 /etc/enchat/enchat.env（600）
#   7. 写 systemd unit 并启动
#   8. 等待 /health 就绪，打印服务器长期公钥
#
# 幂等：重复执行不会重置数据库口令、pepper 与服务器身份密钥。
# 不会做：Nginx / TLS / 域名（需要域名，见第 8 步之后的提示）。
set -Eeuo pipefail

SRC="${1:-}"
[ -n "$SRC" ] || { echo "用法: $0 <分发包路径 .zip|.tar.gz|已解压目录>"; echo "      （需要 root；若已是 root 不必加 sudo）"; exit 1; }

INSTALL_DIR=/opt/enchat
ENV_DIR=/etc/enchat
ENV_FILE="$ENV_DIR/enchat.env"
KEY_DIR=/var/lib/enchat/keys
UPLOAD_DIR=/var/lib/enchat/uploads
SERVICE=/etc/systemd/system/enchat.service

log()  { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

# 以 postgres 用户执行 SQL。
# ⚠️ 云镜像常见「没预装 sudo」（GCP/AWS 的最小镜像尤其如此），
# 直接写 sudo -u postgres 会卡死。这里优先 sudo，没有就回落到 su。
pgsql() {
  if command -v sudo >/dev/null 2>&1; then
    sudo -u postgres psql "$@"
  else
    su -s /bin/sh postgres -c "psql $(printf '%q ' "$@")"
  fi
}

pgsql_once() {
  local sql="$1"
  if command -v sudo >/dev/null 2>&1; then
    sudo -u postgres psql -v ON_ERROR_STOP=1 -c "$sql"
  else
    su -s /bin/sh postgres -c "psql -v ON_ERROR_STOP=1 -c $(printf '%q' "$sql")"
  fi
}

# ---------- 0. 预检 ----------
log "0. 环境预检"
[ "$(id -u)" = 0 ] || die "请用 root 或 sudo 运行"
command -v systemctl >/dev/null 2>&1 || die "本机不是 systemd 系统（该脚本依赖 systemd）"

# Debian/Ubuntu 系检查；其他发行版提示自行安装依赖
if command -v apt-get >/dev/null 2>&1; then
  PKG=apt-get
elif command -v dnf >/dev/null 2>&1; then
  PKG=dnf
elif command -v yum >/dev/null 2>&1; then
  PKG=yum
else
  PKG=""
  warn "未识别的包管理器。请自行确保已安装 Java 17 与 PostgreSQL，脚本会尝试继续。"
fi

# ---------- 1. 解压分发包 ----------
log "1. 解压分发包到 $INSTALL_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ -d "$SRC" ]; then
  # 已是目录：找到含 bin/server 的那层
  if [ -x "$SRC/bin/server" ]; then
    SRC_DIR="$SRC"
  else
    SRC_DIR="$(find "$SRC" -type f -name server -path '*/bin/*' -print -quit 2>/dev/null | xargs -r dirname | xargs -r dirname)"
    [ -n "$SRC_DIR" ] || die "在 $SRC 下找不到 bin/server"
  fi
elif [[ "$SRC" == *.zip ]]; then
  command -v unzip >/dev/null 2>&1 || $PKG install -y unzip >/dev/null 2>&1 || die "需要 unzip"
  unzip -q "$SRC" -d "$TMP"
  # Actions 的 artifact 会保留 server/build/install/server/ 这一长串路径，
  # 也有可能是直接打包的根。统一按文件名定位，避免层级不确定。
  HIT="$(find "$TMP" -type f -name server -path '*/bin/*' -print -quit 2>/dev/null || true)"
  [ -n "$HIT" ] || die "zip 里找不到 bin/server。内容：$(find "$TMP" -maxdepth 4 | head -20)"
  SRC_DIR="$(dirname "$(dirname "$HIT")")"
elif [[ "$SRC" == *.tar.gz || "$SRC" == *.tgz ]]; then
  tar xzf "$SRC" -C "$TMP"
  HIT="$(find "$TMP" -type f -name server -path '*/bin/*' -print -quit 2>/dev/null || true)"
  [ -n "$HIT" ] || { tar tzf "$SRC" | head -20; die "tar.gz 里找不到 bin/server"; }
  SRC_DIR="$(dirname "$(dirname "$HIT")")"
else
  die "无法识别的分发包格式: $SRC（支持 .zip / .tar.gz / 已解压目录）"
fi

ok "找到分发内容: $SRC_DIR"
mkdir -p "$INSTALL_DIR"
cp -a "$SRC_DIR/." "$INSTALL_DIR/"
chmod +x "$INSTALL_DIR/bin/"*
[ -x "$INSTALL_DIR/bin/server" ] || die "$INSTALL_DIR/bin/server 不可执行"
ok "已安装到 $INSTALL_DIR"

# ---------- 2. Java 17 ----------
log "2. Java 17"
if java -version 2>&1 | grep -qE 'version "1[7-9]|version "2[0-9]'; then
  ok "已安装: $(java -version 2>&1 | head -1)"
else
  warn "未检测到 Java 17+，尝试安装..."
  case "$PKG" in
    apt-get) apt-get update -qq && apt-get install -y -qq openjdk-17-jre-headless ;;
    dnf|yum) $PKG install -y java-17-openjdk-headless ;;
    *) die "请手动安装 Java 17 后重跑本脚本" ;;
  esac
  java -version 2>&1 | grep -qE 'version "1[7-9]|version "2[0-9]' || die "Java 17 安装失败"
  ok "已安装: $(java -version 2>&1 | head -1)"
fi

# ---------- 3. PostgreSQL ----------
log "3. PostgreSQL"
PG_UP=0
if command -v psql >/dev/null 2>&1; then
  if systemctl is-active --quiet postgresql 2>/dev/null || pg_isready -q 2>/dev/null; then
    PG_UP=1; ok "PostgreSQL 已在运行"
  else
    systemctl enable --now postgresql >/dev/null 2>&1 && PG_UP=1 && ok "已启动 PostgreSQL" || warn "PostgreSQL 存在但未启动"
  fi
fi
if [ "$PG_UP" = 0 ]; then
  warn "未检测到 PostgreSQL，尝试安装..."
  case "$PKG" in
    apt-get) apt-get update -qq && apt-get install -y -qq postgresql postgresql-contrib ;;
    dnf|yum) $PKG install -y postgresql-server postgresql-contrib && postgresql-setup --initdb ;;
    *) die "请手动安装 PostgreSQL 后重跑本脚本" ;;
  esac
  systemctl enable --now postgresql >/dev/null 2>&1 || die "PostgreSQL 启动失败，请手动排查"
  ok "PostgreSQL 已安装并启动"
fi

# 建库建角色（幂等）
DB_PASS_FILE="$ENV_DIR/.db_password"
mkdir -p "$ENV_DIR"; chmod 700 "$ENV_DIR"

if pgsql -tAc "SELECT 1 FROM pg_roles WHERE rolname='enchat'" 2>/dev/null | grep -q 1; then
  ok "数据库角色 enchat 已存在，复用"
  [ -f "$DB_PASS_FILE" ] || { warn "角色已存在但本机没有保存过口令 —— 若不知道旧口令，可用下面的命令重置：";
    warn "  psql -c \"ALTER ROLE enchat WITH PASSWORD '新口令'\"（以 postgres 用户执行）"; }
else
  DB_PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"
  psql_once "CREATE ROLE enchat LOGIN PASSWORD '$DB_PASS';"
  echo "$DB_PASS" > "$DB_PASS_FILE"; chmod 600 "$DB_PASS_FILE"
  ok "已创建角色 enchat，口令保存到 $DB_PASS_FILE"
fi

if pgsql -tAc "SELECT 1 FROM pg_database WHERE datname='enchat'" 2>/dev/null | grep -q 1; then
  ok "数据库 enchat 已存在，复用"
else
  pgsql_once "CREATE DATABASE enchat OWNER enchat;"
  ok "已创建数据库 enchat"
fi

DB_PASS="$(cat "$DB_PASS_FILE" 2>/dev/null || echo '')"
[ -n "$DB_PASS" ] || die "缺少数据库口令。请写入 $DB_PASS_FILE 或手动重置角色口令"

# ---------- 4. pepper ----------
log "4. Argon2 pepper"
if [ -f "$ENV_FILE" ] && grep -q '^ENCHAT_PEPPER_B64=.\+' "$ENV_FILE"; then
  ok "复用已有的 pepper（绝不覆盖：换 pepper 会导致老用户无法登录）"
  # shellcheck disable=SC1090
  set -a; . "$ENV_FILE"; set +a
else
  PEPPER="$(openssl rand -base64 32)"
  DOMAIN_PLACEHOLDER="https://chat.example.com"
  cat > "$ENV_FILE" <<EOF
ENCHAT_HOST=127.0.0.1
ENCHAT_PORT=8080

ENCHAT_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/enchat
ENCHAT_DB_USER=enchat
ENCHAT_DB_PASSWORD=$DB_PASS
ENCHAT_DB_POOL=8

# ⚠️ 上线后绝不可更改：改了所有老用户都无法登录
ENCHAT_PEPPER_B64=$PEPPER

# 服务器长期身份密钥。必须备份；丢失 = 已装客户端全部连不上
ENCHAT_SERVER_KEY_FILE=$KEY_DIR/server-identity.key
ENCHAT_UPLOAD_DIR=$UPLOAD_DIR

ENCHAT_PUBLIC_BASE_URL=$DOMAIN_PLACEHOLDER
ENCHAT_E2EE_RETENTION_DAYS=30

JAVA_OPTS=-Xms512m -Xmx1500m -XX:+UseG1GC -Dfile.encoding=UTF-8
EOF
  chmod 600 "$ENV_FILE"
  ok "已生成 $ENV_FILE"
  echo ""
  echo "  ⚠️  立刻把下面的 pepper 备份到密码管理器（只显示这一次）："
  echo "      $PEPPER"
fi

# ---------- 5. 用户与目录 ----------
log "5. 系统用户与目录"
id -u enchat >/dev/null 2>&1 || useradd -r -d "$INSTALL_DIR" -s /usr/sbin/nologin enchat
mkdir -p "$KEY_DIR" "$UPLOAD_DIR"
chown -R enchat:enchat "$KEY_DIR" "$UPLOAD_DIR" "$INSTALL_DIR"
chmod 700 "$KEY_DIR"
chown enchat:enchat "$ENV_FILE"; chmod 600 "$ENV_FILE"
ok "enchat 用户与目录就绪"

# ---------- 6. systemd ----------
log "6. systemd 服务"
cat > "$SERVICE" <<'UNIT'
[Unit]
Description=EnChat Server
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
Type=simple
User=enchat
Group=enchat
EnvironmentFile=/etc/enchat/enchat.env
ExecStart=/opt/enchat/bin/server
WorkingDirectory=/opt/enchat
Restart=on-failure
RestartSec=5

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
RestrictNamespaces=true
ReadWritePaths=/var/lib/enchat/keys /var/lib/enchat/uploads
LockPersonality=true

LimitNOFILE=65536
TimeoutStartSec=120

StandardOutput=journal
StandardError=journal
SyslogIdentifier=enchat

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable enchat >/dev/null 2>&1
ok "已写入 $SERVICE"

# ---------- 7. 启动 ----------
log "7. 启动服务"
systemctl restart enchat

for i in $(seq 1 40); do
  if curl -fsS --max-time 3 http://127.0.0.1:8080/health >/dev/null 2>&1; then
    ok "服务已就绪（第 ${i} 次探测）"
    break
  fi
  if [ "$i" = 40 ]; then
    journalctl -u enchat -n 60 --no-pager
    die "服务未就绪。以上为日志，常见问题：数据库口令错误 / JDBC 串不对 / 内存不足"
  fi
  sleep 3
done
echo "  /health -> $(curl -fsS --max-time 5 http://127.0.0.1:8080/health)"

# ---------- 8. 公钥 ----------
log "8. 服务器长期公钥（客户端必须 pinning）"
PUB_ED="$(journalctl -u enchat --no-pager 2>/dev/null | grep -oE 'ed25519=[A-Za-z0-9+/=]+' | tail -1 | cut -d= -f2- || true)"
PUB_X="$(journalctl -u enchat --no-pager 2>/dev/null | grep -oE 'x25519 *= *[A-Za-z0-9+/=]+' | tail -1 | sed 's/.*= *//' || true)"

if [ -z "$PUB_ED" ] || [ -z "$PUB_X" ]; then
  warn "未能从日志解析公钥（密钥可能是本次之前生成的）。手动查看："
  warn "  journalctl -u enchat --no-pager | head -20"
else
  echo ""
  echo "  ed25519 = $PUB_ED"
  echo "  x25519  = $PUB_X"
  echo ""
  echo "  填进 app/build.gradle.kts 后重新编译客户端："
  echo "    buildConfigField(\"String\", \"DEFAULT_HOST\",           \"\\\"https://你的域名\\\"\")"
  echo "    buildConfigField(\"String\", \"SERVER_ED25519_PUB_B64\", \"\\\"$PUB_ED\\\"\")"
  echo "    buildConfigField(\"String\", \"SERVER_X25519_PUB_B64\",  \"\\\"$PUB_X\\\"\")"
fi

# ---------- 完成 ----------
log "安装完成"
cat <<EOF

  安装目录   $INSTALL_DIR
  配置文件   $ENV_FILE
  服务状态   systemctl status enchat
  实时日志   journalctl -u enchat -f

  还没做（需要域名，按顺序来）：
  1. 把 ENCHAT_PUBLIC_BASE_URL 改成 https://你的域名
       sed -i 's|^ENCHAT_PUBLIC_BASE_URL=.*|ENCHAT_PUBLIC_BASE_URL=https://你的域名|' $ENV_FILE
       systemctl restart enchat
  2. 配置 Nginx + TLS：见 docs/06-服务端部署.md 的 Nginx 配置，或参考 deploy/nginx-enchat.conf
       （WebSocket 的 Upgrade/Connection 头不能漏，否则客户端会一直显示「连接中…」）
  3. 防火墙只放行 80/443；8080 只在回环监听，不必对外
  4. 部署自检：./deploy/verify.sh https://你的域名
  5. 备份：./deploy/backup.sh /var/backups/enchat （建议加进 crontab）

  ⚠️ 立刻备份 $KEY_DIR/server-identity.key —— 丢了所有已装客户端会永久连不上。
EOF
