#!/usr/bin/env bash
#
# 备份：PostgreSQL 数据 + 服务器身份密钥。
#
# ⚠️ 服务器身份密钥（server-identity.key）比数据库更关键：
#    数据库可以重建（用户重新注册），但密钥丢失后，所有**已安装客户端**会因为
#    pinning 校验失败而永久连不上 —— 必须发新版客户端更新 pin 才能恢复。
#
# 用法（可放进 crontab）：
#   ./deploy/backup.sh /mnt/backups
#   # 每天 3 点
#   0 3 * * * /opt/enchat/deploy/backup.sh /mnt/backups >> /var/log/enchat-backup.log 2>&1
set -Eeuo pipefail

OUT_DIR="${1:-./backups}"
STAMP="$(date +%F-%H%M%S)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "$OUT_DIR"
log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

log "备份到 $OUT_DIR"

# ---------- 1. 数据库 ----------
cd "$SCRIPT_DIR"
if docker compose ps db --status running 2>/dev/null | grep -q db; then
  DUMP="$OUT_DIR/enchat-db-$STAMP.sql.gz"
  docker compose exec -T db pg_dump -U enchat -d enchat --no-owner --no-acl | gzip > "$DUMP"
  log "数据库已导出: $DUMP ($(du -h "$DUMP" | cut -f1))"
else
  echo "  [跳过] 容器db 未运行，请手动执行: pg_dump -U enchat -d enchat | gzip > $OUT_DIR/enchat-db-$STAMP.sql.gz"
fi

# ---------- 2. 服务器身份密钥 ----------
# 从命名卷导出，避免依赖容器是否运行
VOL="$(docker volume ls -q 2>/dev/null | grep -i 'serverkeys' | head -1 || true)"
if [ -n "$VOL" ]; then
  KEYS="$OUT_DIR/enchat-keys-$STAMP.tar.gz"
  docker run --rm -v "$VOL":/keys:ro -v "$OUT_DIR":/out alpine \
    tar czf "/out/$(basename "$KEYS")" -C /keys . 2>/dev/null \
    && log "服务器密钥已导出: $KEYS" \
    || echo "  [失败] 密钥卷导出失败，请手动: docker run --rm -v $VOL:/k alpine tar czf - /k > $KEYS"
  chmod 600 "$OUT_DIR"/enchat-keys-$STAMP.tar.gz 2>/dev/null || true
else
  # 非 Docker 部署：直接复制文件
  if [ -f /var/lib/enchat/keys/server-identity.key ]; then
    KEYS="$OUT_DIR/enchat-keys-$STAMP.tar.gz"
    tar czf "$KEYS" -C /var/lib/enchat/keys . && chmod 600 "$KEYS"
    log "服务器密钥已导出: $KEYS"
  else
    echo "  [警告] 未找到服务器密钥，请确认 ENCHAT_SERVER_KEY_FILE 指向的路径"
  fi
fi

# ---------- 3. 清理旧备份（保留 14 天）----------
find "$OUT_DIR" -name 'enchat-*' -type f -mtime +14 -delete 2>/dev/null || true
log "已清理 14 天前的备份"

log "完成。建议把备份同步到异地存储（对象存储 / 另一台机器）。"
