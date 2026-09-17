//
// Created by Brandon on 2021-10-21.
//

#ifndef REMOTEINPUT_SUNTOOLKIT_HXX
#define REMOTEINPUT_SUNTOOLKIT_HXX

#include "Toolkit.hxx"
#include "Window.hxx"

namespace java
{
    class SunToolkit : public Toolkit
    {
    public:
        static Window getContainingWindow(Component* component);
    };
}

#endif //REMOTEINPUT_SUNTOOLKIT_HXX
