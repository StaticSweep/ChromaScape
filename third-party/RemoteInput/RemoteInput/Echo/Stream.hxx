//
// Created by Brandon on 2020-12-12.
//

#ifndef REMOTEINPUT_STREAM_HXX
#define REMOTEINPUT_STREAM_HXX

#include <iostream>
#include <vector>
#include <list>
#include <string>
#include <array>
#include <istream>
#include <streambuf>
#include <cstring>

#if __cplusplus >= 202002L
#include <span>
#endif

template<typename char_type, typename traits_type = std::char_traits<char_type>>
class DirectStream : private std::basic_streambuf<char_type, traits_type>, public std::basic_iostream<char_type, traits_type>
{
public:
    typedef typename traits_type::pos_type pos_type;
    typedef typename traits_type::off_type off_type;

    DirectStream(char_type* buffer, std::size_t buffer_size)
            : std::basic_streambuf<char_type, traits_type>(),
              std::basic_iostream<char_type, traits_type>(this), std::ios(0)
    {
        std::basic_streambuf<char_type, traits_type>::pubsetbuf(nullptr, 0);
        std::basic_streambuf<char_type, traits_type>::setp(buffer, buffer + buffer_size);
        std::basic_streambuf<char_type, traits_type>::setg(buffer, buffer, buffer + buffer_size);
        std::basic_iostream<char_type, traits_type>::init(this);
    }

protected:
    pos_type seekpos(pos_type pos, std::ios_base::openmode which)
    {
        return seekoff(pos - pos_type(off_type(0)), std::ios_base::beg, which);
    }

    pos_type seekoff(off_type off, std::ios_base::seekdir dir, std::ios_base::openmode which)
    {
        typedef std::basic_streambuf<char_type, traits_type> this_type;

        if (which & std::ios_base::in)
        {
            if (dir == std::ios_base::beg)
            {
                this_type::setg(this_type::eback(), this_type::eback() + off, this_type::egptr());
            }
            else if (dir == std::ios_base::cur)
            {
                // this_type::gbump(off);
                this_type::setg(this_type::eback(), this_type::gptr() + off, this_type::egptr());
            }
            else if (dir == std::ios_base::end)
            {
                this_type::setg(this_type::eback(), this_type::egptr() + off, this_type::egptr());
            }

            return this_type::gptr() - this_type::eback();
        }

        if (which & std::ios_base::out)
        {
            if (dir == std::ios_base::beg)
            {
                this_type::setp(this_type::pbase() + off, this_type::epptr());
            }
            else if (dir == std::ios_base::cur)
            {
                //this_type::pbump(off);
                this_type::setp(this_type::pptr() + off, this_type::epptr());
            }
            else if (dir == std::ios_base::end)
            {
                this_type::setp(this_type::epptr() + off, this_type::epptr());
            }
            return this_type::pptr() - this_type::pbase();
        }

        return pos_type(off_type(-1));
    }
};


class Stream : private DirectStream<char>
{
public:
    Stream(void* buffer, std::size_t buffer_size) : DirectStream(static_cast<char*>(buffer), buffer_size) {}

    // MARK: - Read

    template<typename T, std::size_t size>
    void read(T (&value)[size]);

    template<typename T>
    typename std::enable_if<std::is_trivially_copyable<T>::value, void>::type read(T &value);

    template<typename T>
    typename std::enable_if<std::is_same<char*, T*>::value, void>::type read(T* value);

    void read(void* value, std::size_t size);

    template<typename T, template<typename, typename...> class U, typename... Args>
    U<T, Args...> read();

    template<typename T>
    T read();

    // MARK: - Write

    template<typename T, std::size_t size>
    void write(const T (&value)[size]);

    template<typename T>
    typename std::enable_if<std::is_trivially_copyable<T>::value, void>::type write(const T &value);

    template<typename T>
    typename std::enable_if<std::is_same<const char*, const T*>::value, void>::type write(const T* value);

    void write(const void* value, std::size_t size);

    template<typename T, template<typename, typename...> class U, typename... Args>
    void write(const U<T, Args...> &value);

    // MARK: - Stream Positions

    std::streampos tellg();
    std::streampos tellp();
    Stream& seekg(std::streamoff off, std::ios_base::seekdir dir = std::ios_base::beg);
    Stream& seekp(std::streamoff off, std::ios_base::seekdir dir = std::ios_base::beg);

    // MARK: - Fundamental Types

    template<typename T>
    typename std::enable_if<std::is_arithmetic<T>::value
                            || std::is_enum<T>::value
                            || std::is_pointer<T>::value
                            || std::is_member_pointer<T>::value
                            || std::is_null_pointer<T>::value, Stream&>::type operator << (const T &value);

    template<typename T>
    typename std::enable_if<std::is_arithmetic<T>::value
                            || std::is_enum<T>::value
                            || std::is_pointer<T>::value
                            || std::is_member_pointer<T>::value
                            || std::is_null_pointer<T>::value, Stream&>::type operator >> (T &value);

    // MARK: - String types

    Stream& operator << (const char* value);
    Stream& operator >> (char* value);


    // MARK: - Container types

    template<typename T, template<typename, typename...> class U, typename... Args>
    Stream& operator << (const U<T, Args...> &value);

    template<typename T, template<typename, typename...> class U, typename... Args>
    Stream& operator >> (U<T, Args...> &value);

    template<typename T, std::size_t Size>
    Stream& operator << (const std::array<T, Size> &value);

    template<typename T, std::size_t Size>
    Stream& operator >> (std::array<T, Size> &value);

    #if __cplusplus >= 202002L
    template<typename T, std::size_t Extent>
    Stream& operator << (const std::span<T, Extent> &value);

    template<typename T, std::size_t Extent>
    Stream& operator >> (std::span<T, Extent> &value) = delete;
    #endif
};

template<typename T, std::size_t size>
void Stream::read(T (&value)[size])
{
    std::size_t read_size = read<std::size_t>();
    DirectStream::read(reinterpret_cast<char*>(&value), std::min(read_size, size) * sizeof(T));
}

template<typename T>
typename std::enable_if<std::is_trivially_copyable<T>::value, void>::type Stream::read(T &value)
{
    DirectStream::read(reinterpret_cast<char *>(&value), sizeof(T));
}

template<typename T>
typename std::enable_if<std::is_same<char*, T*>::value, void>::type Stream::read(T* value)
{
    std::size_t size = 0;
    read(size);
    read(value, size + 1);
}

template<typename T, template<typename, typename...> class U, typename... Args>
U<T, Args...> Stream::read()
{
    U<T, Args...> result;
    *this >> result;
    return result;
}

template<typename T>
T Stream::read()
{
    T result;
    *this >> result;
    return result;
}

template<typename T, std::size_t size>
void Stream::write(const T (&value)[size])
{
    *this << size;
    DirectStream::write(reinterpret_cast<const char*>(&value), size * sizeof(T));
}

template<typename T>
typename std::enable_if<std::is_trivially_copyable<T>::value, void>::type Stream::write(const T &value)
{
    DirectStream::write(reinterpret_cast<const char*>(&value), sizeof(T));
}

template<typename T>
typename std::enable_if<std::is_same<const char*, const T*>::value, void>::type Stream::write(const T* value)
{
    std::size_t size = strlen(value);
    write(size);
    write(value, size);
}

template<typename T, template<typename, typename...> class U, typename... Args>
void Stream::write(const U<T, Args...> &value)
{
    *this << value;
}

template<typename T>
typename std::enable_if<std::is_arithmetic<T>::value
                        || std::is_enum<T>::value
                        || std::is_pointer<T>::value
                        || std::is_member_pointer<T>::value
                        || std::is_null_pointer<T>::value, Stream&>::type Stream::operator << (const T &value)
{
    write(value);
    return *this;
}

template<typename T>
typename std::enable_if<std::is_arithmetic<T>::value
                        || std::is_enum<T>::value
                        || std::is_pointer<T>::value
                        || std::is_member_pointer<T>::value
                        || std::is_null_pointer<T>::value, Stream&>::type Stream::operator >> (T &value)
{
    read(value);
    return *this;
}

template<typename T, template<typename, typename...> class U, typename... Args>
Stream& Stream::operator << (const U<T, Args...> &value)
{
    *this << std::size(value);
    for (const auto &it : value)
    {
        *this << it;
    }
    return *this;
}

template<typename T, template<typename, typename...> class U, typename... Args>
Stream& Stream::operator >> (U<T, Args...> &value)
{
    typename U<T, Args...>::size_type size = 0;
    *this >> size;
    value.reserve(size);

    for (typename U<T, Args...>::size_type i = 0; i < size; ++i)
    {
        T temp;
        *this >> temp;

        if constexpr(std::is_same<std::basic_string<T, Args...>, U<T, Args...>>::value)
        {
            value += std::move(temp);
        }
        else
        {
            value.emplace_back(std::move(temp));
        }
    }
    return *this;
}

template<typename T, std::size_t Size>
Stream& Stream::operator << (const std::array<T, Size> &value)
{
    *this << Size;
    for (const auto &it : value)
    {
        *this << it;
    }
    return *this;
}

template<typename T, std::size_t Size>
Stream& Stream::operator >> (std::array<T, Size> &value)
{
    std::size_t real_size = 0;
    *this >> real_size;

    if (real_size != Size)
    {
        throw std::runtime_error("Stream >> Reading Array of wrong size!");
        return *this;
    }

    for (std::size_t i = 0; i < real_size; ++i)
    {
        T temp;
        *this >> temp;
        value[i] = std::move(temp);
    }
    return *this;
}

#if __cplusplus >= 202002L
template<typename T, std::size_t Extent>
Stream& Stream::operator << (const std::span<T, Extent> &value)
{
    *this << Extent;
    for (const auto &it : value)
    {
        *this << it;
    }
    return *this;
}
#endif

#endif // REMOTEINPUT_STREAM_HXX
