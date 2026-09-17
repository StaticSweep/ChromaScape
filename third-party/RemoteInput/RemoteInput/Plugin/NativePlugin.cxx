//
// Created by Brandon on 2022-09-21.
//

#include "NativePlugin.hxx"

#include <cstring>
#include <unordered_map>
#include <unordered_set>

#include "Platform.hxx"
#include "ControlCenter.hxx"

// MARK: - EXPORTS

void EIOS_Inject(const char* process_name) noexcept
{
    if (process_name)
    {
        std::vector<std::int32_t> pids = InjectProcesses(process_name);
        for (std::int32_t pid : pids)
        {
            if (pid != -1)
            {
                ControlCenter::wait_for_sync(pid);
            }
        }
    }
}

void EIOS_Inject_PID(std::int32_t pid) noexcept
{
    if (pid != -1)
    {
        if (InjectProcess(pid) == pid)
        {
            ControlCenter::wait_for_sync(pid);
        }
    }
}

EIOS* EIOS_From_PID(std::int32_t pid) noexcept
{
    extern std::unordered_map<std::int32_t, EIOS*> clients;
    if (clients.count(pid))
    {
        return clients[pid];
    }
    return nullptr;
}

void Reflect_Release_Object(EIOS* eios, jobject object) noexcept
{
    if (eios)
    {
        //Make sure we're not freeing null objects..
        if (object)
        {
            eios->control_center->reflect_release_object(object);
        }
    }
}

void Reflect_Release_Objects(EIOS* eios, jobject* objects, std::size_t amount) noexcept
{
    if (eios)
    {
        //Make sure we're not freeing null objects..
        if (objects && amount > 0)
        {
            //This is the fastest possible way to remove duplicates..
            //Do NOT use `unordered_set` constructor instead of the for-loop.
            //It is slower than emplace/insert.
            std::unordered_set<jobject> set;
            for (std::size_t i = 0; i < amount; ++i)
            {
                set.insert(objects[i]);
            }

            //Remove null objects
            set.erase(nullptr);

            //Create a contiguous array of objects for the client.
            std::vector<jobject> objects;
            objects.assign(set.begin(), set.end());

            //Make sure we're not freeing null objects..
            if (objects.size())
            {
                eios->control_center->reflect_release_objects(objects.data(), objects.size());
            }
        }
    }
}