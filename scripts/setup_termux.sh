#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# 小智陪伴机器人 — Termux 一键部署脚本
#
# 使用方法:
#   1. 安装 Termux (F-Droid 版本推荐)
#   2. 将项目复制到手机或 git clone
#   3. cd 到项目根目录
#   4. bash scripts/setup_termux.sh
#
# 架构:
#   Termux (后端 Python) ←→ UniApp App (前端)
#   两者都运行在同一台 Android 手机上
#   前端连接 ws://127.0.0.1:18080/ws
# ============================================================

set -e

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC} $1"; }
ok()    { echo -e "${GREEN}[OK]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# ---- 检查 Termux 环境 ----
if [ ! -d "/data/data/com.termux" ]; then
    error "此脚本仅能在 Termux 环境中运行"
fi

info "小智后端 Termux 部署开始..."

# ---- 1. 更新包管理器 ----
info "更新 Termux 包管理器..."
pkg update -y && pkg upgrade -y

# ---- 2. 安装系统依赖 ----
info "安装系统依赖..."
pkg install -y \
    python \
    ffmpeg \
    portaudio \
    openssl \
    git \
    cmake

ok "系统依赖安装完成"

# ---- 3. 升级 pip ----
info "升级 pip..."
python -m pip install --upgrade pip

# ---- 4. 安装 Python 依赖 ----
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
REQUIREMENTS="$PROJECT_DIR/backend/requirements.txt"

if [ -f "$REQUIREMENTS" ]; then
    info "安装 Python 依赖..."
    pip install -r "$REQUIREMENTS"
else
    warn "未找到 requirements.txt，跳过"
fi

# ---- 5. 安装 sounddevice (音频播放) ----
info "安装 sounddevice (音频播放)..."
pip install sounddevice || warn "sounddevice 安装失败，后端将无法本地播放音频"

# ---- 6. 编译安装 opus (如果系统没有) ----
if ! python -c "import opuslib" 2>/dev/null; then
    info "安装 Opus 编解码库..."
    pkg install -y opus || true
    pip install opuslib || warn "opuslib 安装失败，音频编解码可能不可用"
fi

# ---- 7. 创建启动脚本 ----
START_SCRIPT="$PROJECT_DIR/start_termux.sh"
cat > "$START_SCRIPT" << 'START_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# 小智后端启动脚本 (Termux)

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "========================================="
echo "  小智陪伴机器人 — Termux 后端"
echo "========================================="
echo ""
echo "后端地址: http://127.0.0.1:18080"
echo "WebSocket: ws://127.0.0.1:18080/ws"
echo ""
echo "前端 App 设置 → 后端地址填写: 127.0.0.1"
echo ""
echo "按 Ctrl+C 停止"
echo "========================================="
echo ""

cd "$PROJECT_DIR"
python -m backend.main --port 18080 --log-level INFO
START_EOF

chmod +x "$START_SCRIPT"

# ---- 8. 创建 Termux 服务 (可选，后台运行) ----
info "创建 Termux:Boot 自启脚本（可选）..."
BOOT_DIR="$HOME/.termux/boot"
mkdir -p "$BOOT_DIR"
cat > "$BOOT_DIR/start-xiaozhi.sh" << BOOT_EOF
#!/data/data/com.termux/files/usr/bin/bash
# Termux:Boot 开机自启小智后端
# 需要安装 Termux:Boot 插件: pkg install termux-boot
cd "$PROJECT_DIR"
bash start_termux.sh &
BOOT_EOF
chmod +x "$BOOT_DIR/start-xiaozhi.sh"

# ---- 完成 ----
echo ""
echo "========================================="
ok "部署完成!"
echo "========================================="
echo ""
echo -e "${CYAN}启动方式:${NC}"
echo "  bash $START_SCRIPT"
echo ""
echo -e "${CYAN}后台运行:${NC}"
echo "  nohup bash $START_SCRIPT > xiaozhi.log 2>&1 &"
echo ""
echo -e "${CYAN}前端 App 设置:${NC}"
echo "  设置 → 后端地址 → 填写 127.0.0.1"
echo ""
echo -e "${CYAN}开机自启 (需 Termux:Boot 插件):${NC}"
echo "  pkg install termux-boot"
echo "  然后重启手机即可自动启动后端"
echo ""
