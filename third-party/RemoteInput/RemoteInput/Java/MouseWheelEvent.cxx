//
//  MouseWheelEvent.cxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-10.
//  Copyright © 2020 XIO. All rights reserved.
//

#include "MouseWheelEvent.hxx"
#include <utility>

namespace java
{
	MouseWheelEvent::MouseWheelEvent(JNIEnv* env, Component* source, std::int32_t id, std::int64_t when, std::int32_t modifiers, std::int32_t x, std::int32_t y, std::int32_t xAbs, std::int32_t yAbs, std::int32_t clickCount, bool popupTrigger, std::int32_t scrollType, std::int32_t scrollAmount, std::int32_t wheelRotation, double preciseWheelRotation) noexcept : AWTEvent(), env(env), cls(nullptr)
	{
		this->cls = env->FindClass("java/awt/event/MouseWheelEvent");
		env->DeleteLocalRef(std::exchange(this->cls, static_cast<jclass>(env->NewGlobalRef(this->cls))));

		static jmethodID methodId = env->GetMethodID(cls, "<init>", "(Ljava/awt/Component;IJIIIIIIZIIID)V");
		self = env->NewObject(cls, methodId, source->get(), id, when, modifiers, x, y, xAbs, yAbs, clickCount, popupTrigger, scrollType, scrollAmount, wheelRotation, preciseWheelRotation);
		env->DeleteLocalRef(std::exchange(self, static_cast<jclass>(env->NewGlobalRef(self))));
	}

	MouseWheelEvent::~MouseWheelEvent() noexcept
	{
		env->DeleteGlobalRef(cls);
		env->DeleteGlobalRef(self);
	}

	void MouseWheelEvent::Dispatch(JNIEnv* env, Component* receiver, Component* source, std::int32_t id, std::int64_t when, std::int32_t modifiers, std::int32_t x, std::int32_t y, std::int32_t xAbs, std::int32_t yAbs, std::int32_t clickCount, bool popupTrigger, std::int32_t scrollType, std::int32_t scrollAmount, std::int32_t wheelRotation, double preciseWheelRotation, bool is_system_generated) noexcept
	{
		jclass cls = env->FindClass("java/awt/event/MouseWheelEvent");
		if (cls)
		{
			static jmethodID methodId = env->GetMethodID(cls, "<init>", "(Ljava/awt/Component;IJIIIIIIZIIID)V");
			jobject event = env->NewObject(cls, methodId, source->get(), id, when, modifiers, x, y, xAbs, yAbs, clickCount, popupTrigger, scrollType, scrollAmount, wheelRotation, preciseWheelRotation);
			if (event)
			{
				AWTEvent::SetSystemGenerated(env, event, is_system_generated);
				receiver->dispatchEvent(event);
				env->DeleteLocalRef(event);
			}

			env->DeleteLocalRef(cls);
		}
	}

	void MouseWheelEvent::Post(JNIEnv* env, Component* source, std::int32_t id, std::int64_t when, std::int32_t modifiers, std::int32_t x, std::int32_t y, std::int32_t xAbs, std::int32_t yAbs, std::int32_t clickCount, bool popupTrigger, std::int32_t scrollType, std::int32_t scrollAmount, std::int32_t wheelRotation, double preciseWheelRotation, bool is_system_generated) noexcept
	{
		jclass cls = env->FindClass("java/awt/event/MouseWheelEvent");
		if (cls)
		{
			static jmethodID methodId = env->GetMethodID(cls, "<init>", "(Ljava/awt/Component;IJIIIIIIZIIID)V");
			jobject event = env->NewObject(cls, methodId, source->get(), id, when, modifiers, x, y, xAbs, yAbs, clickCount, popupTrigger, scrollType, scrollAmount, wheelRotation, preciseWheelRotation);
			if (event)
			{
				AWTEvent::Post(env, event, is_system_generated);
				env->DeleteLocalRef(event);
			}
			env->DeleteLocalRef(cls);
		}
	}
}