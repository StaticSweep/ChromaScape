//
// Created by Brandon on 2022-09-16.
//

#ifndef REMOTEINPUT_TYPETRAITS_FUNCTIONAL_HXX
#define REMOTEINPUT_TYPETRAITS_FUNCTIONAL_HXX

#include <type_traits>
#include <tuple>

template<typename T>
struct is_function_pointer
{
    static bool const value = std::bool_constant<std::is_pointer<T>::value &&
                                                 std::is_function<typename std::remove_pointer<T>::type>::value>::value;
};

template<typename T>
struct is_lambda
{
    static bool const value = !std::remove_reference_t<T>::value &&
                              !std::is_function<T>::value &&
                              !std::is_pointer<T>::value &&
                              !std::is_member_pointer<T>::value;
};

template<typename T>
struct remove_class {};

template<typename C, typename R, typename... A>
struct remove_class<R(C::*)(A...)>
{
    using type = R(A...);
};

template <typename C, typename R, typename... A>
struct remove_class<R(C::*)(A...) const>
{
    using type = R(A...);
};

template<typename C, typename R, typename... A>
struct remove_class<R(C::*)(A...) volatile>
{
    using type = R(A...);
};

template<typename C, typename R, typename... A>
struct remove_class<R(C::*)(A...) noexcept>
{
    using type = R(A...);
};

template<typename C, typename R, typename... A>
struct remove_class<R(C::*)(A...) const volatile>
{
    using type = R(A...);
};

template<typename C, typename R, typename... A>
struct remove_class<R(C::*)(A...) const noexcept>
{
    using type = R(A...);
};

template<typename C, typename R, typename... A>
struct remove_class<R(C::*)(A...) volatile noexcept>
{
    using type = R(A...);
};

template<typename C, typename R, typename... A>
struct remove_class<R(C::*)(A...) const volatile noexcept>
{
    using type = R(A...);
};

template<typename T>
struct function_signature
{
    using type = typename std::conditional<is_function_pointer<T>::value || std::is_member_function_pointer<T>::value,
                 typename remove_class<decltype(&std::remove_pointer<T>::type::operator())>::type,
                 typename remove_class<decltype(&std::remove_reference<T>::type::operator())>::type>::type;
};

template<typename R, typename... A>
struct function_signature<R(A...)>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(&)(A...)>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(&)(A...) noexcept>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(*)(A...)>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(*)(A...) noexcept>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(A...) const>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(A...) volatile>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(A...) noexcept>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(A...) const volatile>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(A...) const noexcept>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(A...) volatile noexcept>
{
    using type = R(A...);
};

template<typename R, typename... A>
struct function_signature<R(A...) const volatile noexcept>
{
    using type = R(A...);
};

template<typename T>
struct function_arguments_type
{
    using type = typename function_arguments_type<typename function_signature<T>::type>::type;
};

template<typename R, typename... A>
struct function_arguments_type<R(A...)>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(A...) const>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(A...) volatile>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(A...) noexcept>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(A...) const volatile>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(A...) const noexcept>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(A...) volatile noexcept>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(A...) const volatile noexcept>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(&)(A...)>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(&)(A...) noexcept>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(*)(A...)>
{
    using type = typename std::tuple<A...>;
};

template<typename R, typename... A>
struct function_arguments_type<R(*)(A...) noexcept>
{
    using type = typename std::tuple<A...>;
};

template<typename T>
struct function_return_type
{
    using type = typename function_return_type<typename function_signature<T>::type>::type;
};

template<typename R, typename... A>
struct function_return_type<R(A...)>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(A...) const>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(A...) volatile>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(A...) noexcept>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(A...) const volatile>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(A...) const noexcept>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(A...) volatile noexcept>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(A...) const volatile noexcept>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(&)(A...)>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(&)(A...) noexcept>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(*)(A...)>
{
    using type = R;
};

template<typename R, typename... A>
struct function_return_type<R(*)(A...) noexcept>
{
    using type = R;
};

#include "TypeTraits_Functional_Attributes.hxx"

#endif //REMOTEINPUT_TYPETRAITS_FUNCTIONAL_HXX
