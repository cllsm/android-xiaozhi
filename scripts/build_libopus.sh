#!/usr/bin/env bash
# ============================================================================
# libopus Android 交叉编译脚本
#
# 用途：将 opus 音频编解码库编译为 Android ARM64/ARMv7 原生库
# 输出：
#   libs/libopus/android/arm64-v8a/libopus.so
#   libs/libopus/android/armeabi-v7a/libopus.so
#
# 前置条件：
#   1. Android NDK 已安装（自动检测 ANDROID_NDK_HOME 或常见路径）
#   2. CMake 3.10+ 和 Make 已安装
#   3. 在 macOS / Linux / Windows (MSYS2/Git Bash) 上运行
#
# 用法：
#   chmod +x scripts/build_libopus.sh
#   ./scripts/build_libopus.sh                    # 编译两种架构
#   ./scripts/build_libopus.sh --arch arm64-v8a   # 仅编译 ARM64
#   ./scripts/build_libopus.sh --arch armeabi-v7a # 仅编译 ARMv7
#   ./scripts/build_libopus.sh --ndk /path/to/ndk # 指定 NDK 路径
#   ./scripts/build_libopus.sh --opus-version v1.5.2  # 指定 opus 版本
# ============================================================================

set -euo pipefail

# ----- 可配置变量 -----
OPUS_VERSION="${OPUS_VERSION:-v1.5.2}"
OPUS_REPO="${OPUS_REPO:-https://github.com/xiph/opus.git}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_BASE="${PROJECT_ROOT}/libs/libopus/android"
BUILD_BASE="${PROJECT_ROOT}/build/opus_android"
NDK_DIR=""

# ----- 颜色输出 -----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'  # 无颜色

info()  { echo -e "${BLUE}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }

# ----- 参数解析 -----
ARCH_LIST=("arm64-v8a" "armeabi-v7a")

while [[ $# -gt 0 ]]; do
    case "$1" in
        --arch)
            ARCH_LIST=("$2")
            shift 2
            ;;
        --ndk)
            NDK_DIR="$2"
            shift 2
            ;;
        --opus-version)
            OPUS_VERSION="$2"
            shift 2
            ;;
        --help|-h)
            head -30 "$0" | grep '^#' | sed 's/^# \?//'
            exit 0
            ;;
        *)
            error "未知参数: $1"
            ;;
    esac
done

# ----- NDK 路径检测 -----
detect_ndk() {
    # 1. 命令行指定
    if [[ -n "${NDK_DIR}" && -d "${NDK_DIR}" ]]; then
        echo "${NDK_DIR}"
        return
    fi

    # 2. 环境变量
    for env_var in ANDROID_NDK_HOME ANDROID_NDK_ROOT NDK_HOME; do
        local val="${!env_var:-}"
        if [[ -n "${val}" && -d "${val}" ]]; then
            echo "${val}"
            return
        fi
    done

    # 3. 常见安装路径
    local candidates=()

    # macOS
    candidates+=(
        "$HOME/Library/Android/sdk/ndk/"
        "/usr/local/share/android-ndk/"
    )

    # Linux
    candidates+=(
        "$HOME/Android/Sdk/ndk/"
        "/opt/android-ndk/"
    )

    # Windows (MSYS2 / Git Bash)
    candidates+=(
        "/c/Users/$USER/AppData/Local/Android/Sdk/ndk/"
        "/d/Android/Sdk/ndk/"
    )

    for dir in "${candidates[@]}"; do
        if [[ -d "${dir}" ]]; then
            # 可能有多个版本，取最新的
            local latest
            latest=$(ls -1v "${dir}" 2>/dev/null | tail -1)
            if [[ -n "${latest}" ]]; then
                echo "${dir}${latest}"
                return
            fi
        fi
    done

    echo ""
}

# ----- 获取 NDK 工具链路径 -----
get_toolchain() {
    local ndk="$1"
    local host_os

    case "$(uname -s)" in
        Darwin)  host_os="darwin" ;;
        Linux)   host_os="linux" ;;
        MINGW*|MSYS*|CYGWIN*) host_os="windows" ;;
        *)       error "不支持的宿主系统: $(uname -s)" ;;
    esac

    echo "${ndk}/toolchains/llvm/prebuilt/${host_os}-x86_64"
}

# ----- 获取目标信息 -----
get_target_info() {
    local arch="$1"
    case "${arch}" in
        arm64-v8a)
            echo "aarch64-linux-android"
            ;;
        armeabi-v7a)
            echo "armv7a-linux-androideabi"
            ;;
        *)
            error "不支持的架构: ${arch}"
            ;;
    esac
}

# ----- 编译 libopus -----
build_opus() {
    local arch="$1"
    local target
    target=$(get_target_info "${arch}")

    local api_level=21  # Android 5.0 (Lollipop)

    # 架构特定参数
    local extra_cflags=""
    local extra_configure_flags=""

    case "${arch}" in
        arm64-v8a)
            extra_cflags="-march=armv8-a"
            ;;
        armeabi-v7a)
            extra_cflags="-march=armv7-a -mfloat-abi=softfp -mfpu=neon"
            extra_configure_flags="--enable-fixed-point"
            ;;
    esac

    local build_dir="${BUILD_BASE}/${arch}"
    local output_dir="${OUTPUT_BASE}/${arch}"

    info "开始编译 libopus ${OPUS_VERSION} for ${arch}..."
    info "  目标: ${target}"
    info "  API Level: ${api_level}"
    info "  构建目录: ${build_dir}"
    info "  输出目录: ${output_dir}"

    # 创建构建目录
    rm -rf "${build_dir}"
    mkdir -p "${build_dir}"

    # 设置交叉编译环境变量
    local toolchain
    toolchain=$(get_toolchain "${NDK_DIR}")

    if [[ ! -d "${toolchain}" ]]; then
        error "NDK 工具链目录不存在: ${toolchain}"
    fi

    export CC="${toolchain}/bin/${target}${api_level}-clang"
    export CXX="${toolchain}/bin/${target}${api_level}-clang++"
    export AR="${toolchain}/bin/llvm-ar"
    export AS="${toolchain}/bin/llvm-as"
    export LD="${toolchain}/bin/ld"
    export RANLIB="${toolchain}/bin/llvm-ranlib"
    export STRIP="${toolchain}/bin/llvm-strip"
    export NM="${toolchain}/bin/llvm-nm"

    # 检查编译器是否存在
    if [[ ! -f "${CC}" ]]; then
        # Windows 上可能是 .exe 后缀
        if [[ -f "${CC}.exe" ]]; then
            export CC="${CC}.exe"
            export CXX="${CXX}.exe"
        else
            error "编译器不存在: ${CC} (请检查 NDK 版本和 API Level)"
        fi
    fi

    info "  CC: ${CC}"

    # 克隆 opus 源码（如果尚未克隆）
    local opus_src="${BUILD_BASE}/opus-source"
    if [[ ! -d "${opus_src}/.git" ]]; then
        info "克隆 opus 源码 (${OPUS_VERSION})..."
        git clone --depth 1 --branch "${OPUS_VERSION}" "${OPUS_REPO}" "${opus_src}"
    else
        info "opus 源码已存在，更新中..."
        (cd "${opus_src}" && git fetch --depth 1 origin "${OPUS_VERSION}" && git checkout "${OPUS_VERSION}") || true
    fi

    # 进入源码目录
    cd "${opus_src}"

    # 运行 autogen（如果需要）
    if [[ ! -f "configure" ]]; then
        info "运行 autogen.sh..."
        ./autogen.sh
    fi

    # 创建构建子目录
    local arch_build="${build_dir}/build"
    mkdir -p "${arch_build}"
    cd "${arch_build}"

    # 配置
    info "配置 opus (${arch})..."
    "${opus_src}/configure" \
        --host="${target}" \
        --prefix="${build_dir}/install" \
        --enable-shared \
        --disable-static \
        --disable-doc \
        --disable-extra-programs \
        --with-pic \
        ${extra_configure_flags} \
        CFLAGS="-O3 -fPIC ${extra_cflags} -DANDROID" \
        LDFLAGS="-shared -Wl,-soname,libopus.so"

    # 编译
    info "编译 opus (${arch})..."
    make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

    # 安装到临时目录
    info "安装 opus 到临时目录..."
    make install

    # 复制 .so 到输出目录
    mkdir -p "${output_dir}"
    local so_file
    so_file=$(find "${build_dir}/install/lib" -name "libopus.so*" -type f | head -1)
    if [[ -z "${so_file}" ]]; then
        # 有时在 lib64 下
        so_file=$(find "${build_dir}/install" -name "libopus.so*" -type f | head -1)
    fi

    if [[ -n "${so_file}" ]]; then
        cp "${so_file}" "${output_dir}/libopus.so"
        "${STRIP}" --strip-unneeded "${output_dir}/libopus.so"
        ok "libopus.so 已生成: ${output_dir}/libopus.so"
        ok "  文件大小: $(du -h "${output_dir}/libopus.so" | cut -f1)"
    else
        error "未找到编译产物 libopus.so"
    fi

    # 清理构建临时文件（可选）
    info "清理 ${arch} 构建临时文件..."
    rm -rf "${arch_build}"
}

# ----- 主流程 -----
main() {
    echo ""
    echo "============================================"
    echo "  libopus Android 交叉编译脚本"
    echo "  版本: ${OPUS_VERSION}"
    echo "============================================"
    echo ""

    # 检查依赖
    for cmd in git make gcc; do
        if ! command -v "${cmd}" &>/dev/null; then
            error "缺少必要工具: ${cmd}"
        fi
    done

    # 检测 NDK
    info "检测 Android NDK..."
    NDK_DIR=$(detect_ndk)
    if [[ -z "${NDK_DIR}" ]]; then
        error "未找到 Android NDK!

请通过以下方式之一指定 NDK 路径:
  1. 设置环境变量: export ANDROID_NDK_HOME=/path/to/ndk
  2. 命令行参数:    ./scripts/build_libopus.sh --ndk /path/to/ndk

NDK 下载地址: https://developer.android.com/ndk/downloads"
    fi
    ok "NDK 路径: ${NDK_DIR}"

    # 验证 NDK 目录结构
    if [[ ! -f "${NDK_DIR}/source.properties" ]]; then
        warn "NDK 目录可能无效 (未找到 source.properties): ${NDK_DIR}"
    fi

    # 编译各架构
    for arch in "${ARCH_LIST[@]}"; do
        echo ""
        info "===== 编译 ${arch} ====="
        build_opus "${arch}"
    done

    echo ""
    echo "============================================"
    ok "全部编译完成!"
    echo "============================================"
    echo ""
    echo "编译产物:"
    for arch in "${ARCH_LIST[@]}"; do
        local output="${OUTPUT_BASE}/${arch}/libopus.so"
        if [[ -f "${output}" ]]; then
            echo "  ${arch}: ${output} ($(du -h "${output}" | cut -f1))"
        fi
    done
    echo ""
    info "清理构建缓存: rm -rf ${BUILD_BASE}"
}

main "$@"
