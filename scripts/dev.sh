#!/bin/bash
# 一键起开发环境：后端 8080 + 前端 5173
#
# 背景：本机 spring-boot:run 因中文路径 argfile 编码问题不可用，只能用 java -jar。
# 前端 vite 代理指向 8080，所以后端必须起在 8080。
#
# 用法：bash scripts/dev.sh          启动
#       bash scripts/dev.sh stop     停止
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

stop_all() {
  for port in 8080 5173 5174 8099; do
    pid=$(netstat -ano 2>/dev/null | grep ":$port " | grep LISTENING | awk '{print $5}' | head -1)
    if [ -n "$pid" ]; then
      echo "stop :$port (pid $pid)"
      taskkill //F //PID "$pid" >/dev/null 2>&1 || true
    fi
  done
}

if [ "${1:-}" = "stop" ]; then
  stop_all
  exit 0
fi

stop_all
sleep 1

echo "starting backend on 8080 ..."
cd "$ROOT/backend" || exit 1
# 本地密钥：application-local.yml 不入库，存在时自动启用 local profile
PROFILE_ARG=""
if [ -f src/main/resources/application-local.yml ]; then
  PROFILE_ARG="--spring.profiles.active=local"
  echo "  (application-local.yml 存在 → 使用 local profile)"
fi
nohup java -jar target/interview-lab-backend-0.1.0.jar $PROFILE_ARG > /tmp/backend.log 2>&1 &
disown 2>/dev/null || true

echo "starting frontend on 5173 ..."
cd "$ROOT/frontend" || exit 1
nohup npm run dev > /tmp/vite.log 2>&1 &
disown 2>/dev/null || true

# 等后端起来
for i in $(seq 1 40); do
  code=$(curl -s --noproxy "*" -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:8080/api/health 2>/dev/null)
  if [ "$code" = "200" ]; then break; fi
  sleep 1
done

echo "--- backend ---"
curl -s --noproxy "*" --max-time 3 http://127.0.0.1:8080/api/health || echo "后端未起来，看 /tmp/backend.log"
echo
echo "--- frontend ---"
for i in $(seq 1 20); do
  code=$(curl -s --noproxy "*" -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:5173/ 2>/dev/null)
  if [ "$code" = "200" ]; then echo "http://127.0.0.1:5173/ OK"; break; fi
  sleep 1
done
echo "--- 通过 vite 代理访问后端 ---"
curl -s --noproxy "*" --max-time 5 http://127.0.0.1:5173/api/health || echo "代理转发失败"
echo
