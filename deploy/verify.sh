#!/usr/bin/env bash
#
# EnChat 部署后自检：REST + TLS + 鉴权 + 口令策略 + WebSocket 握手 + 数据库表。
# 用来确认「服务真的能跑」，而不只是「进程活着」。
#
# 用法：
#   bash verify.sh https://server.servebeer.com
#   bash verify.sh http://127.0.0.1:8080        # 直连本机 8080（绕过 Nginx）
#
# 注意：会真的注册一个测试账号（enchat_selftest_<时间戳>），因此会往库里写数据。
#      脚本末尾会给出清理命令。
set -Eeuo pipefail

BASE="${1:-http://127.0.0.1:8080}"
BASE="${BASE%/}"

# 规范化：只给域名时自动补 https:// 并去掉末尾斜杠。
# 否则会按 http 走 80 端口，被 Nginx 的 301 跳转让所有接口拿到一段 HTML，
# 看起来像「服务挂了」，其实只是协议没写对。
case "$BASE" in
  http://*|https://*) : ;;
  *) BASE="https://$BASE" ;;
esac
BASE="${BASE%/}"
PASS=0
FAIL=0

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL=$((FAIL+1)); }
info() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

command -v curl >/dev/null 2>&1 || { echo "需要 curl"; exit 1; }

info "目标: $BASE"

# ---------- 1. 健康检查 ----------
info "1. 健康检查"
if curl -fsS --max-time 10 "$BASE/health" -o /tmp/enchat_health.json 2>/dev/null; then
  BODY="$(head -c 200 /tmp/enchat_health.json)"
  if echo "$BODY" | grep -q '^{'; then
    ok "/health -> $BODY"
  else
    # 拿到 HTML 而不是 JSON，几乎必然是重定向/反代没配对，而不是服务挂了
    bad "/health 返回的不是 JSON（多半是重定向或反代错误）"
    echo "      实际返回: $(echo "$BODY" | tr -d '\n' | head -c 150)"
    echo "      排查：curl -sS -L $BASE/health  以及  tail -20 /var/log/nginx/error.log"
    exit 1
  fi
else
  bad "/health 无响应。检查：systemctl status enchat / journalctl -u enchat -n 50"
  exit 1
fi

# ---------- 2. TLS ----------
info "2. TLS"
if [[ "$BASE" == https://* ]]; then
  HOST="${BASE#https://}"
  if curl -fsS --max-time 10 -o /dev/null "$BASE/health" 2>/dev/null; then
    CERT_END="$(echo | openssl s_client -connect "$HOST:443" -servername "$HOST" 2>/dev/null \
      | openssl x509 -noout -enddate 2>/dev/null || echo '未知')"
    ok "HTTPS 正常，证书有效期至: ${CERT_END#notAfter=}"

    # ALPN 探测：若服务端通告 h2，Android 客户端（OkHttp）会协商出 h2，
    # 而 nginx 不支持 RFC 8441（HTTP/2 上的 WebSocket），会导致 App 连不上。
    ALPN="$(echo | openssl s_client -connect "$HOST:443" -servername "$HOST" -alpn h2,http/1.1 2>/dev/null \
      | grep -i 'ALPN protocol' | head -1 || true)"
    if echo "$ALPN" | grep -qi 'h2'; then
      bad "服务端通告 h2（$ALPN）—— WebSocket 会失败，请关闭 HTTP/2："
      echo "       sudo sed -i 's/^    http2 on;\$//; s/ ssl http2;/ ssl;/' /etc/nginx/sites-available/enchat"
      echo "       sudo nginx -t && sudo systemctl reload nginx"
    else
      ok "ALPN: ${ALPN:-（未通告，按 http/1.1 协商）} —— WebSocket 兼容"
    fi
  else
    bad "HTTPS 握手失败"
  fi
else
  echo "  - 目标为 HTTP，跳过 TLS 检查（生产环境应为 HTTPS）"
fi

# ---------- 3. 注册 + 登录 ----------
info "3. 注册与登录（会创建测试账号）"
USER="enchat_selftest_$(date +%s)"
PW='Selftest-Pw-2026!'
NICK="自检账号"

REG=$(curl -fsS --max-time 30 -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PW\",\"nickname\":\"$NICK\"}" 2>/dev/null) || REG=""

if [ -n "$REG" ] && echo "$REG" | grep -q '"ok"[[:space:]]*:[[:space:]]*true'; then
  ok "注册成功"
else
  bad "注册失败: ${REG:0:300}"
  exit 1
fi

# ⚠️ 响应结构：token 在 data 子对象里，字段名叫 accessToken（不是 token）。
#    服务端 auth 返回形如 {"ok":true,"data":{"accessToken":"...","uid":...}}
#    早期脚本按顶层 "token" 取值，结果取到空字符串又被判成「登录失败」——
#    其实注册早就成功了。这里做多路径兼容，避免再被字段名坑。
extract_json() {
  printf '%s' "$1" | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
data = d.get("data") if isinstance(d.get("data"), dict) else d
if not isinstance(data, dict):
    data = d
def pick(*keys):
    for k in keys:
        v = data.get(k)
        if v not in (None, ""):
            return v
    return ""
print(pick(sys.argv[1], *sys.argv[2:]))
' "$2" 2>/dev/null || echo ""
}

TOKEN=""
UID_GOT=""
if command -v python3 >/dev/null 2>&1; then
  TOKEN="$(extract_json "$REG" accessToken token)"
  UID_GOT="$(extract_json "$REG" uid userId id)"
fi

if [ -z "$TOKEN" ]; then
  LOGIN=$(curl -fsS --max-time 30 -X POST "$BASE/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER\",\"password\":\"$PW\"}" 2>/dev/null) || LOGIN=""
  if command -v python3 >/dev/null 2>&1; then
    TOKEN="$(extract_json "$LOGIN" accessToken token)"
    [ -z "$UID_GOT" ] && UID_GOT="$(extract_json "$LOGIN" uid userId id)"
  fi
  [ -n "$TOKEN" ] && ok "登录接口返回 token" || bad "登录失败: ${LOGIN:0:300}"
else
  ok "注册即返回 accessToken"
fi

if [ -z "$TOKEN" ]; then
  bad "未取得访问令牌，后续检查跳过"
  exit 1
fi
ok "取得 token，uid=$UID_GOT"

# ---------- 4. 鉴权接口 ----------
info "4. 鉴权接口"
ME=$(curl -fsS --max-time 15 "$BASE/api/v1/me" -H "Authorization: Bearer $TOKEN" 2>/dev/null) || ME=""
if echo "$ME" | grep -q "$USER"; then ok "GET /me 返回当前用户"; else bad "GET /me 异常: ${ME:0:200}"; fi

NOAUTH=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$BASE/api/v1/me" 2>/dev/null || echo "000")
if [ "$NOAUTH" = "401" ] || [ "$NOAUTH" = "403" ]; then
  ok "未带令牌访问 /me 被拒（HTTP $NOAUTH）"
else
  bad "未鉴权访问 /me 返回 $NOAUTH，应为 401/403"
fi

# ---------- 5. 弱口令拒绝 ----------
info "5. 口令策略"
WEAK=$(curl -s --max-time 20 -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"username":"weaktest_x","password":"12345678","nickname":"w"}' 2>/dev/null) || WEAK=""
if echo "$WEAK" | grep -q '"ok"[[:space:]]*:[[:space:]]*false'; then
  ok "弱口令被拒绝"
else
  bad "弱口令未被拒绝: ${WEAK:0:200}"
fi

# ---------- 6. WebSocket 握手 ----------
#
# ⚠️ 这里不能用 curl 判 101：握手成功后连接不会关闭，curl 会一直挂着直到
#    --max-time 超时（退出码 28），输出 000 —— 明明是通的却被判失败。
#    改用 python 裸 socket 发一次握手请求，只读响应头第一行，不依赖第三方库。
info "6. WebSocket 握手"
HOSTPORT="${BASE#http*://}"
case "$BASE" in
  https://*) WSS=1 ;;
  *)         WSS=0 ;;
esac

if command -v python3 >/dev/null 2>&1; then
  # ⚠️ 命令替换里嵌 heredoc：闭合括号必须写在 PY 结束标记【之后】。
  #    写在起始行末尾会让后面的 python 代码被当成 shell 解析 → syntax error。
  WS_OUT=$(python3 - "$HOSTPORT" "$WSS" <<'PY' 2>&1

import sys, socket, ssl, base64, os
hp = sys.argv[1]; use_tls = sys.argv[2] == "1"
host, _, port = hp.partition(":")
port = int(port or (443 if use_tls else 80))
key = base64.b64encode(os.urandom(16)).decode()
req = (
    "GET /ws HTTP/1.1\r\n"
    f"Host: {host}\r\n"
    "Upgrade: websocket\r\n"
    "Connection: Upgrade\r\n"
    f"Sec-WebSocket-Key: {key}\r\n"
    "Sec-WebSocket-Version: 13\r\n\r\n"
)
try:
    s = socket.create_connection((host, port), timeout=8)
    if use_tls:
        # 显式只通告 http/1.1：ALPN 若协商出 h2，HTTP/2 禁止 Upgrade 头会被拒
        ctx = ssl.create_default_context()
        try:
            ctx.set_alpn_protocols(["http/1.1"])
        except Exception:
            pass
        s = ctx.wrap_socket(s, server_hostname=host)
    s.sendall(req.encode())
    head = s.recv(1024).decode(errors="replace").split("\r\n\r\n")[0]
    # 打印完整响应头：403 时据此判断是 nginx 还是应用返回的
    print(head if head.strip() else "EMPTY")
    s.close()
except Exception as e:
    print(f"EXCEPTION {e}")
PY
)
  if echo "$WS_OUT" | grep -q '101'; then
    ok "WebSocket 握手返回 101"
  else
    bad "WebSocket 握手未返回 101，实际: $(echo "$WS_OUT" | head -3)"
    echo "      检查 Nginx /ws 块是否有 Upgrade 与 Connection 头"
  fi
else
  echo "  - 无 python3，跳过 WebSocket 检查"
fi

# ---------- 7. 数据库表 ----------
info "7. 数据库表结构"
EXPECTED_TABLES="conversations conversation_members friend_requests friendships messages one_time_prekeys sessions signed_prekeys users"
TABLES=""

# Docker Compose 部署
if [ -f "$(dirname "$0")/docker-compose.yml" ] && docker compose version >/dev/null 2>&1; then
  TABLES=$(docker compose -f "$(dirname "$0")/docker-compose.yml" exec -T db \
    psql -U enchat -d enchat -tAc \
    "SELECT string_agg(tablename,',' ORDER BY tablename) FROM pg_tables WHERE schemaname='public'" 2>/dev/null) || TABLES=""
fi

# systemd / 直装 PostgreSQL（install.sh 的部署方式）
#
# 多种取数路径逐个尝试，因为不同环境下可用的方式不一样：
#   a) root + su postgres（peer 认证，最常用）—— 注意必须 cd 到 /tmp，
#      否则 postgres 用户无权进入当前目录，会报 "could not change directory"
#   b) TCP + 口令（/etc/enchat/.db_password 由 install.sh 写入）
#   c) 环境变量 PGPASSWORD 已由调用方设好
if [ -z "$TABLES" ] && command -v psql >/dev/null 2>&1; then
  SQL="SELECT string_agg(tablename,',' ORDER BY tablename) FROM pg_tables WHERE schemaname='public'"

  try_psql() {
    local out=""
    if [ "$(id -u)" = 0 ]; then
      out=$(cd /tmp && su -s /bin/sh postgres -c "psql -d enchat -tAc \"$SQL\"" 2>/dev/null) || out=""
      if [ -z "$out" ]; then
        local pw; pw="$(cat /etc/enchat/.db_password 2>/dev/null || echo '')"
        [ -n "$pw" ] && out=$(PGPASSWORD="$pw" psql -h 127.0.0.1 -U enchat -d enchat -tAc "$SQL" 2>/dev/null) || out=""
      fi
    else
      out=$(cd /tmp && psql -d enchat -tAc "$SQL" 2>/dev/null) || out=""
    fi
    # 统一成逗号分隔：去掉换行与空格，保留逗号
    printf '%s' "$out" | tr -d ' \n'
  }

  TABLES="$(try_psql)"
fi

if [ -n "$TABLES" ]; then
  MISSING=""
  for t in $EXPECTED_TABLES; do
    echo "$TABLES" | tr ',' '\n' | grep -qx "$t" || MISSING="$MISSING $t"
  done
  if [ -z "$MISSING" ]; then
    ok "9 张表齐全: $TABLES"
  else
    bad "缺失表:$MISSING（现有: $TABLES）"
  fi
else
  echo "  - 无法查询数据库，跳过。手动查："
  echo "      su -s /bin/sh postgres -c \"psql -d enchat -c '\\dt'\""
fi

# ---------- 汇总 ----------
printf '\n'
info "自检结果: 通过 $PASS 项，失败 $FAIL 项"
if [ "$FAIL" = 0 ]; then
  echo "服务端可正常使用。可以编译安装客户端了。"
  echo
  echo "测试账号（可选清理）：  $USER"
  echo "  su -s /bin/sh postgres -c \"psql -d enchat -c \\\"DELETE FROM users WHERE username='$USER'\\\"\""
else
  echo "存在失败项，请按上面 [✗] 的提示排查。"
  exit 1
fi
