#!/usr/bin/env bash
#
# EnChat —— Nginx + TLS 配置脚本（承接 install.sh 之后的那一步）
#
# 适用：服务端已由 install.sh 装好并在 127.0.0.1:8080 正常监听，现在要挂域名 + HTTPS。
#
# 用法：
#   sudo bash setup-nginx.sh server.servebeer.com
#   sudo bash setup-nginx.sh server.servebeer.com admin@example.com   # 指定证书通知邮箱
#
# 会依次做：
#   1. 预检：root / nginx / certbot / DNS 是否指向本机 / 8080 服务是否在跑
#   2. 先只放 80 端口配置（不含 443 块）—— 因为 443 引用的证书此时还不存在，
#      若一次性写入会让 nginx -t 直接失败
#   3. certbot certonly --webroot 申请证书（只拿证书，不改动我们的配置文件）
#   4. 再写完整配置（80 跳 HTTPS + 443 反代，含 WebSocket Upgrade）
#   5. 更新 ENCHAT_PUBLIC_BASE_URL 并重启服务
#   6. HTTPS 端到端自检
#
# 幂等：证书已存在则跳过申请；重复执行安全。
set -Eeuo pipefail

DOMAIN="${1:-}"
EMAIL="${2:-admin@${DOMAIN:-localhost}}"

[ -n "$DOMAIN" ] || { echo "用法: sudo bash $0 <域名> [证书通知邮箱]"; echo "例如: sudo bash $0 server.servebeer.com"; exit 1; }

SITE_AVAIL=/etc/nginx/sites-available/enchat
SITE_LINK=/etc/nginx/sites-enabled/enchat
WEBROOT=/var/www/html
ENV_FILE=/etc/enchat/enchat.env

log()  { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- 1. 预检 ----------
log "1. 环境预检"
[ "$(id -u)" = 0 ] || die "请用 root 或 sudo 运行"

if ! command -v nginx >/dev/null 2>&1; then
  warn "未安装 nginx，尝试安装..."
  (apt-get update -qq && apt-get install -y -qq nginx) || die "nginx 安装失败，请手动安装"
fi
if ! command -v certbot >/dev/null 2>&1; then
  warn "未安装 certbot，尝试安装..."
  (apt-get update -qq && apt-get install -y -qq certbot) || die "certbot 安装失败，请手动安装"
fi
ok "nginx $(nginx -v 2>&1 | head -1) / certbot 就绪"

# 8080 服务必须在跑，否则反代之后 /health 拿不到
if curl -fsS --max-time 3 http://127.0.0.1:8080/health >/dev/null 2>&1; then
  ok "本机 8080 服务正常: $(curl -fsS --max-time 3 http://127.0.0.1:8080/health)"
else
  die "127.0.0.1:8080 无响应。请先确认 EnChat 已启动：systemctl status enchat"
fi

# DNS 必须解析到本机公网 IP，否则 HTTP-01 校验必然失败
log "2. DNS 解析检查"
PUBLIC_IP="$(curl -fsS --max-time 10 https://api.ipify.org 2>/dev/null || curl -fsS --max-time 10 ifconfig.me 2>/dev/null || echo '')"
DOMAIN_IP="$(getent hosts "$DOMAIN" 2>/dev/null | awk '{print $1}' | head -1 || echo '')"
if [ -z "$DOMAIN_IP" ]; then
  warn "域名 $DOMAIN 当前解析不出 IP —— 证书申请会失败"
  warn "  请到 DNS 服务商（如 No-IP）确认 A 记录已指向 $PUBLIC_IP"
  warn "  本机查询：dig +short $DOMAIN"
  read -r -p "  仍要继续吗？[y/N] " ANS
  [ "$ANS" = "y" ] || [ "$ANS" = "Y" ] || die "已中止。DNS 生效后重跑本脚本"
elif [ -n "$PUBLIC_IP" ] && [ "$DOMAIN_IP" != "$PUBLIC_IP" ]; then
  warn "域名解析到 $DOMAIN_IP，但本机公网 IP 是 $PUBLIC_IP —— 不一致"
  warn "  请修正 A 记录后重跑。若确认无误（如 CDN/反代场景），可继续。"
  read -r -p "  仍要继续吗？[y/N] " ANS
  [ "$ANS" = "y" ] || [ "$ANS" = "Y" ] || die "已中止"
else
  ok "$DOMAIN -> $DOMAIN_IP（与本机公网 IP 一致）"
fi

# ---------- 3. 先放 80 端口配置 ----------
# 关键：此刻 443 块引用的证书文件还不存在，一次性写入会让 nginx -t 失败。
log "3. 写入临时 HTTP 配置（用于证书校验）"
mkdir -p "$WEBROOT"
rm -f "$SITE_LINK" /etc/nginx/sites-enabled/default
cat > "$SITE_AVAIL" <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;

    location /.well-known/acme-challenge/ {
        root $WEBROOT;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}
EOF
ln -sfn "$SITE_AVAIL" "$SITE_LINK"
if ! nginx -t > /tmp/enchat_nginx_test.log 2>&1; then
  cat /tmp/enchat_nginx_test.log
  die "临时 HTTP 配置校验失败"
fi
tail -1 /tmp/enchat_nginx_test.log
systemctl reload nginx
ok "HTTP 配置已生效"

# ---------- 3.5 探测 nginx 版本，决定 http2 写法 ----------
# ⚠️ 关键兼容点：
#   nginx >= 1.25.1  → 独立指令 `http2 on;`
#   nginx <  1.25.1  → 必须写在 listen 行里：`listen 443 ssl http2;`
# Ubuntu 22.04 自带 nginx 1.18，用新写法会直接报
#   nginx: [emerg] unknown directive "http2"
# 导致 nginx -t 失败、reload 不生效。
NGX_VER="$(nginx -v 2>&1 | sed -E 's|.*nginx/([0-9.]+).*|\1|')"
NGX_GE_1251=0
[ "$NGX_VER" = "$(printf '%s\n%s\n' "$NGX_VER" "1.25.1" | sort -V | tail -1)" ] && NGX_GE_1251=1

# ⚠️ HTTP/2 默认【关闭】，这是刻意的。
#    nginx 全系不支持 RFC 8441（HTTP/2 上的 WebSocket / extended CONNECT），
#    而 HTTP/2 协议本身禁止 Connection / Upgrade 头。
#    一旦启用 http2，ALPN 会协商出 h2，带 Upgrade 的请求会被直接拒成 400，
#    表现为 App 一直显示「连接中…」，但 REST 接口一切正常 —— 极难排查。
#    本应用的主体是长连接 WebSocket，HTTP/2 对 REST 那点收益不值得冒这个险。
#    确需开启：ENABLE_HTTP2=1 sudo bash setup-nginx.sh ...
if [ "${ENABLE_HTTP2:-0}" = "1" ]; then
  if [ "$NGX_GE_1251" = "1" ]; then
    LISTEN_443="listen 443 ssl;"
    LISTEN_443_V6="listen [::]:443 ssl;"
    HTTP2_LINE="    http2 on;"
  else
    LISTEN_443="listen 443 ssl http2;"
    LISTEN_443_V6="listen [::]:443 ssl http2;"
    HTTP2_LINE=""
  fi
  warn "已按 ENABLE_HTTP2=1 开启 HTTP/2 —— 若 App 连不上 WebSocket，请关掉它"
else
  LISTEN_443="listen 443 ssl;"
  LISTEN_443_V6="listen [::]:443 ssl;"
  HTTP2_LINE=""
fi
ok "nginx $NGX_VER，HTTP/2 ${ENABLE_HTTP2:-关}（WebSocket 兼容优先）"

# ---------- 4. 申请证书 ----------
log "4. 申请 TLS 证书"
if [ -d "/etc/letsencrypt/live/$DOMAIN" ]; then
  ok "证书已存在，跳过申请"
else
  warn "提醒：若失败，多半是云平台防火墙未放行 80/443。"
  warn "  GCP：VPC 网络 → 防火墙 → 放行 tcp:80,443（或在实例设置里勾选「允许 HTTP/HTTPS 流量」）"
  certbot certonly --webroot -w "$WEBROOT" -d "$DOMAIN" \
    --agree-tos -m "$EMAIL" --no-eff-email --keep-until-expiring \
    || die "证书申请失败。请确认：1) 防火墙放行 80/443  2) DNS 已生效  3) 80 端口未被其他程序占用"
  ok "证书已签发"
fi
[ -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ] || die "证书文件缺失"

# ---------- 5. 写完整配置 ----------
log "5. 写入完整 HTTPS 配置（含 WebSocket 反代）"
cat > "$SITE_AVAIL" <<EOF
upstream enchat_backend {
    server 127.0.0.1:8080;
    keepalive 32;
}

server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;

    location /.well-known/acme-challenge/ {
        root $WEBROOT;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    $LISTEN_443
    $LISTEN_443_V6
$HTTP2_LINE
    server_name $DOMAIN;

    ssl_certificate     /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;

    add_header Strict-Transport-Security "max-age=63072000" always;
    add_header X-Content-Type-Options nosniff always;

    client_max_body_size 10m;

    # WebSocket：这三条头不能少，少了客户端会一直显示「连接中…」
    location /ws {
        proxy_pass http://enchat_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade    \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host       \$host;
        proxy_set_header X-Real-IP  \$remote_addr;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;

        proxy_read_timeout  3600s;
        proxy_send_timeout  3600s;
        proxy_buffering off;
    }

    location / {
        proxy_pass http://enchat_backend;
        proxy_http_version 1.1;
        proxy_set_header Host      \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Connection "";

        proxy_read_timeout 60s;
    }
}
EOF
if ! nginx -t > /tmp/enchat_nginx_test.log 2>&1; then
  cat /tmp/enchat_nginx_test.log
  die "Nginx 配置校验失败（未 reload，当前服务仍在用旧配置运行）。以上为详细错误"
fi
tail -1 /tmp/enchat_nginx_test.log
systemctl reload nginx
ok "HTTPS 配置已生效"

# ---------- 6. 更新服务端对外地址 ----------
log "6. 更新 ENCHAT_PUBLIC_BASE_URL"
if [ -f "$ENV_FILE" ]; then
  # 已有则替换，没有则追加
  if grep -q '^ENCHAT_PUBLIC_BASE_URL=' "$ENV_FILE"; then
    sed -i "s|^ENCHAT_PUBLIC_BASE_URL=.*|ENCHAT_PUBLIC_BASE_URL=https://$DOMAIN|" "$ENV_FILE"
  else
    echo "ENCHAT_PUBLIC_BASE_URL=https://$DOMAIN" >> "$ENV_FILE"
  fi
  systemctl restart enchat
  sleep 3
  ok "已设为 https://$DOMAIN 并重启服务"
else
  warn "未找到 $ENV_FILE，跳过（服务端可能不是用 install.sh 装的）"
fi

# ---------- 7. 自检 ----------
log "7. HTTPS 自检"
for i in $(seq 1 10); do
  if curl -fsS --max-time 8 "https://$DOMAIN/health" >/dev/null 2>&1; then
    ok "https://$DOMAIN/health -> $(curl -fsS --max-time 8 https://$DOMAIN/health)"
    break
  fi
  [ "$i" = 10 ] && die "HTTPS 健康检查失败。检查：systemctl status nginx / enchat"
  sleep 3
done

# WebSocket Upgrade 检查（不走 ws 库，只看 Upgrade 头是否被正确转发）
# ⚠️ 必须强制 --http1.1：curl 在 HTTPS 上会因 ALPN 协商出 h2，
#    而 HTTP/2 禁止 Upgrade 头，会误报成 400，看着像服务端坏了其实是测试方法不对。
WS_CODE=$(curl -s --http1.1 -o /dev/null -w '%{http_code}' --max-time 10 \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  "https://$DOMAIN/ws" 2>/dev/null || echo "000")
if [ "$WS_CODE" = "101" ]; then
  ok "WebSocket Upgrade 返回 101（长连接通道正常）"
else
  warn "WebSocket Upgrade 返回 $WS_CODE（期望 101）"
  warn "  若客户端一直显示「连接中…」，多半是这里的问题："
  warn "  检查 Nginx 的 /ws 块是否有 Upgrade 与 Connection 头"
fi

# ALPN 探测：确认服务端只通告 http/1.1
ALPN="$(echo | openssl s_client -connect "$DOMAIN:443" -servername "$DOMAIN" -alpn h2,http/1.1 2>/dev/null | grep -i 'ALPN protocol' | head -1 || echo '')"
if echo "$ALPN" | grep -qi 'h2'; then
  warn "服务端仍通告 h2（$ALPN）—— WebSocket 可能受 HTTP/2 影响"
else
  ok "ALPN: ${ALPN:-（未通告，默认 http/1.1）}"
fi

# 证书自动续期
systemctl is-active --quiet certbot.timer 2>/dev/null && ok "certbot.timer 已启用（自动续期）" \
  || warn "certbot.timer 未运行，建议手动加 crontab：0 3 * * * certbot renew --quiet --deploy-hook 'systemctl reload nginx'"

log "完成"
cat <<EOF

  服务地址   https://$DOMAIN
  健康检查   https://$DOMAIN/health
  查看日志   journalctl -u enchat -f
  Nginx 日志 tail -f /var/log/nginx/error.log

  客户端下一步：
  1. app/build.gradle.kts 确认 DEFAULT_HOST = "https://$DOMAIN"
  2. 重新编译安装客户端
  3. 跑 ./deploy/verify.sh https://$DOMAIN 做完整自检

  ⚠️ 别忘了备份 /var/lib/enchat/keys/server-identity.key
EOF
