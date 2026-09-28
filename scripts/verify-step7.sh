#!/usr/bin/env bash
# ============================================================================
# Step 7 验收脚本：MQ 异步化（访问日志异步落库）
#
# 用法：  bash scripts/verify-step7.sh
# 前置：  docker compose up -d 且应用 healthy、rocketmq-broker 已起来
#
# 判据：
#   1. features.accessLog == MQ（开关自证）
#   2. 3 次成功跳转 → access_log 出现 3 条（异步落库确实生效）
#   3. 🔴 落库的 INSERT 发生在 ConsumeMessageThread 上，不是 http-nio 线程
#      —— 这是「异步」最硬的证据：请求线程根本没碰过 access_log 表
#   4. 落库字段完整（ip / user_agent / access_time 都不为空）
#   5. 404 的短码不产生日志（PV 只统计有效跳转）
#   6. 统计接口 PV 与 access_log 实际行数一致
#   7. 🔴 fail-open：停掉 broker 后跳转仍是 302（MQ 挂了不能拖垮主链路）
#   8. 恢复：broker 重启后日志继续落库
#
# ⚠ 性能对照（MQ vs SYNC vs OFF 的延迟/吞吐）不在本脚本里 ——
#   见 `bash bench/run-bench.sh accesslog`，它会重建容器切模式，不适合放进验收脚本。
# ============================================================================
set -u

BASE_URL="${BASE_URL:-http://localhost:8082}"
APP_CTN="${APP_CTN:-city-url-app}"
MYSQL_CTN="${MYSQL_CTN:-city-mysql}"
REDIS_CTN="${REDIS_CTN:-city-redis}"
BROKER_CTN="${BROKER_CTN:-city-rocketmq-broker}"
DB_USER="root"; DB_PASS="123456"; DB_NAME="city_url"

PASS=0; FAIL=0
ok()   { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1"; }
info() { printf '  \033[90m%s\033[0m\n' "$1"; }
head1() { printf '\n\033[36m== %s ==\033[0m\n' "$1"; }

mysql_q() {
  docker exec "$MYSQL_CTN" mysql -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 \
    -N -B -e "SET NAMES utf8mb4; $1" "$DB_NAME" 2>/dev/null | tr -d '\r'
}
redis_cli() { docker exec "$REDIS_CTN" redis-cli "$@" 2>/dev/null | tr -d '\r'; }
jf() { python -c "
import sys, json
try:
    d = json.load(sys.stdin)
    for k in sys.argv[1:]:
        d = d[int(k)] if k.lstrip('-').isdigit() else d[k]
    if d is not None: print(d)
except Exception: pass
" "$@"; }

PWD_="pass123456"
USER_="verify_s7_$$"

register() {
  curl -s -X POST "$BASE_URL/api/user/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$PWD_\"}" >/dev/null
  curl -s -X POST "$BASE_URL/api/user/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$PWD_\"}" | jf data token
}
mkcode() {
  curl -s -X POST "$BASE_URL/api/short/create" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $1" \
    -d "{\"originalUrl\":\"$2\",\"expireDays\":7}" | jf data shortCode
}
# 等 access_log 里某个短码达到期望行数（异步落库需要时间，最多等 15s）
wait_rows() {   # wait_rows <code> <want> [max_sec]
  local code="$1" want="$2" max="${3:-15}" i=0 n=0
  while [ "$i" -lt "$max" ]; do
    n=$(mysql_q "SELECT COUNT(*) FROM access_log WHERE short_code='$code';")
    [ "${n:-0}" -ge "$want" ] && { echo "$n"; return 0; }
    sleep 1; i=$((i + 1))
  done
  echo "${n:-0}"; return 1
}

# ---------------------------------------------------------------- 0. 前置
head1 "0. 前置检查"
HEALTH=$(curl -s -m 5 "$BASE_URL/api/health")
AL_MODE=$(echo "$HEALTH" | jf data features accessLog)
[ "$AL_MODE" = "MQ" ] && ok "features.accessLog = MQ（异步模式已生效）" \
  || bad "features.accessLog = '$AL_MODE'（期望 MQ；若是 SYNC 说明容器 env 没生效）"

BROKER_UP=$(docker ps --filter "name=$BROKER_CTN" --filter "status=running" --format '{{.Names}}')
[ "$BROKER_UP" = "$BROKER_CTN" ] && ok "broker 容器在跑" || bad "broker 未运行，先 docker compose up -d"

TOKEN=$(register "$USER_")
[ -n "$TOKEN" ] && ok "测试用户就绪" || { bad "注册/登录失败"; exit 1; }
docker exec "$REDIS_CTN" sh -c \
  "redis-cli --scan --pattern 'rate:*' | xargs -r redis-cli DEL" >/dev/null 2>&1

# ---------------------------------------------------------------- 1. 异步落库
head1 "1. 成功跳转 → 异步落库"
CODE=$(mkcode "$TOKEN" "https://example.com/step7-async")
[ -n "$CODE" ] && ok "创建短链 $CODE" || { bad "创建短链失败"; exit 1; }

MARK=$(date +%s)   # 记下时刻，用于只数这一段的应用日志
for i in 1 2 3; do
  curl -s -o /dev/null -H 'User-Agent: verify-step7/1.0' -H 'Referer: https://verify.local/' "$BASE_URL/$CODE"
done
ROWS=$(wait_rows "$CODE" 3 15)
[ "${ROWS:-0}" -eq 3 ] && ok "3 次跳转 → access_log 出现 3 条" \
  || bad "access_log 只有 $ROWS 条（期望 3）"

# ---------------------------------------------------------------- 2. 异步的铁证
head1 "2. 🔴 异步的铁证：落库跑在消费者线程上"
# ⚠ 这里**不能**去数 MyBatis 的 "Preparing:" 日志 —— 它在 log-impl=StdOutImpl 下
#   是直接 System.out.println 的，**不经过日志框架**，因此不会带上日志格式里的
#   [%thread] 前缀，根本看不出是哪个线程执行的。
#   要证明「INSERT 不是请求线程做的」，必须用应用自己的 SLF4J 日志（带 %thread）。
#   所以判定依据是 AccessLogConsumer 里那行 debug 日志。
sleep 2
CONSUME_N=$(docker logs --since "$MARK" "$APP_CTN" 2>&1 | grep '访问日志落库' | grep -c 'ConsumeMessageThread' || true)
HTTP_N=$(docker logs --since "$MARK" "$APP_CTN" 2>&1 | grep '访问日志落库' | grep -c 'http-nio' || true)
info "消费者线程落库 = $CONSUME_N 条 ; 请求线程落库 = $HTTP_N 条"
[ "${CONSUME_N:-0}" -ge 3 ] && ok "落库由 ConsumeMessageThread 执行（$CONSUME_N 条）" \
  || bad "消费者线程落库 $CONSUME_N 条（期望 ≥3）—— 异步链路可能没走通"
[ "${HTTP_N:-1}" -eq 0 ] && ok "🔴 请求线程一次都没落库（异步解耦成立）" \
  || bad "请求线程落库了 $HTTP_N 条 —— 没解耦成功！"

# ---------------------------------------------------------------- 3. 字段完整
head1 "3. 落库字段完整性"
ROW=$(mysql_q "SELECT CONCAT_WS('|', IFNULL(ip,'<NULL>'), IFNULL(user_agent,'<NULL>'), IFNULL(access_time,'<NULL>')) \
               FROM access_log WHERE short_code='$CODE' ORDER BY id DESC LIMIT 1;")
IP=$(echo "$ROW" | cut -d'|' -f1); UA=$(echo "$ROW" | cut -d'|' -f2); AT=$(echo "$ROW" | cut -d'|' -f3)
info "ip=$IP  ua=$UA  access_time=$AT"
[ "$IP" != "<NULL>" ] && [ -n "$IP" ] && ok "ip 已落库（$IP）" || bad "ip 为空"
[ "$UA" = "verify-step7/1.0" ] && ok "user_agent 原样落库" || bad "user_agent=$UA（期望 verify-step7/1.0）"
[ "$AT" != "<NULL>" ] && ok "access_time 已落库" || bad "access_time 为空"

# access_time 必须接近「刚才」，不能是 now() 造成的偏差
AGE=$(mysql_q "SELECT TIMESTAMPDIFF(SECOND, MAX(access_time), NOW()) FROM access_log WHERE short_code='$CODE';")
[ "${AGE:-999}" -le 120 ] && ok "access_time 是访问时刻（距今 ${AGE}s）" || bad "access_time 距今 ${AGE}s，偏差过大"

# ---------------------------------------------------------------- 4. 404 不计 PV
head1 "4. 404 的短码不产生日志（PV 只统计有效跳转）"
FAKE=$(python -c "import random,string;print(''.join(random.choice(string.ascii_letters) for _ in range(10)))")
HTTP404=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/$FAKE")
[ "$HTTP404" = "404" ] && ok "随机非法短码 → HTTP 404" || bad "HTTP $HTTP404（期望 404）"
sleep 2
FAKE_ROWS=$(mysql_q "SELECT COUNT(*) FROM access_log WHERE short_code='$FAKE';")
[ "${FAKE_ROWS:-1}" -eq 0 ] && ok "404 没有写入 access_log" || bad "404 也写入了 $FAKE_ROWS 条日志"

# ---------------------------------------------------------------- 5. 统计一致性
head1 "5. 统计接口 PV 与 access_log 行数一致"
DB_PV=$(mysql_q "SELECT COUNT(*) FROM access_log WHERE short_code='$CODE';")
API_PV=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/short/stats/$CODE" | jf data totalPv)
info "DB 行数 = $DB_PV ; 接口 PV = $API_PV"
[ "${API_PV:-0}" -eq "${DB_PV:-0}" ] && [ "${DB_PV:-0}" -ge 3 ] \
  && ok "PV 一致（$API_PV）" || bad "PV 不一致：接口=$API_PV DB=$DB_PV"

# ---------------------------------------------------------------- 6. fail-open
head1 "6. 🔴 fail-open：broker 停机后跳转仍可用"
CODE2=$(mkcode "$TOKEN" "https://example.com/step7-failopen")
curl -s -o /dev/null "$BASE_URL/$CODE2"          # 先访问一次，让路由信息进客户端缓存
sleep 1

info "停止 broker 容器..."
docker stop "$BROKER_CTN" >/dev/null 2>&1
sleep 3

OUT=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' -m 20 "$BASE_URL/$CODE2")
HTTP_CODE=$(echo "$OUT" | cut -d' ' -f1)
COST=$(echo "$OUT" | cut -d' ' -f2)
[ "$HTTP_CODE" = "302" ] && ok "broker 挂了，跳转仍是 302（耗时 ${COST}s）" \
  || bad "broker 挂了 → HTTP $HTTP_CODE（跳转被拖垮了！fail-open 失效）"
info "⚠ 注意：fail-open 保证的是「不失败」，不保证「不变慢」—— 详见压测报告的局限一节"

# 短码缓存命中时更快，这里顺带确认「MQ 挂了不影响缓存链路」
CACHED=$(redis_cli EXISTS "short:url:$CODE2")
[ "$CACHED" = "1" ] && ok "缓存链路不受 MQ 影响（缓存仍在）" || bad "缓存丢失"

# ---------------------------------------------------------------- 7. 恢复
head1 "7. 恢复：broker 重启后日志继续落库"
info "启动 broker 容器（需等它向 namesrv 重新注册）..."
docker start "$BROKER_CTN" >/dev/null 2>&1
READY=0
for i in $(seq 1 40); do
  # broker 注册完成的信号：容器日志里出现 "boot success" / 或直接试投递
  if docker logs --tail 200 "$BROKER_CTN" 2>&1 | grep -q 'boot success'; then READY=1; break; fi
  sleep 3
done
[ "$READY" = "1" ] && ok "broker 已重新就绪" || info "⚠ 没读到 'boot success'，继续尝试投递"

CODE3=$(mkcode "$TOKEN" "https://example.com/step7-recover")
for i in 1 2 3 4 5; do curl -s -o /dev/null "$BASE_URL/$CODE3"; sleep 1; done
RECOVERED=$(wait_rows "$CODE3" 1 30)
[ "${RECOVERED:-0}" -ge 1 ] && ok "broker 恢复后日志继续落库（$RECOVERED 条）" \
  || bad "broker 恢复后仍没有日志（$RECOVERED 条）"

# ---------------------------------------------------------------- 汇总
printf '\n\033[36m== 汇总 ==\033[0m\n'
printf '  PASS=%d  FAIL=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] && printf '  \033[32mStep 7 全部判据通过\033[0m\n' && exit 0
printf '  \033[31m存在失败项\033[0m\n' && exit 1
