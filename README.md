# AI答题

安卓无障碍读屏 → 给出选项答案（可选自动点击）。**两种解题方式**：

- **在线**（推荐）：调智谱 / DeepSeek 等云端模型，准确率最高，题目会发给所选服务商
- **本地**：llama.cpp + Qwen2.5 小模型，全离线、不联网，准确率略低

默认「在线优先，失败自动回退本地」。只开在线可以完全不下载模型。

## 两种模式怎么选

| | 在线（智谱 GLM-4.7-Flash） | 在线（DeepSeek V4.1-Flash） | 本地 1.5B |
|---|---|---|---|
| 准确率 | 高 | 高 | 90%（难题） |
| 单题耗时 | 0.5~1.5 s | 0.5~1.5 s | 0.3~0.8 s |
| 成本 | 免费 | ≈ 0.0005 元/题 | 0 |
| 额外下载 | 无 | 无 | 1.07 GB |
| 断网可用 | 否 | 否 | 是 |
| 题目外发 | 是 | 是 | 否 |

云端失败（Key 失效、限流 429、断网）会自动回退本地模型，不会卡住不答。

## 结构

```
app/src/main/java/com/dsh/aianswer/
  CloudSolver.java   云端调用（OpenAI 兼容 /chat/completions，支持自定义 Base/模型）
  Llama.java         JNI 封装（load / score / gen / free）
  AnswerEngine.java  单线程推理队列 + 云端/本地分流与回退
  PromptBuilder.java Qwen2.5 ChatML 模板，结尾停在「答案：」
  ModelManager.java  GGUF 下载（Range 续传）+ 本地校验
  ScreenScan.java    无障碍节点抓取（移植自刷题助手）
  Layout.java        题干/选项版面解析（移植自刷题助手，纯 Java 可单测）
  QService.java      无障碍服务：事件防抖 → 扫描 → 推理 → 点击
  Overlay.java       答案悬浮窗
  MainActivity.java  配置界面
app/src/main/jniLibs/arm64-v8a/
  libllama.so libggml*.so   由 llama.cpp 交叉编译（NDK 28.2）
  libaiq.so                 自写 JNI，源码在 tools/llama_jni.cpp
```

## 关键设计

**单选不生成，只比 logits（本地模式）。** 正常做法是让模型把答案"说"出来，要逐 token 解码。这里改成：
prompt 以 `答案：` 结尾 → 一次 prefill → 直接读 A/B/C/D 四个 token 在末位的 logits → softmax → argmax。
一次前向出结果，比生成快 3~5 倍，也不会出现"答案是A，因为……"这种需要二次解析的输出。

**云端失败静默回退。** `AnswerEngine` 里云端返回空字母时立刻转本地模型，用户只会看到答案来源变了。

**低置信度不点。** 自动点击前比对阈值（默认 55%），低于阈值只显示不点。

**Key 只存本机。** `SharedPreferences`，不上传、不经中转。

## 构建

前置：JDK 17 + Android SDK（Platform 34、Build-Tools 34.0.0）。

```bash
# 1. 指定 SDK 位置（Android Studio 用户通常已有此文件）
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. 构建（bash；Windows 用 Git Bash）
bash build.sh            # 默认 1 / 1.0.0
bash build.sh 3 1.2.0    # versionCode 3, versionName 1.2.0
```

- 签名密钥 `keystore/aianswer.jks` 不入库。没有它也能构建，产出**未签名 APK**；
  想出签名包，把自建的 jks 放到 `keystore/aianswer.jks`（PKCS12，alias `qh`，密码可用环境变量 `KS_PASS`）
- JDK / build-tools 路径可用环境变量 `JAVA_HOME`、`ANDROID_BT` 覆盖

产物：`构建/AI答题-v<版本>.apk`（约 6.5 MB，不含模型）

## 使用

**只开在线（最省事）：**
1. 装 APK → 打开
2. 打开「① 使用在线模型」，选智谱（免费）
3. 去 `open.bigmodel.cn` 注册 → 控制台 → API Keys → 创建 → 复制
4. 粘贴进 App → 点「测试连接」，显示「✓ 连接正常」就好了
5. 授予无障碍 + 悬浮窗权限 → 打开「读屏自动解题」

**再加本地兜底：**
到「② 本地模型」的列表里点 1.5B 那行的「下载」（约 1.06 GB），下完自动加载。

列表按磁盘真实情况生成：
- 未下载 → 只有「下载」
- 已下载 → 「使用」+「删除」，正在用的那个显示为绿色「使用中」
- 删除会弹确认框，删掉后空间立即释放，下次用要重新下
- 两个都下载了可以随时切换，切换即重新加载

## 实测准确率

本地模型（PC 基准 `tools/bench/`，与 App 同一 prompt）：

| 模型 | 基础题 15 道 | 难题 10 道 | 稳态耗时/题 | 体积 |
|---|---|---|---|---|
| Qwen2.5-0.5B Q4_K_M | 80% | 70% | 30 ms | 469 MB |
| Qwen2.5-1.5B Q4_K_M | 100% | 90% | 50 ms | 1.07 GB |

耗时是 x86 八线程数字，手机约 5~10 倍。第一题会明显慢（权重首次读入）。

云端模型用 `tools/bench/cloud.mjs` 跑同一套题，拿到 Key 后可自行对比：

```bash
cd tools/bench && npm i node-llama-cpp   # 只跑云端不需要，纯 Node 即可
node cloud.mjs zhipu <你的Key>
node cloud.mjs deepseek <你的Key> ./cases_hard.mjs
```

## 已知限制

- **图片题抓不到**：无障碍树里图片没有文字，纯文本方案会返回空。要支持得改成截图 + 多模态模型。
- **多选不如单选准**：本地多选走短生成，准确率明显低于单选；在线多选还算稳。
- **只支持 arm64**：x86 设备装不上。
- **智谱免费档有并发限制**（约 1 次/秒），切题过快会 429 —— 此时会自动回退本地模型。
