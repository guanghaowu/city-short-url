#!/usr/bin/env bash
# ============================================================================
# Step 5 验收脚本：滑动窗口限流防爬（Redis ZSet + Lua + 注解 AOP）
#
# 用法：  bash scripts/verify-step5.sh
# 前置：  docker compose up -d 且应用 healthy，限流默认开启
#
# 判据：
#   1. /api/health 的 features.rateLimit = ON
#   2. 并发 30 次创建短链 → Redis ZSet 成员数必须 == 30
#      （验证 PRD 骨架里「用时间戳当 member」的 bug 已修：member 不唯一时
#        ZADD 会更新 score 而不是新增成员，计数会少算）
#   3. 串行 70 次 → 前 60 次 HTTP 200，第 61 次起 HTTP 429
#   4. 换 X-Forwarded-For 后立刻又能创建 → 配额按 IP 独立
#      （同时暴露一个安全问题：XFF 可伪造，生产必须让 Nginx 覆盖该头）
#   5. Redis 里限流 key 的类型是 zset，成员数 == 阈值
#   6. 等窗口滑过（61s）后又能创建 → 窗口真的在滑动，不是永久封禁
# ============================================================================
set -u

BASE_URL="${BASE_URL:-http://localhost:8082}"
MYSQL_CTN="${MYSQL_CTN:-city-mysql}"
REDIS_CTN="${REDIS_CTN:-city-redis}"
DB_USER="root"; DB_PASS="123456"; DB_NAME="city_url"

PASS=0; FAIL=0
ok()   { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1"; }
head1() { printf '\n\033[36m== %s ==\033[0m\n' "$1"; }

redis_cli() { docker exec "$REDIS_CTN" redis-cli "$@" 2>/dev/null | tr -d '\r'; }
clear_rate() {
  docker exec "$REDIS_CTN" sh -c \
    "redis-cli --scan --pattern 'rate:*' | xargs -r redis-cli DEL" >/dev/null 2>&1
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

USER_NAME="verify_s5_$$"; USER_PASS="pass123456"

# ---------------------------------------------------------------- 0. 前置
head1 "0. 前置检查"
curl -s -o /dev/null -m 5 "$BASE_URL/api/health" || { bad "应用不可达"; exit 1; }
RL=$(curl -s "$BASE_URL/api/health" | jf data features rateLimit)
if [ "$RL" = "ON" ]; then ok "限流开启（features.rateLimit=$RL）"; else bad "限流状态 = $RL（期望 ON）"; fi

curl -s -X POST "$BASE_URL/api/user/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}" >/dev/null
TOKEN=$(curl -s -X POST "$BASE_URL/api/user/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}" | jf data token)
[ -n "$TOKEN" ] && ok "登录成功" || { bad "登录失败"; exit 1; }

create_one() {   # create_one [extra-curl-args...]
  curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/short/create" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
    "$@" -d '{"originalUrl":"https://example.com/rate","expireDays":7}'
}

# ---------------------------------------------------------------- 1. member 唯一性
head1 "1. 并发 30 次 → ZSet 成员数必须 = 30（PRD 骨架 bug 已修）"
clear_rate
# 30 个真并发：member 若不唯一，ZADD 会覆盖、ZCARD 会 < 30。
# ⚠ 不要写成 `xargs -P 30 -I{} bash -c "..."`：子 shell 继承不到 TOKEN 变量，
#   请求会全部 401，根本走不到切面，测出来 ZCARD=0 是脚本的锅不是代码的锅。
#   让父 shell 直接展开变量即可。
seq 1 30 | xargs -P 30 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST "$BASE_URL/api/short/create" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"originalUrl":"https://example.com/rate","expireDays":7}' | sort | uniq -c
KEY=$(redis_cli --scan --pattern 'rate:short:create:*' | head -1)
CARD=$(redis_cli ZCARD "$KEY")
echo "  key=$KEY ; ZCARD=$CARD"
if [ "${CARD:-0}" -eq 30 ]; then
  ok "并发 30 次被完整记账（member 唯一，无覆盖）"
else
  bad "ZCARD=$CARD，期望 30 → member 可能不唯一，限流会少算"
fi

# ---------------------------------------------------------------- 2. 阈值与 429
head1 "2. 串行 70 次 → 前 60 次 200，第 61 次起 429"
clear_rate
OKN=0; RLN=0
for i in $(seq 1 70); do
  c=$(create_one)
  if [ "$c" = "429" ]; then RLN=$((RLN + 1)); else OKN=$((OKN + 1)); fi
done
echo "  200 次数=$OKN ；429 次数=$RLN"
if [ "$OKN" -eq 60 ] && [ "$RLN" -eq 10 ]; then
  ok "阈值生效：60 次放行 + 10 次拦截"
else
  bad "期望 60 放行 / 10 拦截，实际 $OKN / $RLN"
fi

# ---------------------------------------------------------------- 3. 按 IP 隔离
head1 "3. 换 X-Forwarded-For → 配额独立（并暴露伪造风险）"
ALT=$(create_one -H 'X-Forwarded-For: 203.0.113.99')
if [ "$ALT" = "200" ]; then
  ok "换 IP 后立刻又能创建 → 配额按 IP 独立"
else
  bad "换 IP 后仍为 $ALT，说明维度没生效"
fi
ALT_KEY=$(redis_cli --scan --pattern 'rate:short:create:203.0.113.99' | head -1)
if [ -n "$ALT_KEY" ]; then ok "已为伪造 IP 建独立 key（⚠ 这也证明 XFF 可被伪造，生产须由 Nginx 覆盖该头）"; else bad "未建独立 key"; fi

# ---------------------------------------------------------------- 4. ZSet 结构
head1 "4. Redis 结构：类型是 zset，成员数 == 阈值"
MAIN_KEY=$(redis_cli --scan --pattern 'rate:short:create:*' | grep -v '203.0.113.99' | head -1)
TYPE=$(redis_cli TYPE "$MAIN_KEY")
if [ "$TYPE" = "zset" ]; then ok "$MAIN_KEY 类型为 zset"; else bad "类型 = $TYPE"; fi
CARD2=$(redis_cli ZCARD "$MAIN_KEY")
if [ "${CARD2:-0}" -eq 60 ]; then ok "窗口内成员数 = 60（等于阈值）"; else bad "成员数 = $CARD2，期望 60"; fi

# ---------------------------------------------------------------- 5. 窗口滑动
head1 "5. 等窗口滑过（61s）后又能创建"
printf '  等待 61 秒'
for i in $(seq 1 61); do sleep 1; printf '.'; done
printf '\n'
AFTER=$(create_one)
if [ "$AFTER" = "200" ]; then ok "等待后恢复放行 → 窗口真的在滑动（不是永久封禁）"; else bad "等待后仍为 $AFTER"; fi

# ---------------------------------------------------------------- 汇总
printf '\n\033[36m== 汇总 ==\033[0m\n'
printf '  PASS=%d  FAIL=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] && printf '  \033[32mStep 5 全部判据通过\033[0m\n' && exit 0
printf '  \033[31m存在失败项\033[0m\n' && exit 1
