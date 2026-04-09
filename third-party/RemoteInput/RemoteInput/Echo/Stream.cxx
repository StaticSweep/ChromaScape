//
// Created by Brandon on 2020-12-12.
//

#include "Stream.hxx"

void Stream::read(void* value, std::size_t size)
{
    DirectStream::read(reinterpret_cast<char*>(value), size);
}

void Stream::write(const void* value, std::size_t size)
{
    DirectStream::write(reinterpret_cast<const char*>(value), size);
}

std::streampos Stream::tellg()
{
    return DirectStream::tellg();
}

std::streampos Stream::tellp()
{
    return DirectStream::tellp();
}

Stream& Stream::seekg(std::streamoff off, std::ios_base::seekdir dir)
{
    DirectStream::seekg(off, dir);
    return *this;
}

Stream& Stream::seekp(std::streamoff off, std::ios_base::seekdir dir)
{
    DirectStream::seekp(off, dir);
    return *this;
}

Stream& Stream::operator << (const char* value)
{
    write(value);
    return *this;
}

Stream& Stream::operator >> (char* value)
{
    read(value);
    return *this;
}