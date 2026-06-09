"""基于 onnxruntime 的轻量唤醒词检测器.

在 sherpa-onnx 不可用时（如 Termux/Android 环境），
使用 onnxruntime 直接加载 Zipformer transducer 模型进行关键词检测。

模型结构:
  encoder.onnx — Zipformer 编码器，输入 80 维 fbank，输出 320 维编码向量
  decoder.onnx — Transducer 解码器，输入 token IDs，输出 320 维解码向量
  joiner.onnx  — 联合网络，合并编码器和解码器输出，产生 token logits
"""

import logging
from pathlib import Path
from typing import Optional

import numpy as np

logger = logging.getLogger(__name__)


# ==================== 特征提取 ====================

def _hann_window(length: int) -> np.ndarray:
    """生成 Hann 窗函数."""
    n = np.arange(length)
    return 0.5 * (1 - np.cos(2 * np.pi * n / (length - 1)))


def _mel_filterbank(num_filters: int, fft_size: int, sample_rate: int,
                    low_freq: float = 20.0, high_freq: Optional[float] = None) -> np.ndarray:
    """生成 Mel 滤波器组矩阵.

    Args:
        num_filters: Mel 滤波器数量 (80)
        fft_size: FFT 大小
        sample_rate: 采样率
        low_freq: 最低频率
        high_freq: 最高频率（默认为 sample_rate / 2）

    Returns:
        滤波器组矩阵 [num_filters, fft_size // 2 + 1]
    """
    if high_freq is None:
        high_freq = sample_rate / 2

    def hz_to_mel(hz):
        return 2595 * np.log10(1 + hz / 700)

    def mel_to_hz(mel):
        return 700 * (10 ** (mel / 2595) - 1)

    low_mel = hz_to_mel(low_freq)
    high_mel = hz_to_mel(high_freq)
    mel_points = np.linspace(low_mel, high_mel, num_filters + 2)
    hz_points = mel_to_hz(mel_points)

    bin_points = np.floor((fft_size + 1) * hz_points / sample_rate).astype(int)

    num_bins = fft_size // 2 + 1
    filterbank = np.zeros((num_filters, num_bins))

    for i in range(num_filters):
        left = bin_points[i]
        center = bin_points[i + 1]
        right = bin_points[i + 2]

        for j in range(left, center):
            if center != left:
                filterbank[i, j] = (j - left) / (center - left)

        for j in range(center, right):
            if right != center:
                filterbank[i, j] = (right - j) / (right - center)

    return filterbank


class FbankExtractor:
    """80 维 log Mel fbank 特征提取器.

    参数:
        sample_rate: 采样率 (16000)
        num_filters: Mel 滤波器数量 (80)
        win_size: 窗口大小 (25ms = 400 样本)
        hop_size: 步进大小 (10ms = 160 样本)
        fft_size: FFT 大小 (512)
    """

    def __init__(self, sample_rate: int = 16000, num_filters: int = 80,
                 win_size: int = 400, hop_size: int = 160, fft_size: int = 512):
        self.sample_rate = sample_rate
        self.num_filters = num_filters
        self.win_size = win_size
        self.hop_size = hop_size
        self.fft_size = fft_size

        self.window = _hann_window(win_size)
        self.mel_fb = _mel_filterbank(num_filters, fft_size, sample_rate)

        # 音频缓冲区
        self._audio_buffer = np.zeros(0, dtype=np.float32)

    def accept_waveform(self, audio: np.ndarray) -> Optional[np.ndarray]:
        """接收音频数据，返回 fbank 特征（如果有足够的帧）.

        Args:
            audio: float32 音频数据，16kHz 单声道

        Returns:
            fbank 特征 [num_frames, 80]，或 None（数据不足）
        """
        self._audio_buffer = np.concatenate([self._audio_buffer, audio])

        # 需要至少一个完整窗口
        if len(self._audio_buffer) < self.win_size:
            return None

        # 计算可以提取多少帧
        num_frames = (len(self._audio_buffer) - self.win_size) // self.hop_size + 1
        if num_frames <= 0:
            return None

        # 提取 fbank
        features = self._extract(num_frames)

        # 保留未使用的音频
        consumed = (num_frames - 1) * self.hop_size + self.win_size
        self._audio_buffer = self._audio_buffer[consumed:]

        return features

    def _extract(self, num_frames: int) -> np.ndarray:
        """从缓冲区提取 fbank 特征."""
        features = np.zeros((num_frames, self.num_filters), dtype=np.float32)

        for i in range(num_frames):
            start = i * self.hop_size
            frame = self._audio_buffer[start:start + self.win_size]

            # 加窗
            frame = frame * self.window

            # FFT
            spectrum = np.fft.rfft(frame, n=self.fft_size)
            power = np.abs(spectrum) ** 2

            # Mel 滤波器组
            mel_spec = np.dot(self.mel_fb, power)

            # Log（加 epsilon 防止 log(0)）
            features[i] = np.log(mel_spec + 1e-10).astype(np.float32)

        return features

    def reset(self):
        """重置缓冲区."""
        self._audio_buffer = np.zeros(0, dtype=np.float32)


# ==================== 关键词解析 ====================

class KeywordFSM:
    """关键词有限状态机.

    将 keywords.txt 中的 token 序列解析为状态转移图，
    用于约束解码时只匹配关键词路径。
    """

    def __init__(self):
        self.keywords: list[dict] = []
        # token_text -> token_id 的映射
        self.token_to_id: dict[str, int] = {}

    def load(self, tokens_path: Path, keywords_path: Path):
        """加载 tokens.txt 和 keywords.txt.

        Args:
            tokens_path: tokens.txt 文件路径
            keywords_path: keywords.txt 文件路径
        """
        # 加载 token 映射
        # tokens.txt 格式: "token id"（每行一个 token，空格分隔名称和编号）
        # 例如: "<blk> 0", "n 82", "zh 10"
        self.token_to_id = {}
        with open(tokens_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                parts = line.rsplit(" ", 1)
                if len(parts) == 2:
                    token_text = parts[0]
                    token_id = int(parts[1])
                    self.token_to_id[token_text] = token_id
                else:
                    # fallback: 行号作为 id
                    self.token_to_id[line] = len(self.token_to_id)
        logger.debug(f"加载 {len(self.token_to_id)} 个 tokens")

        # 加载关键词
        self.keywords = []
        with open(keywords_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue

                # 格式: "n ǐ h ǎo x iǎo zh ì @你好小智"
                parts = line.split("@")
                if len(parts) < 2:
                    continue

                token_str = parts[0].strip()
                display_text = parts[1].strip()

                token_texts = token_str.split()
                token_ids = []
                valid = True
                for t in token_texts:
                    if t in self.token_to_id:
                        token_ids.append(self.token_to_id[t])
                    else:
                        logger.warning(f"关键词 token 不在词表中: '{t}' (关键词: {display_text})")
                        valid = False
                        break

                if valid and token_ids:
                    self.keywords.append({
                        "tokens": token_ids,
                        "text": display_text,
                        "length": len(token_ids),
                    })

        logger.info(f"加载 {len(self.keywords)} 个关键词: {[k['text'] for k in self.keywords]}")


# ==================== 检测器 ====================

class OnnxWakeWordDetector:
    """基于 onnxruntime 的唤醒词检测器.

    实现 sherpa_onnx.KeywordSpotter 的兼容接口:
    - create_stream() → OnnxStream
    - is_ready(stream) → bool
    - decode_stream(stream)
    - get_result(stream) → str | None
    - reset_stream(stream)
    """

    def __init__(
        self,
        tokens: str,
        encoder: str,
        decoder: str,
        joiner: str,
        keywords_file: str,
        num_threads: int = 4,
        sample_rate: int = 16000,
        feature_dim: int = 80,
        max_active_paths: int = 2,
        keywords_score: float = 1.8,
        keywords_threshold: float = 0.2,
        num_trailing_blanks: int = 1,
        provider: str = "cpu",
    ):
        import onnxruntime as ort

        self.sample_rate = sample_rate
        self.feature_dim = feature_dim
        self.max_active_paths = max_active_paths
        self.keywords_score = keywords_score
        self.keywords_threshold = keywords_threshold
        self.num_trailing_blanks = num_trailing_blanks

        # 加载关键词 FSM
        self.keyword_fsm = KeywordFSM()
        self.keyword_fsm.load(Path(tokens), Path(keywords_file))

        if not self.keyword_fsm.keywords:
            raise ValueError("没有有效的关键词")

        # 创建 ONNX 会话
        sess_opts = ort.SessionOptions()
        sess_opts.intra_op_num_threads = num_threads
        sess_opts.inter_op_num_threads = 1

        provider_name = "CPUExecutionProvider" if provider == "cpu" else provider

        self.encoder_sess = ort.InferenceSession(encoder, sess_opts, providers=[provider_name])
        self.decoder_sess = ort.InferenceSession(decoder, sess_opts, providers=[provider_name])
        self.joiner_sess = ort.InferenceSession(joiner, sess_opts, providers=[provider_name])

        # 获取 encoder 输入输出名称
        self.encoder_input_names = [inp.name for inp in self.encoder_sess.get_inputs()]
        self.encoder_output_names = [out.name for out in self.encoder_sess.get_outputs()]

        # 初始化默认 encoder 状态模板
        self._default_states = self._create_default_states()

        logger.info(f"onnxruntime 唤醒词检测器初始化完成 (关键词: {[k['text'] for k in self.keyword_fsm.keywords]})")

    def _create_default_states(self) -> dict[str, np.ndarray]:
        """根据 encoder 输入签名创建默认零状态张量."""
        states = {}
        for inp in self.encoder_sess.get_inputs():
            if inp.name == "x":
                continue  # x 是特征输入，不是状态
            shape = [d if isinstance(d, int) else 1 for d in inp.shape]
            dtype = np.float32 if inp.type == "tensor(float)" else np.int64
            states[inp.name] = np.zeros(shape, dtype=dtype)
        return states

    def create_stream(self) -> "OnnxStream":
        """创建检测流（兼容 sherpa_onnx 接口）."""
        return OnnxStream(self)

    def is_ready(self, stream: "OnnxStream") -> bool:
        """检查是否有待解码的编码帧."""
        return stream._num_pending_frames() > 0

    def decode_stream(self, stream: "OnnxStream"):
        """执行解码（约束贪心搜索 + 关键词匹配）."""
        stream._decode()

    def get_result(self, stream: "OnnxStream") -> Optional[str]:
        """获取检测结果."""
        return stream._get_result()

    def reset_stream(self, stream: "OnnxStream"):
        """重置检测流状态."""
        stream._reset()


class OnnxStream:
    """检测流，维护编码器状态和解码状态.

    兼容 sherpa_onnx.OnlineStream 的 accept_waveform 接口。
    """

    def __init__(self, detector: OnnxWakeWordDetector):
        self._detector = detector

        # 特征提取器
        self._fbank = FbankExtractor(
            sample_rate=detector.sample_rate,
            num_filters=detector.feature_dim,
        )

        # fbank 帧缓冲
        self._fbank_buffer = np.zeros((0, detector.feature_dim), dtype=np.float32)

        # encoder 需要的帧数（从模型输入 x 的 shape 获取）
        self._encoder_chunk_frames = 45  # 默认 45 帧

        # encoder 状态
        self._encoder_states = {k: v.copy() for k, v in detector._default_states.items()}

        # 编码输出缓冲
        self._encoder_out_buffer = np.zeros((0, 320), dtype=np.float32)

        # 解码状态
        self._blank_token_id = 0  # <blk> token
        self._last_token_id = 1   # <sos/eos> token

        # 关键词匹配状态
        self._keyword_match_pos = {i: 0 for i in range(len(detector.keyword_fsm.keywords))}
        self._keyword_scores = {i: 0.0 for i in range(len(detector.keyword_fsm.keywords))}
        self._trailing_blanks = 0

        # 检测结果
        self._detected: Optional[str] = None

    def accept_waveform(self, sample_rate: int, waveform: np.ndarray):
        """接收音频数据（兼容 sherpa_onnx 接口）."""
        if waveform.dtype != np.float32:
            waveform = waveform.astype(np.float32)

        # 提取 fbank 特征
        fbank = self._fbank.accept_waveform(waveform)
        if fbank is not None:
            self._fbank_buffer = np.concatenate([self._fbank_buffer, fbank], axis=0)

        # 当 fbank 帧数足够时，执行 encoder
        while self._fbank_buffer.shape[0] >= self._encoder_chunk_frames:
            self._encode_chunk()

    def _encode_chunk(self):
        """执行一次 encoder 推理."""
        chunk = self._fbank_buffer[:self._encoder_chunk_frames]
        self._fbank_buffer = self._fbank_buffer[self._encoder_chunk_frames:]

        # 构建 feed dict
        feed = {"x": chunk[np.newaxis, :, :]}  # [1, 45, 80]
        for name, state in self._encoder_states.items():
            feed[name] = state

        # 运行 encoder
        outputs = self._detector.encoder_sess.run(
            self._detector.encoder_output_names, feed
        )

        # 分离 encoder_out 和新的状态
        encoder_out = outputs[0]  # [1, T', 320]
        new_states = outputs[1:]

        # 更新 encoder 状态
        # 输出名是 new_cached_* / new_embed_states / new_processed_lens
        # 输入名是 cached_* / embed_states / processed_lens（没有 new_ 前缀）
        for i, out_name in enumerate(self._detector.encoder_output_names[1:]):
            # 去掉 "new_" 前缀得到对应的输入状态名
            state_name = out_name[4:] if out_name.startswith("new_") else out_name
            self._encoder_states[state_name] = new_states[i]

        # 追加编码输出到缓冲
        encoder_out_2d = encoder_out[0]  # [T', 320]
        self._encoder_out_buffer = np.concatenate(
            [self._encoder_out_buffer, encoder_out_2d], axis=0
        )

    def _num_pending_frames(self) -> int:
        """待解码的编码帧数."""
        return self._encoder_out_buffer.shape[0]

    def _decode(self):
        """执行约束贪心解码."""
        if self._detected is not None:
            return

        while self._encoder_out_buffer.shape[0] > 0:
            # 取一帧 encoder 输出
            enc_frame = self._encoder_out_buffer[0]  # [320]
            self._encoder_out_buffer = self._encoder_out_buffer[1:]

            # 运行 decoder: 输入 [current_token, last_token]
            token_input = np.array([[self._last_token_id, self._blank_token_id]], dtype=np.int64)
            decoder_out = self._detector.decoder_sess.run(
                None, {"y": token_input}
            )[0]  # [1, 320]

            # 运行 joiner
            logit = self._detector.joiner_sess.run(
                None,
                {
                    "encoder_out": enc_frame[np.newaxis, :],   # [1, 320]
                    "decoder_out": decoder_out[0:1, :],         # [1, 320]
                },
            )[0]  # [1, 197]

            # 获取 logits
            logit = logit[0]  # [197]

            # 对关键词 token 加分
            keyword_token_ids = set()
            for kw in self._detector.keyword_fsm.keywords:
                for tid in kw["tokens"]:
                    keyword_token_ids.add(tid)

            boosted_logit = logit.copy()
            for tid in keyword_token_ids:
                if tid < len(boosted_logit):
                    boosted_logit[tid] += self._detector.keywords_score

            # 贪心选择最佳 token
            best_token = int(np.argmax(boosted_logit))

            if best_token == self._blank_token_id:
                self._trailing_blanks += 1
                # 检查是否有关键词匹配成功且满足 trailing blanks 条件
                for i, kw in enumerate(self._detector.keyword_fsm.keywords):
                    if (self._keyword_match_pos[i] >= kw["length"]
                            and self._keyword_scores[i] >= self._detector.keywords_threshold
                            and self._trailing_blanks >= self._detector.num_trailing_blanks):
                        self._detected = kw["text"]
                        logger.info(f"唤醒词检测到: {kw['text']} (分数: {self._keyword_scores[i]:.2f})")
                        return

                # 超过一定数量的 blank 且未匹配完成 → 重置匹配状态
                if self._trailing_blanks > self._detector.num_trailing_blanks + 3:
                    self._reset_match_state()
            else:
                self._trailing_blanks = 0

                # 检查是否匹配关键词路径
                for i, kw in enumerate(self._detector.keyword_fsm.keywords):
                    pos = self._keyword_match_pos[i]
                    if pos < kw["length"] and kw["tokens"][pos] == best_token:
                        self._keyword_match_pos[i] = pos + 1
                        self._keyword_scores[i] += float(logit[best_token])
                        # 标记关键词完成（将在下次 blank 时检查）
                        if self._keyword_match_pos[i] >= kw["length"]:
                            logger.debug(f"关键词 token 序列匹配完成: {kw['text']}")

            self._last_token_id = best_token

    def _get_result(self) -> Optional[str]:
        """获取并清除检测结果."""
        result = self._detected
        return result

    def _reset(self):
        """重置所有状态."""
        self._fbank.reset()
        self._fbank_buffer = np.zeros((0, self._detector.feature_dim), dtype=np.float32)
        self._encoder_states = {k: v.copy() for k, v in self._detector._default_states.items()}
        self._encoder_out_buffer = np.zeros((0, 320), dtype=np.float32)
        self._last_token_id = 1  # <sos/eos>
        self._reset_match_state()
        self._detected = None

    def _reset_match_state(self):
        """重置关键词匹配状态."""
        n = len(self._detector.keyword_fsm.keywords)
        self._keyword_match_pos = {i: 0 for i in range(n)}
        self._keyword_scores = {i: 0.0 for i in range(n)}
        self._trailing_blanks = 0
