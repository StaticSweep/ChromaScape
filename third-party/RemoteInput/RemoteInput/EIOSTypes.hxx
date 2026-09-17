//
// Created by Brandon on 2023-03-28.
//

#ifndef EIOSTYPES_HXX_INCLUDED
#define EIOSTYPES_HXX_INCLUDED

#include <cstdint>

enum class ImageFormat: std::uint32_t
{
    BGR_BGRA,  // Compatibility for Simba's Black pixels representing Transparency. Simba should really fix this.
    BGRA,
    RGBA,
    ARGB,
    ABGR
};

enum class EIOSCommand: std::uint32_t
{
    COMMAND_NONE,
    KILL_APPLICATION,

    GET_IMAGE_DIMENSIONS,
    GET_TARGET_DIMENSIONS,
    HAS_FOCUS,
    GAIN_FOCUS,
    LOSE_FOCUS,
    IS_KEYBOARD_INPUT_ENABLED,
    SET_KEYBOARD_INPUT_ENABLED,
    IS_MOUSE_INPUT_ENABLED,
    SET_MOUSE_INPUT_ENABLED,
    GET_MOUSE,
    GET_REAL_MOUSE,
    MOVE_MOUSE,
    HOLD_MOUSE,
    RELEASE_MOUSE,
    SCROLL_MOUSE,
    IS_MOUSE_HELD,
    SEND_STRING,
    SEND_KEY,
    KEY_SEND,
    HOLD_KEY,
    RELEASE_KEY,
    IS_KEY_HELD,

    GET_KEYBOARD_SPEED,
    SET_KEYBOARD_SPEED,
    GET_KEYBOARD_REPEAT_DELAY,
    SET_KEYBOARD_REPEAT_DELAY,
    STOP_ALL_PROCESSING,

    GET_UI_SCALING,
    SET_UI_SCALING,

    REFLECT_OBJECT,
    REFLECT_COMPARE_OBJECTS,
    REFLECT_INSTANCE_OF,
    REFLECT_RELEASE_OBJECT,
    REFLECT_RELEASE_OBJECTS,
    REFLECT_CHAR,
    REFLECT_BYTE,
    REFLECT_BOOLEAN,
    REFLECT_SHORT,
    REFLECT_INT,
    REFLECT_LONG,
    REFLECT_FLOAT,
    REFLECT_DOUBLE,
    REFLECT_STRING,
    REFLECT_ARRAY,
    REFLECT_ARRAY_SIZE,
    REFLECT_ARRAY_WITH_SIZE,

    REFLECT_ARRAY_ALL,
    REFLECT_ARRAY_WITH_INDEX_SIZE,
    REFLECT_ARRAY_INDICES,

    REFLECT_CLASS_NAME,
    REFLECT_CLASS_TYPE,
    REFLECT_CLASS_LOADER,

    REMOTE_VM_INSTRUCTION
};

typedef struct EIOSData
{
    std::int32_t parent_process_id;
    std::int32_t parent_thread_id;
    std::int32_t image_width;
    std::int32_t image_height;
    std::int32_t target_width;
    std::int32_t target_height;
    bool debug_graphics;
    bool ui_scaling;
    ImageFormat image_format;
    EIOSCommand command;
    std::uint8_t data[100000 * 8];
} EIOSData;

#endif /* EIOSTYPES_HXX_INCLUDED */
