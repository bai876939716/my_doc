---
title: AI 语音技术详解：从 ASR、TTS 到实时语音 Agent 的工程化落地
description: 深入拆解 AI 语音系统底层链路，涵盖音频采集、VAD、ASR、LLM、TTS、流式播放、打断处理、低延迟优化以及云端 API、本地模型、端云混合选型。
category: AI 应用开发
head:
  - - meta
    - name: keywords
      content: AI语音,ASR,TTS,VAD,实时语音Agent,Speech to Speech,语音识别,语音合成,端云混合,Realtime API
---

<!-- @include: @article-header.snippet.md -->

大家好，我是 Guide。

很多开发者第一次做 AI 语音应用时，都会有一个很朴素的想法：用户说话，转成文字，丢给大模型，再把回答播出来。

听起来就是三段调用：**ASR -> LLM -> TTS**。

真推到生产环境，问题马上来了：用户还没说完，系统已经误判结束；用户想打断，AI 还在自顾自朗读；会议室里有空调声和键盘声，ASR 开始胡乱转写；网络稍微抖一下，下行音频就卡成一段一段；看起来模型很聪明，真正说话时却像慢半拍的电话客服。

AI 语音系统最折磨人的地方就在这里：**它不是把文本 Agent 接上麦克风和扬声器这么简单，而是一套实时音频工程、语音模型、对话状态和端云协同共同组成的系统**。

本文接近 2w 字，建议收藏，通过本文你将搞懂：

1. ASR、TTS、VAD 的核心原理，以及云端 API 和本地模型该怎么选。
2. 实时语音交互的核心难点：延迟、打断、噪声、上下文和端侧能力各自卡在哪里。
3. 从 interview-guide 项目看基础版语音 Agent 是怎么一步步实现的。
4. WebRTC 在端侧音频处理中的实际作用和配置选择。
5. 状态机设计、打断处理、成本控制等生产级落地要点。
6. 语音 Agent 的后续演进方向。

## 术语说明

为避免阅读时产生困惑，本文涉及的核心术语做如下说明：

- **端侧** = 客户端（浏览器/App），指用户设备上的前端代码
- **Barge-in** = 打断/插话打断，即用户在大模型响应过程中主动中断 AI 说话
- **增量结果** = 流式输出 = partial results，指 ASR 实时返回的识别中间结果
- **级联方案** = ASR + LLM + TTS 分阶段串联的架构
- **原生 Realtime API** = Speech-to-Speech，端到端多模态模型，直接音频进、音频出

## AI 语音系统到底解决了什么问题？

在说技术之前，先搞清楚我们到底在解决什么问题。

语音 Agent 的本质目标是**让机器能像人一样自然地对话**。这听起来简单，但和文字对话相比，语音多了几个维度：

- **实时性**：用户说话的时候，系统就得开始工作，不能等用户说完再反应。
- **多模态信息**：语气、停顿、情绪，这些在文字里都丢了。
- **打断能力**：人说话可以互相插嘴，机器也得支持。
- **端到端延迟**：文字聊天慢 1 秒用户还能忍，语音慢 1 秒就感觉对方“没反应”。

市面上常见的语音交互有两类：

1. **传统语音助手**：Siri、小爱同学、车载语音。你说“打开空调”，它执行固定命令。本质是个语音版的菜单系统。
2. **大模型语音 Agent**：能理解开放问题、调用工具、持续多轮对话。你问“帮我看看上周那个接口超时是怎么回事”，它需要理解意图、检索上下文、生成回答、还要用语音和你来回确认。

这两者的底层逻辑完全不同。本文主要讨论后者，也就是大模型语音 Agent 的工程化落地。

## 语音识别（ASR）是怎么把声音变成文字的？

ASR（Automatic Speech Recognition）看起来就是“音频进、文字出”，但背后至少包含三个判断：

1. 这段音频说的是什么字。
2. 这些字怎么切分成词和句子。
3. 标点、数字、英文、技术名词怎么规范化。

比如用户说“帮我查一下 Java 21 的虚拟线程”，ASR 要同时识别中文、英文、数字和技术词。如果识别成“加瓦二十一的虚拟线程”，后面的 LLM 再强也得先猜半天。

### ASR 的三条技术路线

| 类型         | 代表方案                                                                                                                           | 优势                                                                  | 短板                                                             | 适合场景                       |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- | ---------------------------------------------------------------- | ------------------------------ |
| 云端 API     | OpenAI Transcription（gpt-4o-transcribe、whisper-1、gpt-4o-transcribe-diarize）、Azure Speech、Google Speech、Deepgram、阿里云 ASR | 接入快，语言覆盖广，运维成本低                                        | 成本、网络延迟、数据合规受限                                     | 客服、会议转写、轻量语音助手   |
| 开源通用模型 | Whisper、faster-whisper、Whisper.cpp、FunASR                                                                                       | 可本地部署，可控性强，支持私有化；faster-whisper 内置 Silero VAD 过滤 | 实时性要自己做工程优化；Whisper turbo 未针对翻译训练，翻译效果差 | 私有化转写、离线字幕、企业内网 |
| 领域定制模型 | 金融、医疗、车载专用 ASR                                                                                                           | 专有名词和口音适配更好                                                | 数据准备和训练成本高                                             | 高频垂直场景、强业务词表       |

**补充说明**：

- OpenAI 的 `gpt-4o-transcribe-diarize` 支持说话人标签，适合会议转写等多人场景；注意：不支持 Realtime API、不支持 prompt 上下文、音频块上限 1400 秒（~23分钟）。如不需要说话人标签，优先使用 `gpt-4o-transcribe` 或 `whisper-1`
- Whisper turbo（large-v3-turbo）是 large-v3 的推理优化版，速度快但**未针对翻译任务训练**，执行 `--task translate` 时会输出原始语言而非英语，需要翻译时请用 medium 或 large

**选型建议**：如果你的核心需求是“实时对话”，不要只看离线 WER（Word Error Rate，词错误率）。你更应该关注：

- **首段延迟**：用户说完到看到第一个字的时间
- **增量结果稳定性**：能不能实时看到识别进度
- **端点检测准确率**：能不能准确判断用户说完了
- **噪声环境表现**：远场、多人说话时准不准
- **热词能力**：能不能识别你的业务专属词汇

### 流式 ASR 和非流式 ASR 的区别

做实时对话必须用流式 ASR。区别在于：

- **非流式 ASR**：等用户说完一段话，再整段识别。延迟 = 说话时长 + 识别时间。
- **流式 ASR**：边说边识别，用户话音刚落就能拿到结果。延迟 ≈ 端点检测时间 + 实时识别时间。

interview-guide 项目用的是**阿里云 DashScope 的 qwen3-asr-flash-realtime**，这是一个服务端 VAD 驱动的流式 ASR：

```java
// QwenAsrService.java
OmniRealtimeConfig config = OmniRealtimeConfig.builder()
    .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
    .enableTurnDetection(true)  // 开启服务端 VAD
    .turnDetectionType("server_vad")
    .turnDetectionSilenceDurationMs(400)  // 400ms 静音判定用户说完
    .transcriptionConfig(transcriptionParam)
    .build();
```

服务端 VAD 的好处是**不用客户端做复杂的语音活动检测**，但代价是你要等 400ms 静音才判定用户说完。实际体验中这 400ms 挺明显的，所以很多方案会改成客户端 VAD 先触发、前端先提交，等服务端确认。

## 语音合成（TTS）是怎么把文字变成声音的？

TTS（Text To Speech）负责把模型回复合成音频。它看起来是输出层，但其实很影响用户对整个 Agent 的感知。

同一句“我帮你查一下”，不同 TTS 的差异可能体现在：

- 首包音频要等多久
- 音色是否自然，长句是否喘得像真人
- 数字、代码、英文缩写是否读得准确
- 是否支持情绪、语速、停顿、音高控制

### TTS 的技术演进

传统 TTS 分好几步走：

```
文本规范化 -> 文本分析 -> 声学模型 -> 声码器 -> 波形输出
```

现在主流的端到端模型（比如 VALL-E、Fish Speech、CosyVoice）把这个链路压缩了，效果也更好。但对实时语音 Agent 来说，**单句音质不是最关键的，流式可播放性才是**。

如果你必须等整段文字生成完才能合成，用户体感会非常慢。如果能按短句甚至 token 流式合成，首包体验会好很多。

### 实时 TTS 的两条路线

| 类型         | 代表方案                                                            | 特点                   |
| ------------ | ------------------------------------------------------------------- | ---------------------- |
| 云端实时 TTS | OpenAI Speech、阿里云 qwen-tts-realtime、Azure TTS、ElevenLabs      | 流式输出，支持实时合成 |
| 本地 TTS     | piper1-gpl（GPL-3.0 ⚠️ 原 Piper 已归档）、Fish Speech（Apache 2.0） | 可控性强，适合离线场景 |

interview-guide 用的也是阿里云的 qwen-tts-realtime，通过 WebSocket 实时合成：

```java
// QwenTtsService.java
QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
    .voice(voice)  // 音色选择
    .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
    .mode("commit")  // 提交模式
    .languageType(languageType)
    .speechRate(speechRate)
    .volume(volume)
    .build();

// 发送文本，实时接收音频块
qwenTtsRealtime.appendText(text);
qwenTtsRealtime.commit();
```

每次合成都会建立新的 WebSocket 连接，接收 `response.audio.delta` 事件，把音频块拼接起来。

## VAD 为什么是语音系统的「隐形守门人」？

VAD（Voice Activity Detection，语音活动检测）这个组件经常被忽略，但它对体验影响极大。

VAD 的任务不是识别内容，而是判断：

- 用户开始说话了吗？
- 用户说完了吗？
- 当前声音是人声、背景噪声、音乐，还是系统自己播放的声音？

这件事看似简单，实际非常难。因为真实用户说话不是朗读新闻稿：

- 句中会停顿：“这个问题……我想问一下……”
- 会有短反馈：“嗯”“对”“不是”
- 会边想边说，音量忽大忽小
- 旁边可能有人说话，扬声器里也可能正在播放 AI 的声音

**端侧 VAD 还是服务端 VAD？**

| 类型       | 代表方案                                      | 优势                     | 短板                                                    |
| ---------- | --------------------------------------------- | ------------------------ | ------------------------------------------------------- |
| 端侧 VAD   | WebRTC VAD、Silero VAD ⚠️、@ricky0123/vad-web | 响应快，不消耗服务端资源 | 需要在客户端部署模型；Silero 召回率约 86%，短语音检测弱 |
| 服务端 VAD | DashScope ASR 内置、Whisper ASR 内置          | 不用管客户端             | 增加服务端负载，有网络延迟                              |

> ⚠️ **Silero VAD 局限**：采用保守策略以降低误报，代价是召回率约 86%，短语音（<1 秒如"嗯""对""不是"）检测能力明显下降。在语音 Agent 场景中，用户的短反馈和打断信号可能被漏检。如果打断响应性是核心指标，建议评估两级 VAD 方案或使用更平衡的检测器。

interview-guide 前端用的是 **@ricky0123/vad-web**，这是一个基于 ONNX 的端侧 VAD：

```typescript
// AudioRecorder.tsx
const vadInstance = await window.vad.MicVAD.new({
  getStream: async () => stream,
  onnxWASMBasePath: "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.22.0/dist/",
  baseAssetPath: "https://cdn.jsdelivr.net/npm/@ricky0123/vad-web@0.0.29/dist/",
  onSpeechStart: () => {
    onSpeechStart?.(); // 用户开始说话
  },
  onSpeechEnd: () => {
    onSpeechEnd?.(); // 用户说完
  },
});
```

**高频踩坑点**：端侧 VAD 触发 `onSpeechEnd` 后，不要以为用户真的说完了。最好再等 300-500ms 静音确认，避免把用户中途停顿当成结束。

我的建议是：**VAD 不要只当开关用，它应该输出一组对话控制信号**。比如：

- `speech_start`：用户开始说话
- `speech_end`：用户说完了（带置信度）
- `maybe_barge_in`：可能是用户在打断
- `noise_only`：只有噪声，没人说话

## 一次完整的语音对话是怎么跑起来的？

先把完整链路拆解清楚，后面讲细节才有上下文。

一次语音 Agent 对话大概经过这些步骤：

1. 音频采集：麦克风采集原始音频
2. 前处理：AEC 消回声、NS 降噪、AGC 增益
3. VAD 检测：判断用户是否在说话，是否说完
4. 音频上传：把处理后的音频发到服务端
5. ASR 转写：把音频转成文字（流式输出增量结果）
6. 上下文组装：拼接系统指令、历史对话、工具定义
7. LLM 推理：理解意图、生成回复、必要时调用工具
8. TTS 合成：把回复文字转成音频（流式输出音频块）
9. 音频下行：客户端边收边播
10. 状态回写：记录本次对话，为下一轮准备上下文

**高频盲区**：实时语音不是等用户说完才开始工作的。

优秀的系统会尽量把可以提前做的事提前做：

- 用户刚开始说话时，先加载会话状态和工具定义
- ASR 出现稳定前缀后，提前做意图预判
- LLM 输出第一个短句时，TTS 立刻开始合成
- 工具调用较慢时，先播一句自然的过渡语

核心做法是**用并行和流式把等待时间藏起来**。

## 实时语音为什么比文字对话难这么多？

这是本文的核心问题。让我拆成五个维度来讲。

### 难点一：延迟预算非常紧

文本聊天慢 1 秒，用户通常还能忍。语音对话慢 1 秒，用户会明显感觉对方“没反应”。

一轮语音交互的延迟来自这些环节：

| 环节         | 常见耗时                            | 优化方向                       |
| ------------ | ----------------------------------- | ------------------------------ |
| 采集与编码   | 音频帧大小、浏览器缓冲              | 小帧采集，减少无意义缓冲       |
| VAD 端点检测 | 等待静音确认用户说完                | 动态静音阈值，短句快速提交     |
| ASR          | 音频上传、解码、增量转写稳定        | 流式 ASR，热词，端侧预处理     |
| LLM          | 首 token 延迟、工具调用、上下文过长 | Prompt 缓存，短回复，异步工具  |
| TTS          | 首包合成、长句切分、声码器推理      | 句子级流式合成，预热音色       |
| 播放         | 网络抖动、解码、播放器缓冲          | WebRTC jitter buffer，边收边播 |

如果每段都多 200ms，整轮对话马上就变成“慢半拍”。

所以实时语音优化的目标不是让某一个组件跑到理论上限，而是**端到端 P95/P99 延迟稳定**。用户感受到的是整条链路，不是某个模型的 benchmark。

### 难点二：打断处理不是暂停按钮

语音 Agent 必须支持 **Barge-in（插话打断）**。

用户说“等一下，不是这个意思”，系统需要同时做几件事：

1. 识别出这是用户在说话，而不是背景噪声或扬声器回声
2. 立即停止本地播放队列，不能继续把旧回答播完
3. 取消服务端仍在生成的 LLM 和 TTS 流
4. 把已经播放、未播放、被打断的内容写进对话状态
5. 用新的用户音频开启下一轮理解

很多系统打断失败，不是因为 VAD 不准，而是**状态机没设计好**。比如播放器停了，但服务端 TTS 还在推流；LLM 停了，但历史里已经把未播出的回答记成了“已说过”。

interview-guide 的做法是：

```typescript
// VoiceInterviewPage.tsx
const handleAudioData = (audioData: string) => {
  // AI 播放时停发音频，避免自己的声音被识别
  if (isAiSpeakingRef.current) {
    return;
  }
  if (wsRef.current && wsRef.current.isConnected()) {
    wsRef.current.sendAudio(audioData);
  }
};
```

前端通过 `isAiSpeakingRef` 标记 AI 是否在说话，说话时停发音频。后端收到 `control` 消息取消生成。

### 难点三：噪声环境比测试环境复杂太多

语音 Demo 往往在安静办公室里跑，生产环境可能是：

- 车内、工厂、商场、地铁站
- 远场麦克风，用户离设备两三米
- 多人同时说话
- 用户开着外放，AI 的声音又被麦克风收回去

这会影响整条链路：

- VAD 把噪声当成人声，导致误触发
- ASR 把背景人声转成文本，污染用户意图
- TTS 播放被麦克风采集，造成自我打断

interview-guide 前端通过 `getUserMedia` 配置了三板斧：

```typescript
const stream = await navigator.mediaDevices.getUserMedia({
  audio: {
    echoCancellation: true, // AEC：消除扬声器回声
    noiseSuppression: true, // NS：压低背景噪声
    autoGainControl: true, // AGC：自动增益，让音量更稳定
    sampleRate: 16000,
  },
});
```

这三个参数能解决一部分问题，但**不能迷信它们**。WebRTC 的 AEC 在强回声场景下效果有限，NS 可能把用户声音也削掉一截。如果你要做硬件或 App 方案，端侧音频前处理会变成非常现实的工程投入。

### 难点四：上下文不只是文字历史

文本 Agent 的上下文主要是消息历史。语音 Agent 的上下文更多：

- 当前用户是否正在说话
- 上一段回答播放到了哪里
- 用户是正常提问，还是正在打断
- ASR 的增量文本是否稳定
- 用户语气是疑问、否定、犹豫，还是不耐烦
- 当前是否有工具调用正在执行

如果只把最终 ASR 文本喂给 LLM，很多信息会丢掉。

比如用户说“不是……我是说上个月那笔订单”，文本里能看到纠正，但看不到他是在打断 AI；系统如果不知道上一段回答播到哪里，就很难知道用户在否定哪一句。

interview-guide 用 WebSocket 消息类型区分了不同状态：

```typescript
// voiceInterview.ts
export interface WebSocketSubtitleMessage {
  type: "subtitle";
  text: string;
  isFinal: boolean; // true 表示用户已确认提交
}

export interface WebSocketAudioResponseMessage {
  type: "audio";
  data: string; // Base64 音频
  text: string; // 对应的文字
}

export interface WebSocketControlMessage {
  type: "control";
  action: string; // 'submit' | 'cancel' | 'pause'
  data?: Record<string, unknown>;
}
```

前端根据 `isFinal` 判断用户是否真的说完了，避免把用户中途停顿当成确认。

### 难点五：回声导致的误打断

还有一个高频踩坑点：**AI 播放的声音被麦克风采集后，VAD 或 ASR 会误判为用户说话，导致 AI 自我打断**。

interview-guide 的当前做法是：

```typescript
if (isAiSpeakingRef.current) {
  return; // AI 说话时停发音频
}
```

这种”静默丢弃”的方案确实避免了自我打断，但代价是**用户在 AI 说话期间的真正打断也被屏蔽了**。

更精细的方案：

- AI 说话时继续接收音频，但不发到 ASR
- 在 AEC 处理后的音频上运行端侧 VAD，而非原始麦克风音频
- 用能量阈值区分用户人声（通常 > -20dB）和回声残余

### 难点六：端侧能力决定体验下限

很多团队把所有能力都放云端，结果在弱网环境下体验崩得很快。

端侧至少应该承担这些职责：

- 麦克风采集和音频前处理
- VAD 或轻量打断检测
- 播放缓冲和取消播放
- 网络断开时的提示和重连

云端模型决定上限，端侧工程决定下限。这句话在语音系统里尤其明显。

## 从 interview-guide 看基础版语音 Agent 是怎么实现的？

说了这么多概念，来点实际的。我以 interview-guide 项目为例，讲解一个最基础的语音面试 Agent 是怎么跑起来的。

### 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        前端 (React)                          │
├─────────────────────────────────────────────────────────────┤
│  AudioRecorder        WebSocket         VoiceInterviewPage   │
│  - getUserMedia       - sendAudio       - 状态管理          │
│  - AudioWorklet       - sendControl     - 手动提交          │
│  - VAD 检测           - 控制消息         - 分块播放          │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                     后端 (Spring Boot)                       │
├─────────────────────────────────────────────────────────────┤
│  VoiceInterviewWebSocketHandler                             │
│  - 会话管理（创建、暂停、恢复、结束）                         │
│  - ASR ready / reconnect 状态同步                            │
│  - 音频路由到 ASR，手动 submit 后触发 LLM                     │
│  - LLM 句子流输出，TTS 边合成边推送                           │
├─────────────────────────────────────────────────────────────┤
│  QwenAsrService          DashscopeLlmService      QwenTtsService │
│  - qwen3-asr-flash-      - qwen-max / qwen-plus   - qwen-tts-    │
│    realtime              - 工具调用支持           realtime       │
└─────────────────────────────────────────────────────────────┘
```

### 前端：音频采集与 VAD

前端的核心是 `AudioRecorder` 组件。它做了这么几件事：

**第一步，获取麦克风权限并配置音频参数：**

```typescript
const stream = await navigator.mediaDevices.getUserMedia({
  audio: {
    echoCancellation: true,
    noiseSuppression: true,
    autoGainControl: true,
    sampleRate: 16000, // ASR 需要 16kHz
  },
});
```

**第二步，初始化端侧 VAD：**

```typescript
const vadInstance = await window.vad.MicVAD.new({
  getStream: async () => stream,
  onSpeechStart: () => {
    onSpeechStart?.(); // 触发回调
  },
  onSpeechEnd: () => {
    onSpeechEnd?.();
  },
});
await vadInstance.start();
```

**第三步，使用 AudioWorklet 做音频分块采集：**

VAD 的 `onSpeechEnd` 只是告诉你用户可能说完了，真正的音频还是要分块发送给服务端。interview-guide 的实现是：

```typescript
await audioContext.audioWorklet.addModule("/audio-worklet/pcm-processor.js");

const workletNode = new AudioWorkletNode(audioContext, "pcm-processor");
workletNode.port.onmessage = (event) => {
  if (!recordingActiveRef.current) {
    return;
  }
  const base64 = arrayBufferToBase64(event.data as ArrayBuffer);
  onAudioData(base64); // 200ms Int16 PCM，发送给后端 ASR
};

source.connect(workletNode);
workletNode.connect(gainNode);
gainNode.connect(audioContext.destination);
```

`pcm-processor.js` 运行在音频渲染线程中，负责把浏览器输入的 Float32 音频重采样成 16kHz、Int16 PCM，并按 200ms 一块通过 `postMessage` 交回主线程。相比已经废弃的 `ScriptProcessorNode`，`AudioWorkletNode` 不会把音频处理压在 UI 主线程上，延迟和卡顿风险更低。

这里有个设计选择：**为什么不等 VAD 触发 `onSpeechEnd` 再发音频？**

因为 VAD 检测有延迟，等它确认用户说完了再开始发音频，会白白多等 400-600ms。更好的做法是**持续分块发送**，VAD 触发 `onSpeechEnd` 只是告诉后端“这一段说完了，可以提交给 LLM 了”。

不过，interview-guide 的语音面试不是“检测到静音就自动提交”，而是**ASR 持续转写、用户手动点击提交**。这样可以避免候选人中途停顿时被系统抢答，也能解决“后面的话覆盖前面的回答”的体验问题：前端只把 ASR 结果作为回答草稿，真正进入下一轮面试由 `submit` 控制消息决定。

### 前端：音频播放

interview-guide 用了两种音频播放模式：

**模式一：HTMLAudioElement（简单场景）：**

```typescript
// VoiceInterviewPage.tsx
const onAudioResponse = (audioData: string, text: string) => {
  if (audioData && audioData.length > 0) {
    setAiAudio(audioData); // 设置 src，触发自动播放
    setAiText(text);
    setAiSpeaking(true);

    // 设置超时watchdog，防止音频播放异常卡住
    const durationMs = estimateWavDurationMs(audioData);
    audioPlaybackWatchdogRef.current = setTimeout(
      finishAiPlayback,
      Math.min(Math.max(durationMs + 1500, 4000), 60_000),
    );
  }
};
```

**模式二：AudioContext 分块播放（更精细控制）：**

```typescript
// 分块处理
const handleAudioChunk = (
  base64Wav: string,
  _index: number,
  isLast: boolean,
) => {
  // 1. 解码 WAV
  const binaryStr = atob(base64Wav);
  const bytes = new Uint8Array(binaryStr.length);
  const pcmOffset = 44;
  const pcmData = new Int16Array(
    bytes.buffer,
    pcmOffset,
    (bytes.length - pcmOffset) / 2,
  );
  const float32 = new Float32Array(pcmData.length);

  // 2. 放入播放队列
  chunkQueueRef.current.push(audioBuffer);
  if (!isChunkPlayingRef.current) {
    playNextChunk();
  }

  // 3. 最后一包或服务端 audio_complete 后，等待队列播完
  if (isLast) {
    scheduleChunkDrainCompletion();
  }
};

// 播放下一块
const playNextChunk = () => {
  if (chunkQueueRef.current.length === 0) {
    isChunkPlayingRef.current = false;
    return;
  }
  const buffer = chunkQueueRef.current.shift()!;
  const source = ctx.createBufferSource();
  source.buffer = buffer;
  source.connect(ctx.destination);
  source.onended = () => playNextChunk();
  source.start(0);
};
```

分块播放的好处是**能更快开始播放**，不用等完整音频文件加载完。但代价是实现复杂度更高，要自己管理队列和状态。

新版实现里，服务端还会在所有 TTS 分片发送完成后额外推一个 `audio_complete` 控制消息。这样前端不再依赖某个音频分片必须带 `isLast=true`，即使某一句 TTS 合成失败，也能在已成功分片播放完后正确结束“面试官正在说话”的状态。

> ⚠️ **注意**：浏览器要求 AudioContext 必须在用户交互后创建或恢复（autoplay policy）。如果在页面加载时创建 AudioContext，大多数浏览器会将其置于 `suspended` 状态。建议在用户点击"开始面试"按钮时调用 `audioContext.resume()` 确保播放正常。

### 后端：WebSocket 会话管理

后端通过 `VoiceInterviewWebSocketHandler` 管理会话生命周期：

```java
// VoiceInterviewWebSocketHandler.java
public class VoiceInterviewWebSocketHandler {
    // 会话状态：idle -> listening -> thinking -> speaking -> completed
    // 支持：pause（暂停）、resume（恢复）、end（结束）

    // 收到客户端音频
    public void handleAudioMessage(String sessionId, String audioBase64) {
        asrService.sendAudio(sessionId, decodeBase64(audioBase64));
    }

    // 收到客户端控制消息
    public void handleControlMessage(String sessionId, String action, Map data) {
        switch (action) {
            case "submit" -> llmService.triggerResponse(sessionId, data);
            case "cancel" -> cancelCurrentGeneration(sessionId);
            case "pause" -> pauseSession(sessionId);
        }
    }
}
```

interview-guide 的会话状态机：

| 状态        | 含义                           | 可转换到          |
| ----------- | ------------------------------ | ----------------- |
| IN_PROGRESS | 面试进行中                     | PAUSED, COMPLETED |
| PAUSED      | 暂停（用户离开页面或主动暂停） | IN_PROGRESS       |
| COMPLETED   | 面试结束                       | -                 |

暂停/恢复机制很有用。比如用户接电话、切换标签页，可以暂停面试，回来后无缝继续。

### 后端：ASR 服务

后端的 ASR 服务封装了阿里云 DashScope 的接口：

```java
// QwenAsrService.java
public void startTranscription(
    String sessionId,
    Consumer<String> onFinal,
    Consumer<String> onPartial,
    Runnable onReady,
    Consumer<Throwable> onError
) {
    // 1. 建立 WebSocket 连接到 DashScope ASR
    OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, callback);

    // 2. 配置：开启服务端 VAD，400ms 静音判定结束
    OmniRealtimeConfig config = OmniRealtimeConfig.builder()
        .enableTurnDetection(true)
        .turnDetectionSilenceDurationMs(400)
        .build();

    // 3. 注册回调：识别完成时触发
    conversation.updateSession(config);
    asrSession.markReady();
    onReady.run(); // 通知前端 asr_ready
}

public void sendAudio(String sessionId, byte[] audioData) {
    AsrSession session = sessions.get(sessionId);
    if (!session.awaitReady(1200)) {
        throw new IllegalStateException("ASR session not ready");
    }
    String audioBase64 = Base64.getEncoder().encodeToString(audioData);
    session.getConversation().appendAudio(audioBase64);
}
```

这一步很关键。早期版本里，前端 WebSocket 一连上就允许用户点麦克风，但 DashScope ASR 的会话还没完全 ready，导致“第一题能说、第二题录不到”这类问题。现在后端在 `updateSession` 完成后才发送 `asr_ready`，前端在此之前禁用麦克风；如果 10 秒后仍未 ready，后端会自动重连 ASR，并推送 `asr_reconnecting` 给前端。

服务端返回识别结果时，Handler 会把增量文字推送给前端：

```java
// WebSocket 推送增量文字
websocket.sendMessage(new WebSocketSubtitleMessage(
    "subtitle",
    transcript,
    isFinal  // true 表示这是最终结果
));
```

### 后端：TTS 服务

```java
// QwenTtsService.java
public byte[] synthesize(String text) {
    CountDownLatch latch = new CountDownLatch(1);
    ByteArrayContainer audioContainer = new ByteArrayContainer();

    QwenTtsRealtime qwenTts = new QwenTtsRealtime(param, callback);
    qwenTts.connect();

    // 配置音色和参数
    QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
        .voice(voice)  // 如 "Cherry"
        .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
        .speechRate(speechRate)
        .build();

    qwenTts.updateSession(config);
    qwenTts.appendText(text);
    qwenTts.commit();

    // 等待音频块接收完成
    latch.await(30, TimeUnit.SECONDS);
    return audioContainer.toByteArray();
}
```

Handler 拿到 PCM 数据后，转成 WAV 推送给前端：

```java
// LLM 每输出一个完整句子，就提交给并发 TTS 队列
OrderedTtsChunkEmitter chunkEmitter = new OrderedTtsChunkEmitter(session, semaphore);
llmService.chatStreamSentences(userText, sentence -> {
    chunkEmitter.submit(sentence);
});

// TTS 分片按句子顺序推送，最后发送 audio_complete 控制消息
chunkEmitter.finish();
chunkEmitter.awaitCompletion();
```

这里的重点不是“把整段回复一次性合成完”，而是**LLM 边生成句子，TTS 边合成，前端边播放**。后端用 `max-concurrent-tts-per-session` 控制单会话并发 TTS 数量，用 `tts-timeout-seconds` 避免某一句卡住整轮播放；如果所有句子级 TTS 都失败，再退回整段文本合成兜底。

## 怎么让语音 Agent 支持打断？

打断是语音 Agent 的高频难点。让我专门讲清楚。

### 打断的三层含义

1. **播放层打断**：用户说话时，停止当前音频播放
2. **生成层打断**：取消服务端正在生成的 LLM 和 TTS
3. **上下文层打断**：正确记录已播放和未播放的内容

interview-guide 的打断逻辑：

```typescript
// 前端：检测到用户说话时停止播放
const handleAudioData = (audioData: string) => {
  // AI 正在说话时，不发音频给后端
  if (isAiSpeakingRef.current) {
    return; // 静默丢弃，不触发打断逻辑
  }
  wsRef.current.sendAudio(audioData);
};

// 音频播放完成时
const finishAiPlayback = () => {
  aiAudioPendingRef.current = false;
  clearAudioPlaybackWatchdog();
  setAiSpeaking(false);
  setIsSubmitting(false);

  // 只有真正播放完的内容才能写入"已说"上下文
  commitAiMessage(aiTextRef.current.trim());
};
```

关键设计是：打断不是“暂停”，而是“取消”。已播放的内容记为“已说”，未播放的内容不记。

### 状态机视角的打断

从状态机角度看，打断是一个几乎可以从任何状态进入的控制事件：

| 当前状态     | 用户打断     | 正确响应                       |
| ------------ | ------------ | ------------------------------ |
| listening    | 用户插话     | 丢弃当前音频，重新开始识别     |
| thinking     | 用户补充     | 取消当前推理，用新输入重新触发 |
| speaking     | 用户插话     | 停止播放，清空队列             |
| tool_calling | 用户说“算了” | 取消工具调用，或停止后续播报   |

如果你的系统没有清晰的取消语义，很快就会出现“AI 一边听新问题，一边还在播旧答案”的混乱体验。

## 浏览器音频捕获与前处理在语音系统中扮演什么角色？

很多文章把 WebRTC 当成“浏览器音视频通话的标准”，讲得很抽象。更准确的说法是：浏览器提供了一套**音频捕获和前处理**能力，语音 Agent 场景主要用的是 `getUserMedia` API。

**重要区分**：

- **Media Capture and Streams API**（`getUserMedia`）：负责从麦克风采集音频，可以配置 AEC/NS/AGC 等前处理。这是 interview-guide 实际使用的。
- **WebRTC 协议**（RTCPeerConnection）：负责端到端的实时传输，包含 ICE、DTLS-SRTP、RTP 等协议。如果你用 OpenAI Realtime API（WebRTC 模式）或 Azure Voice Live，才需要这套东西。

interview-guide 的音频通路是：

```
getUserMedia → AudioWorklet → Base64 编码 → WebSocket 发送
```

这套通路的传输层是 **WebSocket（TCP）**，不是 WebRTC 的 **RTP（UDP）**。WebSocket 保证顺序但可能有 TCP 重传延迟；WebRTC 的 UDP 传输更快但丢包不重传。

### 浏览器音频前处理管线

在语音 Agent 场景下，你主要用到浏览器音频前处理的这些能力：

```
麦克风输入
    │
    ▼
┌─────────────────────────┐
│  AEC (回声消除)          │  消除扬声器播放的声音
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│  NS (噪声抑制)            │  压低背景噪声
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│  AGC (自动增益控制)       │  让音量更稳定
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│  VAD (语音活动检测)       │  判断是否有人声
└─────────────────────────┘
    │
    ▼
编码输出
```

### getUserMedia 的配置选择

interview-guide 用的是最基础的 `getUserMedia` 配置：

```typescript
navigator.mediaDevices.getUserMedia({
  audio: {
    echoCancellation: true,
    noiseSuppression: true,
    autoGainControl: true,
    sampleRate: 16000,
  },
});
```

但这不是唯一选择，不同场景有不同权衡：

| 参数             | true                             | false                          | 建议                                 |
| ---------------- | -------------------------------- | ------------------------------ | ------------------------------------ |
| echoCancellation | 消除扬声器回声，但会损失部分音质 | 保留原始音质，但需要自己做 AEC | 开                                   |
| noiseSuppression | 压低噪声，但可能把用户声音也削掉 | 需要自己做 NS                  | 环境嘈杂时开，安静时关               |
| autoGainControl  | 自动调整音量到合适范围           | 依赖麦克风原始音量             | 开                                   |
| sampleRate       | 越高音质越好，但数据量越大       | 16kHz 对 ASR 够用              | ASR 用 16kHz，TTS 输出可能需要 24kHz |

**一个高频踩坑点**：WebRTC 的 AEC 能力在不同浏览器、不同设备上差异很大。Chrome 桌面版效果不错，但 Safari 和移动端可能大打折扣。如果你做的是生产级应用，建议**在多种设备和浏览器上测试 AEC 效果**。

### WebRTC 的局限性

WebRTC 适合浏览器场景，但如果你做的是 App 或硬件方案，它就不一定适用了。

移动端 native 开发可以用：

- **iOS**：AVAudioEngine + 系统内置的音频处理
- **Android**：AudioRecord + Oboe/AAudio，或者用 Google 的 WebRTC 库

硬件场景（智能音箱、车载）通常需要专门的 DSP 芯片做前端处理，WebRTC 的软件方案满足不了延迟和功耗要求。

## 级联链路和原生实时模型各有什么优劣？

这是选型时的核心问题。

### 方案一：级联式 ASR + LLM + TTS

```
音频 -> VAD -> 流式 ASR -> LLM -> 流式 TTS -> 音频
```

优点：

- ASR 文本可以落库、审计、纠错
- LLM 输入输出都是文本，方便复用现有 Agent 框架
- TTS 可以独立替换音色和供应商
- 每个组件都能单独压测和优化

缺点：

- 每层都有延迟
- ASR 错误会传导到 LLM
- 文本中间层会丢失语气、停顿、情绪
- 打断要跨 ASR、LLM、TTS、播放器统一取消

interview-guide 就是这套方案。它适合的场景：企业知识问答、客服工单、需要合规审计的业务系统。

### 方案二：原生 Realtime Speech-to-Speech

```
音频 -> 原生多模态模型 -> 音频
```

代表方案：OpenAI Realtime API、Gemini Live API、阿里通义 Qwen-Omni。

优点：

- 更低的端到端延迟
- 语气、停顿、情绪等副语言信息保留更多
- 可以统一处理音频输入、文本事件、工具调用

缺点：

- 中间过程更黑盒，问题定位更依赖供应商日志
- 文本审计和话术控制需要额外设计
- 成本模型可能按音频 token 或时长计费
- 如果业务强依赖私有化部署，供应商 API 未必满足要求

**连接方式选择**：

OpenAI Realtime API 支持三种连接方式：

| 连接方式  | 适用场景                                          |
| --------- | ------------------------------------------------- |
| WebRTC    | 浏览器和移动端应用，有更好的 NAT 穿透和抗抖动能力 |
| WebSocket | 服务端到服务端的中间件场景，低延迟且可控          |
| SIP       | VoIP 电话系统集成，适合呼叫中心、电话客服场景     |

### 我的建议

高频、强实时、强自然感的语音产品，优先评估原生 Realtime API。强合规、强审计、强可控的业务场景，级联链路更稳。

**不要第一天就做端云混合**。先把一条链路跑通，再逐步替换。

## 怎么在生产环境中优化语音系统？

讲几个实战抓手。

### 1. 缩短音频帧和提交粒度

实时音频通常按 10ms、20ms、30ms 分帧。帧太大延迟高，帧太小网络开销大。

interview-guide 的选择是 **200ms 分块**：

```typescript
// pcm-processor.js
this.targetSampleRate = 16000;
this.samplesPerChunk = 3200; // 200ms at 16kHz
```

这意味着用户说完一句话，最快 400-600ms 后服务端才能开始识别。这个延迟能接受，但如果要做得更好，可以：

- 减小分块到 100ms
- 前端先发一小段让 ASR“热启动”
- 用服务端 VAD 的增量结果做流式 LLM 输入

### 2. 让 LLM 先说短句

语音回复不是写文章。用户不需要一上来听 500 字完整答案。

更好的策略：

- 先输出确认语：“我看一下”
- 工具调用期间播过渡语：“正在查最近一次订单”
- 查到结果后再给结论
- 长解释拆成多句，每句都能独立合成

### 3. TTS 按语义边界切分

TTS 切分太碎听起来断断续续；切分太长首包延迟高。

建议按优先级切：

1. 句号、问号、感叹号
2. 分号、冒号
3. 较长逗号短语
4. 超长句强制切分

同时要避免把数字、英文缩写、代码名切坏。比如"GPT-4o-mini-tts"不能被随便拆成几段读。

interview-guide 当前采用的就是这个思路：LLM 流式输出过程中，只要检测到一个完整句子，就立刻提交给 `OrderedTtsChunkEmitter` 做句子级 TTS。前端收到 `audio_chunk` 后立即入队播放，收到 `audio_complete` 后再等待播放队列自然清空。这样首段语音不需要等整段回答生成和合成结束。

### 4. 控制上下文长度

语音 Agent 很容易把所有转写、工具结果、播放状态都塞进上下文。短期看没事，长会话里会导致延迟和成本一起上涨。

建议把上下文分成三层：

- **短期原文**：最近几轮完整转写和回答
- **会话摘要**：用户目标、已确认事实、未完成事项
- **事件状态**：当前播放进度、是否被打断、工具调用结果

LLM 不需要知道每个音频帧发生了什么，它需要知道和当前决策相关的高信噪比状态。

### 5. 全链路可观测

interview-guide 用 Redis 做会话状态缓存：

```java
// VoiceInterviewService.java
private static final String SESSION_CACHE_KEY_PREFIX = "voice:interview:session:";

private void cacheSession(VoiceInterviewSessionEntity session) {
    String cacheKey = getSessionCacheKey(session.getId());
    RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
    bucket.set(session, Duration.ofHours(CACHE_TTL_HOURS));
}
```

生产环境还要记录：

- 上行音频时长
- 有效人声时长
- ASR token 或分钟数
- LLM 输入输出 token
- TTS 字符数、音频秒数、被打断秒数
- 每轮端到端延迟和取消次数

没有这些指标，语音 Agent 的成本会很难收敛。

## 语音 Agent 还能怎么演进？

interview-guide 是最基础版本，还有很多可以优化的地方。

### 端云混合

目前 interview-guide 基本是“云端为主”的设计。进阶方向是把更多能力下沉到端侧：

| 环节 | 当前                  | 演进方向                         |
| ---- | --------------------- | -------------------------------- |
| VAD  | 端侧 VAD + 服务端 VAD | 纯端侧 VAD，减少服务端压力       |
| ASR  | 纯云端                | 简单命令放端侧，复杂识别放云端   |
| LLM  | 纯云端                | 小模型端侧兜底，断网可用         |
| TTS  | 纯云端                | 固定提示音放端侧，自然对话放云端 |

端云混合的核心是**把实时性强、隐私敏感、断网要兜底的能力尽量放端侧**。

### 本地模型部署

如果你对数据合规有要求，可以考虑本地部署 ASR 和 TTS：

- **ASR**：faster-whisper、FunASR、SenseVoice
- **TTS**：piper1-gpl（原 Piper 已归档）、Fish Speech、CosyVoice

**注意**：原 Piper 仓库（rhasspy/piper）已于 2025 年 10 月归档，开发已迁移到 [OHF-Voice/piper1-gpl](https://github.com/OHF-Voice/piper1-gpl)。但需注意两点：（1）piper1-gpl 采用 GPL-3.0 许可证，商业项目使用时需评估开源合规要求；（2）该项目目前正在招募新的维护者，长期支持存在不确定性。如果许可证不兼容，可考虑 Fish Speech（Apache 2.0）或 CosyVoice 等替代方案。

本地部署的优势是可控、可离线。劣势是**工程成本高**：GPU/内存/并发容量要自己压测，流式推理、模型热加载、显存回收都要自己做。

### 原生 Realtime API

如果你觉得级联链路的延迟和体验不够好，可以评估原生 Realtime API：

- OpenAI **gpt-realtime**（2025年8月GA，支持MCP/图像/SIP）
- Gemini Live API
- 阿里通义 Qwen-Omni

这些 API 把 ASR、LLM、TTS 融合成一个统一的多模态模型，理论上延迟更低、体验更自然。但代价是**更黑盒、更贵、更难调试**。

OpenAI Realtime API 已正式GA，推出了专用模型 **gpt-realtime**，在复杂指令遵循、工具调用、自然表达语音方面有显著提升。同时新增三大能力：

1. **远程 MCP 服务器支持**，可像级联方案一样调用外部工具；
2. **图像输入支持**，模型可结合用户看到的屏幕内容进行对话；
3. **SIP 电话集成**，支持与传统电话网络连接。

定价方面，gpt-realtime 比 preview 版本降价 20%（输入 $32/1M token，输出 $64/1M token）。

### 打断体验优化

目前 interview-guide 的打断是“静默丢弃”：AI 说话时用户的声音直接不发。这种方式简单，但体验不够自然。

更好的做法：

- AI 说话时继续接收音频，但不发到 ASR
- 检测到用户声音后，先降低 AI 播放音量（渐变而不是突然停止）
- 打断后保留已播放内容的上下文

### 多模态扩展

interview-guide 目前只有语音。可以扩展成：

- **语音 + 屏幕共享**：面试官可以看到候选人的 IDE
- **语音 + 摄像头**：看候选人的表情和肢体语言
- **语音 + 白板**：一起画架构图

这些多模态能力需要更复杂的流管理和状态同步。

## 面试里怎么回答 AI 语音系统问题？

如果面试官问：“你怎么设计一个实时语音 Agent？”

可以按这个思路回答：

1. **先拆链路**：客户端采集音频，VAD 判断说话边界，ASR 流式转写，LLM 做意图理解和工具调用，TTS 流式合成，客户端边收边播。
2. **再讲难点**：实时语音核心难点是端到端延迟、用户打断、噪声环境、上下文状态和端云协同。
3. **再讲状态机**：需要管理 listening、thinking、speaking、interrupted 等状态，打断时要取消播放、取消生成，并处理已播放和未播放上下文。
4. **最后讲选型**：云端 API 上线快，本地模型可控但工程成本高，端云混合适合生产，实时体验强的场景可以评估 Speech-to-Speech API。

一句话总结：

**AI 语音 Agent 的核心不是“语音识别 + 大模型 + 语音合成”，而是围绕实时音频流构建一套可取消、可观测、可降级的对话系统。**

## 总结

AI 语音技术看起来是 ASR、TTS、VAD 几个模块的拼接，真正落地时考验的是系统工程能力。

核心要点回顾：

1. **底层链路**：实时语音 Agent 至少包含采集、前处理、VAD、ASR、LLM、工具调用、TTS、流式播放和状态回写。
2. **实时难点**：延迟、打断、噪声、上下文和端侧能力是最容易把 Demo 打回原形的五个因素。
3. **架构选择**：级联式 ASR + LLM + TTS 可控、易审计；原生 Speech-to-Speech 延迟低、体验自然；端云混合是生产里常见折中。
4. **工程重点**：一定要设计状态机、取消语义、播放确认、全链路 trace 和成本指标。
5. **选型原则**：先用云端能力跑通闭环，再基于成本、合规、延迟和私有化需求逐步替换本地模型或端侧能力。

总结一下：**语音 Agent 的用户体验不是模型一个人决定的，而是整条实时链路共同决定的**。模型负责聪明，工程负责不掉链子。两者缺一不可。
