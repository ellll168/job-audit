#!/usr/bin/env bash
# job-audit 一键演示模式（H2 内存数据库，无需安装 MySQL）
set -e
cd "$(dirname "$0")"

echo "=========================================================="
echo "  job-audit 一键演示模式（H2 内存数据库，无需安装 MySQL）"
echo "  启动完成请访问：http://localhost:8080/api/health"
echo "  前端页面：进入 frontend 目录执行 npm install，再 npm run dev"
echo "=========================================================="

mvn spring-boot:run -Dspring-boot.run.profiles=demo
