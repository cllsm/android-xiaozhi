#!/data/data/com.termux/files/usr/bin/bash
# 小智后端一键安装 — 日志输出到 /sdcard/Download/install.log
exec > >(tee /sdcard/Download/install.log) 2>&1

set -e
echo "[1/5] 更新 Termux 包..."
pkg update -y 2>/dev/null || true
pkg upgrade -y 2>/dev/null || true

echo "[2/5] 安装系统依赖..."
pkg install -y python python-pip ffmpeg portaudio openssl

echo "[3/5] 配置 pip 镜像..."
mkdir -p ~/.pip
echo "[global]" > ~/.pip/pip.conf
echo "index-url = https://pypi.tuna.tsinghua.edu.cn/simple" >> ~/.pip/pip.conf
echo "trusted-host = pypi.tuna.tsinghua.edu.cn" >> ~/.pip/pip.conf

echo "[4/5] 解压项目..."
PROJECT_DIR="$HOME/xiaozhi"
rm -rf "$PROJECT_DIR"
mkdir -p "$PROJECT_DIR"
tar xzf /sdcard/Download/xiaozhi-backend.tar.gz -C "$PROJECT_DIR" --strip-components=1

echo "[5/5] 安装 Python 依赖..."
pip install -r "$PROJECT_DIR/backend/requirements.txt"
pip install sounddevice

# 创建启动脚本
cat > "$PROJECT_DIR/start.sh" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")"
echo "=== 小智后端已启动 ws://127.0.0.1:18080 ==="
echo "=== 前端App设置 后端地址 127.0.0.1 ==="
python -m backend.main --port 18080 --log-level INFO
EOF
chmod +x "$PROJECT_DIR/start.sh"

echo ""
echo "========================================="
echo "  安装完成!"
echo "  启动: bash ~/xiaozhi/start.sh"
echo "========================================="
