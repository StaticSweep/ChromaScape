//
// Created by Brandon on 2023-12-30.
//

#include "Event.hxx"

bool atomic_signal_timedlock(std::atomic_flag* flag, const struct timespec* timeout)
{
    if (!timeout)
    {
        return flag->test_and_set(std::memory_order_acquire);
    }

    std::chrono::nanoseconds time_out = std::chrono::seconds(timeout->tv_sec) +
                                        std::chrono::nanoseconds(timeout->tv_nsec);

    auto start = std::chrono::high_resolution_clock::now();
    while (!flag->test_and_set(std::memory_order_acquire))
    {
        std::chrono::nanoseconds elapsed_time = std::chrono::high_resolution_clock::now() - start;

        if (elapsed_time >= time_out)
        {
            errno = EAGAIN;
            return false;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }

    return true;
}

#if defined(_WIN32) || defined(_WIN64)
Event::Event() : hEvent(nullptr)
{
    hEvent = CreateEvent(nullptr, true, false, nullptr);
}

Event::Event(std::string name) : hEvent(nullptr)
{
    hEvent = OpenEvent(0, false, name.c_str());
    if (!hEvent)
    {
        hEvent = CreateEvent(nullptr, true, false, name.c_str());
    }

    if (!hEvent)
    {
        throw std::runtime_error("Cannot Create or Open Event");
    }
}
#else
Event::Event() : flag(new std::atomic_flag(false)), ref(nullptr), mem_map()
{
}

Event::Event(std::string name) : flag(nullptr), ref(nullptr), mem_map(std::make_unique<MemoryMap>(name, sizeof(std::atomic_flag) + sizeof(std::int32_t), MemoryMap::destroy_mode::unmap))
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

    std::atomic_flag* flag = static_cast<std::atomic_flag*>(mem_map->data());
    std::int32_t* ref = reinterpret_cast<std::int32_t*>(flag + 1);

    if (created)
    {
        std::construct_at(flag, false);
        std::construct_at(ref, 0);
    }

    *ref += 1;
    this->flag = flag;
    this->ref = ref;
    msync(ref, sizeof(*ref), MS_SYNC);
}
#endif

Event::~Event()
{
    #if defined(_WIN32) || defined(_WIN64)
    CloseHandle(hEvent);
    #else
    if (mem_map && flag && mem_map->is_open() && mem_map->is_mapped())
    {
        *ref -= 1;
        msync(ref, sizeof(*ref), MS_SYNC);

        if (*ref == 0)
        {
            std::destroy_at(ref);
            std::destroy_at(flag);
            mem_map->close();
        }
        mem_map->unmap();

        ref = nullptr;
        flag = nullptr;
    }

    flag = nullptr;
    ref = nullptr;
    #endif
}

bool Event::is_signalled() const noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    return WaitForSingleObjectEx(hEvent, 0, true) == WAIT_OBJECT_0;
    #else
    return flag->test(std::memory_order_relaxed);
    #endif
}

void Event::wait() const noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    WaitForSingleObject(hEvent, INFINITE); // == WAIT_OBJECT_0;
    #else
    while (flag->test_and_set(std::memory_order_acquire))
    {
        #if defined(USE_ATOMIC_WAIT)
        flag->wait(false, std::memory_order_relaxed);
        #else
        std::this_thread::yield();
        #endif
    }
    #endif
}

void Event::signal() const noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    SetEvent(hEvent);
    #else
    flag->clear(std::memory_order_release);
    #endif
}

bool Event::try_wait() const noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    return WaitForSingleObject(hEvent, 0) == WAIT_OBJECT_0;
    #else
    return flag->test_and_set(std::memory_order_acquire);
    #endif
}

bool Event::timed_wait(std::uint64_t nanoseconds) const noexcept
{
    if (!nanoseconds)
    {
        return try_wait();
    }

    #if defined(_WIN32) || defined(_WIN64)
    auto milli = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::nanoseconds(nanoseconds));
    return WaitForSingleObject(hEvent, milli.count()) == WAIT_OBJECT_0;
    #else
    auto duration = std::chrono::nanoseconds(nanoseconds);
    auto seconds = std::chrono::duration_cast<std::chrono::seconds>(duration);
    std::chrono::nanoseconds nano_seconds = (duration - seconds);

    struct timespec ts = { seconds.count(), nano_seconds.count() };
    return atomic_signal_timedlock(flag, &ts);
    #endif
}