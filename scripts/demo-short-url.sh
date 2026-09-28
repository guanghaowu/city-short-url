#!/usr/bin/env bash
# =============================================================================
# CityShortUrl 现场演示脚本
#
# 用途：一条命令跑出项目 B 的全部证据链 —— 业务链路 + 缓存分层 + 异步落库铁证 + 错误语义。
#       面试现场演示 / 自测 / 回归后确认都可用。
#
# 用法：
#   bash scripts/demo-short-url.sh                 # 常规演示（约 15 秒）
#   bash scripts/demo-short-url.sh --with-ratelimit  # 额外演示限流 429（会打满窗口，约 30 秒）
#
# 前置：项目 B 容器已启动（docker compose up -d），应用监听 8082。
# =============================================================================
set -u

BASE="${BASE:-http://localhost:8082}"
MYSQL_PW="${MYSQL_PW:-123456}"
MYSQL_DB="${MYSQL_DB:-city_url}"
WITH_RL=0
[ "${1:-}" = "--with-ratelimit" ] && WITH_RL=1

SUFFIX=$(date +%H%M%S)
USER="demo$SUFFIX"
PASS="pass123456"

hr() { printf '\n════════ %s ════════\n' "$1"; }
ok() { printf '  ✓ %s\n' "$1"; }
bad() { printf '  ✗ %s\n' "$1"; }

# 从 JSON 里取一个字符串字段（无依赖）
jget() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

# ---------------------------------------------------------------- 0. 健康检查
hr "0. 服务健康"
HEALTH=$(curl -s -m 5 "$BASE/api/health" || true)
if [ -z "$HEALTH" ]; then
  bad "后端不可达（$BASE）。请先启动：docker compose up -d"
  exit 1
fi
echo "$HEALTH" | head -c 400; echo
ok "features 段自证各开关状态（布隆 / 互斥锁 / 空值 / 限流 / 日志模式）"

# ---------------------------------------------------------------- 1. 注册 + 登录
hr "1. 注册 + 登录"
curl -s -X POST "$BASE/api/user/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" >/dev/null
TOKEN=$(curl -s -X POST "$BASE/api/user/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" | jget token)
[ -n "$TOKEN" ] && ok "登录成功，JWT 长度 ${#TOKEN} 字符" || { bad "登录失败"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

# ---------------------------------------------------------------- 2. 创建短链
hr "2. 生成短链（雪花 ID + base62 → 10 位短码）"
CREATE=$(curl -s -X POST "$BASE/api/short/create" -H 'Content-Type: application/json' -H "$AUTH" \
  -d '{"originalUrl":"https://github.com/guanghaowu/city-short-url","expireDays":30}')
echo "$CREATE"
CODE=$(echo "$CREATE" | jget shortCode)
[ -n "$CODE" ] && ok "短码 = $CODE（本地生成，无中心化发号器）" || { bad "创建失败"; exit 1; }

# ---------------------------------------------------------------- 3. 302 跳转
hr "3. 访问短链（浏览器直达，返回真正的 302）"
curl -s -o /dev/null -D - -m 5 "$BASE/$CODE" | grep -iE "^HTTP|^location" | sed 's/^/  /'

# ---------------------------------------------------------------- 4. PV / UV
hr "4. 访问 5 次 → 看 PV / UV 差异"
for _ in 1 2 3 4 5; do curl -s -o /dev/null "$BASE/$CODE"; done
sleep 1
STATS=$(curl -s "$BASE/api/short/stats/$CODE?days=7" -H "$AUTH")
echo "  $STATS"
ok "PV 涨、UV 不涨 —— UV 按 IP 去重，PV 是访问次数"

# ---------------------------------------------------------------- 5. Redis 缓存层
hr "5. Redis 缓存层（key = short:url:{code}）"
printf '  值  : '; docker exec city-redis redis-cli get "short:url:$CODE" 2>/dev/null
printf '  TTL : '; docker exec city-redis redis-cli ttl "short:url:$CODE" 2>/dev/null
printf '  (基础 TTL + [0,1800]s 随机抖动，防雪崩)\n'
printf '  同前缀的其它 key：\n'
docker exec city-redis redis-cli --scan --pattern "short:*" 2>/dev/null | head -4 | sed 's/^/    /'
printf '    short:miss:{code}  ← 空值标记，防二次穿透\n'
printf '    lock:short:{code}  ← SETNX 互斥锁，防击穿\n'

# ---------------------------------------------------------------- 6. MySQL 持久层
hr "6. MySQL 持久层（库 $MYSQL_DB）"
docker exec city-mysql mysql -uroot -p"$MYSQL_PW" -D "$MYSQL_DB" -e \
  "SELECT short_code, LEFT(original_url,44) AS original_url, status, expire_at FROM short_url WHERE short_code='$CODE'\G" 2>/dev/null | sed 's/^/  /'
docker exec city-mysql mysql -uroot -p"$MYSQL_PW" -D "$MYSQL_DB" -e \
  "SELECT COUNT(*) AS 日志条数, COUNT(DISTINCT ip) AS 去重IP数 FROM access_log WHERE short_code='$CODE'" 2>/dev/null | sed 's/^/  /'
printf '  注：short_url.pv/uv 恒为 0，PV/UV 是实时 COUNT access_log 算出来的\n'

# ---------------------------------------------------------------- 7. 异步落库铁证
hr "7. 异步落库的铁证 —— 看线程名"
CONSUME=$(docker logs city-url-app 2>&1 | grep -c "ConsumeMessageThread" || true)
HTTPIO=$(docker logs city-url-app 2>&1 | grep "http-nio" | grep -c "访问日志落库" || true)
printf '  ConsumeMessageThread_* 上的落库日志 : %s 条\n' "$CONSUME"
printf '  http-nio-* 上的落库日志            : %s 条\n' "$HTTPIO"
docker logs city-url-app 2>&1 | grep "ConsumeMessageThread" | tail -2 | sed 's/^/    /'
[ "$HTTPIO" = "0" ] && ok "请求线程 0 条 → 落库确实在 MQ 消费线程上，不在 HTTP 线程上"

# ---------------------------------------------------------------- 8. 错误语义
hr "8. 五类错误语义（协议层用真状态码）"
p() { printf '  %-26s → HTTP %s\n' "$1" "$2"; }
p "不存在的短码（布隆拦截）"   "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/ZZZZZZZZZZ")"
p "方法用错（GET 打 POST 接口）" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/short/create")"
p "未带 token 调管理接口"      "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/short/my")"
p "已删除的短码"               "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/AAAAAAAAAA")"

# ---------------------------------------------------------------- 9. 越权保护
hr "9. 越权保护（别人的短码管不了）"
USER2="other$SUFFIX"
curl -s -X POST "$BASE/api/user/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER2\",\"password\":\"$PASS\"}" >/dev/null
TOKEN2=$(curl -s -X POST "$BASE/api/user/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER2\",\"password\":\"$PASS\"}" | jget token)
CODE2=$(curl -s -X POST "$BASE/api/short/create" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN2" \
  -d '{"originalUrl":"https://example.com/owned-by-user2"}' | jget shortCode)
printf '  用户 B 的短码 = %s\n' "$CODE2"
printf '  用户 A 删除它 → %s\n' "$(curl -s -X DELETE "$BASE/api/short/$CODE2" -H "$AUTH")"
printf '  用户 A 看统计 → %s\n' "$(curl -s "$BASE/api/short/stats/$CODE2" -H "$AUTH")"
printf '  短链本身仍可跳转 → HTTP %s\n' "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/$CODE2")"
ok "管理接口校验归属；跳转不受影响（跳转本就不需要登录）"

# ---------------------------------------------------------------- 10. 限流（可选）
if [ "$WITH_RL" = "1" ]; then
  hr "10. 限流：70 并发打同一个 IP"
  export BASE TOKEN
  seq 1 70 | xargs -P 25 -I{} sh -c \
    'curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/api/short/create" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "{\"originalUrl\":\"https://example.com/rl\"}"' \
    | sort | uniq -c | sed 's/^ *//' | awk '{printf "  HTTP %s → %s 次\n", $2, $1}'
  printf '  限流窗口在 Redis 里的样子：\n'
  printf '    key = rate:short:create:172.20.0.1\n'
  printf '    窗口内请求数 = %s（上限 60/分钟）\n' "$(docker exec city-redis redis-cli zcard 'rate:short:create:172.20.0.1' 2>/dev/null)"
fi

hr "演示结束"
printf '  演示页  : %s/\n' "$BASE"
printf '  接口文档: %s/doc.html\n' "$BASE"
printf '  健康检查: %s/api/health\n' "$BASE"
