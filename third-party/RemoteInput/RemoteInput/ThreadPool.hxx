#ifndef THREADPOOL_HXX_INCLUDED
#define THREADPOOL_HXX_INCLUDED

#include <thread>
#include <future>
#include <mutex>
#include <atomic>
#include <queue>
#include <functional>
#include <condition_variable>
#include <stdexcept>
#include <vector>

class ThreadPool final
{
private:
    std::mutex mutex;
    std::condition_variable condition;
    std::queue<std::function<void(std::atomic_bool& stopped)>> tasks;
    std::vector<std::thread> threads;
    std::atomic_bool stop;

public:
    ThreadPool() noexcept;
    ThreadPool(std::size_t max_threads) noexcept;
    ~ThreadPool() noexcept;
    ThreadPool(const ThreadPool&) = delete;
    ThreadPool& operator = (const ThreadPool&) = delete;

    void terminate() noexcept;

    void add_task(std::function<void(std::atomic_bool&)> &&task);

    template<typename Task, typename... Args>
    auto enqueue(Task &&task, Args&&... args) noexcept -> std::future<std::invoke_result_t<Task, Args...>>;
};

template<typename Task, typename... Args>
auto ThreadPool::enqueue(Task &&task, Args&&... args) noexcept -> std::future<std::invoke_result_t<Task, Args...>>
{
    auto packaged_task = std::make_shared<std::packaged_task<std::invoke_result_t<Task, Args...>(Args&&...)>>(
        std::bind(std::forward<Task>(task), std::forward<Args>(args)...)
    );

    std::future<std::invoke_result_t<Task, Args...>> result = packaged_task->get_future();
    std::unique_lock<std::mutex> lock(this->mutex);
    if (this->stop)
    {
        throw std::runtime_error("ThreadPool is currently shutting down. Cannot enqueue more tasks.");
    }

    this->tasks.emplace([packaged_task] {
        (*packaged_task)();
    });

    condition.notify_one();
    return result;
}

#endif // THREADPOOL_HXX_INCLUDED
