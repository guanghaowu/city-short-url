#!/usr/bin/env node
/**
 * 极简压测器 / 数据播种器（零依赖，只用 Node 内置 http 模块）。
 *
 * 为什么自己写：沙箱里没有 wrk / hey / ab。Node 的异步 I/O + keep-alive
 * 足以压出可信的<b>相对</b>差异（本项目要的是「有/无布隆」「有/无互斥锁」的对照，
 * 不是绝对极限 QPS）。
 *
 * ⚠ 本工具只负责「打流量 + 统计延迟/状态码」。
 *   「DB 查询了多少次」由外层 run-bench.sh 通过 MySQL performance_schema 的
 *   events_statements_summary_by_digest 统计 —— 那个口径比在客户端猜准确得多。
 *
 * 用法：
 *   # 播种：并发创建 2000 条短链，短码写入 codes.txt
 *   node bench.mjs --mode seed --base http://localhost:8082 --count 2000 --out bench/out/codes.txt
 *
 *   # 热路径：2000 个码 × 10 轮 = 20000 次请求，全部命中缓存
 *   node bench.mjs --mode hit --codes bench/out/codes.txt --rounds 10 --concurrency 50
 *
 *   # 冷路径：2000 个码各请求一次（缓存已清空 → 全部回源 DB）
 *   node bench.mjs --mode hit --codes bench/out/codes.txt --rounds 1 --concurrency 50
 *
 *   # 击穿：200 并发同时打同一个「缓存刚被清空」的热点码
 *   node bench.mjs --mode single --code QnH7KCENZQ --requests 200 --concurrency 200
 *
 *   # 穿透：20000 个互不相同的随机非法码
 *   node bench.mjs --mode invalid --requests 20000 --concurrency 50
 *
 * 输出：一行 JSON（供脚本解析）到 stdout，一行人类可读摘要到 stderr。
 */

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

// ----------------------------------------------------------------- 参数
function arg(name, def) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : def;
}

const base = arg('base', 'http://localhost:8082');
const mode = arg('mode', 'single');            // seed | hit | single | invalid
const codesFile = arg('codes', '');
const rounds = parseInt(arg('rounds', '1'), 10);
const requests = parseInt(arg('requests', '1000'), 10);
const concurrency = parseInt(arg('concurrency', '50'), 10);
const label = arg('label', mode);
const timeoutMs = parseInt(arg('timeout', '10000'), 10);
const count = parseInt(arg('count', '1000'), 10);
const outFile = arg('out', 'bench/out/codes.txt');

const url = new URL(base);
if (url.protocol !== 'http:') throw new Error('本工具只支持 http（本项目是 http）');

const agent = new http.Agent({
  keepAlive: true,
  maxSockets: concurrency,
  maxFreeSockets: concurrency,
});

// ----------------------------------------------------------------- HTTP 工具
function request(method, reqPath, body, token) {
  return new Promise((resolve, reject) => {
    const payload = body ? Buffer.from(JSON.stringify(body), 'utf8') : null;
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port || 80,
        path: reqPath,
        method,
        agent,
        headers: {
          ...(payload
            ? { 'Content-Type': 'application/json', 'Content-Length': payload.length }
            : {}),
          ...(token ? { Authorization: 'Bearer ' + token } : {}),
        },
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let json = null;
          try { json = JSON.parse(text); } catch { /* 可能是 HTML */ }
          resolve({ status: res.statusCode, json, text });
        });
      }
    );
    req.setTimeout(timeoutMs, () => req.destroy(new Error('ETIMEDOUT')));
    req.on('error', reject);
    req.end(payload);
  });
}

// ----------------------------------------------------------------- seed 模式
if (mode === 'seed') {
  const user = 'bench_' + Date.now();
  const pwd = 'bench123456';

  await request('POST', '/api/user/register', { username: user, password: pwd });
  const login = await request('POST', '/api/user/login', { username: user, password: pwd });
  const token = login.json?.data?.token;
  if (!token) throw new Error('登录失败: ' + login.text.slice(0, 200));

  const codes = [];
  let cursor = 0;
  let failed = 0;
  async function worker() {
    for (;;) {
      const i = cursor++;
      if (i >= count) return;
      const r = await request('POST', '/api/short/create', {
        originalUrl: `https://example.com/bench/${i}?from=cityshorturl`,
        expireDays: 30,
      }, token);
      const code = r.json?.data?.shortCode;
      if (code) codes.push(code);
      else failed++;
    }
  }
  await Promise.all(Array.from({ length: concurrency }, worker));

  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, codes.join('\n') + '\n', 'utf8');
  console.log(JSON.stringify({ mode: 'seed', created: codes.length, failed, out: outFile }));
  console.error(`[seed] 创建短链 ${codes.length} 条（失败 ${failed}），已写入 ${outFile}`);
  process.exit(0);
}

// ----------------------------------------------------------------- 造请求列表
const CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';

function randomCode(len = 10) {
  let s = '';
  for (let i = 0; i < len; i++) s += CHARS[(Math.random() * CHARS.length) | 0];
  return s;
}

let paths = [];
let expect;

if (mode === 'hit') {
  if (!codesFile) throw new Error('--mode hit 需要 --codes <file>');
  const codes = fs.readFileSync(codesFile, 'utf8').split(/\r?\n/).filter(Boolean);
  for (let r = 0; r < rounds; r++) for (const c of codes) paths.push('/' + c);
  expect = [302];
} else if (mode === 'single') {
  const code = arg('code');
  if (!code) throw new Error('--mode single 需要 --code <shortCode>');
  for (let i = 0; i < requests; i++) paths.push('/' + code);
  expect = [302];
} else if (mode === 'invalid') {
  // 随机 10 位码。⚠ 必须去重：同一个非法码第二次请求会命中「空值缓存」，
  // 那测的就不是布隆了。去重后每个码都是「第一次见」。
  const set = new Set();
  while (set.size < requests) set.add(randomCode());
  paths = [...set].map((c) => '/' + c);
  expect = [404];
} else {
  throw new Error('未知 --mode: ' + mode);
}

// ----------------------------------------------------------------- 单次请求
function oneRequest(reqPath) {
  return new Promise((resolve) => {
    const t0 = process.hrtime.bigint();
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port || 80,
        path: reqPath,
        method: 'GET',
        agent,
        headers: { Connection: 'keep-alive' },
      },
      (res) => {
        res.resume();
        res.on('end', () => {
          const ms = Number(process.hrtime.bigint() - t0) / 1e6;
          resolve({ ms, status: res.statusCode });
        });
      }
    );
    req.setTimeout(timeoutMs, () => req.destroy(new Error('ETIMEDOUT')));
    req.on('error', (e) => resolve({ ms: -1, status: -1, err: e.code || e.message }));
    req.end();
  });
}

// ----------------------------------------------------------------- 并发跑
const lat = [];
const statusCount = {};
let ok = 0, bad = 0, err = 0;
const started = Date.now();
let cursor = 0;

async function worker() {
  for (;;) {
    const i = cursor++;
    if (i >= paths.length) return;
    const r = await oneRequest(paths[i]);
    if (r.ms < 0) { err++; continue; }
    lat.push(r.ms);
    statusCount[r.status] = (statusCount[r.status] || 0) + 1;
    if (expect.includes(r.status)) ok++; else bad++;
  }
}
await Promise.all(Array.from({ length: concurrency }, worker));
const durationSec = (Date.now() - started) / 1000;

// ----------------------------------------------------------------- 统计
lat.sort((a, b) => a - b);
const pick = (p) =>
  lat.length ? +lat[Math.min(lat.length - 1, Math.floor(lat.length * p))].toFixed(2) : null;
const avg = lat.length
  ? +(lat.reduce((s, v) => s + v, 0) / lat.length).toFixed(2)
  : null;

const result = {
  label, mode, concurrency,
  total: paths.length, ok, bad, err,
  qps: +(paths.length / durationSec).toFixed(1),
  durationSec: +durationSec.toFixed(2),
  min: lat.length ? +lat[0].toFixed(2) : null,
  p50: pick(0.5), p90: pick(0.9), p99: pick(0.99),
  max: lat.length ? +lat[lat.length - 1].toFixed(2) : null,
  avg, status: statusCount,
};

console.log(JSON.stringify(result));
console.error(
  `[${label}] total=${result.total} ok=${ok} bad=${bad} err=${err} ` +
  `qps=${result.qps} p50=${result.p50}ms p90=${result.p90}ms p99=${result.p99}ms avg=${result.avg}ms`
);
