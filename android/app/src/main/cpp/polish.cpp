#include <jni.h>
#include "llama.h"
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "processor/wetext_processor.h"

static std::atomic<bool> cancelled{false};
static std::once_flag initialized;
using Clock = std::chrono::steady_clock;
static bool aborted(void * data) {
    return cancelled.load() || Clock::now() > *static_cast<Clock::time_point *>(data);
}
static std::string bytes(JNIEnv * env, jbyteArray array) {
    std::string value(env->GetArrayLength(array), '\0');
    env->GetByteArrayRegion(array, 0, value.size(), reinterpret_cast<jbyte *>(value.data()));
    return value;
}
struct TextGraphs {
    wetext::Processor natural;
    wetext::Processor canonical;
    std::unique_ptr<fst::StdVectorFst> interjections;
    TextGraphs(const std::string & dir) : natural(dir + "/zh_itn_tagger.fst", dir + "/zh_itn_verbalizer.fst"),
        canonical(dir + "/zh_itn_tagger_small.fst", dir + "/zh_itn_verbalizer.fst"),
        interjections(fst::StdVectorFst::Read(dir + "/remove_interjections.fst")) {
        if (!interjections) throw std::runtime_error("WeText interjection graph could not be loaded");
    }
};
extern "C" JNIEXPORT jlong JNICALL Java_local_voxfix_debug_TextNormalizer_open(JNIEnv * env, jobject, jbyteArray path) {
    try { return reinterpret_cast<jlong>(new TextGraphs(bytes(env, path))); }
    catch (const std::exception & error) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_local_voxfix_debug_TextNormalizer_close(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<TextGraphs *>(handle);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_local_voxfix_debug_TextNormalizer_apply(JNIEnv * env, jobject, jlong handle, jbyteArray input, jint operation) {
    try {
        if (!handle) throw std::runtime_error("WeText is closed");
        auto & graphs = *reinterpret_cast<TextGraphs *>(handle);
        const auto text = bytes(env, input);
        std::string output;
        if (operation == 2) {
            fst::StdVectorFst source, lattice, best;
            fst::StringCompiler<fst::StdArc> compiler(fst::StringTokenType::BYTE);
            fst::StringPrinter<fst::StdArc> printer(fst::StringTokenType::BYTE);
            compiler(text, &source); fst::Compose(source, *graphs.interjections, &lattice);
            fst::ShortestPath(lattice, &best, 1, true); printer(best, &output);
        } else output = operation == 1 ? graphs.canonical.Normalize(text) : graphs.natural.Normalize(text);
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception & error) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_local_voxfix_debug_NativePolisher_cancel(JNIEnv *, jobject) { cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL
Java_local_voxfix_debug_NativePolisher_prepare(JNIEnv *, jobject) { cancelled.store(false); }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_local_voxfix_debug_NativePolisher_generate(JNIEnv * env, jobject, jbyteArray pathBytes, jbyteArray promptBytes, jobject stream) {
    try {
        auto deadline = Clock::now() + std::chrono::seconds(60);
        std::call_once(initialized, [] {
            llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
            llama_backend_init();
        });
        auto path = bytes(env, pathBytes);
        auto prompt = bytes(env, promptBytes);
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.progress_callback = [](float, void * data) { return !aborted(data); };
        mp.progress_callback_user_data = &deadline;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(path.c_str(), mp), llama_model_free);
        if (!model) throw std::runtime_error("Unable to load local model");
        auto vocab = llama_model_get_vocab(model.get());
        int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (count <= 0 || count > 1536) throw std::runtime_error("Text exceeds local model context");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) != count)
            throw std::runtime_error("Unable to tokenize input");
        auto cp = llama_context_default_params();
        cp.n_ctx = 2048; cp.n_batch = 256; cp.n_ubatch = 256;
        cp.n_threads = 4; cp.n_threads_batch = 4; cp.no_perf = true;
        cp.abort_callback = aborted; cp.abort_callback_data = &deadline;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), cp), llama_free);
        if (!context) throw std::runtime_error("Unable to initialize local model");
        for (int offset = 0; offset < count; offset += 256) {
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(256, count - offset));
            if (aborted(&deadline) || llama_decode(context.get(), batch)) throw std::runtime_error("Local processing cancelled or failed");
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        if (!sampler) throw std::runtime_error("Unable to create sampler");
        std::string output;
        jmethodID onText = nullptr;
        if (stream) {
            auto type = env->GetObjectClass(stream);
            onText = env->GetMethodID(type, "onText", "([B)V");
            env->DeleteLocalRef(type);
            if (!onText) return nullptr;
        }
        bool ended = false;
        for (int i = 0; i < 512; ++i) {
            if (aborted(&deadline)) throw std::runtime_error("Local processing cancelled or timed out");
            auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            char piece[256];
            int size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (size < 0) throw std::runtime_error("Unable to decode output token");
            output.append(piece, size);
            if (onText) {
                auto bytes = env->NewByteArray(output.size());
                if (!bytes) return nullptr;
                env->SetByteArrayRegion(bytes, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
                env->CallVoidMethod(stream, onText, bytes);
                env->DeleteLocalRef(bytes);
                if (env->ExceptionCheck()) return nullptr;
            }
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(context.get(), batch)) throw std::runtime_error("Local generation failed");
        }
        if (!ended) throw std::runtime_error("Local output was incomplete");
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception & error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}
