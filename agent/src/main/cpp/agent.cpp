#include <jni.h>
#include "jvmti.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <pthread.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <condition_variable>
#include <cstdlib>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#define LOG_TAG "HotReloadAgent"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint8_t kCmdPing = 0x01;
constexpr uint8_t kCmdLoadDex = 0x02;
constexpr uint8_t kStatusOk = 0x00;
// Real incompatibility: RedefineClasses rejected the bytecode (structural change — unsupported
// in v1). A target class that merely isn't currently loaded is NOT this status: it's skipped
// instead (see HandleLoadDex). Matches Protocol.STATUS_FAIL on the CLI side.
constexpr uint8_t kStatusFail = 0x02;
// Environmental/agent-side error: malformed payload, unreadable dex file — not a code-
// compatibility problem, so the CLI shouldn't tell the user to "rebuild". Matches
// Protocol.STATUS_ERROR.
constexpr uint8_t kStatusError = 0x03;
// LOAD_DEX payload record separator — matches Protocol.RECORD_SEP (0x1E, ASCII Record
// Separator). See ParseLoadDexRecords for the framing this splits.
constexpr char kRecordSep = '\x1E';
// Reported in the PING reply when the on-device runtime's version can't be determined — an
// already-published runtime jar built before ComposeInvalidator.runtimeVersion existed (an old
// .so calling into a new runtime is impossible: the agent ships inside cli.zip, always matched to
// this CLI build). Matches Protocol.UNKNOWN_RUNTIME_VERSION on the CLI side, which treats this as
// "proceed with a warning", not a hard failure.
const char kUnknownRuntimeVersion[] = "unknown";

// adbd forwards `adb forward tcp:PORT localabstract:SOCKET` by connecting to the abstract
// socket directly from its own daemon process, not from the app — so SO_PEERCRED on a
// legitimate CLI-forwarded connection reports adbd's uid, not the app's own uid. Verified on
// a Pixel_3a_API_34 emulator (see fix report): adbd there runs as AID_ROOT already (root
// emulator image), so forwarded connections arrive as uid 0. On a production device / adb
// running unrooted, adbd runs as AID_SHELL (2000) instead. Both are legitimate: only the host
// developer with `adb` access to this device can reach either. What must be rejected is any
// *other on-device app* opening the socket directly (any other uid, generally >= 10000).
constexpr uid_t kAidRoot = 0;
constexpr uid_t kAidShell = 2000;

JavaVM* g_vm = nullptr;
jvmtiEnv* g_jvmti = nullptr;
bool g_started = false;
std::string g_socket_name;
// Own package name (see ReadOwnPackageName) — cached separately from g_socket_name so the PING
// reply can name it directly (see ServeClient's kCmdPing branch) without re-deriving it from the
// socket name's "hotreload-agent-" prefix. Read once in Agent_OnAttach; never changes.
std::string g_pkg_name;

// Signals ServerThread's bind/listen outcome back to Agent_OnAttach so g_started only latches
// true once the socket is actually accepting connections — not merely once pthread_create
// returned, which says nothing about whether the thread went on to bind successfully.
std::mutex g_start_mutex;
std::condition_variable g_start_cv;
enum class StartState { kPending, kOk, kFailed };
StartState g_start_state = StartState::kPending;

void SignalStart(bool ok) {
  {
    std::lock_guard<std::mutex> lock(g_start_mutex);
    g_start_state = ok ? StartState::kOk : StartState::kFailed;
  }
  g_start_cv.notify_one();
}

// Own package name, read from /proc/self/cmdline (first NUL-terminated token — for a regular
// app process this is the package name, the same string the CLI already knows via --package).
// Used to make the abstract socket name per-app so two instrumented apps on one device can't
// collide on a single global socket name.
std::string ReadOwnPackageName() {
  std::ifstream f("/proc/self/cmdline", std::ios::binary);
  std::string name;
  std::getline(f, name, '\0');
  return name;
}

bool ReadFile(const std::string& path, std::vector<unsigned char>* out) {
  std::ifstream f(path, std::ios::binary | std::ios::ate);
  if (!f) return false;
  auto size = f.tellg();
  out->resize(static_cast<size_t>(size));
  f.seekg(0);
  f.read(reinterpret_cast<char*>(out->data()), size);
  return f.good();
}

// FindClass from an attached native thread only sees the system classloader.
// App classes must be located among already-loaded classes instead.
jclass FindLoadedClass(JNIEnv* env, const char* descriptor) {
  jint count = 0;
  jclass* classes = nullptr;
  if (g_jvmti->GetLoadedClasses(&count, &classes) != JVMTI_ERROR_NONE) return nullptr;
  jclass found = nullptr;
  for (jint i = 0; i < count; i++) {
    char* sig = nullptr;
    if (g_jvmti->GetClassSignature(classes[i], &sig, nullptr) == JVMTI_ERROR_NONE) {
      if (found == nullptr && strcmp(sig, descriptor) == 0) {
        found = static_cast<jclass>(env->NewGlobalRef(classes[i]));
      }
      g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig));
    }
    env->DeleteLocalRef(classes[i]);
  }
  g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
  return found;
}

// "Ldev/thuat/hotreload/sample/feature/GreetingKt;" -> "dev.thuat.hotreload.sample.feature.GreetingKt"
std::string DescriptorToBinaryName(const std::string& descriptor) {
  std::string name = descriptor;
  if (!name.empty() && name.front() == 'L') name.erase(0, 1);
  if (!name.empty() && name.back() == ';') name.pop_back();
  for (char& c : name) {
    if (c == '/') c = '.';
  }
  return name;
}

// Called once per LOAD_DEX message with every redefined class's binary name AND the union of
// FunctionKeyMeta keys the CLI extracted host-side for the whole batch (one batch, one call —
// see HandleLoadDex), so ComposeInvalidator can invalidate them directly instead of hunting for a
// holder class on-device (see ComposeInvalidator.reload's doc — that on-device lookup still runs
// as a fallback when keys is empty, e.g. an older CLI or a case extraction missed). Returns the
// tier string ComposeInvalidator.reload reports back ("tier1"/"tier2"/"tier3"/"tier-timeout"), or
// "" if the runtime lib isn't loaded / the call couldn't be made — callers must treat "" as "no
// tier to report", not as a real tier value.
std::string NotifyRuntime(JNIEnv* env, const std::vector<std::string>& binary_names,
                           const std::vector<int32_t>& keys) {
  jclass cls = FindLoadedClass(env, "Ldev/thuat/hotreload/runtime/ComposeInvalidator;");
  if (cls == nullptr) {
    LOGE("ComposeInvalidator not loaded; skipping recompose signal");
    return "";
  }
  std::string tier;
  jmethodID reload = env->GetStaticMethodID(cls, "reload", "([Ljava/lang/String;[I)Ljava/lang/String;");
  if (reload != nullptr) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray names = env->NewObjectArray(static_cast<jsize>(binary_names.size()), stringClass, nullptr);
    for (size_t i = 0; i < binary_names.size(); i++) {
      jstring s = env->NewStringUTF(binary_names[i].c_str());
      env->SetObjectArrayElement(names, static_cast<jsize>(i), s);
      env->DeleteLocalRef(s);
    }
    jintArray key_array = env->NewIntArray(static_cast<jsize>(keys.size()));
    if (!keys.empty()) {
      env->SetIntArrayRegion(key_array, 0, static_cast<jsize>(keys.size()), keys.data());
    }
    auto result = static_cast<jstring>(env->CallStaticObjectMethod(cls, reload, names, key_array));
    if (result != nullptr) {
      const char* chars = env->GetStringUTFChars(result, nullptr);
      if (chars != nullptr) {
        tier.assign(chars);
        env->ReleaseStringUTFChars(result, chars);
      }
      env->DeleteLocalRef(result);
    }
    env->DeleteLocalRef(key_array);
    env->DeleteLocalRef(names);
    env->DeleteLocalRef(stringClass);
  }
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  env->DeleteGlobalRef(cls);
  return tier;
}

// Reads the on-device runtime's own version via JNI (ComposeInvalidator.runtimeVersion(),
// see that method's doc), for the PING reply (see ServeClient's kCmdPing branch). Falls back to
// kUnknownRuntimeVersion — never throws/crashes the agent — when: the class isn't loaded yet
// (HotReloadInitProvider hasn't run, e.g. attach raced app startup), or the method doesn't exist
// (a runtime published before this feature). GetStaticMethodID raises a pending NoSuchMethodError
// on the latter rather than just returning nullptr, so it must be cleared explicitly or every
// later JNI call on this thread would start failing.
std::string ReadRuntimeVersion(JNIEnv* env) {
  jclass cls = FindLoadedClass(env, "Ldev/thuat/hotreload/runtime/ComposeInvalidator;");
  if (cls == nullptr) return kUnknownRuntimeVersion;
  std::string version = kUnknownRuntimeVersion;
  jmethodID method = env->GetStaticMethodID(cls, "runtimeVersion", "()Ljava/lang/String;");
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    method = nullptr;
  }
  if (method != nullptr) {
    auto result = static_cast<jstring>(env->CallStaticObjectMethod(cls, method));
    if (result != nullptr) {
      const char* chars = env->GetStringUTFChars(result, nullptr);
      if (chars != nullptr) {
        version.assign(chars);
        env->ReleaseStringUTFChars(result, chars);
      }
      env->DeleteLocalRef(result);
    }
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
      version = kUnknownRuntimeVersion;
    }
  }
  env->DeleteGlobalRef(cls);
  return version;
}

struct LoadDexRecord {
  std::string descriptor;
  std::string dex_path;
  std::vector<int32_t> keys;
};

// Splits "<descriptor>\n<dex path>\n<space-separated keys>" records joined by kRecordSep (see
// Protocol.kt's RECORD_SEP doc — must match byte-for-byte). Returns false (payload is malformed)
// if any record is missing either '\n' separator or the payload is empty. The keys field may be
// empty (a bare trailing '\n', or nothing after the second '\n') — that's not malformed, it means
// "CLI found no keys for this class", handled by ComposeInvalidator's on-device fallback.
bool ParseLoadDexRecords(const std::string& payload, std::vector<LoadDexRecord>* out) {
  size_t start = 0;
  while (start <= payload.size()) {
    size_t sep = payload.find(kRecordSep, start);
    size_t end = (sep == std::string::npos) ? payload.size() : sep;
    std::string record = payload.substr(start, end - start);
    size_t nl1 = record.find('\n');
    if (nl1 == std::string::npos) return false;
    size_t nl2 = record.find('\n', nl1 + 1);
    if (nl2 == std::string::npos) return false;
    LoadDexRecord parsed;
    parsed.descriptor = record.substr(0, nl1);
    parsed.dex_path = record.substr(nl1 + 1, nl2 - nl1 - 1);
    std::istringstream keys_stream(record.substr(nl2 + 1));
    std::string tok;
    while (keys_stream >> tok) {
      parsed.keys.push_back(static_cast<int32_t>(std::strtol(tok.c_str(), nullptr, 10)));
    }
    out->push_back(std::move(parsed));
    if (sep == std::string::npos) break;
    start = sep + 1;
  }
  return !out->empty();
}

// Comma-joins descriptors for the "skipped" detail segment (see HandleLoadDex doc). Class
// descriptors never contain ", ", so this is unambiguous to split back apart on the CLI side.
std::string JoinDescriptors(const std::vector<std::string>& descriptors) {
  std::string joined;
  for (size_t i = 0; i < descriptors.size(); i++) {
    if (i) joined += ", ";
    joined += descriptors[i];
  }
  return joined;
}

// payload: one or more "<descriptor>\n<dex path>" records (see ParseLoadDexRecords). Loads all
// dex bytes up front, then resolves each record's target class independently: a class that
// isn't currently loaded is SKIPPED rather than failing the whole batch. This is safe because
// ReloadOrchestrator.cycle() already rejects any genuinely new/removed class via its
// diff.added/diff.removed structural check *before* anything is pushed here — every descriptor
// that reaches this function was present in the baseline snapshot, i.e. it exists in the
// installed APK. "Not loaded" for such a class means "exists on disk but not currently
// loaded/executing" (the common case: a `ComposableSingletons$<File>Kt$lambda-N$1` holder the
// Compose compiler emits for a `@Preview` function's lambda, which previews-only code never
// loads at runtime) — never "brand new class the app doesn't have". Skipping it cannot desync
// running code, because no running code is using it; if it's loaded later it gets the APK's
// original bytes until the next full rebuild (see the "skipped" reply segment, a warning, not
// an error).
//
// RedefineClasses(n, defs) is then called once for whatever *did* resolve, which JVMTI applies
// atomically — no mid-batch partial swap is ever observable, even if the runtime rejects that
// smaller batch. A real RedefineClasses rejection is still a hard failure (kStatusFail): that's
// ART saying the bytecode itself is incompatible (structural change), unrelated to whether a
// class was loaded.
//
// Sets *status. On any kStatusOk return, *out_skipped holds the skipped descriptors (may be
// empty), *out_binary_names holds the binary names of classes actually redefined (empty iff
// every record was skipped — the caller uses this to skip the NotifyRuntime call too, since
// nothing changed), and *out_keys holds the union of every redefined record's CLI-supplied keys
// (skipped records' keys are dropped — a class the runtime never loaded has nothing to
// invalidate).
//
// Reply detail format (see Protocol.kt for the CLI-side parser this must match byte-for-byte):
//   "<result>[ | skipped <N>: <d1>, <d2>, ...][ | tierN]"
// <result> is "<redefined descriptors, comma-joined>: redefined" when >=1 class was redefined,
// or "nothing redefined: all <N> class(es) not loaded" when zero were. The optional
// " | skipped <N>: ..." segment is appended (by this function) whenever out_skipped is
// non-empty. The optional trailing " | tierN" segment is appended by the caller (ServeClient)
// after NotifyRuntime returns, so it always ends up last — parseTier's
// `substringAfterLast(" | ")` keeps working unchanged.
std::string HandleLoadDex(JNIEnv* env, const std::string& payload, uint8_t* status,
                           std::vector<std::string>* out_binary_names,
                           std::vector<std::string>* out_skipped,
                           std::vector<int32_t>* out_keys) {
  *status = kStatusError;
  std::vector<LoadDexRecord> records;
  if (!ParseLoadDexRecords(payload, &records)) return "malformed LOAD_DEX payload";

  std::vector<std::vector<unsigned char>> dex_blobs(records.size());
  for (size_t i = 0; i < records.size(); i++) {
    if (!ReadFile(records[i].dex_path, &dex_blobs[i])) {
      return "cannot read dex: " + records[i].dex_path;
    }
  }

  std::vector<jclass> targets;
  std::vector<size_t> resolved_idx;  // parallel to targets: index back into records/dex_blobs
  targets.reserve(records.size());
  for (size_t i = 0; i < records.size(); i++) {
    jclass target = FindLoadedClass(env, records[i].descriptor.c_str());
    if (target == nullptr) {
      out_skipped->push_back(records[i].descriptor);
      continue;
    }
    targets.push_back(target);
    resolved_idx.push_back(i);
  }

  if (targets.empty()) {
    *status = kStatusOk;
    return "nothing redefined: all " + std::to_string(records.size()) + " class(es) not loaded" +
           " | skipped " + std::to_string(out_skipped->size()) + ": " + JoinDescriptors(*out_skipped);
  }

  std::vector<jvmtiClassDefinition> defs(targets.size());
  for (size_t i = 0; i < targets.size(); i++) {
    defs[i].klass = targets[i];
    defs[i].class_byte_count = static_cast<jint>(dex_blobs[resolved_idx[i]].size());
    defs[i].class_bytes = dex_blobs[resolved_idx[i]].data();
  }
  jvmtiError err = g_jvmti->RedefineClasses(static_cast<jint>(defs.size()), defs.data());
  for (auto t : targets) env->DeleteGlobalRef(t);  // exactly once, on both the success and failure paths below

  if (err != JVMTI_ERROR_NONE) {
    *status = kStatusFail;
    char* name = nullptr;
    g_jvmti->GetErrorName(err, &name);
    std::string msg = "RedefineClasses failed: " + std::string(name ? name : "?") +
                      " (structural changes are unsupported in v1 — rebuild)";
    if (name) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    return msg;
  }

  *status = kStatusOk;
  std::string joined;
  for (size_t idx : resolved_idx) {
    if (!joined.empty()) joined += ", ";
    joined += records[idx].descriptor;
    out_binary_names->push_back(DescriptorToBinaryName(records[idx].descriptor));
    out_keys->insert(out_keys->end(), records[idx].keys.begin(), records[idx].keys.end());
  }
  std::string detail = joined + ": redefined";
  if (!out_skipped->empty()) {
    detail += " | skipped " + std::to_string(out_skipped->size()) + ": " + JoinDescriptors(*out_skipped);
  }
  return detail;
}

bool ReadFully(int fd, void* buf, size_t len) {
  auto* p = static_cast<uint8_t*>(buf);
  while (len > 0) {
    ssize_t n = read(fd, p, len);
    if (n <= 0) return false;
    p += n; len -= static_cast<size_t>(n);
  }
  return true;
}

bool WriteFully(int fd, const void* buf, size_t len) {
  auto* p = static_cast<const uint8_t*>(buf);
  while (len > 0) {
    ssize_t n = write(fd, p, len);
    if (n <= 0) return false;
    p += n; len -= static_cast<size_t>(n);
  }
  return true;
}

void SendReply(int fd, uint8_t status, const std::string& detail) {
  uint32_t len = htonl(static_cast<uint32_t>(1 + detail.size()));
  WriteFully(fd, &len, 4);
  WriteFully(fd, &status, 1);
  WriteFully(fd, detail.data(), detail.size());
}

void ServeClient(int fd, JNIEnv* env) {
  for (;;) {
    uint32_t len_be = 0;
    if (!ReadFully(fd, &len_be, 4)) return;
    uint32_t len = ntohl(len_be);
    if (len < 1 || len > 64 * 1024 * 1024) return;
    std::vector<char> buf(len);
    if (!ReadFully(fd, buf.data(), len)) return;
    uint8_t cmd = static_cast<uint8_t>(buf[0]);
    std::string payload(buf.begin() + 1, buf.end());

    if (cmd == kCmdPing) {
      // "pong:<pkg>:<runtimeVersion>" — must match Protocol.PING_REPLY_PREFIX /
      // Protocol.pingPackageOf / Protocol.pingRuntimeVersionOf on the CLI side byte-for-byte.
      // <pkg> lets the CLI verify it actually reached *this* app's agent before sending any
      // LOAD_DEX, instead of trusting whatever a possibly-stale `adb forward` mapping happens to
      // point at (see ReloadOrchestrator.verifyAgentIdentity). <runtimeVersion> (added after
      // <pkg> to keep pingPackageOf's parsing exact-prefix-compatible with an old agent's
      // two-field reply) lets the CLI refuse to proceed against a runtime library it doesn't
      // match instead of silently no-op'ing a reload (see ReadRuntimeVersion's doc and the fix
      // report this closes).
      SendReply(fd, kStatusOk, "pong:" + g_pkg_name + ":" + ReadRuntimeVersion(env));
    } else if (cmd == kCmdLoadDex) {
      uint8_t status = kStatusError;
      std::vector<std::string> binary_names;
      std::vector<std::string> skipped;
      std::vector<int32_t> keys;
      std::string detail = HandleLoadDex(env, payload, &status, &binary_names, &skipped, &keys);
      // Only notify the runtime when something actually changed — binary_names is empty iff
      // every record in the batch was skipped as not-loaded (see HandleLoadDex), and there's no
      // point asking Compose to recompose when nothing was redefined.
      if (status == kStatusOk && !binary_names.empty()) {
        std::string tier = NotifyRuntime(env, binary_names, keys);
        if (!tier.empty()) detail += " | " + tier;
      }
      SendReply(fd, status, detail);
      LOGI("LOAD_DEX: %s", detail.c_str());
    } else {
      SendReply(fd, kStatusFail, "unknown command");
    }
  }
}

// Only the app's own uid and adb's daemon uid may use this socket — see kAidRoot/kAidShell
// doc for why a forwarded `adb` connection doesn't arrive as the app's own uid. Any other uid
// is some other on-device app trying to inject code via LOAD_DEX and must be rejected.
bool PeerAuthorized(int fd, struct ucred* out_cred) {
  socklen_t len = sizeof(*out_cred);
  if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, out_cred, &len) != 0) {
    LOGE("SO_PEERCRED failed: %s; rejecting connection", strerror(errno));
    return false;
  }
  uid_t self = geteuid();
  if (out_cred->uid == self || out_cred->uid == kAidRoot || out_cred->uid == kAidShell) {
    LOGI("accepted peer uid=%u pid=%d (self uid=%u)", static_cast<unsigned>(out_cred->uid), out_cred->pid, static_cast<unsigned>(self));
    return true;
  }
  LOGW("rejected connection from unauthorized uid=%u pid=%d (self uid=%u)", static_cast<unsigned>(out_cred->uid), out_cred->pid, static_cast<unsigned>(self));
  return false;
}

void* ServerThread(void*) {
  JNIEnv* env = nullptr;
  JavaVMAttachArgs args = {JNI_VERSION_1_6, "HotReloadAgent", nullptr};
  if (g_vm->AttachCurrentThread(&env, &args) != JNI_OK) {
    LOGE("cannot attach server thread");
    SignalStart(false);
    return nullptr;
  }

  int server = socket(AF_UNIX, SOCK_STREAM, 0);
  if (server < 0) {
    LOGE("socket() failed: %s", strerror(errno));
    g_vm->DetachCurrentThread();
    SignalStart(false);
    return nullptr;
  }
  sockaddr_un addr = {};
  addr.sun_family = AF_UNIX;
  addr.sun_path[0] = '\0';  // abstract namespace
  size_t max_name = sizeof(addr.sun_path) - 1 - 1;  // leading NUL byte + this NUL isn't required, but stay well inside the buffer
  if (g_socket_name.size() > max_name) {
    LOGE("socket name too long (%zu bytes): %s", g_socket_name.size(), g_socket_name.c_str());
    close(server);
    g_vm->DetachCurrentThread();
    SignalStart(false);
    return nullptr;
  }
  memcpy(addr.sun_path + 1, g_socket_name.data(), g_socket_name.size());
  socklen_t addr_len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + g_socket_name.size());
  if (bind(server, reinterpret_cast<sockaddr*>(&addr), addr_len) != 0 || listen(server, 1) != 0) {
    LOGE("bind/listen failed: %s", strerror(errno));
    close(server);
    g_vm->DetachCurrentThread();
    SignalStart(false);
    return nullptr;
  }
  LOGI("listening on @%s", g_socket_name.c_str());
  SignalStart(true);

  for (;;) {
    int client = accept(server, nullptr, nullptr);
    if (client < 0) break;
    // Deliberately uninitialized: PeerAuthorized fully populates every field via getsockopt
    // before anything reads it, and unlike this loop's earlier code, is never read on the
    // false-return path.
    struct ucred peer;
    if (!PeerAuthorized(client, &peer)) {
      close(client);
      continue;
    }
    ServeClient(client, env);
    close(client);
  }
  g_vm->DetachCurrentThread();
  return nullptr;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* /*options*/, void* /*reserved*/) {
  if (g_started) return JNI_OK;  // am attach-agent may be issued again; server already up
  g_vm = vm;
  if (vm->GetEnv(reinterpret_cast<void**>(&g_jvmti), JVMTI_VERSION_1_2) != JNI_OK) {
    LOGE("no jvmti env — is the app debuggable?");
    return JNI_ERR;
  }
  jvmtiCapabilities caps = {};
  caps.can_redefine_classes = 1;
  if (g_jvmti->AddCapabilities(&caps) != JVMTI_ERROR_NONE) {
    LOGE("can_redefine_classes unavailable");
    return JNI_ERR;
  }

  g_pkg_name = ReadOwnPackageName();
  g_socket_name = "hotreload-agent-" + g_pkg_name;
  {
    std::lock_guard<std::mutex> lock(g_start_mutex);
    g_start_state = StartState::kPending;
  }
  pthread_t t;
  if (pthread_create(&t, nullptr, ServerThread, nullptr) != 0) {
    LOGE("pthread_create failed: %s", strerror(errno));
    return JNI_ERR;
  }
  pthread_detach(t);  // never joined; detach so its resources are reclaimed on exit

  // Wait for the thread to actually report a bind/listen outcome before latching g_started —
  // a failed bind must NOT permanently disable the agent (old bug: g_started was set true
  // unconditionally right after pthread_create, regardless of what the thread went on to do).
  std::unique_lock<std::mutex> lock(g_start_mutex);
  g_start_cv.wait(lock, [] { return g_start_state != StartState::kPending; });
  if (g_start_state == StartState::kFailed) {
    LOGE("server thread failed to start; agent not marked started (a later attach-agent will retry)");
    return JNI_ERR;
  }
  g_started = true;
  LOGI("agent attached");
  return JNI_OK;
}
