//
//  Semaphore.cxx
//  RemoteInput
//
//  Created by Brandon on 2019-09-14.
//  Copyright © 2019 XIO. All rights reserved.
//

#ifndef SEMAPHORE_HXX_INCLUDED
#define SEMAPHORE_HXX_INCLUDED

#include <ctime>
#include <cstdint>
#include <string>
#include <chrono>
#include <memory>
#include <thread>

#if defined(_WIN32) || defined(_WIN64)
#include <windows.h>
#endif

#if defined(__APPLE__)
#include <dispatch/dispatch.h>
#endif

#if !defined(_WIN32) && !defined(_WIN64)
#include <pthread.h>
#include <semaphore.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <errno.h>
#include <unistd.h>
#endif

#include "MemoryMap.hxx"

class Semaphore
{
private:
    #if defined(_WIN32) || defined(_WIN64)
    HANDLE hSemaphore;
    #else
    bool shared;
    bool owned;
    void* hSem;
    std::string name;
    #endif

    bool timed_acquire(std::uint64_t nanoseconds) const noexcept;

public:
    Semaphore(std::int32_t count = 0);
    Semaphore(const std::string &name, std::int32_t count = 0);
    ~Semaphore();

    Semaphore(const Semaphore &other) = delete;
    Semaphore& operator = (const Semaphore &other) = delete;

    void acquire() const;
    void release() const;
    
    bool try_acquire() const noexcept;

    template<typename Rep, typename Period>
    bool try_acquire_for(const std::chrono::duration<Rep, Period>& duration) const noexcept
    {
        if (duration == std::chrono::duration<Rep, Period>::zero())
        {
            return try_acquire();
        }
        
        return timed_acquire(std::chrono::duration_cast<std::chrono::nanoseconds>(duration));
    }

    template<typename Clock, typename Duration>
    bool try_acquire_until(const std::chrono::time_point<Clock, Duration>& absolute_time) const noexcept
    {
        auto const current = Clock::now();
        if (current >= absolute_time)
        {
            return try_acquire();
        }
        
        return try_acquire_for(absolute_time - current);
    }
};

#endif //SEMAPHORE_HXX_INCLUDED
