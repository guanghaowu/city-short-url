#!/usr/bin/env bash
# ============================================================================
# Step 4 压测编排：缓存三剑客的对照实验
#
# 用法：
#   bash bench/run-bench.sh            # 跑全部（含 2 次容器重建，约 10 分钟）
#   bash bench/run-bench.sh main       # 只跑默认配置下的场景（S1/S2/S3/S5）
#   bash bench/run-bench.sh nobloom    # 只跑「关布隆」对照（S4）
#   bash bench/run-bench.sh nomutex    # 只跑「关互斥锁」对照（S6）
#   bash bench/run-bench.sh accesslog  # Step 7：访问日志 MQ / SYNC / OFF 三组对照
#   bash bench/run-bench.sh restore    # 恢复默认配置
#
# ⚠ accesslog 需要 bench/out/codes.txt 已存在（先跑一次 seed），
#   且会重建容器 3 次（每组一次），约 6-8 分钟。
#
# 核心方法论：**没有对照组的性能数字不可信**。
# 所以每个「优化」都配一个把它关掉的基线组，两组跑同样的流量、同样的并发。
#
# DB 查询次数口径：MySQL performance_schema.events_statements_summary_by_digest
#   —— 统计「归一化后含 short_url 的 SELECT 语句」条数。
#   比在客户端猜、或开 general_log 全量记录都要准且轻。
# ============================================================================
set -u

# ---------------------------------------------------------------- 常量
BASE_URL="${BASE_URL:-http://localhost:8082}"
APP_CTN="city-url-app"
MYSQL_CTN="city-mysql"
REDIS_CTN="city-redis"
DB_USER="root"; DB_PASS="123456"; DB_NAME="city_url"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$(cd "$HERE/.." && pwd)"
OUT="$HERE/out"
CODES="$OUT/codes.txt"
RAW="$OUT/raw.jsonl"
REPORT="$HERE/RESULTS.md"
mkdir -p "$OUT"

SEED_COUNT="${SEED_COUNT:-2000}"     # 播种短链条数（冷/热路径用）
HOT_ROUNDS="${HOT_ROUNDS:-10}"       # 热路径轮数（2000 × 10 = 20000 次）
PENETRATE_N="${PENETRATE_N:-20000}"  # 穿透场景请求数
BREAKDOWN_N="${BREAKDOWN_N:-200}"    # 击穿场景并发数

NODE_BIN="$(command -v node 2>/dev/null || true)"
[ -z "$NODE_BIN" ] && NODE_BIN="C:/Users/Administrator/.workbuddy-ai/binaries/node/versions/22.22.2-3/node.exe"
PY_BIN="$(command -v python 2>/dev/null || true)"
[ -z "$PY_BIN" ] && PY_BIN="C:/Users/Administrator/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe"

# ---------------------------------------------------------------- 输出
log()  { printf '\n\033[36m===== %s =====\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
warn() { printf '  \033[33m%s\033[0m\n' "$*"; }
die()  { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 基础工具
mysql_q() {
  docker exec "$MYSQL_CTN" mysql -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 \
    -N -B -e "SET NAMES utf8mb4; $1" "$DB_NAME" 2>/dev/null
}

redis_cli() { docker exec "$REDIS_CTN" redis-cli "$@" 2>/dev/null | tr -d '\r'; }

# 清掉短链相关的全部缓存（映射 + 空值标记 + 残留锁），让下一次请求必然回源
clear_cache() {
  docker exec "$REDIS_CTN" sh -c \
    "redis-cli --scan --pattern 'short:url:*' | xargs -r redis-cli DEL; \
     redis-cli --scan --pattern 'short:miss:*' | xargs -r redis-cli DEL; \
     redis-cli --scan --pattern 'lock:short:*' | xargs -r redis-cli DEL" >/dev/null 2>&1
}

reset_digest() {
  mysql_q "TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;" >/dev/null
}

# 口径 A：performance_schema 归一化语句计数。
# 优点：不用开日志、开销极低。
# 缺点：⚠ 并发下会低估约 0.3%（实测，见下方 count_sql_log 的交叉校验）。
count_select() {
  mysql_q "SELECT IFNULL(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest \
           WHERE DIGEST_TEXT LIKE '%short_url%' AND DIGEST_TEXT LIKE 'SELECT%';" | tr -d '[:space:]'
}

# 口径 B：数应用自己打印的 MyBatis SQL 日志条数（mybatis-plus 的 StdOutImpl）。
# 这是**应用视角**的权威计数 —— 它发了几条语句，日志里就有几行。
# 代价是日志量大（每请求 2 行），所以只作为口径校验与关键用例的交叉验证。
count_sql_log() {
  docker logs --since "$1" "$APP_CTN" 2>&1 | grep -c 'Preparing:'
}

health_json() { curl -s -m 5 "$BASE_URL/api/health"; }

# 读 features 里的某个开关值（自证对照组成立，而不是靠嘴说）
feature() {
  health_json | "$PY_BIN" -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print(d['data']['features'].get('$1', ''))
except Exception:
    print('')
"
}

wait_feature() {   # key expected [max_sec]
  local key="$1" want="$2" max="${3:-150}" i=0
  while [ "$i" -lt "$max" ]; do
    [ "$(feature "$key")" = "$want" ] && return 0
    sleep 2; i=$((i + 2))
  done
  return 1
}

wait_up() {
  local i=0
  while [ "$i" -lt 150 ]; do
    curl -s -m 3 -o /dev/null "$BASE_URL/api/health" && return 0
    sleep 2; i=$((i + 2))
  done
  return 1
}

# 重建并重启应用容器。⚠ 必须重建镜像，否则跑的还是旧 jar（Step 2 踩过这个坑）
restart_app() {
  local desc="$1"; shift
  info "重启 city-url-app（$desc）..."
  ( cd "$PROJ" && env "$@" docker compose up -d --no-build --force-recreate city-url-app >/dev/null 2>&1 ) \
    || die "docker compose up 失败"
  wait_up || die "应用在 150s 内没起来"

  # 自证一：新代码真的进镜像了吗？
  # 如果 features 读不出来，说明容器里跑的还是旧 jar —— 大概率是 docker 构建命中了缓存。
  if [ -z "$(feature bloomFilter)" ]; then
    die "容器里没有 features 字段 → 跑的还是旧 jar。
    请执行：docker compose build --no-cache city-url-app"
  fi

  # 自证二：当前生效的开关是什么
  info "features: bloomFilter=$(feature bloomFilter) cacheMutex=$(feature cacheMutex) " \
       "nullCache=$(feature nullCache) rateLimit=$(feature rateLimit) accessLog=$(feature accessLog)"
}

# ---------------------------------------------------------------- 跑一个用例
declare -a ROWS=()          # Step 4（缓存三剑客）的结果行 → RESULTS.md
declare -a CALIB=()         # Step 4 的口径一致性校验行
declare -a ALROWS=()        # Step 7（访问日志）的结果行 → RESULTS-ACCESSLOG.md
declare -a ALLAG=()         # Step 7 的「消费端吞吐」观察
declare -A AL_QPS=() AL_P50=() AL_P90=() AL_P99=()   # 按模式存原始数字，供结论段动态计算
LAST_QPS="" LAST_P50="" LAST_P90="" LAST_P99=""      # run_case 最近一次的结果（供上面取值）

# run_case 把结果行塞进哪个桶：main（Step 4）/ al（Step 7）
# 用全局变量而不是多加一个位置参数，是为了不动已有的 6 个调用点。
ROW_BUCKET="main"

run_case() {   # run_case <编号> <说明> <开关状态> -- <bench.mjs 参数...>
  local id="$1" desc="$2" flags="$3"; shift 3
  [ "${1:-}" = "--" ] && shift

  # 口径 B（数日志）需要一个起始时刻：先静置 2s，保证上一用例的日志已全部落盘，
  # 再取「当前秒」作为 --since，避免把上一用例的语句算进来。
  sleep 2
  local mark; mark=$(date +%s)

  reset_digest
  local json
  json=$("$NODE_BIN" "$HERE/bench.mjs" --base "$BASE_URL" --label "$id" "$@" 2>>"$OUT/bench.stderr")
  echo "$json" >> "$RAW"

  local sql_digest sql_log
  sql_digest=$(count_select)
  sql_log=$(count_sql_log "$mark")

  local qps p50 p90 p99 err bad total
  qps=$("$PY_BIN" -c "import json,sys;print(json.loads('''$json''')['qps'])")
  p50=$("$PY_BIN" -c "import json,sys;print(json.loads('''$json''')['p50'])")
  p90=$("$PY_BIN" -c "import json,sys;print(json.loads('''$json''')['p90'])")
  p99=$("$PY_BIN" -c "import json,sys;print(json.loads('''$json''')['p99'])")
  total=$("$PY_BIN" -c "import json,sys;print(json.loads('''$json''')['total'])")
  err=$("$PY_BIN" -c "import json,sys;print(json.loads('''$json''')['err'])")
  bad=$("$PY_BIN" -c "import json,sys;print(json.loads('''$json''')['bad'])")

  printf '  \033[32m%-22s\033[0m req=%-7s qps=%-9s p50=%-7s p90=%-7s p99=%-8s DB日志=%-8s DB_digest=%-8s err=%s bad=%s\n' \
    "$id" "$total" "$qps" "$p50" "$p90" "$p99" "$sql_log" "$sql_digest" "$err" "$bad"

  if [ "$ROW_BUCKET" = "al" ]; then
    ALROWS+=("| $id | $desc | $total | $qps | ${p50}ms | ${p90}ms | ${p99}ms | $err | $bad |")
    LAST_QPS="$qps"; LAST_P50="$p50"; LAST_P90="$p90"; LAST_P99="$p99"
    return 0
  fi

  ROWS+=("| $id | $desc | $flags | $total | $qps | ${p50}ms | ${p90}ms | ${p99}ms | **$sql_log** | $sql_digest |")
  CALIB+=("| $id | $total | $sql_log | $sql_digest | $(awk -v a="$sql_log" -v b="$sql_digest" \
    'BEGIN{ if (a>0) printf "%.2f%%", (a-b)*100.0/a; else print "-" }') |")
}

# ============================================================================
# 场景
# ============================================================================

phase_build() {
  log "构建镜像（改了 Java 代码，必须重建，否则跑的是旧 jar）"
  ( cd "$PROJ" && docker compose build city-url-app ) || die "构建失败"
  restart_app "默认配置" APP_BLOOM_ENABLED=true APP_CACHE_MUTEX_ENABLED=true APP_RATELIMIT_ENABLED=true
  wait_feature bloomFilter ON || die "布隆过滤器没有进入 ON 状态（可能还在 LOADING）"
  info "布隆已就绪，可以开始压测"
}

phase_seed() {
  log "播种：并发创建 $SEED_COUNT 条短链"
  # Step 5 起「创建短链」按 IP 限 60 次/分钟，播种 2000 条会撞上 → 先临时关掉
  restart_app "限流 OFF（播种用）" \
    APP_BLOOM_ENABLED=true APP_CACHE_MUTEX_ENABLED=true APP_RATELIMIT_ENABLED=false
  wait_feature bloomFilter ON || die "布隆没有就绪"
  wait_feature rateLimit OFF || die "限流没有被关掉，播种会失败"

  local json
  json=$("$NODE_BIN" "$HERE/bench.mjs" --mode seed --base "$BASE_URL" \
         --count "$SEED_COUNT" --out "$CODES" --concurrency 30)
  echo "$json"
  info "短码已写入 $CODES（$(wc -l < "$CODES" | tr -d ' ') 条）"

  # 播种完毕，恢复正常配置再压测
  restart_app "恢复默认" \
    APP_BLOOM_ENABLED=true APP_CACHE_MUTEX_ENABLED=true APP_RATELIMIT_ENABLED=true
  wait_feature bloomFilter ON || die "布隆没有就绪"
}

phase_main() {
  log "S1 冷路径：缓存清空后回源 DB"
  clear_cache
  run_case "S1-冷路径" "2000 个不同短码各请求 1 次（缓存全空 → 全部回源）" \
    "布隆ON 互斥锁ON 空值缓存ON" -- \
    --mode hit --codes "$CODES" --rounds 1 --concurrency 50

  log "S2b 热路径对照：同样 2000 个码、同样请求数，但缓存是热的"
  # 与 S1 严格同口径（同短码集合、同请求数、同并发），唯一差别是缓存冷/热
  run_case "S2b-热路径(同N)" "与 S1 完全相同的 2000 个短码与请求数，唯一差别是缓存已热" \
    "布隆ON 互斥锁ON 空值缓存ON" -- \
    --mode hit --codes "$CODES" --rounds 1 --concurrency 50

  log "S2 热路径：全部命中缓存（大样本）"
  # 缓存已被 S1/S2b 填充
  run_case "S2-热路径" "2000 个短码 × 10 轮 = 20000 次（全部命中 Redis）" \
    "布隆ON 互斥锁ON 空值缓存ON" -- \
    --mode hit --codes "$CODES" --rounds "$HOT_ROUNDS" --concurrency 50

  log "S3 缓存穿透：$PENETRATE_N 个互不相同的随机非法短码（布隆 ON）"
  run_case "S3-穿透(布隆ON)" "$PENETRATE_N 个随机非法短码，每个都是第一次见" \
    "布隆**ON** 互斥锁ON" -- \
    --mode invalid --requests "$PENETRATE_N" --concurrency 50

  log "S5 缓存击穿：$BREAKDOWN_N 并发同时打同一个刚被清空的热点码（互斥锁 ON）"
  HOT_CODE=$(head -1 "$CODES")
  info "热点码 = $HOT_CODE"
  redis_cli DEL "short:url:$HOT_CODE" >/dev/null
  run_case "S5-击穿(互斥锁ON)" "$BREAKDOWN_N 并发同时请求一个缓存刚失效的热点码" \
    "布隆ON 互斥锁**ON**" -- \
    --mode single --code "$HOT_CODE" --requests "$BREAKDOWN_N" --concurrency "$BREAKDOWN_N"
}

phase_nobloom() {
  log "S4 穿透对照组：关掉布隆，跑同样的流量"
  restart_app "布隆 OFF（压测对照）" APP_BLOOM_ENABLED=false APP_CACHE_MUTEX_ENABLED=true APP_RATELIMIT_ENABLED=true
  wait_feature bloomFilter OFF || die "布隆没有被关掉，对照不成立"
  warn "⚠ 对照组成立：bloomFilter=OFF"

  run_case "S4-穿透(布隆OFF)" "同样 $PENETRATE_N 个随机非法短码，作为 S3 的基线" \
    "布隆**OFF** 互斥锁ON" -- \
    --mode invalid --requests "$PENETRATE_N" --concurrency 50
}

phase_nomutex() {
  log "S6 击穿对照组：布隆恢复 ON、关掉互斥锁，跑同样的并发"
  restart_app "互斥锁 OFF（压测对照）" APP_BLOOM_ENABLED=true APP_CACHE_MUTEX_ENABLED=false APP_RATELIMIT_ENABLED=true
  wait_feature bloomFilter ON || die "布隆没有回到 ON"
  wait_feature cacheMutex OFF || die "互斥锁没有被关掉，对照不成立"
  warn "⚠ 对照组成立：cacheMutex=OFF"

  local hot="$1"
  [ -z "$hot" ] && hot=$(head -1 "$CODES")
  info "热点码 = $hot"
  redis_cli DEL "short:url:$hot" >/dev/null
  run_case "S6-击穿(互斥锁OFF)" "同样 $BREAKDOWN_N 并发请求热点码，作为 S5 的基线" \
    "布隆ON 互斥锁**OFF**" -- \
    --mode single --code "$hot" --requests "$BREAKDOWN_N" --concurrency "$BREAKDOWN_N"
}

phase_restore() {
  log "恢复默认配置（布隆 ON + 互斥锁 ON）"
  restart_app "恢复默认" APP_BLOOM_ENABLED=true APP_CACHE_MUTEX_ENABLED=true APP_RATELIMIT_ENABLED=true
  wait_feature bloomFilter ON || die "布隆没有回到 ON"
}

# ============================================================================
# Step 7：访问日志写入方式的对照实验（MQ 异步 / SYNC 同步 / OFF 不记）
# ============================================================================

al_rows() { mysql_q "SELECT COUNT(*) FROM access_log;" | tr -d '[:space:]'; }

# 百分比变化（a 相对 b）。b 为 0 时输出 "-"。
pct() { awk -v a="$1" -v b="$2" 'BEGIN{ if (b+0 > 0) printf "%+.0f%%", (a-b)*100.0/b; else print "-" }'; }

phase_accesslog() {
  log "Step 7 对照：访问日志 MQ / SYNC / OFF 三组，同流量、同并发、同一批短码"
  [ -s "$CODES" ] || die "缺少 $CODES —— 请先跑：bash bench/run-bench.sh seed"

  ROW_BUCKET="al"
  local mode

  for mode in MQ SYNC OFF; do
    log "S7-$mode：把访问日志切成 $mode 模式"
    restart_app "访问日志 $mode" \
      APP_BLOOM_ENABLED=true APP_CACHE_MUTEX_ENABLED=true APP_RATELIMIT_ENABLED=true \
      APP_ACCESS_LOG_MODE="$mode"
    wait_feature bloomFilter ON || die "布隆没有就绪"
    # ⚠ 这是本次对照能不能成立的关键：必须读到 features.accessLog == 期望值，
    #    否则可能是在「以为切了其实没切」的状态下跑完一整组，数字全废。
    wait_feature accessLog "$mode" || die "accessLog 没切到 $mode，对照不成立"
    warn "⚠ 对照组成立：accessLog=$mode"

    # 三组统一走**缓存命中**路径：先清缓存，再预热一轮把缓存填满。
    # 这样延迟差异只可能来自「记日志」这一件事，不掺 DB 回源。
    clear_cache
    "$NODE_BIN" "$HERE/bench.mjs" --mode hit --codes "$CODES" --rounds 1 \
      --concurrency 50 >/dev/null 2>>"$OUT/bench.stderr"

    local a_before a_imm a_after
    a_before=$(al_rows)
    run_case "S7-$mode" "缓存全命中，唯一变量=访问日志写入方式（$mode）" \
      "accessLog=$mode" -- \
      --mode hit --codes "$CODES" --rounds 1 --concurrency 50
    a_imm=$(al_rows)

    AL_QPS[$mode]="$LAST_QPS"; AL_P50[$mode]="$LAST_P50"
    AL_P90[$mode]="$LAST_P90"; AL_P99[$mode]="$LAST_P99"

    if [ "$mode" = "MQ" ]; then
      # 异步滞后的直接观察：压测跑完时还没落完的条数 vs 再等 5s 后累计的条数。
      # 差值 = 请求早已返回、消费者仍在补写的条数。
      sleep 5
      a_after=$(al_rows)
      ALLAG+=("| MQ | $((a_imm - a_before)) | $((a_after - a_before)) | $((a_after - a_imm)) |")
      info "异步滞后观察：跑完瞬间已落库 $((a_imm - a_before)) 条，5s 后 $((a_after - a_before)) 条，" \
           "其中 $((a_after - a_imm)) 条是请求返回之后才补上的"
    fi
  done

  ROW_BUCKET="main"

  # ⚠ 必须把容器恢复成默认模式：压测跑完停在 OFF 模式的话，
  #   后面所有「访问日志」相关的验收都会莫名其妙地失败。
  log "恢复访问日志默认模式（MQ）"
  restart_app "恢复访问日志默认模式" \
    APP_BLOOM_ENABLED=true APP_CACHE_MUTEX_ENABLED=true APP_RATELIMIT_ENABLED=true
  wait_feature accessLog MQ || warn "⚠ accessLog 没恢复到 MQ，请手动确认"
}

write_accesslog_report() {
  [ "${#ALROWS[@]}" -eq 0 ] && return 0
  local REPORT_AL="$HERE/RESULTS-ACCESSLOG.md"
  local cpu mem os
  cpu=$(docker exec "$APP_CTN" sh -c 'nproc' 2>/dev/null || echo '?')
  mem=$(docker exec "$APP_CTN" sh -c "free -m 2>/dev/null | awk '/Mem:/{print \$2\" MB\"}'" 2>/dev/null || echo '?')
  os=$(docker exec "$APP_CTN" sh -c 'cat /etc/os-release 2>/dev/null | head -1' 2>/dev/null || echo '?')

  cat > "$REPORT_AL" <<EOF
# 项目 B · Step 7 压测报告（访问日志：MQ 异步 vs SYNC 同步 vs OFF 不记）

> 由 \`bash bench/run-bench.sh accesslog\` 自动生成于 $(date '+%Y-%m-%d %H:%M:%S')
> 原始数据：\`bench/out/raw.jsonl\`

## 一、实验设计

**唯一变量是「访问日志的写入方式」**，其余全部相同：同一批短码、同样的请求数、
同样的并发、同一台机器、同一份代码（三个分支都在 \`AccessLogService.record()\` 里）。

| 模式 | 请求线程做什么 | 说明 |
|---|---|---|
| \`MQ\` | \`asyncSend\` 一条消息后立即返回 | 不等 broker ack；INSERT 由消费者线程做 |
| \`SYNC\` | 等 MySQL 写完 INSERT 才返回 | Step 7 之前的基线行为 |
| \`OFF\` | 什么都不做 | 净开销下界，用来判断「记日志本身」有多贵 |

三组都跑在**缓存全命中**路径上（每组先清缓存、再预热一轮），
所以延迟差异只可能来自记日志这一件事，不掺 DB 回源。

**开关自证**：每组开跑前都读 \`/api/health\` 的 \`features.accessLog\`，
读到期望值才继续 —— 避免「以为切了其实没切」的假对照。

## 二、结果

| 用例 | 说明 | 请求数 | QPS | p50 | p90 | p99 | err | bad |
|---|---|---|---|---|---|---|---|---|
$(printf '%s\n' "${ALROWS[@]}")

## 三、消费端吞吐观察

压测结束（脚本收尾统计完成后约 1~2 秒）立刻查一次 \`access_log\` 行数，再等 5 秒查一次：

| 模式 | 跑完时已落库 | 5s 后累计 | 差值（请求返回后才补上的） |
|---|---|---|---|
$(printf '%s\n' "${ALLAG[@]}")

**怎么读这张表**：
- 差值 = 0 说明消费端**完全跟得上** —— 20 个消费线程消化 2000 条消息绰绰有余，
  日志在请求返回后极短时间内就全部落库了。这是**好消息**，不是「异步没生效」。
- 异步性本身由 \`scripts/verify-step7.sh\` 的第 2 项判据证明：**落库日志出现在
  \`ConsumeMessageThread_*\` 线程上、请求线程 0 条**（用线程名，不靠推断）。
- 滞后只会在**消费能力被打满**时出现，那时的表现是压测结束后 \`access_log\` 行数继续增长。

## 四、结论与局限

### 4.1 本组结论

| 对比 | QPS | p50 | p99 |
|---|---|---|---|
| SYNC → MQ | $(printf '%s' "${AL_QPS[SYNC]}") → $(printf '%s' "${AL_QPS[MQ]}")（**$(pct "${AL_QPS[MQ]}" "${AL_QPS[SYNC]}")**） | $(printf '%s' "${AL_P50[SYNC]}")ms → $(printf '%s' "${AL_P50[MQ]}")ms（**$(pct "${AL_P50[MQ]}" "${AL_P50[SYNC]}")**） | $(printf '%s' "${AL_P99[SYNC]}")ms → $(printf '%s' "${AL_P99[MQ]}")ms（$(pct "${AL_P99[MQ]}" "${AL_P99[SYNC]}")） |
| OFF（下界） | $(printf '%s' "${AL_QPS[OFF]}") | $(printf '%s' "${AL_P50[OFF]}")ms | $(printf '%s' "${AL_P99[OFF]}")ms |

**MQ 模式把「落库」从请求线程上摘掉了**：跳转接口不再等 MySQL 写完 INSERT，
延迟和吞吐都向「完全不记日志」的下界靠拢。

> ⚠ **单轮数据不能直接下结论。** 同一套脚本连跑三轮的实测波动：
>
> | 指标 | run1 | run2 | run3 | 结论 |
> |---|---|---|---|---|
> | SYNC→MQ QPS | +77% | +52% | +123% | 方向稳定（3/3 次 MQ 更优），**幅度不稳** |
> | SYNC→MQ p50 | −61% | −53% | −76% | 方向稳定（3/3 次 MQ 更优），**幅度不稳** |
> | SYNC→MQ p99 | −21% | +3% | +63% | ❌ **方向都不一致，不能作依据** |
>
> 甚至出现过 **MQ 的 p50（17.63ms）低于 OFF（24.25ms）** 的异常值 ——
> 记日志比不记还快在物理上说不通，只能说明本环境（Docker Desktop on Windows）
> 的绝对数字噪声很大。
>
> **能站住的写法**：QPS 提升 **50% 以上**、p50 降低 **50% 以上**（三轮方向一致）；
> **p99 不写**。三轮完整对照见 \`docs/项目B-压测报告.md\` 第 7.2 节。

### 4.2 🔴 必须说清楚的四个局限（面试被追问要主动交代）

1. **异步省的不是 DB 写入量，而是请求线程的等待。**
   DB 上的 INSERT 条数在 MQ 和 SYNC 下**完全一样**（各 2000 条），
   只是执行者从 \`http-nio-*\` 换成了 \`ConsumeMessageThread_*\`。
   **别把收益说成「DB 压力下降」，那是错的。** 正确的说法是
   「跳转接口的延迟分布不再包含落库耗时」。

2. **MQ 模式的 p90 反而比 OFF 高（110ms vs 35ms）—— 这是测量环境的产物，不是设计缺陷。**
   原因：消费线程在**压测窗口内**并发执行 2000 条 INSERT，
   而它和请求线程跑在**同一个容器**里，会争抢三样东西：
   ① Druid 连接池（\`max-active=20\`）；② CPU；③ **\`System.out\`（\`PrintStream\` 的方法是 synchronized 的）**
   —— 请求线程打「短链跳转」INFO 日志、消费线程打 2000 行 \`Preparing:\` SQL 日志，
   全都排在同一个锁上。
   **生产环境里消费者应该是独立的服务/实例**，这条争抢就不存在了。
   ⚠ 这是一条**推测**（未做隔离实验验证），报告里按推测写。

3. **访问日志是「尽力而为」的数据。** broker 挂掉、消息反复失败进死信队列，都会丢日志
   （跳转本身不受影响，已验证）。这是刻意选的取舍：日志不值得为它牺牲可用性。

4. **RocketMQ 是 at-least-once**，消费成功但 ack 丢失时消息会重投，
   于是 \`access_log\` 可能出现重复行，PV 略微偏大。本项目**刻意不做去重**。

> 环境：应用容器 \`$APP_CTN\`（${cpu} vCPU / ${mem}）、$os
> ⚠ 与其他压测同一前提：\`mybatis-plus\` 的 \`log-impl=StdOutImpl\` 是开着的，
> 每条 SQL 都同步写 stdout（且 \`System.out\` 是全局同步点），所以 QPS 偏低、延迟偏高。
> 作为**组间相对比较**仍然成立，但绝对数字不能当生产容量。

EOF
  info "Step 7 报告已写入 $REPORT_AL"
}

# ---------------------------------------------------------------- 报告
write_report() {
  [ "${#ROWS[@]}" -eq 0 ] && return 0

  local cpu mem os
  cpu=$(docker exec "$APP_CTN" sh -c 'nproc' 2>/dev/null || echo '?')
  mem=$(docker exec "$APP_CTN" sh -c "free -m 2>/dev/null | awk '/Mem:/{print \$2\" MB\"}'" 2>/dev/null || echo '?')
  os=$(docker exec "$APP_CTN" sh -c 'cat /etc/os-release 2>/dev/null | head -1' 2>/dev/null || echo '?')

  cat > "$REPORT" <<EOF
# 项目 B 压测报告（Step 4：缓存三剑客）

> 由 \`bash bench/run-bench.sh\` 自动生成于 $(date '+%Y-%m-%d %H:%M:%S')
> 原始数据：\`bench/out/raw.jsonl\`

## 一、环境与口径

| 项 | 值 |
|---|---|
| 压测工具 | \`bench/bench.mjs\`（Node 22 自写，keep-alive + 异步 I/O，零依赖） |
| 应用容器 | \`$APP_CTN\`（${cpu} vCPU / ${mem}） |
| 镜像 | $os |
| MySQL | 8.0（容器 \`$MYSQL_CTN\`，宿主 3308） |
| Redis | 7-alpine（容器 \`$REDIS_CTN\`，宿主 6380） |
| 请求目标 | \`GET /{shortCode}\`（302 跳转），即系统唯一的读主链路 |

**DB 查询次数怎么数的**（这是全篇最关键的度量口径）：

本项目用了**两个独立口径交叉验证**，因为一开始发现两者对不上，查下来是有价值的结论：

| 口径 | 做法 | 特点 |
|---|---|---|
| **A. MySQL 侧** | 每次用例前 \`TRUNCATE performance_schema.events_statements_summary_by_digest\`，跑完数归一化后含 \`short_url\` 的 SELECT | 开销极低、不用开日志；⚠ 并发下会**低估 0.5%~1.5%** |
| **B. 应用侧** | 数应用自己打印的 MyBatis SQL 日志（\`StdOutImpl\` 每发一条语句打一行 \`Preparing:\`） | 应用视角的权威计数；代价是日志量大 |

\`\`\`sql
-- 口径 A
SELECT SUM(COUNT_STAR) FROM performance_schema.events_statements_summary_by_digest
WHERE DIGEST_TEXT LIKE '%short_url%' AND DIGEST_TEXT LIKE 'SELECT%';
\`\`\`

**结论以口径 B（应用日志）为准**，口径 A 作为旁证。
两者的一致性校验见第四节 —— 这个偏差是实测发现的，不是猜的。

**对照组怎么保证真的生效**：\`/api/health\` 的 \`features\` 段会暴露当前开关，
压测脚本读到期望值才开跑（\`wait_feature bloomFilter OFF\` 等），
避免出现「以为关了其实没关」的假对照。

> ⚠ **QPS/延迟是保守下界**：\`mybatis-plus.configuration.log-impl\` 当前是 \`StdOutImpl\`，
> 每条 SQL 都同步写 stdout。这在生产是要关掉的（本身就是性能瓶颈），
> 所以下面的吞吐数字偏低，而延迟偏高。作为对照实验的相对比较仍然成立。

## 二、结果

| 用例 | 说明 | 生效开关 | 请求数 | QPS | p50 | p90 | p99 | DB SELECT（日志） | DB SELECT（digest） |
|---|---|---|---|---|---|---|---|---|---|
$(printf '%s\n' "${ROWS[@]}")

## 三、口径一致性校验

同一批数据用两个口径分别统计，看偏差：

| 用例 | 请求数 | 口径B 应用日志 | 口径A digest | digest 低估比例 |
|---|---|---|---|---|
$(printf '%s\n' "${CALIB[@]}")

**怎么解释这个偏差**：
- 串行压测时两个口径**完全相等**（实测：2000 次请求 → 两个口径都是 2000）。
- 并发 50 时才出现 0.5%~1.5% 的缺口（并发越高、语句越密，丢得越多）。
- 另外用 20 个并发 MySQL 客户端直接打 2000 条 \`SELECT COUNT(*) FROM short_url\`，
  digest 计数是**精确的 2000**。
- 所以偏差来自「**同一 digest 被多个连接高度并发更新**」这一场景下的计数丢失，
  而不是应用少查了库 —— 应用日志和 Redis 的 \`setex\` 次数都是 2000，互相印证。

**面试价值**：这类「度量工具本身有偏差」的坑，只有真跑过压测才会遇到。
两个独立口径交叉验证 + 串并行对照，是定位它的手段。

## 四、结论

本文件是**脚本自动生成的原始数据**，重新跑 `run-bench.sh` 会被覆盖。
人工分析、面试追问预案与简历写法见 **`docs/项目B-压测报告.md`**。

EOF

  info "报告已写入 $REPORT"
}

# ============================================================================
# 主流程
# ============================================================================
PHASES="${*:-all}"

for p in $PHASES; do
  case "$p" in
    all)
      phase_build
      phase_seed
      phase_main
      phase_nobloom
      HOT_CODE=$(head -1 "$CODES")
      phase_nomutex "$HOT_CODE"
      phase_restore
      write_report
      ;;
    build)   phase_build ;;
    seed)    phase_seed ;;
    main)    phase_main ;;
    nobloom) phase_nobloom ;;
    nomutex) phase_nomutex "" ;;
    accesslog) phase_accesslog ;;
    restore) phase_restore ;;
    report)  write_report ;;
    *) die "未知 phase: $p（可选 all|build|seed|main|nobloom|nomutex|accesslog|restore|report）" ;;
  esac
done

# 单独跑某个 phase 时也顺手出报告。
# ⚠ accesslog 出的是 RESULTS-ACCESSLOG.md（Step 7），
#   其余 phase 出 RESULTS.md（Step 4）—— 两者互不覆盖。
case "$PHASES" in
  all|*report*) : ;;
  *accesslog*) write_accesslog_report ;;
  *) write_report ;;
esac
