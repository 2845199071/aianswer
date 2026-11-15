// AI答题 · 端侧推理 JNI 层
// 设计要点：不逐 token 生成，只 prefill 一次取 logits，直接比 A/B/C/D 的概率。
// 单题耗时 ≈ prompt prefill，比自由生成快 3~5 倍，且答案不会"跑偏成一段解释"。
#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>
#include "llama.h"

#define TAG "AIQ"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct Handle {
    llama_model        * model = nullptr;
    llama_context      * ctx   = nullptr;
    const llama_vocab  * vocab = nullptr;
    int                  n_ctx = 1024;
};

static std::string toStr(JNIEnv * env, jstring s) {
    if (!s) return "";
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

// 把文本 tokenize 成 vector；缓冲区不够时按返回值扩容重试
static std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    std::vector<llama_token> buf(text.size() + 64);
    int n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                           buf.data(), (int32_t) buf.size(), add_special, true);
    if (n < 0) {
        buf.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                           buf.data(), (int32_t) buf.size(), add_special, true);
    }
    if (n < 0) return {};
    buf.resize(n);
    return buf;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_dsh_aianswer_Llama_nativeLoad(JNIEnv * env, jclass, jstring jpath, jint nCtx, jint nThreads) {
    std::string path = toStr(env, jpath);
    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                          // 纯 CPU，手机上 GPU 后端不稳
    mp.load_mode    = LLAMA_LOAD_MODE_MMAP;       // 关键：mmap 加载，RSS 远低于整块读进堆

    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("模型加载失败: %s", path.c_str()); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) nCtx;
    cp.n_batch         = (uint32_t) std::min(nCtx, 1024);
    cp.n_ubatch        = (uint32_t) std::min(nCtx, 512);
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;
    cp.no_perf         = true;

    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("context 创建失败"); llama_model_free(model); return 0; }

    auto * h = new Handle();
    h->model = model;
    h->ctx   = ctx;
    h->vocab = llama_model_get_vocab(model);
    h->n_ctx = nCtx;
    LOGI("模型就绪 n_ctx=%d threads=%d", nCtx, nThreads);
    return (jlong) h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dsh_aianswer_Llama_nativeFree(JNIEnv *, jclass, jlong ptr) {
    auto * h = (Handle *) ptr;
    if (!h) return;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

// 返回每个候选字母的归一化概率（与 letters 同序）
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_dsh_aianswer_Llama_nativeScore(JNIEnv * env, jclass, jlong ptr,
                                        jstring jprompt, jobjectArray jletters) {
    auto * h = (Handle *) ptr;
    jsize nOpt = jletters ? env->GetArrayLength(jletters) : 0;
    jfloatArray out = env->NewFloatArray(nOpt);
    if (!h || h->n_ctx <= 0 || nOpt <= 0) return out;

    std::string prompt = toStr(env, jprompt);
    std::vector<llama_token> toks = tokenize(h->vocab, prompt, false);
    if (toks.empty()) { LOGE("prompt tokenize 为空"); return out; }
    if ((int) toks.size() + 8 >= h->n_ctx) {
        LOGE("prompt 超长 %zu / %d", toks.size(), h->n_ctx);
        return out;
    }

    // 每道题都从干净 KV 开始，避免上一题的上下文串味
    llama_memory_clear(llama_get_memory(h->ctx), true);

    llama_batch batch = llama_batch_init((int32_t) toks.size(), 0, 1);
    for (size_t i = 0; i < toks.size(); i++) {
        batch.token[i]      = toks[i];
        batch.pos[i]        = (llama_pos) i;
        batch.n_seq_id[i]   = 1;
        batch.seq_id[i][0]  = 0;
        batch.logits[i]     = 0;
    }
    batch.n_tokens = (int32_t) toks.size();
    batch.logits[toks.size() - 1] = 1;   // 只取最后一个位置的分布

    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("decode 失败");
        llama_batch_free(batch);
        return out;
    }
    llama_batch_free(batch);

    const float * logits = llama_get_logits_ith(h->ctx, -1);
    if (!logits) { LOGE("logits 为空"); return out; }

    int nVocab = llama_vocab_n_tokens(h->vocab);
    std::vector<float> score(nOpt, -1e30f);

    for (jsize i = 0; i < nOpt; i++) {
        jstring jl = (jstring) env->GetObjectArrayElement(jletters, i);
        std::string letter = toStr(env, jl);
        env->DeleteLocalRef(jl);
        if (letter.empty()) continue;

        // 同一字母可能有多种 token 形态（"A" / " A"），取最大者作为该选项得分
        const char * variants[4] = {letter.c_str(), nullptr, nullptr, nullptr};
        std::string withSpace = " " + letter;
        std::string withDot   = letter + ".";
        variants[1] = withSpace.c_str();
        variants[2] = withDot.c_str();

        for (int v = 0; v < 3; v++) {
            if (!variants[v] || !*variants[v]) continue;
            std::vector<llama_token> lt = tokenize(h->vocab, variants[v], false);
            if (lt.empty()) continue;
            llama_token t = lt[0];
            if (t < 0 || t >= nVocab) continue;
            score[i] = std::max(score[i], logits[t]);
        }
    }

    // softmax（数值稳定版）
    float mx = -1e30f;
    for (float s : score) mx = std::max(mx, s);
    float sum = 0.f;
    for (float & s : score) { s = std::exp(s - mx); sum += s; }
    if (sum <= 0.f) sum = 1.f;

    std::vector<float> prob(nOpt);
    for (jsize i = 0; i < nOpt; i++) prob[i] = score[i] / sum;
    env->SetFloatArrayRegion(out, 0, nOpt, prob.data());
    return out;
}

// 贪心生成（多选题、需要解释时用）
extern "C" JNIEXPORT jstring JNICALL
Java_com_dsh_aianswer_Llama_nativeGen(JNIEnv * env, jclass, jlong ptr,
                                      jstring jprompt, jint maxTok) {
    auto * h = (Handle *) ptr;
    if (!h) return env->NewStringUTF("");

    std::string prompt = toStr(env, jprompt);
    std::vector<llama_token> toks = tokenize(h->vocab, prompt, false);
    if (toks.empty()) return env->NewStringUTF("");

    llama_memory_clear(llama_get_memory(h->ctx), true);

    llama_batch batch = llama_batch_init((int32_t) toks.size(), 0, 1);
    for (size_t i = 0; i < toks.size(); i++) {
        batch.token[i]     = toks[i];
        batch.pos[i]       = (llama_pos) i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = (i == toks.size() - 1) ? 1 : 0;
    }
    batch.n_tokens = (int32_t) toks.size();
    if (llama_decode(h->ctx, batch) != 0) { llama_batch_free(batch); return env->NewStringUTF(""); }

    llama_sampler * smpl = llama_sampler_init_greedy();
    std::string result;
    int nPast = (int) toks.size();

    for (int i = 0; i < maxTok && nPast < h->n_ctx - 4; i++) {
        llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, id)) break;

        char buf[64];
        int n = llama_token_to_piece(h->vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) result.append(buf, n);

        batch.n_tokens = 0;
        llama_batch_free(batch);
        batch = llama_batch_init(1, 0, 1);
        batch.token[0]     = id;
        batch.pos[0]       = (llama_pos) nPast;
        batch.n_seq_id[0]  = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]    = 1;
        batch.n_tokens     = 1;
        if (llama_decode(h->ctx, batch) != 0) break;
        nPast++;
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);
    return env->NewStringUTF(result.c_str());
}
