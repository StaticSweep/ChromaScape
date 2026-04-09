//
//  Mutex.cxx
//  RemoteInput
//
//  Created by Brandon on 2019-09-14.
//  Copyright © 2019 XIO. All rights reserved.
//

#include "Mutex.hxx"

#if !defined(_WIN32) && !defined(_WIN64) && !(_POSIX_TIMEOUTS >= 200112L)
int pthread_mutex_timedlock(pthread_mutex_t* mutex, const struct timespec* timeout)
{
    if (!timeout)
    {
        return pthread_mutex_trylock(mutex);
    }
    
    int retcode = 0;
    std::chrono::nanoseconds time_out = std::chrono::seconds(timeout->tv_sec) +
                                        std::chrono::nanoseconds(timeout->tv_nsec);

    auto start = std::chrono::high_resolution_clock::now();
    while ((retcode = pthread_mutex_trylock(mutex)) == EBUSY)
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


#if defined (_WIN32) || defined(_WIN64)
Mutex::Mutex() : hMutex(INVALID_HANDLE_VALUE)
{
    hMutex = CreateMutex(0, false, nullptr);

    if (!hMutex)
    {
        throw std::runtime_error("Cannot create mutex");
    }
}

Mutex::Mutex(const std::string &name) : hMutex(INVALID_HANDLE_VALUE)
{
    hMutex = OpenMutex(0, false, name.c_str());
    if (!hMutex)
    {
        hMutex = CreateMutex(0, false, name.c_str());
    }

    if (!hMutex)
    {
        throw std::runtime_error("Cannot create mutex");
    }
}

Mutex::~Mutex()
{
    CloseHandle(hMutex);
}
#else
Mutex::Mutex() : mutex(nullptr), ref(nullptr), mem_map()
{
    pthread_mutexattr_t attributes;
    if (pthread_mutexattr_init(&attributes))
    {
        throw std::runtime_error("Cannot allocated mutex");
    }

    if (pthread_mutexattr_setpshared(&attributes, PTHREAD_PROCESS_PRIVATE))
    {
        pthread_mutexattr_destroy(&attributes);
        throw std::runtime_error("Cannot set mutex private attributes");
    }

    mutex = new pthread_mutex_t();
    if (pthread_mutex_init(mutex, &attributes))
    {
        pthread_mutexattr_destroy(&attributes);
        delete mutex;
        throw std::runtime_error("Cannot create mutex");
    }

    pthread_mutexattr_destroy(&attributes);
}

Mutex::Mutex(const std::string &name) : mutex(nullptr), ref(nullptr), mem_map(std::make_unique<MemoryMap>(name, sizeof(pthread_mutex_t) + sizeof(std::int32_t)))
{
    bool created = false;
    if (!mem_map->open(MemoryMap::open_mode::read | MemoryMap::open_mode::write))
    {
        created = true;
        if (!mem_map->open(MemoryMap::open_mode::read | MemoryMap::open_mode::write | MemoryMap::open_mode::create))
        {
            perror(nullptr);
            throw std::runtime_error("Cannot create or open memory map");
        }
    }

    if (!mem_map->map())
    {
        throw std::runtime_error("Cannot map memory");
    }

    pthread_mutex_t* mutex = static_cast<pthread_mutex_t*>(mem_map->data());
    std::int32_t* ref = reinterpret_cast<std::int32_t*>(mutex + 1);

    if (created)
    {
        pthread_mutexattr_t attributes;
        if (pthread_mutexattr_init(&attributes))
        {
            throw std::runtime_error("Cannot create mutex attributes");
        }

        if (pthread_mutexattr_setpshared(&attributes, PTHREAD_PROCESS_SHARED))
        {
            pthread_mutexattr_destroy(&attributes);
            throw std::runtime_error("Cannot set mutex shared attributes");
        }

        if (pthread_mutex_init(mutex, &attributes))
        {
            pthread_mutexattr_destroy(&attributes);
            throw std::runtime_error("Cannot create mutex");
        }

        pthread_mutexattr_destroy(&attributes);
    }

    *ref += 1;
    this->mutex = mutex;
    this->ref = ref;
}

Mutex::~Mutex()
{
    if (mutex)
    {
        if (mem_map->is_open() && mem_map->is_mapped())
        {
            *ref -= 1;
            if (*ref == 0)
            {
                pthread_mutex_destroy(mutex);
                mem_map->close();
            }
            mem_map->unmap();
        }
        else
        {
            pthread_mutex_destroy(mutex);
            delete mutex;
        }
    }

    mutex = nullptr;
    ref = nullptr;
}
#endif

void Mutex::lock() const
{
    #if defined(_WIN32) || defined(_WIN64)
    if (WaitForSingleObject(hMutex, INFINITE) != WAIT_OBJECT_0)
    {
        throw std::system_error(GetLastError(), std::system_category());
    }
    #else
    if (pthread_mutex_lock(mutex))
    {
        throw std::system_error(errno, std::system_category());
    }
    #endif
}

void Mutex::unlock() const
{
    #if defined(_WIN32) || defined(_WIN64)
    if (!ReleaseMutex(hMutex))
    {
        throw std::system_error(GetLastError(), std::system_category());
    }
    #else
    if (pthread_mutex_unlock(mutex))
    {
        throw std::system_error(errno, std::system_category());
    }
    #endif
}

bool Mutex::try_lock() const noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    return WaitForSingleObject(hMutex, 0) == WAIT_OBJECT_0;
    #else
    return mutex && !pthread_mutex_trylock(mutex);
    #endif
}

bool Mutex::timed_lock(std::uint64_t nanoseconds) const noexcept
{
    if (!nanoseconds)
    {
        return try_lock();
    }

    #if defined(_WIN32) || defined(_WIN64)
    auto milli = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::nanoseconds(nanoseconds));
    return WaitForSingleObject(hMutex, milli.count()) == WAIT_OBJECT_0;
    #else
    auto duration = std::chrono::nanoseconds(nanoseconds);
    auto seconds = std::chrono::duration_cast<std::chrono::seconds>(duration);
    std::chrono::nanoseconds nano_seconds = (duration - seconds);

    struct timespec ts = { seconds.count(), nano_seconds.count() };
    return !pthread_mutex_timedlock(mutex, &ts);
    #endif
}
