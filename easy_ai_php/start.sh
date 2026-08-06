#!/bin/bash
# Easy AI 服务启动脚本
cd "$(dirname "$0")"
PORT=${1:-8880}
# 杀掉已有的 easy_ai 服务
pkill -f "php -S 0.0.0.0:$PORT" 2>/dev/null
sleep 0.3
nohup env PHP_CLI_SERVER_WORKERS=8 php -S 0.0.0.0:$PORT \
  -d output_buffering=0 \
  -d upload_max_filesize=20M \
  -d post_max_size=25M \
  -d memory_limit=256M \
  router.php >/tmp/easy_ai_server.log 2>&1 &
sleep 1
curl -s -m 3 http://127.0.0.1:$PORT/api.php | head -c 60 && echo " ← 服务已启动: 端口 $PORT"
