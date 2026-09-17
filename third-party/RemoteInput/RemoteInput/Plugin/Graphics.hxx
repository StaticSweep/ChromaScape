#ifndef Graphics_HXX_INCLUDED
#define Graphics_HXX_INCLUDED

#include "EIOSTypes.hxx"
#include <cstdint>
#include <cstring>
#include <string>
#include <type_traits>

#if defined(_WIN32) || defined(_WIN64)
#include <d3d9.h>
#include <d3d11.h>
#include <d3dx9shader.h>
#endif

//[[gnu::optimize(0)]]
//[[clang::optimize(0)]] //optnone

#if defined(_MSC_VER)
#pragma optimize("s", on)
#else
#pragma GCC push_options
#pragma GCC optimize ("-O3")
#endif

typedef struct bgr_bgra_t
{
    std::uint8_t b;
    std::uint8_t g;
    std::uint8_t r;
    std::uint8_t a;
} bgr_bgra;

typedef struct abgr_t
{
    std::uint8_t a;
    std::uint8_t b;
    std::uint8_t g;
    std::uint8_t r;
} abgr;

typedef struct argb_t
{
    std::uint8_t a;
    std::uint8_t r;
    std::uint8_t g;
    std::uint8_t b;
} argb;

typedef struct rgba_t
{
    std::uint8_t r;
    std::uint8_t g;
    std::uint8_t b;
    std::uint8_t a;
} rgba;

typedef struct bgra_t
{
    std::uint8_t b;
    std::uint8_t g;
    std::uint8_t r;
    std::uint8_t a;
} bgra;

void FlipImageBytes(void* In, void* Out, std::int32_t width, std::int32_t height, std::uint32_t Bpp) noexcept;
void FlipImageVertically(std::int32_t width, std::int32_t height, std::uint8_t* data) noexcept;
void FlipImageVertically2(std::int32_t width, std::int32_t height, std::uint8_t* data) noexcept;

//Native
void draw_circle(std::int32_t x, std::int32_t y, std::int32_t radius, void* buffer, std::int32_t width, std::int32_t height, std::int32_t stride, bool filled, std::int32_t abgr_colour) noexcept;
void copy_image_to_bgra(void* dest_buffer, void* source_buffer, std::int32_t width, std::int32_t height, std::int32_t stride, ImageFormat source_format) noexcept;
void copy_image_from_bgra(void* dest_buffer, void* source_buffer, std::int32_t width, std::int32_t height, std::int32_t stride, ImageFormat destination_format) noexcept;
void draw_image(void* dest_buffer, void* source_buffer, std::int32_t width, std::int32_t height, std::int32_t stride, ImageFormat format) noexcept;

//OpenGL
void gl_draw_point(void* ctx, float x, float y, float z, float radius) noexcept;
void gl_draw_image(void* ctx, void* source_buffer, float x, float y, std::int32_t width, std::int32_t height, std::int32_t stride, ImageFormat format) noexcept;

//DirectX

#if defined(_WIN32) || defined(_WIN64)
IDirect3DPixelShader9* dx_compile_shader(IDirect3DDevice9* device, ID3DXConstantTable* &constant_table, const std::string &shader_string) noexcept;
void dx_apply_shader(IDirect3DDevice9* device, IDirect3DPixelShader9* shader, ID3DXConstantTable* constant_table, ImageFormat format) noexcept;
void dx_load_texture(IDirect3DDevice9* device, IDirect3DTexture9* &texture, IDirect3DPixelShader9* &shader, ID3DXConstantTable* &constants_table, ImageFormat image_format, std::uint8_t* buffer, std::int32_t width, std::int32_t height) noexcept;
void dx_draw_texture(IDirect3DDevice9* device, IDirect3DTexture9* texture, ID3DXConstantTable* &constant_table, IDirect3DPixelShader9* &shader, IDirect3DVertexBuffer9* &vertex_buffer, ImageFormat image_format, float X1, float Y1, float X2, float Y2) noexcept;
void dx_read_pixels(IDirect3DDevice9* device, void* buffer, std::int32_t width, std::int32_t height, bool& minimized, ImageFormat image_format) noexcept;
void dx_draw_point(IDirect3DDevice9* device, float cx, float cy, float radius, D3DCOLOR colour);
#endif

#if defined(_MSC_VER)
#pragma optimize("", on)
#else
#pragma GCC pop_options
#endif

#endif /* Graphics_HXX_INCLUDED */
