#!/usr/bin/env bash
# ============================================================================
# Step 4 验收脚本：缓存三剑客（穿透 / 击穿 / 雪崩）
#
# 用法：  bash scripts/verify-step4.sh
# 前置：  docker compose up -d 且应用 healthy（布隆、互斥锁均为默认 ON）
#
# 判据：
#   1. 健康检查暴露 features 段，且 bloomFilter=ON / cacheMutex=ON
#   2. 雪崩防护：TTL 带随机抖动 —— 同一批写入的 key 的 TTL 不全相同
#   3. 穿透防护：布隆拦下非法码（DB 零查询）
#   4. 穿透兜底：布隆漏过的码（人工制造：DB 删行但布隆里还在）
#      第一次查库 1 次，第二次被空值缓存挡住（DB 查询不再增加）
#   5. 击穿防护：热点 key 缓存失效瞬间 N 并发 → DB 只被查 1 次
#   6. 锁一定被释放（否则后续请求全部退化成串行等待）
# ============================================================================
set -u

BASE_URL="${BASE_URL:-http://localhost:8082}"
APP_CTN="${APP_CTN:-city-url-app}"
MYSQL_CTN="${MYSQL_CTN:-city-mysql}"
REDIS_CTN="${REDIS_CTN:-city-redis}"
DB_USER="root"; DB_PASS="123456"; DB_NAME="city_url"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$(cd "$HERE/.." && pwd)"

PASS=0; FAIL=0
ok()   { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1"; }
head1() { printf '\n\033[36m== %s ==\033[0m\n' "$1"; }

mysql_q() {
  docker exec "$MYSQL_CTN" mysql -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 \
    -N -B -e "SET NAMES utf8mb4; $1" "$DB_NAME" 2>/dev/null
}
redis_cli() { docker exec "$REDIS_CTN" redis-cli "$@" 2>/dev/null | tr -d '\r'; }
reset_digest() { mysql_q "TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;" >/dev/null; }
count_select() {
  mysql_q "SELECT IFNULL(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest \
           WHERE DIGEST_TEXT LIKE '%short_url%' AND DIGEST_TEXT LIKE 'SELECT%';" | tr -d '[:space:]'
}
jf() { python -c "
import sys, json
try:
    d = json.load(sys.stdin)
    for k in sys.argv[1:]:
        d = d[int(k)] if k.lstrip('-').isdigit() else d[k]
    if d is not None: print(d)
except Exception: pass
" "$@"; }

USER_NAME="verify_s4_$$"; USER_PASS="pass123456"

# ---------------------------------------------------------------- 0. 前置
head1 "0. 前置检查"
curl -s -o /dev/null -m 5 "$BASE_URL/api/health" || { bad "应用不可达"; exit 1; }
FEATURES=$(curl -s "$BASE_URL/api/health" | jf data features)
if [ -n "$FEATURES" ]; then ok "features 段存在: $FEATURES"; else bad "features 段缺失（镜像里是旧代码？）"; exit 1; fi
BF=$(curl -s "$BASE_URL/api/health" | jf data features bloomFilter)
MX=$(curl -s "$BASE_URL/api/health" | jf data features cacheMutex)
[ "$BF" = "ON" ] && ok "bloomFilter = ON" || bad "bloomFilter = $BF（压测后没恢复？）"
[ "$MX" = "ON" ] && ok "cacheMutex = ON" || bad "cacheMutex = $MX（压测后没恢复？）"

curl -s -X POST "$BASE_URL/api/user/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}" >/dev/null
TOKEN=$(curl -s -X POST "$BASE_URL/api/user/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}" | jf data token)
[ -n "$TOKEN" ] && ok "登录成功" || { bad "登录失败"; exit 1; }

mkcode() {
  curl -s -X POST "$BASE_URL/api/short/create" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"originalUrl\":\"$1\",\"expireDays\":7}" | jf data shortCode
}

# ---------------------------------------------------------------- 1. TTL 抖动
head1 "1. 雪崩防护：TTL 随机抖动"
CODES=()
for i in 1 2 3 4 5 6 7 8; do
  c=$(mkcode "https://example.com/jitter/$i")
  CODES+=("$c")
  curl -s -o /dev/null "$BASE_URL/$c"    # 触发一次解析，让缓存被写入
done
TTLS=$(for c in "${CODES[@]}"; do redis_cli TTL "short:url:$c"; done | tr '\n' ' ')
UNIQ=$(echo "$TTLS" | tr ' ' '\n' | grep -c . )
DISTINCT=$(echo "$TTLS" | tr ' ' '\n' | sort -u | grep -c .)
echo "  TTL 样本: $TTLS"
if [ "$DISTINCT" -gt 1 ]; then
  ok "8 个 key 的 TTL 有 $DISTINCT 种不同取值（抖动生效，不会同时失效）"
else
  bad "8 个 key 的 TTL 完全相同 → 抖动没生效"
fi
MIN_TTL=$(echo "$TTLS" | tr ' ' '\n' | grep . | sort -n | head -1)
MAX_TTL=$(echo "$TTLS" | tr ' ' '\n' | grep . | sort -n | tail -1)
if [ "$MIN_TTL" -ge 86300 ] && [ "$MAX_TTL" -le 88300 ]; then
  ok "TTL 落在 [86400, 88200] 区间内（基准 24h + [0,1800]s 抖动）"
else
  bad "TTL 区间异常: min=$MIN_TTL max=$MAX_TTL"
fi

# ---------------------------------------------------------------- 2. 穿透：布隆拦截
head1 "2. 穿透防护：布隆拦下非法码"
reset_digest
for c in zzzzzzzzq1 zzzzzzzzq2 zzzzzzzzq3 zzzzzzzzq4 zzzzzzzzq5; do
  curl -s -o /dev/null "$BASE_URL/$c"
done
SQL=$(count_select)
if [ "${SQL:-1}" -eq 0 ]; then ok "5 个非法码 → DB SELECT = 0（布隆全拦）"; else bad "DB SELECT = $SQL"; fi

# ---------------------------------------------------------------- 3. 穿透兜底：空值缓存
head1 "3. 穿透兜底：空值缓存挡住布隆漏过的码"
# 人工制造一个「布隆里有、DB 里没有」的码：
#   先正常创建（这样会同步写进布隆），再把 DB 里的行删掉。
#   这是布隆假阳性场景的确定性复现，比等随机假阳性靠谱。
GHOST=$(mkcode "https://example.com/ghost")
redis_cli DEL "short:url:$GHOST" >/dev/null
mysql_q "DELETE FROM short_url WHERE short_code='$GHOST';" >/dev/null

reset_digest
curl -s -o /dev/null "$BASE_URL/$GHOST"
SQL1=$(count_select)
curl -s -o /dev/null "$BASE_URL/$GHOST"
SQL2=$(count_select)
MISS_EXISTS=$(redis_cli EXISTS "short:miss:$GHOST")
echo "  第一次请求后 DB SELECT=$SQL1，第二次后 DB SELECT=$SQL2，miss key 存在=$MISS_EXISTS"
if [ "${SQL1:-0}" -eq 1 ]; then ok "第一次：布隆放行 → 查库 1 次"; else bad "第一次 DB SELECT=$SQL1，期望 1"; fi
if [ "${SQL2:-0}" -eq 1 ]; then ok "第二次：被空值缓存挡住，DB 查询没增加（仍是 1）"; else bad "第二次 DB SELECT=$SQL2，期望仍为 1"; fi
if [ "$MISS_EXISTS" = "1" ]; then ok "short:miss:$GHOST 已写入"; else bad "空值标记 key 不存在"; fi

# ---------------------------------------------------------------- 4. 击穿：互斥锁
head1 "4. 击穿防护：互斥锁把并发回源压到 1 次"
HOT=$(mkcode "https://example.com/hot")
curl -s -o /dev/null "$BASE_URL/$HOT"       # 先暖一次，确认可访问
redis_cli DEL "short:url:$HOT" >/dev/null   # 模拟「缓存刚刚失效」

NODE_BIN="$(command -v node 2>/dev/null || true)"
[ -z "$NODE_BIN" ] && NODE_BIN="C:/Users/Administrator/.workbuddy-ai/binaries/node/versions/22.22.2-3/node.exe"

# 用 Node 压测器发起真并发（bash 的 `&` 在 Windows 上要 spawn 上百个进程，太慢）
reset_digest
"$NODE_BIN" "$PROJ/bench/bench.mjs" --base "$BASE_URL" --label "击穿" \
  --mode single --code "$HOT" --requests 100 --concurrency 100 >/dev/null 2>&1
SQL=$(count_select)
echo "  100 个并发请求后 DB SELECT = $SQL"
if [ "${SQL:-99}" -le 3 ]; then
  ok "100 个并发请求 → DB 只被查了 $SQL 次（互斥锁生效；理论值 1，容忍少量自旋超时降级）"
else
  bad "100 个并发请求 → DB 被查了 $SQL 次，互斥锁没生效"
fi

# ---------------------------------------------------------------- 5. 锁被释放
head1 "5. 锁一定被释放"
LOCK_LEFT=$(redis_cli EXISTS "lock:short:$HOT")
if [ "$LOCK_LEFT" = "0" ]; then ok "lock:short:$HOT 已释放（Lua CAS 删除生效）"; else bad "锁残留，后续请求会串行等待"; fi

# ---------------------------------------------------------------- 汇总
printf '\n\033[36m== 汇总 ==\033[0m\n'
printf '  PASS=%d  FAIL=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] && printf '  \033[32mStep 4 全部判据通过\033[0m\n' && exit 0
printf '  \033[31m存在失败项\033[0m\n' && exit 1
