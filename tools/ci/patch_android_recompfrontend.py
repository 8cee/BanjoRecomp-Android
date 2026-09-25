#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CPP = ROOT / "lib/RecompFrontend/recompui/src/composites/ui_mod_menu.cpp"
HDR = ROOT / "lib/RecompFrontend/recompui/src/composites/ui_mod_menu.h"

def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"Android frontend patch anchor missing: {label}")
    return text.replace(old, new, 1)

cpp = CPP.read_text(encoding="utf-8-sig")
hdr = HDR.read_text(encoding="utf-8")

cpp = replace_once(
    cpp,
    """    return ok;
}

} // namespace
#endif
""",
    """    return ok;
}

bool launch_android_mod_server() {
    JNIEnv* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
    jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());
    if (env == nullptr || activity == nullptr) {
        return false;
    }

    jclass activity_class = env->GetObjectClass(activity);
    if (activity_class == nullptr) {
        env->DeleteLocalRef(activity);
        return false;
    }

    jmethodID method = env->GetMethodID(activity_class, "openModServerBrowser", "()V");
    if (method == nullptr) {
        env->DeleteLocalRef(activity_class);
        env->DeleteLocalRef(activity);
        return false;
    }

    env->CallVoidMethod(activity, method);
    bool ok = !env->ExceptionCheck();
    if (!ok) {
        env->ExceptionClear();
    }

    env->DeleteLocalRef(activity_class);
    env->DeleteLocalRef(activity);
    return ok;
}

} // namespace
#endif
""",
    "JNI mod-server launcher",
)

cpp = replace_once(
    cpp,
    """void ModMenu::mod_toggled(bool enabled) {
""",
    """void ModMenu::open_mod_server() {
#if defined(__ANDROID__)
    if (!launch_android_mod_server()) {
        recompui::file::show_error_message_box("Browse Mods", "Unable to open the Banjo-Recompiled Thunderstore browser.");
    }
#endif
}

void ModMenu::mod_toggled(bool enabled) {
""",
    "ModMenu::open_mod_server",
)

cpp = replace_once(
    cpp,
    """            install_mods_button->set_enabled(false);
            refresh_button->set_enabled(false);
""",
    """            install_mods_button->set_enabled(false);
            browse_mods_button->set_enabled(false);
            refresh_button->set_enabled(false);
""",
    "disable browse button after game start",
)

cpp = replace_once(
    cpp,
    """            install_mods_button = context.create_element<Button>(footer_container, "Install Mods", recompui::ButtonStyle::Primary);
            install_mods_button->add_pressed_callback([this](){ open_install_dialog(); });

            Element* footer_spacer""",
    """            install_mods_button = context.create_element<Button>(footer_container, "Install Mods", recompui::ButtonStyle::Primary);
            install_mods_button->add_pressed_callback([this](){ open_install_dialog(); });

#if defined(__ANDROID__)
            browse_mods_button = context.create_element<Button>(footer_container, "Browse Mods", recompui::ButtonStyle::Secondary);
            browse_mods_button->add_pressed_callback([this](){ open_mod_server(); });
#endif

            Element* footer_spacer""",
    "Browse Mods button",
)

hdr = replace_once(
    hdr,
    """    void open_install_dialog();
    void mod_toggled(bool enabled);
""",
    """    void open_install_dialog();
    void open_mod_server();
    void mod_toggled(bool enabled);
""",
    "open_mod_server declaration",
)

hdr = replace_once(
    hdr,
    """    Button *install_mods_button = nullptr;
    IconButton *refresh_button = nullptr;
""",
    """    Button *install_mods_button = nullptr;
    Button *browse_mods_button = nullptr;
    IconButton *refresh_button = nullptr;
""",
    "browse button member",
)

CPP.write_text(cpp, encoding="utf-8")
HDR.write_text(hdr, encoding="utf-8")
print("Applied Android Browse Mods frontend patch.")
