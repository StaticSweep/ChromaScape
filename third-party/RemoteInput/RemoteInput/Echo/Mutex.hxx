//
//  Mutex.hxx
//  RemoteInput
//
//  Created by Brandon on 2019-09-14.
//  Copyright © 2019 XIO. All rights reserved.
//

#ifndef MUTEX_HXX_INCLUDED
#define MUTEX_HXX_INCLUDED

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

class Mutex
{
private:
    #if defined(_WIN32) || defined(_WIN64)
    HANDLE hMutex;
    #else
    pthread_mutex_t* mutex;
    std::int32_t* ref;
    std::unique_ptr<MemoryMap> mem_map;
    #endif

    bool timed_lock(std::uint64_t nanoseconds) const noexcept;

public:
    Mutex();
    Mutex(const std::string &name);
    ~Mutex();

    Mutex(const Mutex &other) = delete;
    Mutex& operator = (const Mutex &other) = delete;

    void lock() const;
    void unlock() const;
    
    bool try_lock() const noexcept;

    template<typename Rep, typename Period>
    bool try_lock_for(const std::chrono::duration<Rep, Period>& duration) const noexcept
    {
        if (duration == std::chrono::duration<Rep, Period>::zero())
        {
            return try_lock();
        }
        
        return timed_lock(std::chrono::duration_cast<std::chrono::nanoseconds>(duration));
    }

    template<typename Clock, typename Duration>
    bool try_lock_until(const std::chrono::time_point<Clock, Duration>& absolute_time) const noexcept
    {
        auto const current = Clock::now();
        if (current >= absolute_time)
        {
            return try_lock();
        }
        
        return try_lock_for(absolute_time - current);
    }
};

#endif /* MUTEX_HXX_INCLUDED */
