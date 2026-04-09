//
//  ReflectionHook.cxx
//  RemoteInput
//
//  Created by Brandon on 2019-12-01.
//  Copyright © 2019 XIO. All rights reserved.
//

#include "ReflectionHook.hxx"

ReflectionHook::ReflectionHook(jobject object, std::string cls, std::string field, std::string desc) noexcept : object(object), cls(cls), field(field), desc(desc)
{}

Stream& operator << (Stream& stream, const ReflectionHook &hook)
{
    return stream << hook.object << hook.cls << hook.field << hook.desc;
}

Stream& operator >> (Stream& stream, ReflectionHook &hook)
{
    return stream >> hook.object >> hook.cls >> hook.field >> hook.desc;
}