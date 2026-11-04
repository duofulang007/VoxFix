#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <sched.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "VoxFix"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> cancel{false};
    std::atomic<bool> busy{false};
    std::mutex mutex;
    bool pin_cores = true;
};

std::once_flag g_backend;
void ensure_backend() {
    std::call_once(g_backend, [] { llama_backend_init(); });
}

std::string to_utf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throw_illegal(JNIEnv *env, const char *code) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, code);
}

bool abort_callback(void *data) {
    return static_cast<Session *>(data)->cancel.load(std::memory_order_relaxed);
}

/** Worker threads inherit the caller's affinity mask, so pinning here keeps ggml's pool on the
 *  four highest-numbered cores (big cluster on the K60) instead of spilling onto the A510s. */
void pin_to_big_cores() {
    const long cores = sysconf(_SC_NPROCESSORS_ONLN);
    if (cores <= 0) return;
    cpu_set_t set;
    CPU_ZERO(&set);
    const int first = cores > 4 ? static_cast<int>(cores) - 4 : 0;
    for (int cpu = first; cpu < static_cast<int>(cores); ++cpu) CPU_SET(cpu, &set);
    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        LOGI("TRANSLATE_AFFINITY_FAILED cores=%ld", cores);
    }
}

int32_t append_piece(const llama_vocab *vocab, llama_token token, std::string &output) {
    char buffer[256];
    const int32_t length = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, true);
    if (length > 0) {
        output.append(buffer, static_cast<size_t>(length));
        return length;
    }
    if (length < 0) {
        std::vector<char> piece(static_cast<size_t>(-length));
        const int32_t written = llama_token_to_piece(vocab, token, piece.data(),
                                                     static_cast<int32_t>(piece.size()), 0, true);
        if (written > 0) output.append(piece.data(), static_cast<size_t>(written));
        return written;
    }
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_local_voxfix_debug_LlamaBridge_load(JNIEnv *env, jobject, jstring path, jint threads, jint context,
                                       jboolean pin_cores, jboolean quantized_kv, jint batch,
                                       jint flash_attn) {
    ensure_backend();
    if (pin_cores) pin_to_big_cores();
    const std::string file = to_utf8(env, path);
    if (file.empty()) return 0;
    auto *session = new Session();
    session->pin_cores = pin_cores;
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    session->model = llama_model_load_from_file(file.c_str(), model_params);
    if (session->model == nullptr) {
        LOGE("TRANSLATE_MODEL_LOAD_FAILED %s", file.c_str());
        delete session;
        return 0;
    }
    session->vocab = llama_model_get_vocab(session->model);
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = context > 0 ? context : 2048;
    const int32_t effective_batch = batch > 0 ? std::min<int32_t>(batch, 2048) : 256;
    context_params.n_batch = effective_batch;
    context_params.n_ubatch = effective_batch;
    context_params.n_threads = threads > 0 ? threads : 4;
    context_params.n_threads_batch = context_params.n_threads;
    context_params.type_k = quantized_kv ? GGML_TYPE_Q8_0 : GGML_TYPE_F16;
    context_params.type_v = quantized_kv ? GGML_TYPE_Q8_0 : GGML_TYPE_F16;
    // -1 keeps llama.cpp's own heuristic; 0/1 force it off/on for the A/B measurement.
    context_params.flash_attn_type = flash_attn < 0 ? LLAMA_FLASH_ATTN_TYPE_AUTO
        : (flash_attn > 0 ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED);
    context_params.abort_callback = abort_callback;
    context_params.abort_callback_data = session;
    session->ctx = llama_init_from_model(session->model, context_params);
    if (session->ctx == nullptr) {
        LOGE("TRANSLATE_CONTEXT_FAILED");
        llama_model_free(session->model);
        delete session;
        return 0;
    }
    LOGI("TRANSLATE_NATIVE_LOADED ctx=%d threads=%d batch=%d kv=%s fa=%d", context_params.n_ctx,
         context_params.n_threads, effective_batch, quantized_kv ? "q8_0" : "f16",
         static_cast<int>(context_params.flash_attn_type));
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_local_voxfix_debug_LlamaBridge_translate(JNIEnv *env, jobject, jlong handle, jstring prompt,
                                              jint max_tokens, jlong timeout_ms, jobject on_update) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) {
        throw_illegal(env, "SESSION");
        return nullptr;
    }
    bool expected = false;
    if (!session->busy.compare_exchange_strong(expected, true)) {
        throw_illegal(env, "BUSY");
        return nullptr;
    }
    session->cancel.store(false, std::memory_order_relaxed);
    if (session->pin_cores) pin_to_big_cores();
    const std::string text = to_utf8(env, prompt);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    auto elapsed = [](const std::chrono::steady_clock::time_point &from,
                      const std::chrono::steady_clock::time_point &to) {
        return static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(to - from).count());
    };
    const auto tokenize_started = std::chrono::steady_clock::now();

    std::lock_guard<std::mutex> guard(session->mutex);
    const int32_t needed = -llama_tokenize(session->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                           nullptr, 0, false, true);
    if (needed <= 0) {
        session->busy.store(false);
        throw_illegal(env, "PROMPT_TOO_LONG");
        return nullptr;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    if (llama_tokenize(session->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                       tokens.data(), needed, false, true) < 0) {
        session->busy.store(false);
        throw_illegal(env, "PROMPT_TOO_LONG");
        return nullptr;
    }
    const auto tokenize_done = std::chrono::steady_clock::now();
    const int32_t context_tokens = llama_n_ctx(session->ctx);
    if (static_cast<int32_t>(tokens.size()) + 16 >= context_tokens) {
        session->busy.store(false);
        throw_illegal(env, "PROMPT_TOO_LONG");
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(session->ctx), true);
    // llama_decode asserts if a prompt exceeds n_batch; feed long OCR input in batches.
    const int32_t batch_size = llama_n_batch(session->ctx);
    for (int32_t offset = 0; offset < needed; offset += batch_size) {
        if (session->cancel.load() || std::chrono::steady_clock::now() > deadline) {
            session->busy.store(false);
            throw_illegal(env, session->cancel.load() ? "CANCELLED" : "TIMEOUT");
            return nullptr;
        }
        const int32_t count = std::min(batch_size, needed - offset);
        if (llama_decode(session->ctx, llama_batch_get_one(tokens.data() + offset, count)) != 0) {
            session->busy.store(false);
            throw_illegal(env, session->cancel.load() ? "CANCELLED" : "DECODE");
            return nullptr;
        }
    }
    const auto prefill_done = std::chrono::steady_clock::now();
    // Pure prefill cost has to be visible even when generation later times out: the deadline
    // covers tokenize + prefill + decode, so a slow prompt silently eats the output budget.
    LOGI("TRANSLATE_NATIVE_PREFILL prefill_ms=%lld prompt_tokens=%d batch=%d",
         elapsed(tokenize_done, prefill_done), needed, batch_size);

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    jmethodID update_method = nullptr;
    if (on_update != nullptr) {
        jclass callback_class = env->GetObjectClass(on_update);
        update_method = env->GetMethodID(callback_class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
        env->DeleteLocalRef(callback_class);
        if (update_method == nullptr) {
            llama_sampler_free(sampler);
            session->busy.store(false);
            return nullptr;
        }
    }

    std::string output;
    int32_t produced = 0;
    const int32_t limit = std::min(max_tokens > 0 ? max_tokens : 1024, context_tokens - needed - 1);
    while (produced < limit) {
        if (session->cancel.load(std::memory_order_relaxed)) {
            llama_sampler_free(sampler);
            session->busy.store(false);
            throw_illegal(env, "CANCELLED");
            return nullptr;
        }
        if (std::chrono::steady_clock::now() > deadline) {
            llama_sampler_free(sampler);
            session->busy.store(false);
            throw_illegal(env, "TIMEOUT");
            return nullptr;
        }
        const llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, token)) break;
        llama_sampler_accept(sampler, token);
        append_piece(session->vocab, token, output);
        ++produced;
        if (produced == 1) {
            LOGI("TRANSLATE_NATIVE_FIRST_TOKEN first_token_ms=%lld",
                 elapsed(prefill_done, std::chrono::steady_clock::now()));
        }
        // Decode throughput per 32 tokens, so a timeout can be attributed to slow decoding
        // instead of being guessed from the wall clock alone.
        if (produced % 32 == 0) {
            const long long decode_ms = elapsed(prefill_done, std::chrono::steady_clock::now());
            LOGI("TRANSLATE_NATIVE_PROGRESS produced=%d decode_ms=%lld tps=%.2f", produced, decode_ms,
                 decode_ms > 0 ? produced * 1000.0 / static_cast<double>(decode_ms) : 0.0);
        }
        if (on_update != nullptr) {
            // Pass bytes: a token can contain only part of a UTF-8 character.
            jbyteArray bytes = env->NewByteArray(static_cast<jsize>(output.size()));
            if (bytes != nullptr) {
                env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(output.size()),
                                       reinterpret_cast<const jbyte *>(output.data()));
                jobject returned = env->CallObjectMethod(on_update, update_method, bytes);
                if (returned != nullptr) env->DeleteLocalRef(returned);
                env->DeleteLocalRef(bytes);
            }
            if (env->ExceptionCheck()) {
                llama_sampler_free(sampler);
                session->busy.store(false);
                return nullptr;
            }
        }
        if (llama_decode(session->ctx, llama_batch_get_one(const_cast<llama_token *>(&token), 1)) != 0) {
            llama_sampler_free(sampler);
            session->busy.store(false);
            throw_illegal(env, "DECODE");
            return nullptr;
        }
    }
    llama_sampler_free(sampler);
    session->busy.store(false);
    if (produced == limit) {
        throw_illegal(env, "OUTPUT_LIMIT");
        return nullptr;
    }
    const auto decode_done = std::chrono::steady_clock::now();
    LOGI("TRANSLATE_NATIVE_TIMING tokenize_ms=%lld prefill_ms=%lld decode_ms=%lld prompt_tokens=%d output_tokens=%d",
         elapsed(tokenize_started, tokenize_done), elapsed(tokenize_done, prefill_done),
         elapsed(prefill_done, decode_done), needed, produced);
    LOGI("TRANSLATE_NATIVE_DONE prompt_tokens=%d output_tokens=%d bytes=%zu", needed, produced, output.size());
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_local_voxfix_debug_LlamaBridge_cancel(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session != nullptr) session->cancel.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_local_voxfix_debug_LlamaBridge_free(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    session->cancel.store(true, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> guard(session->mutex);
        if (session->ctx != nullptr) llama_free(session->ctx);
        if (session->model != nullptr) llama_model_free(session->model);
    }
    delete session;
    LOGI("TRANSLATE_NATIVE_FREED");
}
