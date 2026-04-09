//
//  KeyEvent.cxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#include "KeyEvent.hxx"
#include <utility>

namespace java
{
	KeyEvent::KeyEvent(JNIEnv* env, Component* source, std::int32_t id, std::int64_t when, std::int32_t modifiers, std::int32_t keyCode, jchar keyChar, std::int32_t keyLocation) noexcept : AWTEvent(), env(env), cls(nullptr)
	{
		this->cls = env->FindClass("java/awt/event/KeyEvent");
		env->DeleteLocalRef(std::exchange(this->cls, static_cast<jclass>(env->NewGlobalRef(this->cls))));

		static jmethodID methodId = env->GetMethodID(cls, "<init>", "(Ljava/awt/Component;IJIICI)V");
		self = env->NewObject(cls, methodId, source->get(), id, when, modifiers, keyCode, keyChar, keyLocation);
		env->DeleteLocalRef(std::exchange(self, static_cast<jclass>(env->NewGlobalRef(self))));
	}

	KeyEvent::~KeyEvent() noexcept
	{
		env->DeleteGlobalRef(cls);
		env->DeleteGlobalRef(self);
	}

	void KeyEvent::Dispatch(JNIEnv* env, Component* receiver, Component* source, std::int32_t id, std::int64_t when, std::int32_t modifiers, std::int32_t keyCode, jchar keyChar, std::int32_t keyLocation, bool is_system_generated) noexcept
	{
		jclass cls = env->FindClass("java/awt/event/KeyEvent");
		if (cls)
		{
			static jmethodID methodId = env->GetMethodID(cls, "<init>", "(Ljava/awt/Component;IJIICI)V");
			jobject event = env->NewObject(cls, methodId, source->get(), id, when, modifiers, keyCode, keyChar, keyLocation);
			if (event)
			{
				AWTEvent::SetSystemGenerated(env, event, is_system_generated);
				receiver->dispatchEvent(event);
				env->DeleteLocalRef(event);
			}

			env->DeleteLocalRef(cls);
		}
	}

	void KeyEvent::Post(JNIEnv* env, Component* source, std::int32_t id, std::int64_t when, std::int32_t modifiers, std::int32_t keyCode, jchar keyChar, std::int32_t keyLocation, bool is_system_generated) noexcept
	{
		jclass cls = env->FindClass("java/awt/event/KeyEvent");
		if (cls)
		{
			static jmethodID methodId = env->GetMethodID(cls, "<init>", "(Ljava/awt/Component;IJIICI)V");
			jobject event = env->NewObject(cls, methodId, source->get(), id, when, modifiers, keyCode, keyChar, keyLocation);
			if (event)
			{
				AWTEvent::Post(env, event, is_system_generated);
				env->DeleteLocalRef(event);
			}
			env->DeleteLocalRef(cls);
		}
	}
}