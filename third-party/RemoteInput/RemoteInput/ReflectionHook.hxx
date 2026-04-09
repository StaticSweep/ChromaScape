//
//  ReflectionHook.hxx
//  RemoteInput
//
//  Created by Brandon on 2019-12-01.
//  Copyright © 2019 XIO. All rights reserved.
//

#ifndef ReflectionHook_HXX_INCLUDED
#define ReflectionHook_HXX_INCLUDED

#include <cstring>
#include <string>

#include "JNI_Common.hxx"
#include "Stream.hxx"
#include "TypeTraits.hxx"

struct ReflectionHook
{
    jobject object;
    std::string cls;
    std::string field;
    std::string desc;
    
    ReflectionHook(jobject object = nullptr, std::string cls = "", std::string field = "", std::string desc = "") noexcept;
};

Stream& operator << (Stream& stream, const ReflectionHook &hook);
Stream& operator >> (Stream& stream, ReflectionHook &hook);

template<>
struct std::hash<ReflectionHook>
{
    std::size_t operator()(const ReflectionHook& hook) const noexcept
    {
        std::size_t hash = 0;
        hash_combine(hash, hook.cls);
        hash_combine(hash, hook.field);
        hash_combine(hash, hook.desc);
        return hash;
    }
};

#endif /* ReflectionHook_HXX_INCLUDED */
