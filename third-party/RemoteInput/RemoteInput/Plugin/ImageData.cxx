//
// Created by Brandon on 2023-03-22.
//

#include "ImageData.hxx"
#include <bit>

ImageData::ImageData(void* buffer, std::size_t size) : stream(), image_data(static_cast<EIOSData*>(buffer), [](EIOSData* data) { }) //data(std::construct_at(static_cast<EIOSData*>(buffer)), [](EIOSData* data) { std::destroy_at(data); })
{
    std::size_t offset = offsetof(EIOSData, data);
    stream = std::make_unique<Stream>(image_data->data, size - offset);
    stream->seekg(0, std::ios::beg);
    stream->seekp(0, std::ios::beg);
}

ImageData::~ImageData()
{
    // std::launder(reinterpret_cast<ImageData*>(buffer))->~ImageData();
}

void ImageData::prepare_for_read() const
{
    stream->seekg(0, std::ios::beg);
}

void ImageData::prepare_for_write() const
{
    stream->seekp(0, std::ios::beg);
}

std::int32_t ImageData::parent_process_id() const
{
    return image_data->parent_process_id;
}

std::int32_t ImageData::parent_thread_id() const
{
    return image_data->parent_thread_id;
}

std::int32_t ImageData::image_width() const
{
    return image_data->image_width;
}

std::int32_t ImageData::image_height() const
{
    return image_data->image_height;
}

std::int32_t ImageData::target_width() const
{
    return image_data->target_width;
}

std::int32_t ImageData::target_height() const
{
    return image_data->target_height;
}

bool ImageData::debug_graphics() const
{
    return image_data->debug_graphics;
}

bool ImageData::ui_scaling() const
{
    return image_data->ui_scaling;
}

ImageFormat ImageData::image_format() const
{
    return image_data->image_format;
}

EIOSCommand ImageData::command() const
{
    return image_data->command;
}

void ImageData::set_parent_process_id(std::int32_t pid)
{
    image_data->parent_process_id = pid;
}

void ImageData::set_parent_thread_id(std::int32_t tid)
{
    image_data->parent_thread_id = tid;
}

void ImageData::set_image_width(std::int32_t width)
{
    image_data->image_width = width;
}

void ImageData::set_image_height(std::int32_t height)
{
    image_data->image_height = height;
}

void ImageData::set_target_width(std::int32_t width)
{
    image_data->target_width = width;
}

void ImageData::set_target_height(std::int32_t height)
{
    image_data->target_height = height;
}

void ImageData::set_debug_graphics(bool debug_graphics)
{
    image_data->debug_graphics = debug_graphics;
}

void ImageData::set_ui_scaling(bool ui_scaling)
{
    image_data->ui_scaling = ui_scaling;
}

void ImageData::set_image_format(ImageFormat format)
{
    image_data->image_format = format;
}

void ImageData::set_command(EIOSCommand command)
{
    image_data->command = command;
}

void* ImageData::data_buffer(std::ios_base::openmode mode) const noexcept
{
    if (mode & std::ios_base::in)
    {
        return image_data->data + stream->tellg();
    }
    else if (mode & std::ios_base::out)
    {
        return image_data->data + stream->tellp();
    }
    else
    {
        return nullptr;
    }
}

std::uint8_t* ImageData::image_buffer() const noexcept
{
    return reinterpret_cast<std::uint8_t*>(image_data.get()) + sizeof(EIOSData);
}

std::uint8_t* ImageData::debug_image_buffer() const noexcept
{
    return image_buffer() + image_width() * image_height() * 4;
}

Stream& ImageData::data_stream() const noexcept
{
    return *stream;
}