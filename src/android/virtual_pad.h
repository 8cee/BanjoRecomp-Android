#pragma once
#include <cstdint>

namespace banjo::android::virtualpad {

enum ButtonId : int {
    PAD_A = 0,
    PAD_B = 1,
    PAD_Z = 2,
    PAD_L = 3,
    PAD_R = 4,
    PAD_START = 5,
    PAD_C_UP = 6,
    PAD_C_DOWN = 7,
    PAD_C_LEFT = 8,
    PAD_C_RIGHT = 9,
    PAD_DPAD_UP = 10,
    PAD_DPAD_DOWN = 11,
    PAD_DPAD_LEFT = 12,
    PAD_DPAD_RIGHT = 13,
    PAD_MENU = 14,
    PAD_COUNT = 15,
};

bool init_jni(void* env, void* view);
void set_button(int button_id, bool pressed);
void set_stick(float x, float y);
void merge_input(int controller_num, uint16_t* buttons, float* x, float* y);
void notify_game_started(bool game_started);
bool is_game_started();

} // namespace banjo::android::virtualpad
