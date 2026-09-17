//
//  Toolkit.cxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#include "Toolkit.hxx"
#include <utility>

namespace java
{
	Toolkit::Toolkit(JNIEnv* env, jclass cls, jobject toolkit) noexcept : env(env), cls(cls), toolkit(toolkit)
	{
		if (!this->cls)
		{
			this->cls = env->GetObjectClass(toolkit);
		}

		env->DeleteLocalRef(std::exchange(this->cls, static_cast<jclass>(env->NewGlobalRef(this->cls))));
		env->DeleteLocalRef(std::exchange(this->toolkit, env->NewGlobalRef(this->toolkit)));
	}

	Toolkit::~Toolkit() noexcept
	{
		if (cls)
		{
			env->DeleteGlobalRef(cls);
		}

		if (toolkit)
		{
			env->DeleteGlobalRef(toolkit);
		}
	}

	Toolkit::Toolkit(Toolkit&& other) noexcept : env(other.env), cls(other.cls), toolkit(other.toolkit)
	{
		other.env = nullptr;
		other.cls = nullptr;
		other.toolkit = nullptr;
	}

	Toolkit& Toolkit::operator = (Toolkit&& other) noexcept
	{
		this->env = other.env;
		this->cls = other.cls;
		this->toolkit = other.toolkit;
		other.env = nullptr;
		other.cls = nullptr;
		other.toolkit = nullptr;
		return *this;
	}

	Toolkit Toolkit::getDefaultToolkit(JNIEnv* env) noexcept
	{
		jclass cls = env->FindClass("java/awt/Toolkit");
		static jmethodID methodId = env->GetStaticMethodID(cls, "getDefaultToolkit", "()Ljava/awt/Toolkit;");
		jobject toolkit = env->CallStaticObjectMethod(cls, methodId);
		return Toolkit{env, cls, toolkit};
	}

	EventQueue Toolkit::getSystemEventQueue() const noexcept
	{
		static jmethodID methodId = env->GetMethodID(cls, "getSystemEventQueue", "()Ljava/awt/EventQueue;");
		jobject queue = env->CallObjectMethod(toolkit, methodId);
		return EventQueue(env, env->FindClass("java/awt/EventQueue"), queue);
	}
}