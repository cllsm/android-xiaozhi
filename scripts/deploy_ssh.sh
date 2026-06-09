#!/bin/bash
# ============================================================
# 小智后端 — SSH 部署到手机 Termux
#
# 使用方法:
#   1. 手机 Termux 安装 SSH:
#      pkg install openssh
#      sshd
#      whoami        # 记下用户名
#      ifconfig      # 记下 IP (通常 wlan0)
#
#   2. 电脑端执行:
#      bash scripts/deploy_ssh.sh <手机IP> [用户名]
#
# 示例:
#   bash scripts/deploy_ssh.sh 192.168.1.100
#   bash scripts/deploy_ssh.sh 192.168.1.100 u0_a123
#
# 部署完成后，在手机 Termux 中执行:
#   bash ~/xiaozhi/start.sh
# ============================================================

set -e

# ---- 配置 ----
PHONE_IP="${1:?用法: $0 <手机IP> [用户名]}"
PHONE_USER="${2:-$(whoami)}"
PHONE_PORT=8022
REMOTE_DIR="xiaozhi"
SSH_TARGET="${PHONE_USER}@${PHONE_IP}"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC} $1"; }
ok()    { echo -e "${GREEN}[OK]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
die()   { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# 项目根目录
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# ---- 1. 检查 SSH 连接 ----
info "测试 SSH 连接: ${SSH_TARGET}:${PHONE_PORT}..."
if ! ssh -o ConnectTimeout=5 -p "$PHONE_PORT" "${SSH_TARGET}" "echo ok" > /dev/null 2>&1; then
    die "无法连接 ${SSH_TARGET}:${PHONE_PORT}
    请确认:
      1. 手机 Termux 已安装 openssh: pkg install openssh
      2. Termux 已启动 sshd: sshd
      3. 手机和电脑在同一局域网
      4. IP 地址正确: ${PHONE_IP}
      5. 用户名正确: ${PHONE_USER} (在 Termux 中执行 whoami 查看)
      6. 如需密码登录，先在 Termux 中执行: passwd"
fi
ok "SSH 连接成功"

# ---- 2. 打包后端文件 ----
info "打包后端文件..."
TMP_TAR="/tmp/xiaozhi-backend-$(date +%Y%m%d%H%M%S).tar.gz"

# 打包: backend/ + models/ + assets/ + scripts/ + 根目录配置文件
cd "$PROJECT_DIR"
tar czf "$TMP_TAR" \
    --exclude='__pycache__' \
    --exclude='*.pyc' \
    --exclude='.git' \
    --exclude='node_modules' \
    --exclude='frontend/dist' \
    --exclude='.idea' \
    --exclude='.vscode' \
    --exclude='.hbuilderx' \
    --exclude='.vite' \
    --exclude='*.log' \
    --exclude='.env' \
    backend/ \
    models/ \
    assets/ \
    scripts/ \
    2>/dev/null || true

TAR_SIZE=$(du -sh "$TMP_TAR" | cut -f1)
ok "打包完成: ${TAR_SIZE}"

# ---- 3. 传输到手机 ----
info "传输文件到手机 (${SSH_TARGET})..."
scp -P "$PHONE_PORT" "$TMP_TAR" "${SSH_TARGET}:~/tmp-xiaozhi-backend.tar.gz"
ok "文件传输完成"

# ---- 4. 远程执行部署 ----
info "在手机上执行部署..."
ssh -p "$PHONE_PORT" "${SSH_TARGET}" bash -s << 'REMOTE_SCRIPT'
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'
info()  { echo -e "${CYAN}[手机]${NC} $1"; }
ok()    { echo -e "${GREEN}[手机]${NC} $1"; }

PROJECT_DIR="$HOME/xiaozhi"

# 解压项目
info "解压后端文件..."
rm -rf "$PROJECT_DIR"
mkdir -p "$PROJECT_DIR"
tar xzf ~/tmp-xiaozhi-backend.tar.gz -C "$PROJECT_DIR"
rm -f ~/tmp-xiaozhi-backend.tar.gz
ok "文件解压完成"

# 安装依赖（首次部署时）
if ! python -c "import aiohttp" 2>/dev/null; then
    info "安装系统依赖..."
    pkg update -y 2>/dev/null || true
    pkg install -y python python-pip ffmpeg portaudio openssl 2>/dev/null || true

    info "配置 pip 镜像..."
    mkdir -p ~/.pip
    echo "[global]" > ~/.pip/pip.conf
    echo "index-url = https://pypi.tuna.tsinghua.edu.cn/simple" >> ~/.pip/pip.conf
    echo "trusted-host = pypi.tuna.tsinghua.edu.cn" >> ~/.pip/pip.conf

    info "安装 Python 依赖..."
    pip install -r "$PROJECT_DIR/backend/requirements.txt"
    pip install sounddevice 2>/dev/null || true
    pip install onnxruntime 2>/dev/null || true
    ok "依赖安装完成"
else
    info "依赖已安装，跳过"
fi

# 创建启动脚本
cat > "$PROJECT_DIR/start.sh" << 'START_EOF'
#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")"
echo "========================================="
echo "  小智陪伴机器人 — Termux 后端"
echo "========================================="
echo ""
echo "  后端地址: http://127.0.0.1:18080"
echo "  WebSocket: ws://127.0.0.1:18080/ws"
echo ""
echo "  前端 App 设置 → 后端地址: 127.0.0.1"
echo "  按 Ctrl+C 停止"
echo ""
echo "========================================="
python -m backend.main --port 18080 --log-level INFO
START_EOF
chmod +x "$PROJECT_DIR/start.sh"

ok "部署完成!"
REMOTE_SCRIPT

# 清理本地临时文件
rm -f "$TMP_TAR"

# ---- 完成 ----
echo ""
echo "========================================="
ok "🚀 部署完成!"
echo "========================================="
echo ""
echo -e "${CYAN}在手机 Termux 中启动后端:${NC}"
echo "  bash ~/xiaozhi/start.sh"
echo ""
echo -e "${CYAN}后台运行:${NC}"
echo "  nohup bash ~/xiaozhi/start.sh > ~/xiaozhi.log 2>&1 &"
echo ""
echo -e "${CYAN}前端 App 设置:${NC}"
echo "  设置页 → 后端地址 → 填写 127.0.0.1"
echo ""
echo -e "${CYAN}再次部署（更新代码）:${NC}"
echo "  bash scripts/deploy_ssh.sh ${PHONE_IP} ${PHONE_USER}"
