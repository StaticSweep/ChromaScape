//
// Created by Brandon on 2023-03-22.
//

#ifndef IMAGEDATA_HXX_INCLUDED
#define IMAGEDATA_HXX_INCLUDED

#include <memory>
#include "EIOSTypes.hxx"
#include "Stream.hxx"

struct EIOSData;
enum class EIOSCommand: std::uint32_t;

class ImageData
{
private:
    std::unique_ptr<Stream> stream;
    std::unique_ptr<EIOSData, void(*)(EIOSData*)> image_data;

public:
    ImageData(void* buffer, std::size_t size);
    ~ImageData();

    void prepare_for_read() const;
    void prepare_for_write() const;

    std::int32_t parent_process_id() const;
    std::int32_t parent_thread_id() const;
    std::int32_t image_width() const;
    std::int32_t image_height() const;
    std::int32_t target_width() const;
    std::int32_t target_height() const;
    bool debug_graphics() const;
    bool ui_scaling() const;
    ImageFormat image_format() const;
    EIOSCommand command() const;

    void set_parent_process_id(std::int32_t pid);
    void set_parent_thread_id(std::int32_t tid);
    void set_image_width(std::int32_t width);
    void set_image_height(std::int32_t height);
    void set_target_width(std::int32_t width);
    void set_target_height(std::int32_t height);
    void set_debug_graphics(bool debug_graphics);
    void set_ui_scaling(bool ui_scaling);
    void set_image_format(ImageFormat format);
    void set_command(EIOSCommand command);

    void* data_buffer(std::ios_base::openmode mode) const noexcept;

    std::uint8_t* image_buffer() const noexcept;
    std::uint8_t* debug_image_buffer() const noexcept;
    Stream& data_stream() const noexcept;
};

#endif /* IMAGEDATA_HXX_INCLUDED */
