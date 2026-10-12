#include <cstdint>
#include <cstdio>
#include <cstring>
#include <chrono>
#include <string>
#include <unistd.h>
#include <rime_api.h>

extern "C" {
const char* feelime_rime_version();
int feelime_rime_initialize(const char*, const char*, const char*);
std::uintptr_t feelime_rime_create_session(const char*);
int feelime_rime_process_key(std::uintptr_t, int, int);
int feelime_rime_get_context(std::uintptr_t, char*, std::size_t, char*, std::size_t, int*, int*);
int feelime_rime_select_candidate(std::uintptr_t, std::size_t);
int feelime_rime_get_commit(std::uintptr_t, char*, std::size_t);
void feelime_rime_destroy_session(std::uintptr_t);
void feelime_rime_finalize();
}

int main(int argc, char** argv) {
  if (argc != 4 || !feelime_rime_initialize(argv[1], argv[2], argv[3])) return 1;
  std::printf("RIME_VERSION %s\n", feelime_rime_version());
  const auto* api = rime_get_api();
  if (!RIME_API_AVAILABLE(api, get_candidate_preview)) return 6;
  const std::string seed = std::string(argv[1]) + "/voxfix_personal.dict.yaml";
  if (access(seed.c_str(), F_OK) == 0) {
    api->deployer_initialize(nullptr);
    const std::string schema = std::string(argv[1]) + "/voxfix_personal.schema.yaml";
    if (!api->deploy_schema(schema.c_str())) return 10;
    std::puts("PERSONAL_DICTIONARY_DEPLOY_PASS");
  }
  const char* cases[] = {"nihao", "women", "wzdl", "nihaoq", "qqqqqq",
    "woxianghebeikafei", "mingtianxiawusandianjian", "wozhuzailiwu"};
  for (const char* raw : cases) {
    auto session = feelime_rime_create_session("voxfix");
    if (!session) return 2;
    auto start = std::chrono::steady_clock::now();
    for (const char* key = raw; *key; ++key) {
      if (!feelime_rime_process_key(session, *key, 0)) return 3;
    }
    char preedit[2048]{}, candidates[16384]{}, commit[2048]{};
    int page = 0, last = 0;
    if (feelime_rime_get_context(session, preedit, sizeof(preedit), candidates,
                                 sizeof(candidates), &page, &last) != 1) return 4;
    auto ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::printf("CASE %s %.3fms preedit=%s\n%s\n", raw, ms, preedit, candidates);
    if (!std::strcmp(raw, "nihaoq")) {
      RimeCandidateListIterator iterator{};
      int index = -1;
      if (api->candidate_list_begin(session, &iterator)) {
        while (api->candidate_list_next(&iterator)) {
          if (!std::strcmp(iterator.candidate.text, "你好")) { index = iterator.index; break; }
        }
        api->candidate_list_end(&iterator);
      }
      if (index < 0 || !api->highlight_candidate(session, index)) return 7;
      RIME_STRUCT(RimeCandidatePreview, preview);
      if (!api->get_candidate_preview(session, &preview)) return 8;
      std::printf("PARTIAL_PREVIEW before=%s selected=%s after=%s\n",
                  preview.text_before_selection, preview.selected_text, preview.text_after_selection);
      bool valid = !std::strcmp(preview.selected_text, "你好") && !std::strcmp(preview.text_after_selection, "q");
      api->free_candidate_preview(&preview);
      if (!valid) return 9;
    }
    if (!std::strcmp(raw, "nihao")) {
      if (!std::strstr(candidates, "你好") || !feelime_rime_select_candidate(session, 0)
          || feelime_rime_get_commit(session, commit, sizeof(commit)) != 1
          || std::strcmp(commit, "你好")) return 5;
    }
    feelime_rime_destroy_session(session);
  }
  feelime_rime_finalize();
  std::puts("RIME_ARM64_PROBE_PASS");
  return 0;
}
