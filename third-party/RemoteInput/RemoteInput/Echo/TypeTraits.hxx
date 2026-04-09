//
//  PythonTraits.hxx
//  RemoteInput
//
//  Created by Brandon on 2020-11-10.
//  Copyright © 2020 XIO. All rights reserved.
//

#ifndef REMOTEINPUT_TYPETRAITS_HXX
#define REMOTEINPUT_TYPETRAITS_HXX

#if defined(_WIN32) || defined(_WIN64)
#include <windows.h>
#endif

#include <type_traits>
#include <tuple>
#include <string>
#include <numeric>
#include <locale>
#include <codecvt>
#include <string_view>

template<typename T, typename... Ts>
struct is_same_of : std::bool_constant<(std::is_same<typename std::remove_cv<T>::type, typename std::remove_cv<Ts>::type>::value || ...)> { };

template<typename T>
struct is_vector
{
    static bool const value = false;
};

template<typename T>
struct is_vector<std::vector<T>>
{
    static bool const value = true;
};

template<typename... Ts>
struct all_of : std::bool_constant<(Ts::value && ...)> { };  //std::conjunction<Ts...>

template<typename... Ts>
struct any_of : std::bool_constant<(Ts::value || ...)> { };  //std::disjunction<Ts...>

template<typename... Ts>
struct none_of : std::bool_constant<!any_of<Ts...>::value> { };

struct string_hash
{
    using is_transparent = void;  //c++20 heterogeneous lookup

    size_t operator()(const char* string) const
    {
        return std::hash<std::string_view>{}(string);
    }

    size_t operator()(std::string_view string) const
    {
        return std::hash<std::string_view>{}(string);
    }

    size_t operator()(const std::string &string) const
    {
        return std::hash<std::string>{}(string);
    }
};

template<typename T>
inline void hash_combine(std::size_t& seed, const T &value)
{
    // boost::hash
    seed ^= std::hash<T>{}(value) + 0x9E3779B9 + (seed << 6) + (seed >> 2);
}

template<typename Tuple, std::size_t... Ints>
auto select_tuple(Tuple&& tuple, std::index_sequence<Ints...>)
{
    return std::tuple<std::tuple_element_t<Ints, Tuple>...>(std::get<Ints>(std::forward<Tuple>(tuple))...);
}

template<std::size_t N, typename Seq>
struct offset_sequence;

template<std::size_t N, std::size_t... Ints>
struct offset_sequence<N, std::index_sequence<Ints...>>
{
    using type = std::index_sequence<Ints + N...>;
};

template<std::size_t N, typename Seq>
using offset_sequence_t = typename offset_sequence<N, Seq>::type;

template<std::size_t StartIndex, std::size_t Length>
using offset_index_sequence_t = offset_sequence_t<StartIndex, std::make_index_sequence<Length>>;

template<std::size_t N, typename... Args, std::size_t... Is>
constexpr auto tuple_remove_first_n_impl(std::tuple<Args...>&& tp, std::index_sequence<Is...>)
{
    return std::tuple{std::get<Is + N>(tp)...};
}

template<std::size_t N, typename... Args>
constexpr auto tuple_remove_first_n(std::tuple<Args...> tp)
{
    return tuple_remove_first_n_impl<N>(std::forward<std::tuple<Args...>>(tp), std::make_index_sequence<sizeof...(Args) - N>{});
}

template<typename... Args, std::size_t... Is>
constexpr auto tuple_remove_last_n_impl(std::tuple<Args...> tp, std::index_sequence<Is...>)
{
    return std::tuple{std::get<Is>(tp)...};
}

template<std::size_t N, typename... Args>
constexpr auto tuple_remove_last_n(std::tuple<Args...> tp)
{
    return tuple_remove_last_n_impl(tp, std::make_index_sequence<sizeof...(Args) - N>{});
}

template<typename Fn, typename Tp, std::size_t... Ns>
auto tuple_index_impl(Fn&& fn, Tp&& t, std::index_sequence<Ns...>)
{
    if constexpr(sizeof...(Ns) == 0)
    {
        return std::tuple<>(); // no values in tuple
    }
    else if constexpr(std::is_same_v<decltype(fn(std::get<0>(t), 0)), void>)
    {
        (fn(std::get<Ns>(t), Ns), ...); // no return value expected
        return;
    }
    else if constexpr(std::is_lvalue_reference_v<decltype(fn(std::get<0>(t), 0))>)
    {
        return std::tie(fn(std::get<Ns>(t), Ns)...);
    }
    else if constexpr(std::is_rvalue_reference_v<decltype(fn(std::get<0>(t), 0))>)
    {
        return std::forward_as_tuple(fn(std::get<Ns>(t), Ns)...);
    }
    else
    {
        return std::tuple(fn(std::get<Ns>(t), Ns)...);
    }
}

template<typename Fn, typename... Ts>
auto tuple_transform(Fn&& fn, const std::tuple<Ts...>& tuple)
{
    auto transform = [fn = std::forward<Fn>(fn)](auto arg, [[maybe_unused]] std::size_t index) {
        return fn(arg);
    };
    return tuple_index_impl(transform, tuple, std::make_index_sequence<sizeof...(Ts)>());
}

template<typename Fn, typename... Tp>
auto tuple_map(Fn&& fn, const std::tuple<Tp...>& tp)
{
    return tuple_index_impl(std::forward<Fn>(fn), tp, std::make_index_sequence<sizeof...(Tp)>());
}

template<typename Tp, typename Fn>
void tuple_for_each(Tp&& tuple, Fn&& fn)
{
    return std::apply(std::forward<Tp>(tuple), [&fn](auto&&... args) {
        return (fn(std::forward<decltype(args)>(args)) && ...);
    });
}

template<typename T, typename Predicate>
std::string join(const std::vector<T> &range, const std::string &separator, Predicate converter)
{
    if (range.empty())
    {
        return std::string();
    }

    if (range.size() == 1)
    {
        return converter(range[0]);
    }

    std::string result = "";
    for (std::size_t i = 0; i < range.size() - 1; ++i)
    {
        result += converter(range[i]) + separator;
    }
    return result + converter(range[range.size() - 1]);

//    return std::accumulate(
//            next(begin(range)),
//            end(range),
//            converter(range[0]), // the initial value
//            [&separator, &converter](auto result, const auto &value) {
//                return result + separator + converter(value);
//            }
//    );
}

template<typename... Args>
std::string to_string(const std::tuple<Args...> &ts, const char* separator = "")
{
    auto convert_to_utf8 = [](const std::wstring &value) -> std::string {
        #if defined(_WIN32) || defined(_WIN64)
        int len = WideCharToMultiByte(CP_UTF8, 0, value.c_str(), -1, NULL, 0, 0, 0);
        if (len)
        {
            std::string utf8 = std::string(len, '\0');
            WideCharToMultiByte(CP_UTF8, 0, value.c_str(), -1, &utf8[0], len, 0, 0);
            return utf8;
        }
        return std::string();
        #elif __cplusplus > 199711L && __cplusplus < 201703L
        return std::wstring_convert<std::codecvt_utf8<wchar_t>>().to_bytes(value);
        #else
        int utf16len = wcslen(value.c_str());
        int utf8len = std::wcstombs(nullptr, value.c_str(), utf16len);
        if (utf8len)
        {
            std::string utf8 = std::string(utf8len, '\0');
            std::wcstombs(&utf8[0], value.c_str(), utf16len);
            return utf8;
        }
        return std::string();
        #endif
    };

    auto convert_pointer_to_string = [](const auto &value) -> std::string {
        char buffer[(sizeof(void*) * 2) + 2] = {0};
        snprintf(buffer, sizeof(buffer) - 1, "%p", value);
        return std::string(buffer);
    };

    auto convert_to_string = [convert_to_utf8, convert_pointer_to_string](auto value) -> std::string {
        if constexpr(is_same_of<std::decay_t<decltype(value)>, char, char*, const char*, std::string>::value)
        {
            return value;
        }
        else if constexpr(is_same_of<std::decay_t<decltype(value)>, wchar_t, std::wstring>::value)
        {
            return convert_to_utf8(value);
        }
        else if constexpr (is_vector<std::decay_t<decltype(value)>>::value)
        {
            using value_type = typename std::decay_t<decltype(value)>::value_type;
            if constexpr(is_same_of<value_type, char, char*, const char*, std::string>::value)
            {
                return "[" + join(value, " ", [](const value_type &value) { return value; }) + "]";
            }
            else if constexpr(is_same_of<value_type, wchar_t, wchar_t*, const wchar_t*, std::wstring>::value)
            {
                return "[" + join(value, " ", [convert_to_utf8](const value_type &value) { return convert_to_utf8(value); }) + "]";
            }
            else if constexpr(std::is_pointer<value_type>::value)
            {
                return "[" + join(value, " ", convert_pointer_to_string) + "]";
            }
            else if constexpr(std::is_union<value_type>::value)
            {
                return "[cannot_convert_type_to_string]";
            }
            else
            {
                return "[" + join(value, " ", [](const value_type &value) { return std::to_string(value); }) + "]";
            }
        }
        else if constexpr(std::is_pointer<decltype(value)>::value)
        {
            return convert_pointer_to_string(value);
        }
        else
        {
            return std::to_string(value);
        }
    };

    return std::apply([separator, convert_to_string](Args... ts) {
        std::string result;
        const char* sep = "";
        ((static_cast<void>(result += sep + convert_to_string(ts)), sep = separator), ...);
        return result;
    }, ts);
}

#endif //REMOTEINPUT_TYPETRAITS_HXX
