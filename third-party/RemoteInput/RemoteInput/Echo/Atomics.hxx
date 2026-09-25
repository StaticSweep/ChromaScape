//
// Created by Brandon on 2023-12-30.
//

#ifndef REMOTEINPUT_ATOMICS_HXX
#define REMOTEINPUT_ATOMICS_HXX

#include <atomic>
#include <chrono>
#include <thread>

class counting_semaphore
{
private:
    std::atomic_ptrdiff_t counter;

    static bool wait_polling(counting_semaphore* self, bool (*test)(counting_semaphore*), std::chrono::nanoseconds max_elapsed = std::chrono::nanoseconds::zero());

public:
    constexpr explicit counting_semaphore(std::ptrdiff_t count) : counter(count) {}
    ~counting_semaphore() = default;

    counting_semaphore(const counting_semaphore&) = delete;
    counting_semaphore& operator=(const counting_semaphore&) = delete;

    void release(std::ptrdiff_t amount = 1);
    void acquire();
    bool try_acquire();

    template<class Rep, class Period>
    bool try_acquire_for(const std::chrono::duration<Rep, Period> &relative_time)
    {
        auto nano_seconds = std::chrono::duration_cast<std::chrono::nanoseconds>(relative_time);
        if (nano_seconds == std::chrono::duration<Rep, Period>::zero())
        {
            return try_acquire();
        }

        const auto test = [](counting_semaphore* self) { return self->try_acquire(); };
        return wait_polling(this, test, nano_seconds);
    }

    template <class Clock, class Duration>
    bool try_acquire_until(const std::chrono::time_point<Clock, Duration> &absolute_time)
    {
        auto const current = Clock::now();
        if (current >= absolute_time)
        {
            return try_acquire();
        }
        else
        {
            return try_acquire_for(absolute_time - current);
        }
    }
};

class binary_semaphore
{
private:
    #if defined(USE_ATOMIC_BOOL)
    std::atomic_bool flag;
    #else
    std::atomic_flag flag;
    #endif

    static bool wait_polling(binary_semaphore* self, bool (*test)(binary_semaphore*), std::chrono::nanoseconds max_elapsed = std::chrono::nanoseconds::zero());

public:
    constexpr explicit binary_semaphore(bool is_set = false) : flag(!is_set) {}
    ~binary_semaphore() = default;

    binary_semaphore(const binary_semaphore&) = delete;
    binary_semaphore& operator=(const binary_semaphore&) = delete;

    void release();
    void acquire();
    bool try_acquire();

    template<class Rep, class Period>
    bool try_acquire_for(const std::chrono::duration<Rep, Period> &relative_time)
    {
        if (relative_time == std::chrono::duration<Rep, Period>::zero())
        {
            return try_acquire();
        }

        const auto test = [](binary_semaphore* self) { return self->try_acquire(); };
        return wait_polling(this, test, std::chrono::duration_cast<std::chrono::nanoseconds>(relative_time));
    }

    template <class Clock, class Duration>
    bool try_acquire_until(const std::chrono::time_point<Clock, Duration> &absolute_time)
    {
        const auto current = Clock::now();
        if (current >= absolute_time)
        {
            return try_acquire();
        }
        else
        {
            return try_acquire_for(absolute_time - current);
        }
    }
};

struct atomic_lock
{
private:
    std::atomic_flag flag;

    static bool wait_polling(atomic_lock* self, bool (*test)(atomic_lock*), std::chrono::nanoseconds max_elapsed = std::chrono::nanoseconds::zero());

public:
    atomic_lock();
    ~atomic_lock() = default;

    void lock();
    void unlock();

    bool try_lock();

    template<class Rep, class Period>
    bool try_lock_for(const std::chrono::duration<Rep, Period> &relative_time)
    {
        if (relative_time == std::chrono::duration<Rep, Period>::zero())
        {
            return try_lock();
        }

        const auto test = [](atomic_lock* self) { return self->try_lock(); };
        return wait_polling(this, test, std::chrono::duration_cast<std::chrono::nanoseconds>(relative_time));
    }

    template <class Clock, class Duration>
    bool try_lock_until(const std::chrono::time_point<Clock, Duration> &absolute_time)
    {
        const auto current = Clock::now();
        if (current >= absolute_time)
        {
            return try_lock();
        }
        else
        {
            return try_lock_for(absolute_time - current);
        }
    }
};

#endif //REMOTEINPUT_ATOMICS_HXX
