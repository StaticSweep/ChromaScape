//
//  InputEvent.hxx
//  RemoteInput
//
//  Created by Brandon on 2019-12-27.
//  Copyright © 2019 XIO. All rights reserved.
//

#ifndef InputEvent_HXX_INCLUDED
#define InputEvent_HXX_INCLUDED

#include "JNI_Common.hxx"
#include <cstdint>

namespace java
{
    struct InputEvent
    {
        enum InputEventMasks : std::uint32_t
        {
            SHIFT_DOWN_MASK = 1 << 6,
            CTRL_DOWN_MASK = 1 << 7,
            META_DOWN_MASK = 1 << 8,
            ALT_DOWN_MASK = 1 << 9,
            BUTTON1_DOWN_MASK = 1 << 10,
            BUTTON2_DOWN_MASK = 1 << 11,
            BUTTON3_DOWN_MASK = 1 << 12,
            ALT_GRAPH_DOWN_MASK = 1 << 13
        };

        static std::int32_t GetDownMaskForButton(std::int32_t button) noexcept;
    };
}

#endif /* InputEvent_HXX_INCLUDED */
