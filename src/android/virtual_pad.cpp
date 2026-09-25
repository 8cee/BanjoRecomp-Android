#include "virtual_pad.h"

#ifdef __ANDROID__

#include <atomic>
#include <cmath>
#include <jni.h>
#include <android/log.h>

#include "SDL2/SDL.h"
#include "recompinput/input_state.h"

#define VP_LOG(...) __android_log_print(ANDROID_LOG_INFO, "BanjoRecomp/VirtualPad", __VA_ARGS__)

namespace banjo::android::virtualpad {
namespace {

constexpr uint16_t N64_MASK[PAD_COUNT] = {
    0x8000, 0x4000, 0x2000, 0x0020, 0x0010, 0x1000,
    0x0008, 0x0004, 0x0002, 0x0001,
    0x0800, 0x0400, 0x0200, 0x0100,
    0x0000,
};

constexpr int8_t SDL_BUTTON_MAP[PAD_COUNT] = {
    SDL_CONTROLLER_BUTTON_A,
    SDL_CONTROLLER_BUTTON_X,
    -1, -1, -1, -1,
    -1, -1, -1, -1,
    SDL_CONTROLLER_BUTTON_DPAD_UP,
    SDL_CONTROLLER_BUTTON_DPAD_DOWN,
    SDL_CONTROLLER_BUTTON_DPAD_LEFT,
    SDL_CONTROLLER_BUTTON_DPAD_RIGHT,
    SDL_CONTROLLER_BUTTON_BACK,
};

constexpr SDL_JoystickID VP_DEVICE_ID = 0x424B;

struct State {
    std::atomic<uint16_t> buttons{0};
    std::atomic<float> stick_x{0.0f};
    std::atomic<float> stick_y{0.0f};
    std::atomic<bool> game_started{false};
    std::atomic<bool> bridge_ready{false};
    JavaVM* vm = nullptr;
    jclass view_class = nullptr;
    jmethodID on_game_started = nullptr;
};

State& state() {
    static State s;
    return s;
}

void push_button(int sdl_button, bool pressed) {
    if (sdl_button < 0 || !SDL_WasInit(SDL_INIT_GAMECONTROLLER)) return;
    SDL_Event ev{};
    ev.type = pressed ? SDL_CONTROLLERBUTTONDOWN : SDL_CONTROLLERBUTTONUP;
    ev.cbutton.which = VP_DEVICE_ID;
    ev.cbutton.button = static_cast<uint8_t>(sdl_button);
    ev.cbutton.state = pressed ? SDL_PRESSED : SDL_RELEASED;
    SDL_PushEvent(&ev);
}

void push_axis(float x, float y) {
    if (!SDL_WasInit(SDL_INIT_GAMECONTROLLER)) return;
    auto send = [](uint8_t axis, float value) {
        SDL_Event ev{};
        ev.type = SDL_CONTROLLERAXISMOTION;
        ev.caxis.which = VP_DEVICE_ID;
        ev.caxis.axis = axis;
        ev.caxis.value = static_cast<Sint16>(value * 32767.0f);
        SDL_PushEvent(&ev);
    };
    send(SDL_CONTROLLER_AXIS_LEFTX, x);
    send(SDL_CONTROLLER_AXIS_LEFTY, y);
}

JNIEnv* get_env() {
    State& s = state();
    if (s.vm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    if (s.vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (s.vm->AttachCurrentThread(&env, nullptr) == JNI_OK) return env;
    return nullptr;
}

void notify_java(bool started) {
    State& s = state();
    JNIEnv* env = get_env();
    if (env == nullptr || s.view_class == nullptr || s.on_game_started == nullptr) return;
    env->CallStaticVoidMethod(s.view_class, s.on_game_started, started ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

} // namespace

bool init_jni(void* env_ptr, void* view_ptr) {
    auto* env = static_cast<JNIEnv*>(env_ptr);
    auto view = static_cast<jobject>(view_ptr);
    if (env == nullptr || view == nullptr) return false;

    State& s = state();
    if (s.bridge_ready.load(std::memory_order_acquire)) return true;

    if (s.vm == nullptr) env->GetJavaVM(&s.vm);

    jclass local = env->FindClass("io/github/banjorecomp/VirtualPadView");
    if (local == nullptr) {
        env->ExceptionClear();
        local = env->GetObjectClass(view);
    }
    if (local == nullptr) {
        env->ExceptionClear();
        return false;
    }

    s.view_class = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    s.on_game_started = env->GetStaticMethodID(s.view_class, "onGameStarted", "(Z)V");
    if (s.on_game_started == nullptr) {
        env->ExceptionClear();
        return false;
    }

    s.bridge_ready.store(true, std::memory_order_release);
    VP_LOG("virtual pad bridge ready");
    return true;
}

void set_button(int button_id, bool pressed) {
    if (button_id < 0 || button_id >= PAD_COUNT) return;
    const uint16_t mask = N64_MASK[button_id];

    uint16_t current = state().buttons.load(std::memory_order_relaxed);
    while (true) {
        const uint16_t next = pressed
            ? static_cast<uint16_t>(current | mask)
            : static_cast<uint16_t>(current & ~mask);
        if (state().buttons.compare_exchange_weak(current, next, std::memory_order_relaxed)) break;
    }

    push_button(SDL_BUTTON_MAP[button_id], pressed);
}

void set_stick(float x, float y) {
    const float mag2 = x * x + y * y;
    if (mag2 > 1.0f) {
        const float inv = 1.0f / std::sqrt(mag2);
        x *= inv;
        y *= inv;
    }
    state().stick_x.store(x, std::memory_order_relaxed);
    state().stick_y.store(y, std::memory_order_relaxed);
    push_axis(x, y);
}

void merge_input(int controller_num, uint16_t* buttons, float* x, float* y) {
    if (controller_num != 0 || buttons == nullptr || x == nullptr || y == nullptr) return;
    if (recompinput::game_input_disabled()) return;

    *buttons = static_cast<uint16_t>(*buttons | state().buttons.load(std::memory_order_relaxed));

    const float px = state().stick_x.load(std::memory_order_relaxed);
    const float py = state().stick_y.load(std::memory_order_relaxed);
    if ((px * px + py * py) > ((*x) * (*x) + (*y) * (*y))) {
        *x = px;
        *y = py;
    }
}

void notify_game_started(bool started) {
    state().game_started.store(started, std::memory_order_relaxed);
    static std::atomic<bool> previous{false};
    const bool old = previous.exchange(started, std::memory_order_relaxed);
    if (old != started && state().bridge_ready.load(std::memory_order_acquire)) {
        notify_java(started);
    }
}

bool is_game_started() {
    return state().game_started.load(std::memory_order_relaxed);
}

} // namespace banjo::android::virtualpad

#define VP_JNI extern "C" __attribute__((visibility("default"))) JNIEXPORT

VP_JNI jboolean JNICALL
Java_io_github_banjorecomp_VirtualPadView_nativeInit(JNIEnv* env, jobject thiz) {
    return banjo::android::virtualpad::init_jni(env, thiz) ? JNI_TRUE : JNI_FALSE;
}

VP_JNI void JNICALL
Java_io_github_banjorecomp_VirtualPadView_nativeButton(JNIEnv*, jobject, jint id, jboolean pressed) {
    banjo::android::virtualpad::set_button(static_cast<int>(id), pressed == JNI_TRUE);
}

VP_JNI void JNICALL
Java_io_github_banjorecomp_VirtualPadView_nativeAxis(JNIEnv*, jobject, jfloat x, jfloat y) {
    banjo::android::virtualpad::set_stick(static_cast<float>(x), static_cast<float>(y));
}

VP_JNI jboolean JNICALL
Java_io_github_banjorecomp_VirtualPadView_nativeIsGameStarted(JNIEnv*, jobject) {
    return banjo::android::virtualpad::is_game_started() ? JNI_TRUE : JNI_FALSE;
}

#endif
