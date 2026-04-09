//
// Created by Brandon on 2022-09-23.
//

#ifndef REMOTEINPUT_TYPETRAITS_FUNCTIONAL_ATTRIBUTES_HXX
#define REMOTEINPUT_TYPETRAITS_FUNCTIONAL_ATTRIBUTES_HXX

#if defined(_WIN32) && !defined(_WIN64)

template<typename C, typename R, typename... A>
struct remove_class<R(__stdcall C::*)(A...)>
{
    using type = R(A...);
};

template <typename C, typename R, typename... A>
struct remove_class<R(__stdcall C::*)(A...) const>
{
    using type = R(A...);
};

template<typename C, typename R, typename... A>
struct remove_class<R(__stdcall C::*)(A...) volatile>
{
    using type = R(A...);
};

template<typename C, typename R, typename... A>
struct remove_class<R(__stdcall C::*)(A...) noexcept>
{
    using type = R(A...);
};

template<typename C, typename R, typename... A>
struct remove_class<R(__stdcall C::*)(A...) const volatile>
{
    using type = R(A...);
};

template<typename C, typename R, typename... A>
struct remove_class<R(__stdcall C::*)(A...) const noexcept>
{
    using type = R(A...);
};

template<typename C, typename R, typename... A>
struct remove_class<R(__stdcall C::*)(A...) volatile noexcept>
{
    using type = R(A...);
};

template<typename C, typename R, typename... A>
struct remove_class<R(__stdcall C::*)(A...) const volatile noexcept>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R __stdcall (A...)>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(__stdcall &)(A...)>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(__stdcall &)(A...) noexcept>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(__stdcall *)(A...)>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(__stdcall *)(A...) noexcept>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R __stdcall (A...) const>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R __stdcall (A...) volatile>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R __stdcall (A...) noexcept>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R __stdcall (A...) const volatile>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R __stdcall (A...) const noexcept>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R __stdcall (A...) volatile noexcept>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R __stdcall (A...) const volatile noexcept>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_arguments_type<R __stdcall (A...)>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R __stdcall (A...) const>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R __stdcall (A...) volatile>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R __stdcall (A...) noexcept>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R __stdcall (A...) const volatile>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R __stdcall (A...) const noexcept>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R __stdcall (A...) volatile noexcept>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R __stdcall (A...) const volatile noexcept>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(__stdcall &)(A...)>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(__stdcall &)(A...) noexcept>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(__stdcall *)(A...)>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(__stdcall *)(A...) noexcept>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_return_type<R __stdcall (A...)>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R __stdcall (A...) const>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R __stdcall (A...) volatile>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R __stdcall (A...) noexcept>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R __stdcall (A...) const volatile>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R __stdcall (A...) const noexcept>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R __stdcall (A...) volatile noexcept>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R __stdcall (A...) const volatile noexcept>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(__stdcall &)(A...)>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(__stdcall &)(A...) noexcept>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(__stdcall *)(A...)>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(__stdcall *)(A...) noexcept>
{
    using type = R;
};

#endif //_Win32

#endif //REMOTEINPUT_TYPETRAITS_FUNCTIONAL_ATTRIBUTES_HXX
