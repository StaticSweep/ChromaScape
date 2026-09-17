//
//  Frame.hxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#ifndef FRAME_HXX_INCLUDED
#define FRAME_HXX_INCLUDED

#include "JNI_Common.hxx"
#include "Window.hxx"

namespace java
{
    class Frame final : public Window
    {
    public:
        Frame(JNIEnv* env, jobject frame, bool canDelete = true) noexcept;

        Component getContentPane() const noexcept;
    };
}

#endif /* FRAME_HXX_INCLUDED */
