#!/usr/bin/env bash
# ============================================================================
# Step 6 验收脚本：管理接口（详情 / 我的短链 / 删除 / 统计）+ 越权校验
#
# 用法：  bash scripts/verify-step6.sh
# 前置：  docker compose up -d 且应用 healthy
#
# 判据（🔴 越权是红线，任何一条 FAIL 都不能算完成）：
#   1. 详情：自己的短链 → 200 + 字段正确，且不带 userId
#   2. 越权读：用 B 的 token 查 A 的短链 → 403，且响应里绝不能出现 originalUrl
#   3. 越权删：B 删 A 的短链 → 403，且 A 的短链仍然存在
#   4. 越权统计：B 查 A 的统计 → 403
#   5. 未登录访问管理接口 → 401
#   6. 分页：size=5 → 返回 5 条，total 等于总数
#   7. 分页 size 上限：传 100000 → 被夹到 100
#   8. 软删：删除后跳转 404 + 列表里查不到（缓存被清是关键）
#   9. 统计：手工插样本 → PV/UV 正确（UV 按 IP 去重）
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

mysql_q() {
  docker exec "$MYSQL_CTN" mysql -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 \
    -N -B -e "SET NAMES utf8mb4; $1" "$DB_NAME" 2>/dev/null
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

A_USER="verify_s6_a_$$"; B_USER="verify_s6_b_$$"; PWD_="pass123456"

register() {   # register <username> → 打印 token
  curl -s -X POST "$BASE_URL/api/user/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$PWD_\"}" >/dev/null
  curl -s -X POST "$BASE_URL/api/user/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$PWD_\"}" | jf data token
}

mkcode() {   # mkcode <token> <url>
  curl -s -X POST "$BASE_URL/api/short/create" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $1" \
    -d "{\"originalUrl\":\"$2\",\"expireDays\":7}" | jf data shortCode
}

# ---------------------------------------------------------------- 0. 准备
head1 "0. 准备两个用户"
TOKEN_A=$(register "$A_USER")
TOKEN_B=$(register "$B_USER")
[ -n "$TOKEN_A" ] && [ -n "$TOKEN_B" ] && ok "A / B 两个用户就绪" || { bad "注册或登录失败"; exit 1; }

# 清掉限流计数，避免前面脚本留下的配额影响
docker exec "$REDIS_CTN" sh -c \
  "redis-cli --scan --pattern 'rate:*' | xargs -r redis-cli DEL" >/dev/null 2>&1

CODE_A=$(mkcode "$TOKEN_A" "https://example.com/secret-of-A")
[ -n "$CODE_A" ] && ok "A 创建短链 $CODE_A" || { bad "A 创建短链失败"; exit 1; }

# ---------------------------------------------------------------- 1. 详情
head1 "1. 详情（自己的）"
INFO=$(curl -s -H "Authorization: Bearer $TOKEN_A" "$BASE_URL/api/short/info/$CODE_A")
GOT=$(echo "$INFO" | jf data shortCode)
ORIG=$(echo "$INFO" | jf data originalUrl)
[ "$GOT" = "$CODE_A" ] && ok "返回 shortCode 正确" || bad "shortCode=$GOT"
[ "$ORIG" = "https://example.com/secret-of-A" ] && ok "返回 originalUrl 正确" || bad "originalUrl=$ORIG"
echo "$INFO" | grep -q '"userId"' && bad "响应里带了 userId（不该暴露）" || ok "响应未暴露 userId"

# ---------------------------------------------------------------- 2. 越权读
head1 "2. 🔴 越权读（B 查 A 的短链必须 403）"
B_INFO=$(curl -s -H "Authorization: Bearer $TOKEN_B" "$BASE_URL/api/short/info/$CODE_A")
B_CODE=$(echo "$B_INFO" | jf code)
[ "$B_CODE" = "403" ] && ok "业务码 = 403" || bad "业务码 = $B_CODE（期望 403）"
echo "$B_INFO" | grep -q "secret-of-A" && bad "🔴 响应里泄露了 A 的原始链接！" || ok "响应未泄露 A 的原始链接"

# ---------------------------------------------------------------- 3. 越权删
head1 "3. 🔴 越权删（B 删 A 的短链必须 403，且数据不变）"
B_DEL=$(curl -s -X DELETE -H "Authorization: Bearer $TOKEN_B" "$BASE_URL/api/short/$CODE_A")
B_DEL_CODE=$(echo "$B_DEL" | jf code)
[ "$B_DEL_CODE" = "403" ] && ok "业务码 = 403" || bad "业务码 = $B_DEL_CODE（期望 403）"
ST=$(mysql_q "SELECT status FROM short_url WHERE short_code='$CODE_A';")
[ "$ST" = "1" ] && ok "A 的短链仍在（status=1）" || bad "status=$ST，数据被改了！"

# ---------------------------------------------------------------- 4. 越权统计
head1 "4. 🔴 越权统计"
B_ST=$(curl -s -H "Authorization: Bearer $TOKEN_B" "$BASE_URL/api/short/stats/$CODE_A" | jf code)
[ "$B_ST" = "403" ] && ok "业务码 = 403" || bad "业务码 = $B_ST（期望 403）"

# ---------------------------------------------------------------- 5. 未登录
head1 "5. 未登录访问管理接口 → 401"
NOAUTH=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/short/info/$CODE_A")
[ "$NOAUTH" = "401" ] && ok "未登录 → HTTP 401" || bad "未登录 → HTTP $NOAUTH（期望 401）"
NOAUTH2=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/short/my")
[ "$NOAUTH2" = "401" ] && ok "/api/short/my 未登录 → 401" || bad "HTTP $NOAUTH2"

# ---------------------------------------------------------------- 6. 分页
head1 "6. 分页"
for i in 1 2 3 4 5 6 7; do mkcode "$TOKEN_A" "https://example.com/page/$i" >/dev/null; done
P1=$(curl -s -H "Authorization: Bearer $TOKEN_A" "$BASE_URL/api/short/my?current=1&size=5")
TOTAL=$(echo "$P1" | jf data total)
NREC=$(echo "$P1" | python -c "import sys,json;print(len(json.load(sys.stdin)['data']['records']))")
echo "  total=$TOTAL ; 本页条数=$NREC"
[ "${NREC:-0}" -eq 5 ] && ok "size=5 → 返回 5 条" || bad "返回 $NREC 条，期望 5"
[ "${TOTAL:-0}" -ge 8 ] && ok "total = $TOTAL（≥ 8，含前面创建的）" || bad "total=$TOTAL"
# 结果必须全部属于 A
OTHER=$(echo "$P1" | python -c "
import sys,json
recs=json.load(sys.stdin)['data']['records']
print(sum(1 for r in recs if 'page' not in r['originalUrl'] and 'secret-of-A' not in r['originalUrl']))")
[ "${OTHER:-1}" -eq 0 ] && ok "列表里没有别人的短链（userId 过滤生效）" || bad "列表里混入了 $OTHER 条别人的短链"

# ---------------------------------------------------------------- 7. size 上限
head1 "7. 分页 size 上限（防 ?size=100000 拖垮库）"
PSZ=$(curl -s -H "Authorization: Bearer $TOKEN_A" "$BASE_URL/api/short/my?size=100000" | jf data size)
[ "${PSZ:-0}" -eq 100 ] && ok "size 被夹到 100" || bad "size=$PSZ，期望 100"

# ---------------------------------------------------------------- 8. 软删
head1 "8. 软删：删除后必须跳转 404（缓存被清是关键）"
# 先访问一次让缓存写进去，再删 —— 这样能验证「删除真的把缓存清了」
curl -s -o /dev/null "$BASE_URL/$CODE_A"
CACHED_BEFORE=$(redis_cli EXISTS "short:url:$CODE_A")
[ "$CACHED_BEFORE" = "1" ] && ok "删除前缓存存在（构造出「缓存里还有映射」的场景）" || bad "缓存没写进去，测不到清除逻辑"

DEL=$(curl -s -X DELETE -H "Authorization: Bearer $TOKEN_A" "$BASE_URL/api/short/$CODE_A")
DEL_CODE=$(echo "$DEL" | jf code)
[ "$DEL_CODE" = "0" ] && ok "A 删自己的短链成功" || bad "删除返回 code=$DEL_CODE"

CACHED_AFTER=$(redis_cli EXISTS "short:url:$CODE_A")
[ "$CACHED_AFTER" = "0" ] && ok "🔴 缓存已被清除（否则删除不生效、短链仍能跳转）" || bad "缓存仍在，删除形同虚设！"

REDIRECT=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/$CODE_A")
[ "$REDIRECT" = "404" ] && ok "删除后跳转 → HTTP 404" || bad "删除后跳转 → HTTP $REDIRECT（仍可访问！）"

ST2=$(mysql_q "SELECT status FROM short_url WHERE short_code='$CODE_A';")
[ "$ST2" = "2" ] && ok "DB 里是软删（status=2，行还在）" || bad "status=$ST2"

INFO_AFTER=$(curl -s -H "Authorization: Bearer $TOKEN_A" "$BASE_URL/api/short/info/$CODE_A" | jf code)
[ "$INFO_AFTER" = "404" ] && ok "详情也查不到了" || bad "详情返回 code=$INFO_AFTER"

# ---------------------------------------------------------------- 9. 统计
head1 "9. 统计：PV/UV（UV 按 IP 去重）"
CODE_S=$(mkcode "$TOKEN_A" "https://example.com/stats")
mysql_q "INSERT INTO access_log (short_code, ip, access_time) VALUES
  ('$CODE_S','10.0.0.1',NOW()),
  ('$CODE_S','10.0.0.1',NOW()),
  ('$CODE_S','10.0.0.2',NOW());" >/dev/null
S=$(curl -s -H "Authorization: Bearer $TOKEN_A" "$BASE_URL/api/short/stats/$CODE_S")
TPV=$(echo "$S" | jf data totalPv)
TUV=$(echo "$S" | jf data totalUv)
echo "  totalPv=$TPV ; totalUv=$TUV"
[ "${TPV:-0}" -eq 3 ] && ok "PV = 3（3 条日志）" || bad "PV=$TPV，期望 3"
[ "${TUV:-0}" -eq 2 ] && ok "UV = 2（同 IP 去重）" || bad "UV=$TUV，期望 2"
DAYS=$(echo "$S" | python -c "import sys,json;print(len(json.load(sys.stdin)['data']['daily']))")
[ "${DAYS:-0}" -ge 1 ] && ok "按天明细有 $DAYS 条" || bad "按天明细为空"

# ---------------------------------------------------------------- 汇总
printf '\n\033[36m== 汇总 ==\033[0m\n'
printf '  PASS=%d  FAIL=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] && printf '  \033[32mStep 6 全部判据通过\033[0m\n' && exit 0
printf '  \033[31m存在失败项\033[0m\n' && exit 1
