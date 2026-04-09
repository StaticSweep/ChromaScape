#include "DetachedThreadPool.hxx"

DetachedThreadPool::DetachedThreadPool() noexcept : DetachedThreadPool(std::thread::hardware_concurrency())
{
}

DetachedThreadPool::DetachedThreadPool(std::size_t max_threads) noexcept : mutex(std::make_shared<std::mutex>()), condition(std::make_shared<std::condition_variable>()), tasks(std::make_shared<std::queue<std::function<void(std::atomic_bool& stopped)>>>()), threads(), stop(std::make_shared<std::atomic_bool>(false)), max_threads(max_threads)
{
    this->create_threads();
}

DetachedThreadPool::DetachedThreadPool(DetachedThreadPool&& other) noexcept : mutex(std::move(other.mutex)), condition(std::move(other.condition)), tasks(std::move(other.tasks)), threads(std::move(other.threads)), stop(std::move(other.stop)), max_threads(other.max_threads)
{
    other.max_threads = 0;
}

DetachedThreadPool::~DetachedThreadPool() noexcept
{
    if (max_threads == 0)
    {
        return;
    }

    this->terminate();
}

void DetachedThreadPool::create_threads() noexcept
{
    if (max_threads == 0)
    {
        return;
    }

    if (threads.size() != max_threads)
    {
        threads.reserve(max_threads);

        for (std::size_t i = 0; i < max_threads; ++i)
        {
            threads.emplace_back([mutex = this->mutex, condition = this->condition, tasks = this->tasks, stop = this->stop] {
                while(true)
                {
                    std::unique_lock<std::mutex> lock(*mutex);
                    condition->wait(lock, [tasks, stop] {
                        return !tasks->empty() || !stop || *stop;
                    });

                    if (!stop || *stop) //&& tasks->empty()
                    {
                        lock.unlock();
                        break;
                    }

                    auto task = std::move(tasks->front());
                    tasks->pop();

                    lock.unlock();

                    task(*stop.get());
                }
            });

            this->threads[i].detach();
        }
    }
}

void DetachedThreadPool::terminate() noexcept
{
    if (stop && !*stop)
    {
        std::unique_lock<std::mutex> lock(*mutex);
        *stop = true;
        lock.unlock();
        condition->notify_all();
        std::vector<std::thread>().swap(this->threads);
    }
}

void DetachedThreadPool::add_task(std::function<void(std::atomic_bool& stopped)> &&task)
{
    if (stop && !*stop)
    {
        std::unique_lock<std::mutex> lock(*mutex);
        tasks->emplace(task);
        lock.unlock();
        condition->notify_one();
    }
    else
    {
        throw std::runtime_error("DetachedThreadPool is currently shutting down. Cannot enqueue more tasks.");
    }
}
