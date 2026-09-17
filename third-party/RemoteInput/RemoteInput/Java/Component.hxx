//
//  Component.hxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#ifndef COMPONENT_HXX_INCLUDED
#define COMPONENT_HXX_INCLUDED

#include <cstdint>
#include <cstddef>
#include "JNI_Common.hxx"
#include "AWTEvent.hxx"

namespace java
{
    class Container;

    class Component
    {
    protected:
        JNIEnv* env;
        jclass cls;
        jobject component;
        bool canDelete;

    public:
        Component(JNIEnv* env, jobject component, bool canDelete = true) noexcept;
        Component(JNIEnv* env, jclass cls, jobject component, bool canDelete = true) noexcept;
        Component(Component&& other) noexcept;
        Component(const Component& other) = delete;
        virtual ~Component() noexcept;


        Component& operator = (Component&& other) noexcept;
        Component& operator = (const Component& other) = delete;

        JNIEnv* getEnv() const noexcept;
        jobject get() const noexcept;

        void getLocationOnScreen(std::int32_t &x, std::int32_t &y) const noexcept;
        void getMousePosition(std::int32_t &x, std::int32_t &y) const noexcept;
        void getLocation(std::int32_t &x, std::int32_t &y) const noexcept;
        void getSize(std::size_t &width, std::size_t &height) const noexcept;
        Component getComponentAt(std::int32_t x, std::int32_t y) const noexcept;
        Container getParent() const noexcept;
        void dispatchEvent(AWTEvent* event) const noexcept;
        void dispatchEvent(jobject event) const noexcept;


        bool isVisible() const noexcept;
        bool isValid() const noexcept;
        bool isEnabled() const noexcept;
        void setEnabled(bool enabled) const noexcept;

        bool hasFocus() const noexcept;
        bool requestFocusInWindow() const noexcept;
        void requestFocus() const noexcept;
    };
}

#endif /* COMPONENT_HXX_INCLUDED */
