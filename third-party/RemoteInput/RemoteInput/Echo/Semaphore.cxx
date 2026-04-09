//
//  Semaphore.cxx
//  RemoteInput
//
//  Created by Brandon on 2019-09-14.
//  Copyright © 2019 XIO. All rights reserved.
//

#include "Semaphore.hxx"

#if !defined(_WIN32) && !defined(_WIN64) && !(_POSIX_TIMEOUTS >= 200112L)
int sem_timedwait(sem_t* sem, const struct timespec* timeout)
{
    if (!timeout)
    {
        return sem_trywait(sem);
    }
    
    int retcode = 0;
    std::chrono::nanoseconds time_out = std::chrono::seconds(timeout->tv_sec) +
                                        std::chrono::nanoseconds(timeout->tv_nsec);

    auto start = std::chrono::high_resolution_clock::now();
    while ((retcode = sem_trywait(sem)) != 0)
    {
        std::chrono::nanoseconds elapsed_time = std::chrono::high_resolution_clock::now() - start;
        
        if (elapsed_time >= time_out)
        {
            errno = EAGAIN;
            return -1;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }

    return retcode;
}
#endif

#if defined(_WIN32) || defined(_WIN64)
Semaphore::Semaphore(std::int32_t count)
{
    hSemaphore = OpenSemaphore(SEMAPHORE_ALL_ACCESS, false, nullptr);
    if (!hSemaphore)
    {
        hSemaphore = CreateSemaphore(nullptr, count, LONG_MAX, nullptr);
    }

    if (!hSemaphore)
    {
        throw std::runtime_error("Cannot create semaphore");
    }
}

Semaphore::Semaphore(const std::string &name, std::int32_t count)
{
    hSemaphore = OpenSemaphore(SEMAPHORE_ALL_ACCESS, false, name.c_str());
    if (!hSemaphore)
    {
        hSemaphore = CreateSemaphore(nullptr, count, LONG_MAX, name.c_str());
    }

    if (!hSemaphore)
    {
        throw std::runtime_error("Cannot create semaphore");
    }
}

Semaphore::~Semaphore()
{
    CloseHandle(hSemaphore);
}
#else
Semaphore::Semaphore(std::int32_t count) : shared(false), owned(true), hSem(SEM_FAILED), name()
{
    #if defined(__APPLE__)
    hSem = dispatch_semaphore_create(count);
    if (hSem == nullptr)
    {
        throw std::runtime_error("Cannot create or open semaphore");
    }
    #else
    hSem = new sem_t();
    if (sem_init(static_cast<sem_t*>(hSem), false, count) == -1)
    {
        throw std::runtime_error("Cannot initialize semaphore");
    }

    if (hSem == SEM_FAILED)
    {
        throw std::runtime_error("Cannot create or open semaphore");
    }
    #endif
}

Semaphore::Semaphore(const std::string &name, std::int32_t count) : shared(true), owned(true), hSem(SEM_FAILED), name(name)
{
    hSem = sem_open(name.c_str(), O_CREAT | O_EXCL | O_RDWR, S_IRWXU, count);
    if (hSem == SEM_FAILED && errno == EEXIST)
    {
        owned = false;
        hSem = sem_open(name.c_str(), O_RDWR, S_IRWXU, count);
    }

    if (hSem == SEM_FAILED)
    {
        throw std::runtime_error("Cannot create or open semaphore");
    }
}

Semaphore::~Semaphore()
{
    #if defined(__APPLE__)
    if (owned && !shared && hSem != nullptr)
    {
        dispatch_release(static_cast<dispatch_semaphore_t>(hSem));
        return;
    }
    #endif

    if (hSem != SEM_FAILED)
    {
        if (!shared && name.empty())
        {
            #if !defined(__APPLE__)
            sem_destroy(static_cast<sem_t*>(hSem));
            #endif
            delete static_cast<sem_t*>(hSem);
            hSem = SEM_FAILED;
        }
        else
        {
            sem_close(static_cast<sem_t*>(hSem));
            hSem = SEM_FAILED;
        }

        if (owned && shared && !name.empty())
        {
            sem_unlink(name.c_str());
        }
    }
}
#endif

void Semaphore::acquire() const
{
    #if defined(_WIN32) || defined(_WIN64)
    if (WaitForSingleObject(hSemaphore, INFINITE) != WAIT_OBJECT_0)
    {
        throw std::system_error(GetLastError(), std::system_category());
    }
    #elif defined(__APPLE__)
    if (owned && !shared)
    {
        if (!dispatch_semaphore_wait(static_cast<dispatch_semaphore_t>(hSem), DISPATCH_TIME_FOREVER))
        {
            throw std::system_error(errno, std::system_category());
        }
    }
    #else
    if (sem_wait(static_cast<sem_t*>(hSem)))
    {
        throw std::system_error(errno, std::system_category());
    }
    #endif
}

void Semaphore::release() const
{
    #if defined(_WIN32) || defined(_WIN64)
    if (!ReleaseSemaphore(hSemaphore, 1, nullptr))
    {
        throw std::system_error(GetLastError(), std::system_category());
    }
    #elif defined(__APPLE__)
    if (owned && !shared)
    {
        if (dispatch_semaphore_signal(static_cast<dispatch_semaphore_t>(hSem)))
        {
            throw std::system_error(errno, std::system_category());
        }
    }
    #else
    if (sem_post(static_cast<sem_t*>(hSem)))
    {
        throw std::system_error(errno, std::system_category());
    }
    #endif
}

bool Semaphore::try_acquire() const noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    return WaitForSingleObject(hSemaphore, 0) == WAIT_OBJECT_0;
    #elif defined(__APPLE__)
    if (owned && !shared)
    {
        return !dispatch_semaphore_wait(static_cast<dispatch_semaphore_t>(hSem), DISPATCH_TIME_NOW);
    }
    #else
    return !sem_trywait(static_cast<sem_t*>(hSem));
    #endif
}

bool Semaphore::timed_acquire(std::uint64_t nanoseconds) const noexcept
{
    if (!nanoseconds)
    {
        return try_acquire();
    }

    #if defined(_WIN32) || defined(_WIN64)
    auto milli = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::nanoseconds(nanoseconds));
    return WaitForSingleObject(hSemaphore, milli.count()) == WAIT_OBJECT_0;
    #elif defined(__APPLE__)
    if (owned && !shared)
    {
        auto milli = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::nanoseconds(nanoseconds));
        dispatch_time_t time_out = dispatch_time(DISPATCH_TIME_NOW, milli.count());
        return !dispatch_semaphore_wait(static_cast<dispatch_semaphore_t>(hSem), time_out);
    }
    #else
    auto duration = std::chrono::nanoseconds(nanoseconds);
    auto seconds = std::chrono::duration_cast<std::chrono::seconds>(duration);
    std::chrono::nanoseconds nano_seconds = (duration - seconds);

    struct timespec ts = { seconds.count(), nano_seconds.count() };
    return !sem_timedwait(static_cast<sem_t*>(hSem), &ts);
    #endif
}
