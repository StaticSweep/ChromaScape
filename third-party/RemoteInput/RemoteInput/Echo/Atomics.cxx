//
// Created by Brandon on 2023-12-30.
//

#include "Atomics.hxx"

bool back_off(std::chrono::nanoseconds elapsed)
{
    if (elapsed > std::chrono::milliseconds(128))
    {
        std::this_thread::sleep_for(std::chrono::milliseconds(8));
    }
    else if (elapsed > std::chrono::microseconds(64))
    {
        std::this_thread::sleep_for(elapsed / 2);
    }
    else if (elapsed > std::chrono::microseconds(4))
    {
        std::this_thread::yield();
    }
    return false;
}

template<typename T, typename TestFn, typename BackoffFn>
bool wait_polling(T* self, TestFn&& test, BackoffFn &&back_off, std::chrono::nanoseconds max_elapsed = std::chrono::nanoseconds::zero())
{
    int count = 0;
    const auto start = std::chrono::high_resolution_clock::now();
    while (true)
    {
        if (test(self))
        {
            return true;
        }

        if (count < 64)
        {
            ++count;
            continue;
        }

        const std::chrono::nanoseconds elapsed = std::chrono::high_resolution_clock::now() - start;
        if (max_elapsed != std::chrono::nanoseconds::zero() && max_elapsed < elapsed)
        {
            return false;
        }

        if (back_off(elapsed))
        {
            return false;
        }
    }
}

bool counting_semaphore::wait_polling(counting_semaphore* self, bool (*test)(counting_semaphore* self), std::chrono::nanoseconds max_elapsed)
{
    return ::wait_polling(self, test, back_off, max_elapsed);
}

void counting_semaphore::release(std::ptrdiff_t amount)
{
    if (counter.fetch_add(amount, std::memory_order_release) >= 0)
    {

    }
    else if(amount > 1)
    {
        counter.notify_all();
    }
    else
    {
        counter.notify_one();
    }
}

void counting_semaphore::acquire()
{
    #if !defined(USE_ATOMIC_POLLING)
    const auto test = [](counting_semaphore* self) -> bool {
        auto old = self->counter.load(std::memory_order_relaxed);
        if (old != 0)
        {
            return self->counter.compare_exchange_strong(old, old - 1, std::memory_order_acquire, std::memory_order_relaxed);
        }
        return false;
    };

    wait_polling(this, test);
    #else
    auto old = counter.load(std::memory_order_relaxed);
    while (old == 0 || !counter.compare_exchange_strong(old, old - 1, std::memory_order_acquire, std::memory_order_relaxed))
    {
        old = counter.load(std::memory_order_relaxed);

        #if defined(USE_ATOMIC_WAIT)
        counter.wait(old - 1);
        #else
        std::this_thread::yield();
        #endif
    }
    #endif
}

bool counting_semaphore::try_acquire()
{
    auto old = counter.load(std::memory_order_acquire);
    while (true)
    {
        if (old == 0)
        {
            return false;
        }

        if (counter.compare_exchange_strong(old, old - 1, std::memory_order_acquire, std::memory_order_relaxed))
        {
            return true;
        }
    }
}

bool binary_semaphore::wait_polling(binary_semaphore* self, bool (*test)(binary_semaphore*), std::chrono::nanoseconds max_elapsed)
{
    return ::wait_polling(self, test, back_off, max_elapsed);
}

void binary_semaphore::release()
{
    #if defined(USE_ATOMIC_BOOL)
    flag = false;
    flag.notify_one();
    #else
    flag.clear();
    flag.notify_one();
    #endif
}

void binary_semaphore::acquire()
{
    #if defined(USE_ATOMIC_BOOL)
    bool old;
    while (!flag.compare_exchange_weak(old = false, true))
    {
        #if defined(USE_ATOMIC_WAIT)
        flag.wait(old);
        #else
        std::this_thread::yield();
        #endif
    }
    #else
    bool old;
    while ((old = flag.test_and_set(std::memory_order_acquire)))
    {
        #if defined(USE_ATOMIC_WAIT)
        flag.wait(old, std::memory_order_relaxed);
        #else
        std::this_thread::yield();
        #endif
    }
    #endif
}

bool binary_semaphore::try_acquire()
{
    #if defined(USE_ATOMIC_BOOL)
    bool old = false;
    return flag.compare_exchange_weak(old, true);
    #else
    return !flag.test_and_set(std::memory_order_acquire);
    #endif
}

bool atomic_lock::wait_polling(atomic_lock* self, bool (*test)(atomic_lock*), std::chrono::nanoseconds max_elapsed)
{
    return ::wait_polling(self, test, back_off, max_elapsed);
}

atomic_lock::atomic_lock() : flag(false) {}

void atomic_lock::lock()
{
    while (flag.test_and_set(std::memory_order_acquire))
    {
        #if defined(USE_ATOMIC_WAIT)
        flag.wait(false, std::memory_order_relaxed);
        #else
        std::this_thread::yield();
        #endif
    }
}

void atomic_lock::unlock()
{
    flag.clear(std::memory_order_release);
}

bool atomic_lock::try_lock()
{
    return flag.test_and_set(std::memory_order_acquire);
}