//
//  InputEvent.cxx
//  RemoteInput
//
//  Created by Brandon on 2019-12-27.
//  Copyright © 2019 XIO. All rights reserved.
//

#include "InputEvent.hxx"

namespace java
{
    std::int32_t InputEvent::GetDownMaskForButton(std::int32_t button) noexcept
    {
        static const std::int32_t masks[] = {
            InputEventMasks::BUTTON1_DOWN_MASK,
            InputEventMasks::BUTTON2_DOWN_MASK,
            InputEventMasks::BUTTON3_DOWN_MASK,
            1 << 14, //4th physical button (this is not a wheel!)
            1 << 15, //(this is not a wheel!)
            1 << 16,
            1 << 17,
            1 << 18,
            1 << 19,
            1 << 20,
            1 << 21,
            1 << 22,
            1 << 23,
            1 << 24,
            1 << 25,
            1 << 26,
            1 << 27,
            1 << 28,
            1 << 29,
            1 << 30
        };

        return masks[button - 1];
    }
}