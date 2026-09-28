#!/usr/bin/env bash
# ============================================================================
# Step 3 验收脚本：短链跳转（302 + Redis 缓存 + Guava 布隆过滤器）
#
# 用法：  bash scripts/verify-step3.sh
# 前置：  docker compose up -d 且 city-url-app 为 healthy
#
# 判据（每条都必须 PASS）：
#   1. 短码为 10 位（雪花 ID 量级决定，不是 PRD 里写的 6-8 位）
#   2. GET /{shortCode} 返回 302，Location 等于原始 URL
#   3. 非法短码返回真正的 HTTP 404（Step 4 起短链路径不再沿用「200 + 业务码」模型）
#   4. 首次解析后 Redis 里存在 short:url:{code}，TTL ≈ 24h
#   5. 二次请求仍是 302（走缓存，不再查库）
#   6. GET /doc.html 返回 200 —— 短码路径正则没有把静态资源吞掉
#   7. 连续请求 5 个非法短码，MySQL 里 short_url 相关语句数 = 0（布隆拦住了）
# ============================================================================
set -u

BASE_URL="${BASE_URL:-http://localhost:8082}"
MYSQL_CTN="${MYSQL_CTN:-city-mysql}"
REDIS_CTN="${REDIS_CTN:-city-redis}"
APP_CTN="${APP_CTN:-city-url-app}"
DB_USER="root"
DB_PASS="123456"
DB_NAME="city_url"

USER_NAME="verify_s3_$$"
USER_PASS="pass123456"

PASS=0
FAIL=0
ok()   { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1"; }
head1() { printf '\n\033[36m== %s ==\033[0m\n' "$1"; }

# 从 JSON 里按路径取字段，避免依赖 jq
# 用法： echo "$JSON" | jget data token   → 等价于 j['data']['token']
jget() {
  python -c '
import sys, json
try:
    d = json.load(sys.stdin)
    for k in sys.argv[1:]:
        d = d[int(k)] if k.lstrip("-").isdigit() else d[k]
    if d is not None:
        print(d)
except Exception:
    pass
' "$@"
}

mysql_q() {
  docker exec "$MYSQL_CTN" mysql -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 \
    -N -B -e "SET NAMES utf8mb4; $1" "$DB_NAME" 2>/dev/null
}

# ---------------------------------------------------------------- 0. 前置
head1 "0. 前置检查"
if curl -s -o /dev/null -m 5 "$BASE_URL/api/health"; then
  ok "应用可达 $BASE_URL"
else
  bad "应用不可达，先 docker compose up -d"; exit 1
fi

# ---------------------------------------------------------------- 1. 登录
head1 "1. 注册 / 登录拿 token"
curl -s -X POST "$BASE_URL/api/user/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}" >/dev/null
LOGIN=$(curl -s -X POST "$BASE_URL/api/user/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}")
TOKEN=$(echo "$LOGIN" | jget data token)
if [ -n "$TOKEN" ]; then ok "拿到 token（${#TOKEN} 字符）"; else bad "登录失败: $LOGIN"; exit 1; fi

# ---------------------------------------------------------------- 2. 建短链
head1 "2. 创建短链"
ORIGIN="https://spring.io/projects/spring-boot"
CREATE=$(curl -s -X POST "$BASE_URL/api/short/create" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"originalUrl\":\"$ORIGIN\",\"expireDays\":7}")
CODE=$(echo "$CREATE" | jget data shortCode)
if [ -n "$CODE" ]; then ok "创建成功 shortCode=$CODE"; else bad "创建失败: $CREATE"; exit 1; fi

if [ "${#CODE}" -eq 10 ]; then ok "短码长度 = 10 位"; else bad "短码长度 = ${#CODE}，期望 10"; fi

# ---------------------------------------------------------------- 3. 302 跳转
head1 "3. 302 跳转"
LOC=$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "$BASE_URL/$CODE")
STATUS=${LOC%% *}
TARGET=${LOC#* }
if [ "$STATUS" = "302" ]; then ok "HTTP 302"; else bad "HTTP $STATUS（期望 302）"; fi
if [ "$TARGET" = "$ORIGIN" ]; then ok "Location 正确 → $TARGET"; else bad "Location=$TARGET，期望 $ORIGIN"; fi

# ---------------------------------------------------------------- 4. Redis 缓存
head1 "4. Redis 缓存回填"
TTL=$(docker exec "$REDIS_CTN" redis-cli TTL "short:url:$CODE" 2>/dev/null | tr -d '\r')
if [ "$TTL" -gt 86000 ] 2>/dev/null; then ok "short:url:$CODE 存在，TTL=${TTL}s（≈24h）"; else bad "TTL=$TTL，期望 ≈86400"; fi
CACHED=$(docker exec "$REDIS_CTN" redis-cli GET "short:url:$CODE" 2>/dev/null | tr -d '\r')
if [ "$CACHED" = "$ORIGIN" ]; then ok "缓存值 = 原始 URL"; else bad "缓存值=$CACHED"; fi

# ---------------------------------------------------------------- 5. 缓存命中
head1 "5. 二次请求走缓存"
STATUS2=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/$CODE")
if [ "$STATUS2" = "302" ]; then ok "二次请求仍 302（命中缓存）"; else bad "二次请求 HTTP $STATUS2"; fi

# ---------------------------------------------------------------- 6. 非法短码
head1 "6. 非法短码返回真正的 HTTP 404"
# Step 4 起，短链路径不再沿用「HTTP 200 + 业务码」模型：
# 短链的客户端是浏览器，返回 HTTP 200 + JSON 会让用户看到一坨裸 JSON。
BAD_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/zzzzzzzzzz")
if [ "$BAD_STATUS" = "404" ]; then ok "非法短码 → HTTP 404"; else bad "非法短码 → HTTP $BAD_STATUS（期望 404）"; fi

# ---------------------------------------------------------------- 7. 静态资源未被吞
head1 "7. /doc.html 未被短码路由吞掉"
DOC=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/doc.html")
if [ "$DOC" = "200" ]; then ok "/doc.html → HTTP 200"; else bad "/doc.html → HTTP $DOC"; fi

# ---------------------------------------------------------------- 8. 布隆拦截
head1 "8. 布隆过滤器拦截（MySQL 零查询）"
mysql_q "SET GLOBAL general_log='ON'; SET GLOBAL log_output='TABLE';" >/dev/null
mysql_q "TRUNCATE TABLE mysql.general_log;" >/dev/null
for c in aaaaaaaaab aaaaaaaaac aaaaaaaaad aaaaaaaaae aaaaaaaaaf; do
  curl -s -o /dev/null "$BASE_URL/$c"
done
SQL_CNT=$(mysql_q "SELECT COUNT(*) FROM mysql.general_log WHERE argument LIKE '%short_url%' AND command_type IN ('Query','Execute') AND argument NOT LIKE '%general_log%';")
mysql_q "SET GLOBAL general_log='OFF';" >/dev/null
if [ "${SQL_CNT:-1}" -eq 0 ]; then
  ok "5 次非法短码 → MySQL 中 short_url 语句数 = 0（全部被布隆拦下）"
else
  bad "MySQL 语句数 = $SQL_CNT，期望 0（布隆没拦住）"
fi

# ---------------------------------------------------------------- 汇总
printf '\n\033[36m== 汇总 ==\033[0m\n'
printf '  PASS=%d  FAIL=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] && printf '  \033[32mStep 3 全部判据通过\033[0m\n' && exit 0
printf '  \033[31m存在失败项\033[0m\n' && exit 1
