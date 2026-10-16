#!/usr/bin/env bash
#
# EnChat 服务端一键部署（Docker + Nginx + Let's Encrypt）。
#
# 用法：
#   sudo ./deploy/deploy.sh chat.example.com
#
# 会依次做：
#   1. 环境预检（docker / compose / nginx / 64 位 Linux）
#   2. 生成 .env（DB_PASSWORD、PEPPER_B64 自动生成并回显）
#   3. 构建并启动容器（Postgres + Server）
#   4. 打印服务器长期公钥（需要写回客户端重新编译）
#   5. 申请 TLS 证书并配置 Nginx 反代
#   6. 端到端健康检查
#
# 幂等：重复执行会复用已有 .env 中的密钥，不会重置数据库或服务器身份密钥。
set -Eeuo pipefail

DOMAIN="${1:-}"
if [ -z "$DOMAIN" ]; then
  echo "用法: sudo $0 <域名>   例如: sudo $0 chat.example.com"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY_DIR="$REPO_ROOT/deploy"
ENV_FILE="$DEPLOY_DIR/.env"

log()  { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- 1. 预检 ----------
log "环境预检"
[ "$(id -u)" = 0 ] || die "请用 root 或 sudo 运行"

command -v docker >/dev/null 2>&1 || die "未安装 docker"
docker compose version >/dev/null 2>&1 || die "docker compose 插件不可用（需要 v2）"
command -v nginx  >/dev/null 2>&1 || die "未安装 nginx"
command -v openssl >/dev/null 2>&1 || die "未安装 openssl"
command -v curl   >/dev/null 2>&1 || die "未安装 curl"

# 域名必须解析到本机，否则 certbot 的 HTTP-01 校验会失败
SERVER_IP="$(curl -fsS --max-time 10 https://api.ipify.org || hostname -I | awk '{print $1}')"
DOMAIN_IP="$(getent hosts "$DOMAIN" | awk '{print $1}' | head -1 || true)"
if [ -z "$DOMAIN_IP" ]; then
  warn "域名 $DOMAIN 当前无法解析 —— 请先添加 A 记录指向 $SERVER_IP，否则证书申请会失败"
elif [ "$DOMAIN_IP" != "$SERVER_IP" ]; then
  warn "域名 $DOMAIN 解析到 $DOMAIN_IP，但本机公网 IP 是 $SERVER_IP —— 证书 HTTP-01 校验可能失败"
fi

# ---------- 2. 生成 .env ----------
log "准备环境变量"
if [ -f "$ENV_FILE" ]; then
  echo "复用已有的 $ENV_FILE（不会重置数据库口令与 pepper）"
else
  DB_PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"
  PEPPER="$(openssl rand -base64 32)"
  cat > "$ENV_FILE" <<EOF
DB_PASSWORD=$DB_PASS
PEPPER_B64=$PEPPER
PUBLIC_BASE_URL=https://$DOMAIN
EOF
  chmod 600 "$ENV_FILE"
  echo "已生成 $ENV_FILE"
  echo "  DB_PASSWORD = $DB_PASS"
  echo "  PEPPER_B64  = $PEPPER"
  echo ""
  echo "⚠️  PEPPER_B64 请立刻备份到密码管理器：上线后改动会导致所有老用户无法登录。"
fi
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a
[ -n "${PEPPER_B64:-}" ] || die ".env 里缺少 PEPPER_B64"
[ -n "${DB_PASSWORD:-}" ] || die ".env 里缺少 DB_PASSWORD"
export PUBLIC_BASE_URL="https://$DOMAIN"

# ---------- 3. 构建并启动 ----------
log "构建并启动容器（首次构建约需 5-15 分钟）"
cd "$DEPLOY_DIR"
docker compose --env-file "$ENV_FILE" up -d --build

# ---------- 4. 等待健康检查 ----------
log "等待服务就绪"
for i in $(seq 1 60); do
  if curl -fsS --max-time 3 "http://127.0.0.1:8080/health" >/dev/null 2>&1; then
    echo "服务已就绪（第 ${i} 次探测）"
    break
  fi
  [ "$i" = 60 ] && { docker compose logs --tail 60 server; die "服务 60 次探测仍未就绪，以上为容器日志"; }
  sleep 3
done

# ---------- 5. 打印服务器长期公钥 ----------
log "服务器长期公钥（务必保存，客户端必须 pinning）"
# 从容器日志里取首次启动时打印的两行
PUB_ED="$(docker compose logs server 2>/dev/null | grep -oE 'ed25519=[A-Za-z0-9+/=]+' | tail -1 | cut -d= -f2- || true)"
PUB_X="$(docker compose logs server 2>/dev/null | grep -oE 'x25519 *= *[A-Za-z0-9+/=]+' | tail -1 | sed 's/.*= *//' || true)"

if [ -z "$PUB_ED" ] || [ -z "$PUB_X" ]; then
  warn "未能从日志解析公钥（可能密钥是之前生成的）。请查看密钥文件："
  warn "  docker compose run --rm server cat /var/lib/enchat/keys/server-identity.key | base64 -w0"
  warn "或直接：docker compose logs server | head -20"
else
  echo ""
  echo "  ed25519 = $PUB_ED"
  echo "  x25519  = $PUB_X"
  echo ""
  echo "把这两行填进 app/build.gradle.kts："
  echo "  buildConfigField(\"String\", \"SERVER_ED25519_PUB_B64\", \"\\\"$PUB_ED\\\"\")"
  echo "  buildConfigField(\"String\", \"SERVER_X25519_PUB_B64\",  \"\\\"$PUB_X\\\"\")"
  echo "  buildConfigField(\"String\", \"DEFAULT_HOST\", \"\\\"https://$DOMAIN\\\"\")"
  echo "然后重新编译客户端 —— 公钥不匹配会导致所有客户端连不上。"
fi

# ---------- 6. Nginx + TLS ----------
log "配置 Nginx"
NGX_SRC="$DEPLOY_DIR/nginx-enchat.conf"
NGX_DST="/etc/nginx/sites-available/enchat"
if [ ! -f "$NGX_DST" ] || ! cmp -s "$NGX_SRC" "$NGX_DST"; then
  sed "s|chat\.example\.com|$DOMAIN|g" "$NGX_SRC" > "$NGX_DST"
  ln -sfn "$NGX_DST" /etc/nginx/sites-enabled/enchat
  echo "已写入 $NGX_DST"
else
  echo "Nginx 配置已是最新"
fi
mkdir -p /var/www/html

if command -v certbot >/dev/null 2>&1; then
  log "申请 TLS 证书"
  if [ -d "/etc/letsencrypt/live/$DOMAIN" ]; then
    echo "证书已存在，跳过申请"
  else
    certbot certonly --webroot -w /var/www/html -d "$DOMAIN" --agree-tos -m "admin@$DOMAIN" --no-eff-email \
      || warn "证书申请失败 —— 请确认域名已解析到本机且 80 端口可访问，之后手动重跑 certbot"
  fi
else
  warn "未安装 certbot，跳过证书申请。安装：apt install -y certbot python3-certbot-nginx"
fi

nginx -t && systemctl reload nginx

# ---------- 7. 端到端验证 ----------
log "端到端验证"
if [ -d "/etc/letsencrypt/live/$DOMAIN" ]; then
  echo -n "HTTPS 健康检查: "
  curl -fsS --max-time 10 "https://$DOMAIN/health" && echo "" || warn "HTTPS 健康检查失败（检查 Nginx 与证书）"
fi

echo ""
log "部署完成"
cat <<EOF

  服务地址   https://$DOMAIN
  健康检查   https://$DOMAIN/health
  查看日志   cd $DEPLOY_DIR && docker compose logs -f server
  停止服务   cd $DEPLOY_DIR && docker compose down
  备份密钥   docker run --rm -v \$(docker volume ls -q | grep serverkeys):/k -v \$PWD:/b alpine tar czf /b/enchat-keys-\$(date +%F).tar.gz /k

  下一步（必须做）：
  1. 把上面的 ed25519 / x25519 公钥填进 app/build.gradle.kts 并重新编译客户端
  2. 设置数据库定时备份（pg_dump）
  3. 见 docs/06-服务端部署.md 的风险与未验证项
EOF
