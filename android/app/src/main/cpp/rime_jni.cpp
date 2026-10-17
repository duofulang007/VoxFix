#include <jni.h>
#include <rime_api.h>
#include <rime_levers_api.h>
#include <fstream>
#include <sys/stat.h>
#include <string>
#include <vector>
#include <unistd.h>
#include <malloc.h>

extern "C" JNIEXPORT jboolean JNICALL Java_local_voxfix_debug_NativeMemory_nativePurge(JNIEnv*, jobject, jboolean all) {
  if (all && mallopt(M_PURGE_ALL, 0)) return JNI_TRUE;
  return mallopt(M_PURGE, 0) ? JNI_TRUE : JNI_FALSE;
}

extern "C" int feelime_rime_initialize(const char*, const char*, const char*);
extern "C" void feelime_rime_finalize();

static jstring java_string(JNIEnv* env, const char* value) {
  const std::string text = value ? value : "";
  auto bytes = env->NewByteArray(text.size());
  env->SetByteArrayRegion(bytes, 0, text.size(), reinterpret_cast<const jbyte*>(text.data()));
  auto type = env->FindClass("java/lang/String");
  auto encoding = env->NewStringUTF("UTF-8");
  auto result = static_cast<jstring>(env->NewObject(type, env->GetMethodID(type, "<init>", "([BLjava/lang/String;)V"), bytes, encoding));
  env->DeleteLocalRef(bytes); env->DeleteLocalRef(type); env->DeleteLocalRef(encoding);
  return result;
}

static jobjectArray strings(JNIEnv* env, const std::vector<std::string>& values) {
  auto type = env->FindClass("java/lang/String");
  auto result = env->NewObjectArray(values.size(), type, nullptr);
  env->DeleteLocalRef(type);
  for (std::size_t i = 0; i < values.size(); ++i) {
    auto value = java_string(env, values[i].c_str());
    env->SetObjectArrayElement(result, i, value); env->DeleteLocalRef(value);
  }
  return result;
}

extern "C" JNIEXPORT jlong JNICALL Java_local_voxfix_debug_PinyinEngine_nativeStart(JNIEnv* env, jobject, jstring shared, jstring user, jstring staging) {
  const char* a = env->GetStringUTFChars(shared, nullptr);
  const char* b = env->GetStringUTFChars(user, nullptr);
  const char* c = env->GetStringUTFChars(staging, nullptr);
  bool initialized = feelime_rime_initialize(a, b, c);
  env->ReleaseStringUTFChars(shared, a); env->ReleaseStringUTFChars(user, b); env->ReleaseStringUTFChars(staging, c);
  auto* api = rime_get_api();
  if (!initialized || !RIME_API_AVAILABLE(api, get_candidate_preview)) return 0;
  const char* user_path = env->GetStringUTFChars(user, nullptr);
  std::string user_dir(user_path);
  env->ReleaseStringUTFChars(user, user_path);
  if (access((user_dir + "/lexicon-migrated").c_str(), F_OK) != 0) {
    auto* module = api->find_module("levers");
    auto* levers = module && module->get_api ? reinterpret_cast<RimeLeversApi*>(module->get_api()) : nullptr;
    if (!levers || !RIME_API_AVAILABLE(levers, export_user_dict)) { feelime_rime_finalize(); return 0; }
    const auto directory = user_dir + "/lexicon-export";
    mkdir(directory.c_str(), 0700);
    bool ok = true;
    RimeUserDictIterator iter{};
    if (levers->user_dict_iterator_init(&iter)) {
      while (const char* name = levers->next_user_dict(&iter)) {
        const std::string dict(name);
        if (dict.find_first_of("/\\") != std::string::npos || dict.find("..") != std::string::npos ||
            levers->export_user_dict(name, (directory + "/" + dict + ".tsv").c_str()) < 0) { ok = false; break; }
      }
      levers->user_dict_iterator_destroy(&iter);
    }
    if (!ok || !std::ofstream(directory + "/ready").put('1')) { feelime_rime_finalize(); return 0; }
  }
  auto session = api->create_session();
  if (session && !api->select_schema(session, "voxfix")) { api->destroy_session(session); return 0; }
  return session;
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_local_voxfix_debug_PinyinEngine_nativeQuery(JNIEnv* env, jobject, jlong session, jstring input, jint limit) {
  auto* api = rime_get_api();
  const char* chars = env->GetStringUTFChars(input, nullptr);
  const std::string raw(chars);
  env->ReleaseStringUTFChars(input, chars);
  const char* current = api->get_input(session);
  const std::string previous(current ? current : "");
  if (raw.compare(0, previous.size(), previous) == 0) {
    for (std::size_t i = previous.size(); i < raw.size(); ++i) api->process_key(session, raw[i], 0);
  } else if (previous.compare(0, raw.size(), raw) == 0) {
    api->set_caret_pos(session, previous.size());
    for (std::size_t i = raw.size(); i < previous.size(); ++i) api->process_key(session, 0xff08, 0);
  } else {
    api->clear_composition(session);
    for (char key : raw) api->process_key(session, key, 0);
  }
  std::vector<std::string> result;
  RIME_STRUCT(RimeContext, context);
  if (api->get_context(session, &context)) {
    result.emplace_back(context.composition.preedit ? context.composition.preedit : "");
    api->free_context(&context);
  } else result.emplace_back("");
  RimeCandidateListIterator iterator{};
  if (api->candidate_list_begin(session, &iterator)) {
    while (result.size() < static_cast<std::size_t>(limit * 2 + 1) && api->candidate_list_next(&iterator)) {
      result.emplace_back(std::to_string(iterator.index));
      result.emplace_back(iterator.candidate.text ? iterator.candidate.text : "");
    }
    api->candidate_list_end(&iterator);
  }
  return strings(env, result);
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_local_voxfix_debug_PinyinEngine_nativePreview(JNIEnv* env, jobject, jlong session, jint index) {
  auto* api = rime_get_api();
  std::vector<std::string> result;
  RIME_STRUCT(RimeCandidatePreview, preview);
  // An already-highlighted candidate can report no change; preview still validates its text.
  api->highlight_candidate(session, index);
  if (api->get_candidate_preview(session, &preview)) {
    result.emplace_back(preview.text_before_selection ? preview.text_before_selection : "");
    result.emplace_back(preview.selected_text ? preview.selected_text : "");
    result.emplace_back(preview.text_after_selection ? preview.text_after_selection : "");
    api->free_candidate_preview(&preview);
  }
  return strings(env, result);
}

extern "C" JNIEXPORT void JNICALL Java_local_voxfix_debug_PinyinEngine_nativeClose(JNIEnv*, jobject, jlong session) {
  rime_get_api()->destroy_session(session);
  feelime_rime_finalize();
}
extern "C" JNIEXPORT void JNICALL Java_local_voxfix_debug_PinyinEngine_nativeReset(JNIEnv*, jobject, jlong session) {
  rime_get_api()->clear_composition(session);
}
