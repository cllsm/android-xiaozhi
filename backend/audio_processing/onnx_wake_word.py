"""基于 onnxruntime 的轻量唤醒词检测器.

在 sherpa-onnx 不可用时（如 Termux/Android 环境），
使用 onnxruntime 直接加载 Zipformer transducer 模型进行关键词检测。

架构:
  - FbankExtractor: 纯 numpy 实现 80 维 log Mel fbank 特征提取
  - SimpleVAD: 基于能量的语音活动检测，过滤静音帧
  - ContextGraph: Aho-Corasick 自动机，用于关键词路径匹配和 boosting
  - Hypothesis: beam search 中的解码假设
  - OnnxStream: 流式检测，维护编码器状态和 beam search 状态
  - OnnxWakeWordDetector: 检测器入口，兼容 sherpa_onnx.KeywordSpotter 接口

模型结构:
  encoder.onnx — Zipformer 编码器，输入 [N, 45, 80] fbank，输出 [N, T', 320]
  decoder.onnx — Transducer 解码器，输入 [N, 2] context tokens，输出 [N, 320]
  joiner.onnx  — 联合网络，输入 encoder_out + decoder_out，输出 [N, 197] logits
"""

import collections
import logging
from dataclasses import dataclass, field
from typing import Optional

import numpy as np

logger = logging.getLogger(__name__)

# 特殊 token ID
BLANK_TOKEN = 0   # <blk>
SOS_TOKEN = 1     # <sos/eos>
UNK_TOKEN = 2     # <unk>


# ==================== 特征提取 ====================

def _hann_window(length: int) -> np.ndarray:
    """生成 Hann 窗函数."""
    n = np.arange(length)
    return 0.5 * (1 - np.cos(2 * np.pi * n / (length - 1)))


def _mel_filterbank(num_filters: int, fft_size: int, sample_rate: int,
                    low_freq: float = 20.0, high_freq: Optional[float] = None) -> np.ndarray:
    """生成 Mel 滤波器组矩阵 [num_filters, fft_size // 2 + 1]."""
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

    参数: 25ms 窗口, 10ms 步进, 512 FFT
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
        self._audio_buffer = np.zeros(0, dtype=np.float32)

    def accept_waveform(self, audio: np.ndarray) -> Optional[np.ndarray]:
        """接收音频数据，返回 fbank 特征 [num_frames, 80] 或 None."""
        self._audio_buffer = np.concatenate([self._audio_buffer, audio])

        if len(self._audio_buffer) < self.win_size:
            return None

        num_frames = (len(self._audio_buffer) - self.win_size) // self.hop_size + 1
        if num_frames <= 0:
            return None

        features = self._extract(num_frames)
        consumed = (num_frames - 1) * self.hop_size + self.win_size
        self._audio_buffer = self._audio_buffer[consumed:]

        return features

    def _extract(self, num_frames: int) -> np.ndarray:
        """从缓冲区提取 fbank 特征."""
        features = np.zeros((num_frames, self.num_filters), dtype=np.float32)
        for i in range(num_frames):
            start = i * self.hop_size
            frame = self._audio_buffer[start:start + self.win_size]
            frame = frame * self.window
            spectrum = np.fft.rfft(frame, n=self.fft_size)
            power = np.abs(spectrum) ** 2
            mel_spec = np.dot(self.mel_fb, power)
            features[i] = np.log(mel_spec + 1e-10).astype(np.float32)
        return features

    def reset(self):
        """重置缓冲区."""
        self._audio_buffer = np.zeros(0, dtype=np.float32)


# ==================== VAD ====================

class SimpleVAD:
    """基于能量的语音活动检测器.

    用于过滤静音帧，减少不必要的模型推理。
    支持 hangover（语音结束后保留若干帧）和 pre-speech（语音开始前保留若干帧）。
    """

    def __init__(self, energy_threshold: float = 0.008, hangover_frames: int = 15,
                 pre_speech_frames: int = 8):
        self.energy_threshold = energy_threshold
        self.hangover_frames = hangover_frames
        self.pre_speech_frames = pre_speech_frames
        self._ring_buffer: collections.deque = collections.deque(maxlen=pre_speech_frames)
        self._hangover_count = 0
        self._in_speech = False

    def is_speech(self, audio: np.ndarray) -> bool:
        """检测音频帧是否包含语音.

        Args:
            audio: float32 音频数据

        Returns:
            True 表示包含语音，False 表示静音
        """
        if len(audio) == 0:
            return False

        rms = float(np.sqrt(np.mean(audio ** 2)))
        current_is_speech = rms > self.energy_threshold

        if current_is_speech:
            self._in_speech = True
            self._hangover_count = self.hangover_frames
            return True

        # 非语音帧：检查 hangover
        if self._hangover_count > 0:
            self._hangover_count -= 1
            return True

        self._in_speech = False
        return False

    def reset(self):
        """重置 VAD 状态."""
        self._ring_buffer.clear()
        self._hangover_count = 0
        self._in_speech = False


# ==================== Aho-Corasick 关键词匹配图 ====================

class ContextState:
    """ContextGraph 中的节点，对应 Aho-Corasick 自动机的一个状态."""

    __slots__ = ('token', 'node_score', 'output_score', 'ac_threshold',
                 'level', 'is_end', 'phrase', 'next', 'fail', 'output')

    def __init__(self, token: int = -1):
        self.token = token
        self.node_score: float = 0.0       # 到达此节点的累积 boosting 分
        self.output_score: float = 0.0     # 终端节点的匹配分数
        self.ac_threshold: float = 0.25    # 触发阈值
        self.level: int = 0                # 在关键词中的深度
        self.is_end: bool = False          # 是否是关键词终点
        self.phrase: str = ""              # 显示文本
        self.next: dict[int, 'ContextState'] = {}  # token -> 下一个状态
        self.fail: Optional['ContextState'] = None  # Aho-Corasick 失败链接
        self.output: Optional['ContextState'] = None  # 输出链接

    def __repr__(self):
        return (f"ContextState(token={self.token}, level={self.level}, "
                f"is_end={self.is_end}, phrase='{self.phrase}')")


class ContextGraph:
    """基于 Aho-Corasick 自动机的关键词匹配图.

    功能:
    - 将关键词 token 序列构建为 trie 树
    - 构建 Aho-Corasick 的 fail 和 output 链接
    - 在 beam search 中为匹配关键词路径的假设提供 boosting score
    - 检测关键词匹配完成

    与旧 KeywordFSM 的关键区别:
    - 通过 fail link 允许部分匹配回退（而不是直接重置）
    - blank token 不推进 ContextGraph（只增加 trailing_blanks）
    - 支持 per-keyword 的 threshold 和 boosting score
    """

    def __init__(self):
        self.root = ContextState(token=-1)
        self.root.fail = self.root  # root 的 fail 指向自身
        self.keywords: list[dict] = []

    def build(self, keywords: list[dict], context_score: float, threshold: float):
        """构建关键词匹配图.

        Args:
            keywords: [{"tokens": [id...], "text": "...", "length": N}, ...]
            context_score: 关键词 boosting 分数
            threshold: 检测阈值
        """
        self.keywords = keywords

        for kw in keywords:
            current = self.root
            for i, token_id in enumerate(kw["tokens"]):
                if token_id not in current.next:
                    node = ContextState(token=token_id)
                    node.level = i + 1
                    current.next[token_id] = node
                current = current.next[token_id]
                current.node_score += context_score / len(kw["tokens"])

            # 标记终端节点
            current.is_end = True
            current.phrase = kw["text"]
            current.output_score = context_score
            current.ac_threshold = threshold

        # 构建 fail 和 output 链接（BFS）
        self._fill_fail_output()

        kw_texts = [kw["text"] for kw in keywords]
        logger.info(f"ContextGraph 构建完成: {len(keywords)} 个关键词 {kw_texts}")

    def _fill_fail_output(self):
        """构建 Aho-Corasick 的 fail 和 output 链接."""
        queue = collections.deque()

        # 第一层节点的 fail 指向 root
        for token_id, node in self.root.next.items():
            node.fail = self.root
            queue.append(node)

        # BFS 构建后续层
        while queue:
            current = queue.popleft()

            for token_id, child in current.next.items():
                queue.append(child)

                # 沿着 fail 链找到第一个有 token_id 转移的节点
                fail = current.fail
                while fail is not self.root and token_id not in fail.next:
                    fail = fail.fail

                child.fail = fail.next.get(token_id, self.root)
                if child.fail is child:
                    child.fail = self.root

                # 构建 output 链接：如果 fail 指向终端节点，则设置 output
                if child.fail.is_end:
                    child.output = child.fail
                elif child.fail.output is not None:
                    child.output = child.fail.output

    def forward(self, state: ContextState, token_id: int) -> tuple[float, ContextState]:
        """沿 token_id 推进匹配，返回 (boosting_score, new_state).

        如果当前状态没有 token_id 的直接转移，则沿 fail 链回退。
        如果 fail 链上也没有，则回到 root。

        Args:
            state: 当前 ContextGraph 节点
            token_id: 输入的 token ID

        Returns:
            (boosting_score, new_state): boosting 分数和新的状态节点
        """
        score = 0.0

        # 沿 fail 链寻找匹配
        current = state
        while current is not self.root and token_id not in current.next:
            current = current.fail

        if token_id in current.next:
            new_state = current.next[token_id]
            score = new_state.node_score
        else:
            new_state = self.root

        return score, new_state

    def is_matched(self, state: ContextState) -> tuple[bool, str, float]:
        """检查当前状态是否完成了关键词匹配.

        检查当前节点本身是否是终端节点，以及通过 output 链是否能到达终端节点。

        Returns:
            (matched, phrase, threshold): 是否匹配，匹配的文本，触发阈值
        """
        if state.is_end:
            return True, state.phrase, state.ac_threshold

        # 检查 output 链
        output = state.output
        while output is not None:
            if output.is_end:
                return True, output.phrase, output.ac_threshold
            output = output.output

        return False, "", 0.0


# ==================== 解码假设 ====================

@dataclass
class Hypothesis:
    """beam search 中的解码假设.

    Attributes:
        ys: 已预测的 token ID 序列
        log_prob: 累积 log 概率（log-softmax 归一化后）
        num_trailing_blanks: 连续 blank 帧数
        ys_probs: 每个 token 的声学 log 概率（用于关键词阈值判断）
        context_state: ContextGraph 中的当前节点
    """
    ys: list = field(default_factory=list)
    log_prob: float = 0.0
    num_trailing_blanks: int = 0
    ys_probs: list = field(default_factory=list)
    context_state: Optional[ContextState] = None

    def key(self) -> tuple:
        """用于合并相同 token 序列的假设."""
        return tuple(self.ys[-2:]) if len(self.ys) >= 2 else tuple(self.ys)

    def clone(self) -> 'Hypothesis':
        """深拷贝假设."""
        return Hypothesis(
            ys=self.ys[:],
            log_prob=self.log_prob,
            num_trailing_blanks=self.num_trailing_blanks,
            ys_probs=self.ys_probs[:],
            context_state=self.context_state,
        )


# ==================== 工具函数 ====================

def log_softmax(x: np.ndarray) -> np.ndarray:
    """计算 log-softmax（数值稳定版本）."""
    x_max = np.max(x)
    return x - x_max - np.log(np.sum(np.exp(x - x_max)))


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
        max_active_paths: int = 4,
        keywords_score: float = 1.8,
        keywords_threshold: float = 0.25,
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

        # 加载关键词并构建 ContextGraph
        token_to_id = self._load_tokens(tokens)
        keywords = self._load_keywords(keywords_file, token_to_id)

        self.context_graph = ContextGraph()
        self.context_graph.build(keywords, keywords_score, keywords_threshold)

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

        logger.info(
            f"onnxruntime 唤醒词检测器初始化完成 "
            f"(关键词: {[k['text'] for k in keywords]}, "
            f"max_paths={max_active_paths}, score={keywords_score}, "
            f"threshold={keywords_threshold})"
        )

    @staticmethod
    def _load_tokens(tokens_path: str) -> dict[str, int]:
        """加载 token 映射表."""
        token_to_id: dict[str, int] = {}
        with open(tokens_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                parts = line.rsplit(" ", 1)
                if len(parts) == 2:
                    token_to_id[parts[0]] = int(parts[1])
                else:
                    token_to_id[line] = len(token_to_id)
        return token_to_id

    @staticmethod
    def _load_keywords(keywords_path: str, token_to_id: dict[str, int]) -> list[dict]:
        """加载关键词文件并转换为 token ID 序列."""
        keywords = []
        with open(keywords_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue

                # 格式: "n ǐ h ǎo x iǎo zh ì @你好小智"
                # 可选 score/threshold: "n ǐ h ǎo :1.8 #0.25 @你好小智"
                parts = line.split("@")
                if len(parts) < 2:
                    continue

                token_str = parts[0].strip()
                display_text = parts[-1].strip()

                # 解析可选的 :score 和 #threshold
                custom_score = None
                custom_threshold = None
                token_texts = []
                for t in token_str.split():
                    if t.startswith(":"):
                        try:
                            custom_score = float(t[1:])
                        except ValueError:
                            pass
                    elif t.startswith("#"):
                        try:
                            custom_threshold = float(t[1:])
                        except ValueError:
                            pass
                    else:
                        token_texts.append(t)

                token_ids = []
                valid = True
                for t in token_texts:
                    if t in token_to_id:
                        token_ids.append(token_to_id[t])
                    else:
                        logger.warning(f"关键词 token 不在词表中: '{t}' (关键词: {display_text})")
                        valid = False
                        break

                if valid and token_ids:
                    kw = {
                        "tokens": token_ids,
                        "text": display_text,
                        "length": len(token_ids),
                    }
                    if custom_score is not None:
                        kw["score"] = custom_score
                    if custom_threshold is not None:
                        kw["threshold"] = custom_threshold
                    keywords.append(kw)

        if not keywords:
            raise ValueError(f"没有有效的关键词（文件: {keywords_path}）")

        logger.info(f"加载 {len(keywords)} 个关键词: {[k['text'] for k in keywords]}")
        return keywords

    def _create_default_states(self) -> dict[str, np.ndarray]:
        """根据 encoder 输入签名创建默认零状态张量."""
        states = {}
        for inp in self.encoder_sess.get_inputs():
            if inp.name == "x":
                continue
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
        """执行 beam search 解码 + 关键词匹配."""
        stream._decode()

    def get_result(self, stream: "OnnxStream") -> Optional[str]:
        """获取检测结果."""
        return stream._get_result()

    def reset_stream(self, stream: "OnnxStream"):
        """重置检测流状态."""
        stream._reset()


# ==================== 检测流 ====================

class OnnxStream:
    """检测流，维护编码器状态和 beam search 状态.

    兼容 sherpa_onnx.OnlineStream 的 accept_waveform 接口。

    核心流程:
    1. accept_waveform: 音频 → VAD → fbank → encoder
    2. _decode: encoder output → beam search + ContextGraph → 检测结果
    """

    def __init__(self, detector: OnnxWakeWordDetector):
        self._detector = detector

        # 特征提取器
        self._fbank = FbankExtractor(
            sample_rate=detector.sample_rate,
            num_filters=detector.feature_dim,
        )

        # VAD（基于能量）
        self._vad = SimpleVAD()

        # fbank 帧缓冲
        self._fbank_buffer = np.zeros((0, detector.feature_dim), dtype=np.float32)

        # encoder 需要的帧数
        self._encoder_chunk_frames = 45

        # encoder 状态
        self._encoder_states = {k: v.copy() for k, v in detector._default_states.items()}

        # 编码输出缓冲
        self._encoder_out_buffer = np.zeros((0, 320), dtype=np.float32)

        # decoder context_size（从模型输入 shape 确认）
        self._context_size = 2

        # beam search: 初始假设
        root_state = detector.context_graph.root
        self._hypotheses: list[Hypothesis] = [
            Hypothesis(
                ys=[SOS_TOKEN, SOS_TOKEN],  # 初始 context: [sos, sos]
                log_prob=0.0,
                num_trailing_blanks=0,
                ys_probs=[],
                context_state=root_state,
            )
        ]

        # 检测结果
        self._detected: Optional[str] = None

        # 诊断计数器
        self._decode_count = 0
        self._speech_frame_count = 0
        self._silence_frame_count = 0

    def accept_waveform(self, sample_rate: int, waveform: np.ndarray):
        """接收音频数据（兼容 sherpa_onnx 接口）.

        流程: VAD 过滤 → fbank 提取 → encoder 推理
        """
        if waveform.dtype != np.float32:
            waveform = waveform.astype(np.float32)

        # VAD 过滤静音帧
        if not self._vad.is_speech(waveform):
            self._silence_frame_count += 1
            return

        self._speech_frame_count += 1

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
        feed = {"x": chunk[np.newaxis, :, :].astype(np.float32)}  # [1, 45, 80]
        for name, state in self._encoder_states.items():
            feed[name] = state

        # 运行 encoder
        outputs = self._detector.encoder_sess.run(
            self._detector.encoder_output_names, feed
        )

        # 分离 encoder_out 和新状态
        encoder_out = outputs[0]  # [1, T', 320]
        new_states = outputs[1:]

        # 更新 encoder 状态（new_xxx -> xxx）
        for i, out_name in enumerate(self._detector.encoder_output_names[1:]):
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
        """执行 modified beam search 解码（兼容 sherpa-onnx 算法）.

        对 encoder_out_buffer 中的每一帧:
        1. 对所有活跃假设，批量运行 decoder + joiner
        2. 对 logits 做 log-softmax 归一化
        3. 对每个 (假设, token) 对计算总分
        4. 选 top-k 并推进 ContextGraph
        5. 合并相同 token 序列的假设
        6. 检查关键词匹配
        """
        if self._detected is not None:
            return

        while self._encoder_out_buffer.shape[0] > 0:
            # 取一帧 encoder 输出
            enc_frame = self._encoder_out_buffer[0]  # [320]
            self._encoder_out_buffer = self._encoder_out_buffer[1:]

            hyps = self._hypotheses
            if not hyps:
                break

            self._decode_count += 1

            # --- 批量运行 decoder + joiner ---
            num_hyps = len(hyps)

            # 构建所有假设的 decoder context [num_hyps, context_size]
            all_contexts = np.array(
                [hyp.ys[-self._context_size:] for hyp in hyps],
                dtype=np.int64
            )

            # 批量 decoder 推理
            decoder_out = self._detector.decoder_sess.run(
                None, {"y": all_contexts}
            )[0]  # [num_hyps, 320]

            # 批量 joiner 推理（encoder frame broadcast）
            enc_expanded = np.broadcast_to(
                enc_frame[np.newaxis, :],
                (num_hyps, 320)
            ).copy()

            logits = self._detector.joiner_sess.run(
                None,
                {
                    "encoder_out": enc_expanded,
                    "decoder_out": decoder_out,
                },
            )[0]  # [num_hyps, 197]

            # --- 收集所有候选 (假设索引, token ID, 总分) ---
            candidates = []
            num_tokens = logits.shape[1]

            for hyp_idx in range(num_hyps):
                # log-softmax 归一化
                frame_logprobs = log_softmax(logits[hyp_idx])  # [197]
                hyp = hyps[hyp_idx]

                for token_id in range(num_tokens):
                    # 跳过 <unk> token
                    if token_id == UNK_TOKEN:
                        continue

                    # 基础分数 = 假设累积分 + 帧分数
                    total_score = hyp.log_prob + frame_logprobs[token_id]

                    # ContextGraph boosting（仅对非 blank token）
                    if token_id != BLANK_TOKEN:
                        boost_score, _ = self._detector.context_graph.forward(
                            hyp.context_state, token_id
                        )
                        total_score += boost_score

                    candidates.append((hyp_idx, token_id, total_score, float(frame_logprobs[token_id])))

            # --- 选择 top-k 候选 ---
            max_paths = self._detector.max_active_paths
            candidates.sort(key=lambda c: c[2], reverse=True)
            top_candidates = candidates[:max_paths * 3]  # 多取一些用于合并后筛选

            # --- 生成新假设 ---
            new_hyps: list[Hypothesis] = []

            for hyp_idx, token_id, total_score, frame_logprob in top_candidates:
                hyp = hyps[hyp_idx]
                new_hyp = hyp.clone()
                new_hyp.log_prob = total_score

                if token_id == BLANK_TOKEN:
                    # blank: 增加 trailing_blanks，不改变 ys
                    new_hyp.num_trailing_blanks += 1
                else:
                    # 非 blank: 追加 token，推进 ContextGraph
                    new_hyp.ys.append(token_id)
                    new_hyp.num_trailing_blanks = 0
                    new_hyp.ys_probs.append(frame_logprob)

                    # 推进 ContextGraph
                    _, new_ctx_state = self._detector.context_graph.forward(
                        hyp.context_state, token_id
                    )
                    new_hyp.context_state = new_ctx_state

                new_hyps.append(new_hyp)

            # --- 合并相同 key 的假设（保留最高分）---
            merged: dict[tuple, Hypothesis] = {}
            for h in new_hyps:
                k = h.key()
                if k not in merged or h.log_prob > merged[k].log_prob:
                    merged[k] = h

            # 保留 top-k
            sorted_hyps = sorted(merged.values(), key=lambda h: h.log_prob, reverse=True)
            self._hypotheses = sorted_hyps[:max_paths]

            # --- 检查关键词匹配 ---
            for hyp in self._hypotheses:
                if hyp.num_trailing_blanks < self._detector.num_trailing_blanks:
                    continue

                matched, phrase, threshold = self._detector.context_graph.is_matched(
                    hyp.context_state
                )

                if matched and phrase:
                    # 计算关键词 token 的平均声学概率
                    if hyp.ys_probs:
                        # 取最后 N 个概率（N = 关键词 token 数）
                        keyword_token_count = 0
                        for kw in self._detector.context_graph.keywords:
                            if kw["text"] == phrase:
                                keyword_token_count = kw["length"]
                                break

                        if keyword_token_count > 0 and len(hyp.ys_probs) >= keyword_token_count:
                            relevant_probs = hyp.ys_probs[-keyword_token_count:]
                            ys_prob = sum(relevant_probs) / len(relevant_probs)
                        else:
                            ys_prob = sum(hyp.ys_probs) / max(len(hyp.ys_probs), 1)
                    else:
                        ys_prob = 0.0

                    if ys_prob >= threshold:
                        self._detected = phrase
                        logger.info(
                            f"唤醒词检测到: {phrase} "
                            f"(ys_prob={ys_prob:.3f}, threshold={threshold:.3f}, "
                            f"trailing_blanks={hyp.num_trailing_blanks})"
                        )
                        return

            # --- 诊断日志 ---
            if self._decode_count <= 3 or self._decode_count % 50 == 0:
                best = self._hypotheses[0] if self._hypotheses else None
                if best:
                    logger.debug(
                        f"KWS 解码 #{self._decode_count}: "
                        f"hyps={len(self._hypotheses)}, "
                        f"best_score={best.log_prob:.3f}, "
                        f"best_ys_tail={best.ys[-5:]}, "
                        f"blanks={best.num_trailing_blanks}, "
                        f"ctx_level={best.context_state.level if best.context_state else 0}, "
                        f"speech={self._speech_frame_count}, "
                        f"silence={self._silence_frame_count}"
                    )

    def _get_result(self) -> Optional[str]:
        """获取并清除检测结果."""
        return self._detected

    def _reset(self):
        """重置所有状态."""
        self._fbank.reset()
        self._vad.reset()
        self._fbank_buffer = np.zeros((0, self._detector.feature_dim), dtype=np.float32)
        self._encoder_states = {k: v.copy() for k, v in self._detector._default_states.items()}
        self._encoder_out_buffer = np.zeros((0, 320), dtype=np.float32)

        # 重置 beam search 状态
        root_state = self._detector.context_graph.root
        self._hypotheses = [
            Hypothesis(
                ys=[SOS_TOKEN, SOS_TOKEN],
                log_prob=0.0,
                num_trailing_blanks=0,
                ys_probs=[],
                context_state=root_state,
            )
        ]

        self._detected = None
        self._decode_count = 0
        self._speech_frame_count = 0
        self._silence_frame_count = 0
