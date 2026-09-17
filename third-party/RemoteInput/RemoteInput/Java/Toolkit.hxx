//
//  Toolkit.hxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#ifndef Toolkit_HXX_INCLUDED
#define Toolkit_HXX_INCLUDED

#include "JNI_Common.hxx"
#include "EventQueue.hxx"

namespace java
{
    class Toolkit
    {
    private:
        JNIEnv* env;
        jclass cls;
        jobject toolkit;

    protected:
        Toolkit(JNIEnv* env, jclass cls, jobject toolkit) noexcept;

    public:
        Toolkit(Toolkit&& other) noexcept;
        Toolkit(const Toolkit& other) = delete;
        virtual ~Toolkit() noexcept;

        Toolkit& operator = (Toolkit&& other) noexcept;
        Toolkit& operator = (const Toolkit& other) = delete;

        static Toolkit getDefaultToolkit(JNIEnv* env) noexcept;

        EventQueue getSystemEventQueue() const noexcept;
    };
}

#endif /* Toolkit_HXX_INCLUDED */
