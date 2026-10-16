#!/usr/bin/env bash
#
# 部署后自检：REST + 数据库表结构 + WebSocket 握手。
# 用来确认「服务真的能跑」，而不只是「进程活着」。
#
# 用法：
#   ./deploy/verify.sh https://chat.example.com
#   ./deploy/verify.sh http://127.0.0.1:8080          # 直连容器
#
# 会真的注册一个测试账号（用户名 enchat_selftest_<时间戳>），因此会往库里写数据。
# 如需清理，见脚本末尾提示。
set -Eeuo pipefail

BASE="${1:-http://127.0.0.1:8080}"
BASE="${BASE%/}"
PASS=0
FAIL=0

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL=$((FAIL+1)); }
info() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

command -v curl >/dev/null || { echo "需要 curl"; exit 1; }
command -v python3 >/dev/null || echo "（无 python3，将跳过 WebSocket 检查）"

info "目标: $BASE"

# ---------- 1. 健康检查 ----------
info "1. 健康检查"
if curl -fsS --max-time 10 "$BASE/health" -o /tmp/enchat_health.json 2>/dev/null; then
  ok "/health  -> $(cat /tmp/enchat_health.json | head -c 200)"
else
  bad "/health 无响应。检查：docker compose logs server"
  exit 1
fi

# ---------- 2. TLS ----------
info "2. TLS"
if [[ "$BASE" == https://* ]]; then
  if curl -fsS --max-time 10 -o /dev/null -w '%{http_code}' "$BASE/health" >/dev/null 2>&1; then
    CERT_END="$(echo | openssl s_client -connect "${BASE#https://}:443" -servername "${BASE#https://}" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null || echo '未知')"
    ok "HTTPS 正常，证书有效期至: ${CERT_END#notAfter=}"
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
  echo "$REG" > /tmp/enchat_reg.json
else
  bad "注册失败: ${REG:0:300}"
  exit 1
fi

TOKEN=$(echo "$REG" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("token",""))' 2>/dev/null || echo "")
UID_GOT=$(echo "$REG" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("uid", d.get("user",{}).get("uid","")))' 2>/dev/null || echo "")

# 上一步若没返回 token，则走登录接口
if [ -z "$TOKEN" ]; then
  LOGIN=$(curl -fsS --max-time 30 -X POST "$BASE/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER\",\"password\":\"$PW\"}" 2>/dev/null) || LOGIN=""
  TOKEN=$(echo "$LOGIN" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("token",""))' 2>/dev/null || echo "")
  if [ -n "$TOKEN" ]; then ok "登录成功（注册未直接返回 token，改走登录接口）"; echo "$LOGIN" > /tmp/enchat_login.json; else bad "登录失败: ${LOGIN:0:300}"; fi
else
  ok "注册即返回 token"
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
if [ "$NOAUTH" = "401" ] || [ "$NOAUTH" = "403" ]; then ok "未带令牌访问 /me 被拒（HTTP $NOAUTH）"; else bad "未鉴权访问 /me 返回 $NOAUTH，应为 401/403"; fi

# ---------- 5. 弱口令拒绝 ----------
info "5. 口令策略"
WEAK=$(curl -s --max-time 20 -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"username":"weaktest_x","password":"12345678","nickname":"w"}' 2>/dev/null) || WEAK=""
if echo "$WEAK" | grep -q '"ok"[[:space:]]*:[[:space:]]*false'; then
  ok "弱口令被拒绝: $(echo "$WEAK" | head -c 150)"
else
  bad "弱口令未被拒绝: ${WEAK:0:200}"
fi

# ---------- 6. WebSocket 握手 ----------
info "6. WebSocket 握手"
WS_URL="${BASE/http/ws}/ws"
if command -v python3 >/dev/null && python3 -c "import websockets" 2>/dev/null; then
  python3 - "$WS_URL" <<'PY' && ok "WebSocket 握手成功（Upgrade 101）" || bad "WebSocket 握手失败 —— 检查 Nginx 的 Upgrade/Connection 头"
import sys, asyncio, websockets
async def main():
    async with websockets.connect(sys.argv[1], open_timeout=15) as ws:
        # 连上即断开，只验证握手（101 Switching Protocols）
        await ws.close()
asyncio.run(main())
PY
else
  # 无 websockets 库时，用裸 HTTP 检查 Upgrade 是否被反代正确转发
  CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
    -H "Connection: Upgrade" -H "Upgrade: websocket" \
    -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
    "$WS_URL" 2>/dev/null || echo "000")
  if [ "$CODE" = "101" ]; then
    ok "WebSocket Upgrade 返回 101"
  else
    bad "WebSocket Upgrade 返回 $CODE（应为 101）。若走 Nginx，请确认已配置 Upgrade/Connection 头"
  fi
fi

# ---------- 7. 数据库表 ----------
info "7. 数据库表结构（进入容器查询）"
if docker compose version >/dev/null 2>&1 && [ -f "$(dirname "$0")/docker-compose.yml" ]; then
  TABLES=$(docker compose -f "$(dirname "$0")/docker-compose.yml" exec -T db \
    psql -U enchat -d enchat -tAc \
    "SELECT string_agg(tablename,',' ORDER BY tablename) FROM pg_tables WHERE schemaname='public'" 2>/dev/null || echo "")
  if [ -n "$TABLES" ]; then
    ok "已建表: $TABLES"
    for t in users sessions signed_prekeys one_time_prekeys friendships friend_requests conversations conversation_members messages; do
      if echo "$TABLES" | tr ',' '\n' | grep -qx "$t"; then ok "  - $t 存在"; else bad "  - $t 缺失"; fi
    done
  else
    echo "  - 非 Docker 部署或 psql 不可用，跳过（可手动：psql -U enchat -d enchat -c '\\dt'）"
  fi
else
  echo "  - 未检出 docker compose 环境，跳过"
fi

# ---------- 汇总 ----------
printf '\n'
info "自检结果: 通过 $PASS 项，失败 $FAIL 项"
if [ "$FAIL" = 0 ]; then
  echo "服务端可正常使用。"
  echo
  echo "测试账号（如要清理）：  $USER"
  echo "  docker compose exec -T db psql -U enchat -d enchat -c \"DELETE FROM users WHERE username='$USER'\""
else
  echo "存在失败项，请按上面 [✗] 的提示排查。"
  exit 1
fi
