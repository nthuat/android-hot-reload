#include <jni.h>
#include "jvmti.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <pthread.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <fstream>
#include <string>
#include <vector>

#define LOG_TAG "HotReloadAgent"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr char kSocketName[] = "hotreload-agent";
constexpr uint8_t kCmdPing = 0x01;
constexpr uint8_t kCmdLoadDex = 0x02;
constexpr uint8_t kStatusOk = 0x00;
constexpr uint8_t kStatusFail = 0x02;

JavaVM* g_vm = nullptr;
jvmtiEnv* g_jvmti = nullptr;
bool g_started = false;

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

void NotifyRuntime(JNIEnv* env) {
  jclass cls = FindLoadedClass(env, "Ldev/hotreload/runtime/ComposeInvalidator;");
  if (cls == nullptr) {
    LOGE("ComposeInvalidator not loaded; skipping recompose signal");
    return;
  }
  jmethodID reload = env->GetStaticMethodID(cls, "reload", "()V");
  if (reload != nullptr) {
    env->CallStaticVoidMethod(cls, reload);
  }
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  env->DeleteGlobalRef(cls);
}

// payload: "<descriptor>\n<dex path>". Returns reply detail; sets *ok.
std::string HandleLoadDex(JNIEnv* env, const std::string& payload, bool* ok) {
  *ok = false;
  size_t nl = payload.find('\n');
  if (nl == std::string::npos) return "malformed LOAD_DEX payload";
  std::string descriptor = payload.substr(0, nl);
  std::string dex_path = payload.substr(nl + 1);

  std::vector<unsigned char> dex;
  if (!ReadFile(dex_path, &dex)) return "cannot read dex: " + dex_path;

  jclass target = FindLoadedClass(env, descriptor.c_str());
  if (target == nullptr) return "class not loaded: " + descriptor + " (new classes are unsupported in v1 — rebuild)";

  jvmtiClassDefinition def;
  def.klass = target;
  def.class_byte_count = static_cast<jint>(dex.size());
  def.class_bytes = dex.data();
  jvmtiError err = g_jvmti->RedefineClasses(1, &def);
  env->DeleteGlobalRef(target);

  if (err != JVMTI_ERROR_NONE) {
    char* name = nullptr;
    g_jvmti->GetErrorName(err, &name);
    std::string msg = "RedefineClasses failed: " + std::string(name ? name : "?") +
                      " (structural changes are unsupported in v1 — rebuild)";
    if (name) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    return msg;
  }
  *ok = true;
  return descriptor + ": redefined";
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
      SendReply(fd, kStatusOk, "pong");
    } else if (cmd == kCmdLoadDex) {
      bool ok = false;
      std::string detail = HandleLoadDex(env, payload, &ok);
      if (ok) NotifyRuntime(env);
      SendReply(fd, ok ? kStatusOk : kStatusFail, detail);
      LOGI("LOAD_DEX: %s", detail.c_str());
    } else {
      SendReply(fd, kStatusFail, "unknown command");
    }
  }
}

void* ServerThread(void*) {
  JNIEnv* env = nullptr;
  JavaVMAttachArgs args = {JNI_VERSION_1_6, "HotReloadAgent", nullptr};
  if (g_vm->AttachCurrentThread(&env, &args) != JNI_OK) {
    LOGE("cannot attach server thread");
    return nullptr;
  }

  int server = socket(AF_UNIX, SOCK_STREAM, 0);
  sockaddr_un addr = {};
  addr.sun_family = AF_UNIX;
  addr.sun_path[0] = '\0';  // abstract namespace
  strcpy(addr.sun_path + 1, kSocketName);
  socklen_t addr_len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + strlen(kSocketName));
  if (bind(server, reinterpret_cast<sockaddr*>(&addr), addr_len) != 0 || listen(server, 1) != 0) {
    LOGE("bind/listen failed: %s", strerror(errno));
    g_vm->DetachCurrentThread();
    return nullptr;
  }
  LOGI("listening on @%s", kSocketName);

  for (;;) {
    int client = accept(server, nullptr, nullptr);
    if (client < 0) break;
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
  pthread_t t;
  pthread_create(&t, nullptr, ServerThread, nullptr);
  g_started = true;
  LOGI("agent attached");
  return JNI_OK;
}
