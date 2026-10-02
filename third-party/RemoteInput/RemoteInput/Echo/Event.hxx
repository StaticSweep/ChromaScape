//
// Created by Brandon on 2023-12-30.
//

#ifndef EVENT_HXX_INCLUDED
#define EVENT_HXX_INCLUDED

#include <atomic>
#include <chrono>
#include <thread>
#include <string>
#include <new>

#if defined(_WIN32) || defined(_WIN64)
#include <windows.h>
#endif

#include "MemoryMap.hxx"

class Event
{
private:
    #if defined(_WIN32) || defined(_WIN64)
    HANDLE hEvent;
    #else
    std::atomic_flag* flag;
    std::int32_t* ref;
    std::unique_ptr<MemoryMap> mem_map;
    #endif

    bool timed_wait(std::uint64_t nanoseconds) const noexcept;

public:
    Event();
    Event(std::string name);
    ~Event();

    bool is_signalled() const noexcept;

    void wait() const noexcept;
    void signal() const noexcept;

    bool try_wait() const noexcept;

    template<typename Rep, typename Period>
    bool try_wait_for(const std::chrono::duration<Rep, Period>& relative_time) const noexcept
    {
        auto nano_seconds = std::chrono::duration_cast<std::chrono::nanoseconds>(relative_time);
        if (nano_seconds == std::chrono::duration<Rep, Period>::zero())
        {
            return try_wait();
        }

        return timed_wait(nano_seconds.count());
    }

    template<typename Clock, typename Duration>
    bool try_wait_until(const std::chrono::time_point<Clock, Duration>& absolute_time) const noexcept
    {
        const auto current = Clock::now();
        if (current >= absolute_time)
        {
            return try_wait();
        }
        else
        {
            return try_wait_for(absolute_time - current);
        }
    }
};

#endif //EVENT_HXX_INCLUDED
