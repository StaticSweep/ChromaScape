//
//  Module.hxx
//  RemoteInput
//
//  Created by Brandon on 2019-09-14.
//  Copyright © 2019 XIO. All rights reserved.
//

#ifndef MODULE_HXX_INCLUDED
#define MODULE_HXX_INCLUDED

#if defined _WIN32 || defined _WIN64
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>
#else
#include <dlfcn.h>
#endif

#include <string>
#include <stdexcept>
#include <locale>
#include <codecvt>

/********************************************//**
 * @brief Wraps the loading of modules/shared-libraries
 ***********************************************/
class Module
{
private:
    void* module;

public:
    /********************************************//**
     * @brief Loads a module/shared-library.
     *
     * @param path const char* Path to the module to be loaded.
     *
     ***********************************************/
    Module(const char* path) noexcept;


    /********************************************//**
     * @brief Loads a module/shared-library.
     *
     * @param path const wchar_t* Path to the module to be loaded.
     *
     ***********************************************/
    Module(const wchar_t* path) noexcept;


    /********************************************//**
     * @brief Releases the module.
     ***********************************************/
    ~Module() noexcept;



    /********************************************//**
     * @brief Copy constructor explicitly deleted. Modules cannot be "copied".
     *
     * @param other const Module& - Module to copy.
     *
     ***********************************************/
    Module(const Module& other) = delete;


    /********************************************//**
     * @brief  Move constructs a module from another module. Leaves the other module in a destructible state.
     *
     * @param other.module - Module to be moved into this container.
     *
     ***********************************************/
    Module(Module&& other) noexcept : module(other.module) { other.module = nullptr; }


    /********************************************//**
     * @brief When this class is casted to a void*.
     *
     * @return void* - Handle to the underlying module.
     *
     ***********************************************/
    explicit operator void*() const noexcept { return module; }


    /********************************************//**
     * @brief When this class is casted to a bool.
     *
     * @return bool - Boolean indicating whether the module was loaded successfully.
     *
     ***********************************************/
    explicit operator bool() const noexcept { return module != nullptr; }


    /********************************************//**
     * @brief Returns the loaded state of the underlying module.
     *
     * @return bool - Boolean indicating whether the module was loaded successfully.
     *
     ***********************************************/
    bool IsLoaded() const noexcept { return module != nullptr; }


    /********************************************//**
     * @brief Retrieves the address of a function within the underlying module.
     *
     * @param FunctionName const std::string& - Name of the function whose address is to be retrieved.
     * @return T - Casts the address to type T and returns the result.
     *
     ***********************************************/
    template<typename T>
    T AddressOf(const std::string &FunctionName) const;


    /********************************************//**
     * @brief Retrieves the address of a function within the underlying module.
     *
     * @param FunctionDefinition T& - Reference to a pointer that the address will be stored in.
     * @param FunctionName const std::string& - Name of the function whose address is to be retrieved.
     * @return bool - True if the function exists in the module, false otherwise.
     *
     ***********************************************/
    template<typename T>
    bool AddressOf(T &FunctionDefinition, const std::string &FunctionName) const noexcept;


    /********************************************//**
     * @brief Calls a function via pointer and passes the specified arguments to it.
     *
     * @param func void* - Address of the function to be called.
     * @param args Args... - Arguments to pass to the function pointer.
     *
     * Uses the __stdcall convention!
     *
     ***********************************************/
    template<typename... Args>
    void Call(void* func, Args... args) const noexcept;


    /********************************************//**
     * @brief Calls a function via pointer, passes the specified arguments to it and returns the result of the function call.
     *
     * @param func void* - Address of the function to be called.
     * @param args Args... - Arguments to pass to the function pointer.
     * @return R - Result of the function call.
     *
     * Uses the __stdcall convention!
     *
     ***********************************************/
    template<typename R, typename... Args>
    R Call(void* func, Args... args) const noexcept;
};


template<typename T>
T Module::AddressOf(const std::string &Name) const
{
    #if defined _WIN32 || defined _WIN64
    T Result = reinterpret_cast<T>(GetProcAddress(static_cast<HMODULE>(module), Name.c_str()));
    #else
    T Result = reinterpret_cast<T>(dlsym(module, Name.c_str()));
    #endif
    return Result ? Result : throw std::runtime_error(Name);
}

template<typename T>
bool Module::AddressOf(T &Definition, const std::string &Name) const noexcept
{
    return (Definition = AddressOf<T>(Name));
}

template<typename... Args>
void Module::Call(void* func, Args... args) const noexcept
{
    #if defined(_WIN32)
    return reinterpret_cast<void (__stdcall *)(Args...)>(func)(std::forward<Args>(args)...);
    #else
    return reinterpret_cast<void (*)(Args...)>(func)(std::forward<Args>(args)...);
    #endif
}

template<typename R, typename... Args>
R Module::Call(void* func, Args... args) const noexcept
{
    #if defined(_WIN32)
    return reinterpret_cast<R(__stdcall *)(Args...)>(func)(std::forward<Args>(args)...);
    #else
    return reinterpret_cast<R(*)(Args...)>(func)(std::forward<Args>(args)...);
    #endif
}

#endif /* MODULE_HXX_INCLUDED */
