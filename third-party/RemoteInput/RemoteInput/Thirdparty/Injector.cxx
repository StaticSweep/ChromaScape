//
// Created by brandon on 04/11/24.
//

#include "Injector.hxx"
#include "kubo_injector/include/injector.h"

Injector::Injector(std::int32_t pid) : pid(pid), injector(nullptr), handle(nullptr)
{
    if (injector_attach(reinterpret_cast<injector_t**>(&injector), pid) != 0)
    {
        injector = nullptr;
    }
}

Injector::~Injector()
{
    if (injector)
    {
        injector_detach(reinterpret_cast<injector_t*>(injector));
    }

    handle = nullptr;
    injector = nullptr;
}

bool Injector::Inject(std::string module_path) noexcept
{
    if (injector && !handle)
    {
        return injector_inject(static_cast<injector_t*>(injector), module_path.c_str(), &handle) == 0;
    }
    return false;
}

bool Injector::UnInject() noexcept
{
    if (injector && handle)
    {
        if (injector_uninject(static_cast<injector_t*>(injector), handle) == 0)
        {
            handle = nullptr;
            return true;
        }
    }
    return false;
}

std::int32_t Injector::get_pid() const noexcept
{
    return pid;
}

bool Injector::is_injected() const noexcept
{
    return injector && handle;
}