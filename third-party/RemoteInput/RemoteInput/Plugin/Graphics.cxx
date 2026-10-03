#include "Graphics.hxx"
#include "Thirdparty/Hook.hxx"
#include <cstdint>
#include <memory>

#if defined(_WIN32) || defined(_WIN64)
#if defined(_MSC_VER)
#include <windows.h>
#include <gl/GL.h>

#define GL_PIXEL_PACK_BUFFER              0x88EB
#define GL_STREAM_READ                    0x88E1
#define GL_READ_ONLY                      0x88B8
#define GL_CLAMP_TO_EDGE                  0x812F
#define GL_BGRA                           0x80E1
#else
#include <gl/gl.h>
#include <gl/glext.h>
#endif
#elif defined(__APPLE__)
#include <OpenGL/OpenGL.h>
#include <OpenGL/CGLMacro.h>
#include <OpenGL/gl.h>
#include <OpenGL/glext.h>
#else
#include <GL/gl.h>
#include <GL/glext.h>
#include <GL/glx.h>
#endif


#if !defined(GL_TEXTURE_RECTANGLE)
#define GL_TEXTURE_RECTANGLE              0x84F5
#endif

#if !defined(GL_PROGRAM_POINT_SIZE)
#define GL_PROGRAM_POINT_SIZE             0x8642
#endif

#if defined(__APPLE__)
#define GL_VERTEX_ARRAY_BINDING           GL_VERTEX_ARRAY_BINDING_APPLE
#define glGenVertexArrays                 glGenVertexArraysAPPLE
#define glBindVertexArray                 glBindVertexArrayAPPLE
#endif


// ARGB To Format

std::uint32_t argb_to_abgr(std::uint32_t argb_colour)
{
    std::uint8_t a = (argb_colour >> 24) & 0xFF;
    std::uint8_t r = (argb_colour >> 16) & 0xFF;
    std::uint8_t g = (argb_colour >> 8)  & 0xFF;
    std::uint8_t b = (argb_colour >> 0)  & 0xFF;
    return (a << 24) | (b << 16) | (g << 8) | r;
}

std::uint32_t argb_to_bgra(std::uint32_t argb_colour)
{
    std::uint8_t a = (argb_colour >> 24) & 0xFF;
    std::uint8_t r = (argb_colour >> 16) & 0xFF;
    std::uint8_t g = (argb_colour >> 8)  & 0xFF;
    std::uint8_t b = (argb_colour >> 0)  & 0xFF;
    return (b << 24) | (g << 16) | (r << 8) | a;
}

std::uint32_t argb_to_rgba(std::uint32_t argb_colour)
{
    std::uint8_t a = (argb_colour >> 24) & 0xFF;
    std::uint8_t r = (argb_colour >> 16) & 0xFF;
    std::uint8_t g = (argb_colour >> 8)  & 0xFF;
    std::uint8_t b = (argb_colour >> 0)  & 0xFF;
    return (r << 24) | (g << 16) | (b << 8) | a;
}

// ABGR to Format

std::uint32_t abgr_to_argb(std::uint32_t abgr_colour)
{
    std::uint8_t a = (abgr_colour >> 24) & 0xFF;
    std::uint8_t b = (abgr_colour >> 16) & 0xFF;
    std::uint8_t g = (abgr_colour >> 8)  & 0xFF;
    std::uint8_t r = (abgr_colour >> 0)  & 0xFF;
    return (a << 24) | (r << 16) | (g << 8) | b;
}

std::uint32_t abgr_to_bgra(std::uint32_t abgr_colour)
{
    std::uint8_t a = (abgr_colour >> 24) & 0xFF;
    std::uint8_t b = (abgr_colour >> 16) & 0xFF;
    std::uint8_t g = (abgr_colour >> 8)  & 0xFF;
    std::uint8_t r = (abgr_colour >> 0)  & 0xFF;
    return (a << 24) | (b << 16) | (g << 8) | r;
}

std::uint32_t abgr_to_rgba(std::uint32_t abgr_colour)
{
    std::uint8_t a = (abgr_colour >> 24) & 0xFF;
    std::uint8_t b = (abgr_colour >> 16) & 0xFF;
    std::uint8_t g = (abgr_colour >> 8)  & 0xFF;
    std::uint8_t r = (abgr_colour >> 0)  & 0xFF;
    return (r << 24) | (g << 16) | (b << 8) | a;
}

// RGBA to Format

std::uint32_t rgba_to_argb(std::uint32_t rgba_colour)
{
    std::uint8_t r = (rgba_colour >> 24) & 0xFF;
    std::uint8_t g = (rgba_colour >> 16) & 0xFF;
    std::uint8_t b = (rgba_colour >> 8)  & 0xFF;
    std::uint8_t a = (rgba_colour >> 0)  & 0xFF;
    return (a << 24) | (r << 16) | (g << 8) | b;
}

std::uint32_t rgba_to_abgr(std::uint32_t rgba_colour)
{
    std::uint8_t r = (rgba_colour >> 24) & 0xFF;
    std::uint8_t g = (rgba_colour >> 16) & 0xFF;
    std::uint8_t b = (rgba_colour >> 8)  & 0xFF;
    std::uint8_t a = (rgba_colour >> 0)  & 0xFF;
    return (a << 24) | (b << 16) | (g << 8) | r;
}

std::uint32_t rgba_to_bgra(std::uint32_t rgba_colour)
{
    std::uint8_t r = (rgba_colour >> 24) & 0xFF;
    std::uint8_t g = (rgba_colour >> 16) & 0xFF;
    std::uint8_t b = (rgba_colour >> 8)  & 0xFF;
    std::uint8_t a = (rgba_colour >> 0)  & 0xFF;
    return (b << 24) | (g << 16) | (r << 8) | a;
}

// BGRA to Format

std::uint32_t bgra_to_abgr(std::uint32_t bgra_colour)
{
    std::uint8_t b = (bgra_colour >> 24) & 0xFF;
    std::uint8_t g = (bgra_colour >> 16) & 0xFF;
    std::uint8_t r = (bgra_colour >> 8)  & 0xFF;
    std::uint8_t a = (bgra_colour >> 0)  & 0xFF;
    return (a << 24) | (b << 16) | (g << 8) | r;
}

std::uint32_t bgra_to_argb(std::uint32_t bgra_colour)
{
    std::uint8_t b = (bgra_colour >> 24) & 0xFF;
    std::uint8_t g = (bgra_colour >> 16) & 0xFF;
    std::uint8_t r = (bgra_colour >> 8)  & 0xFF;
    std::uint8_t a = (bgra_colour >> 0)  & 0xFF;
    return (a << 24) | (r << 16) | (g << 8) | b;
}

std::uint32_t bgra_to_rgba(std::uint32_t bgra_colour)
{
    std::uint8_t b = (bgra_colour >> 24) & 0xFF;
    std::uint8_t g = (bgra_colour >> 16) & 0xFF;
    std::uint8_t r = (bgra_colour >> 8)  & 0xFF;
    std::uint8_t a = (bgra_colour >> 0)  & 0xFF;
    return (r << 24) | (g << 16) | (b << 8) | a;
}

// Other

std::uint32_t RGBA(std::uint8_t r, std::uint8_t g, std::uint8_t b, std::uint8_t a) noexcept
{
    return (r << 24) + (g << 16) + (b << 8) + (a);
}

void RGBA(std::uint32_t colour, std::uint8_t& r, std::uint8_t& g, std::uint8_t& b, std::uint8_t& a) noexcept
{
    r = (colour >> 24) & 0xFF;
    g = (colour >> 16) & 0xFF;
    b = (colour >> 8) & 0xFF;
    a = (colour & 0xFF);
}

template<typename S, typename D>
void convert_pixels(S source, D dest, std::int32_t width, std::int32_t height, std::int32_t stride)
{
    if constexpr(std::is_same<S, D>::value)
    {
        std::memcpy(dest, source, width * height * stride);
    }
    else if constexpr(std::is_same<S, bgr_bgra_t*>::value)
    {
        for (std::int32_t i = 0; i < width * height * stride; i += stride)
        {
            dest->a = *reinterpret_cast<std::uint32_t *>(source) == 0x00 ? 0x00 : 0xFF;
            if (dest->a != 0x00)
            {
                dest->r = source->r;
                dest->g = source->g;
                dest->b = source->b;
            }
            ++source;
            ++dest;
        }
    }
    else
    {
        for (std::int32_t i = 0; i < width * height * stride; i += stride)
        {
            dest->r = source->r;
            dest->g = source->g;
            dest->b = source->b;
            dest->a = source->a;
            ++source;
            ++dest;
        }
    }
};

template<typename S, typename D>
void alpha_blend_pixels(S source, D dest, std::int32_t width, std::int32_t height, std::int32_t stride)
{
    auto blend_alpha = [](std::uint8_t back, std::uint8_t front, std::uint8_t alpha) -> std::uint8_t {
        return ((front * alpha) + (back * (0xFF - alpha))) / 0xFF;
    };

    for (std::int32_t i = 0; i < width * height * stride; i += stride)
    {
        if constexpr(std::is_same<S, D>::value && std::is_same<S, bgr_bgra_t*>::value)
        {
            dest->a = *reinterpret_cast<std::uint32_t*>(source) == 0x00 ? 0x00 : 0xFF;
            if (dest->a != 0x00)
            {
                dest->r = source->r;
                dest->g = source->g;
                dest->b = source->b;
            }
        }
        else
        {
            // Pre-Multiplied Alpha
            dest->r = blend_alpha(dest->r, source->r, source->a);
            dest->g = blend_alpha(dest->g, source->g, source->a);
            dest->b = blend_alpha(dest->b, source->b, source->a);
            dest->a = 0xFF;
        }

        ++source;
        ++dest;
    }
}

void FlipImageBytes(void* In, void* Out, std::int32_t width, std::int32_t height, std::uint32_t Bpp) noexcept
{
    std::size_t Chunk = (Bpp > 24 ? width * 4 : width * 3 + width % 4);
   std::uint8_t* Destination = static_cast<std::uint8_t*>(Out);
    std::uint8_t* Source = static_cast<std::uint8_t*>(In) + Chunk * (height - 1);

   while(Source != In)
   {
      //std::memcpy(Destination, Source, Chunk);
       for (std::size_t i = 0; i < Chunk; ++i)
       {
           *(Destination + i) = *(Source + i);
       }

      Destination += Chunk;
      Source -= Chunk;
   }
}

void FlipImageVertically(std::int32_t width, std::int32_t height, std::uint8_t* data) noexcept
{
    bgra_t pixel = {0};
    for (std::int32_t y = 0; y < height / 2; ++y)
    {
        for (std::int32_t x = 0; x < width; ++x)
        {
            std::uint32_t top = (x + y * width) * sizeof(bgra_t);
            std::uint32_t bottom = (x + (height - y - 1) * width) * sizeof(bgra_t);

            std::memcpy(&pixel, data + top, sizeof(bgra_t));
            std::memcpy(data + top, data + bottom, sizeof(bgra_t));
            std::memcpy(data + bottom, &pixel, sizeof(bgra_t));
        }
    }
}

void FlipImageVertically2(std::int32_t width, std::int32_t height, std::uint8_t* data) noexcept
{
    const std::size_t stride = width * 4;
    std::unique_ptr<std::uint8_t[]> row = std::make_unique<std::uint8_t[]>(stride);

    for (std::uint8_t* it = data, *jt = &data[(height - 1) * stride]; it < jt; it += stride, jt -= stride)
    {
        std::memcpy(row.get(), it, stride);
        std::memcpy(it, jt, stride);
        std::memcpy(jt, row.get(), stride);
    }
}

void draw_circle(std::int32_t x, std::int32_t y, std::int32_t radius, void* buffer, std::int32_t width, std::int32_t height, std::int32_t stride, bool filled, std::int32_t abgr_colour) noexcept
{
    bgra_t draw_colour = {0};
    RGBA(abgr_colour, draw_colour.r, draw_colour.g, draw_colour.b, draw_colour.a);

    auto set_pixel = [&](int x, int y) {
        std::uint8_t* ptr = static_cast<std::uint8_t*>(buffer);
        bgra* pixel = reinterpret_cast<bgra*>(&ptr[(y * width + x) * stride]);
        *pixel = draw_colour;
    };

    int radiusSq = radius * radius;
    int area = (radiusSq << 2) + (radius << 2) + 1;
    int rr = (radius << 1) + 1;

    for (int i = 0; i < area; ++i)
    {
        int xx = (i % rr) - radius;
        int yy = (i / rr) - radius;

        //clamp
        if (x + xx >= 0 && y + yy >= 0 && x + xx < width && y + yy < height)
        {
            if (filled)
            {
                if (xx * xx + yy * yy < radiusSq + radius)
                {
                    set_pixel(x + xx, y + yy);
                }
            }
            else
            {
                if (xx * xx + yy * yy > radiusSq - radius && xx * xx + yy * yy < radiusSq + radius)
                {
                    set_pixel(x + xx, y + yy);
                }
            }
        }
    }
}

void copy_image_to_bgra(void* dest_buffer, void* source_buffer, std::int32_t width, std::int32_t height, std::int32_t stride, ImageFormat source_format) noexcept
{
    switch (source_format)
    {
        case ImageFormat::BGR_BGRA:
            convert_pixels(static_cast<bgr_bgra_t*>(source_buffer), static_cast<bgra_t*>(dest_buffer), width, height, stride);
            break;

        case ImageFormat::BGRA:
            convert_pixels(static_cast<bgra_t*>(source_buffer), static_cast<bgra_t*>(dest_buffer), width, height, stride);
            break;

        case ImageFormat::RGBA:
            convert_pixels(static_cast<rgba_t*>(source_buffer), static_cast<bgra_t*>(dest_buffer), width, height, stride);
            break;

        case ImageFormat::ARGB:
            convert_pixels(static_cast<argb_t*>(source_buffer), static_cast<bgra_t*>(dest_buffer), width, height, stride);
            break;

        case ImageFormat::ABGR:
            convert_pixels(static_cast<abgr_t*>(source_buffer), static_cast<bgra_t*>(dest_buffer), width, height, stride);
            break;
    }
}

void copy_image_from_bgra(void* dest_buffer, void* source_buffer, std::int32_t width, std::int32_t height, std::int32_t stride, ImageFormat destination_format) noexcept
{
    switch (destination_format)
    {
        case ImageFormat::BGR_BGRA:
            convert_pixels(static_cast<bgra_t*>(source_buffer), static_cast<bgr_bgra_t*>(dest_buffer), width, height, stride);
            break;

        case ImageFormat::BGRA:
            convert_pixels(static_cast<bgra_t*>(source_buffer), static_cast<bgra_t*>(dest_buffer), width, height, stride);
            break;

        case ImageFormat::RGBA:
            convert_pixels(static_cast<bgra_t*>(source_buffer), static_cast<rgba_t*>(dest_buffer), width, height, stride);
            break;

        case ImageFormat::ARGB:
            convert_pixels(static_cast<bgra_t*>(source_buffer), static_cast<argb_t*>(dest_buffer), width, height, stride);
            break;

        case ImageFormat::ABGR:
            convert_pixels(static_cast<bgra_t*>(source_buffer), static_cast<abgr_t*>(dest_buffer), width, height, stride);
            break;
    }
}

void draw_image(void* dest_buffer, void* source_buffer, std::int32_t width, std::int32_t height, std::int32_t stride, ImageFormat format) noexcept
{
    switch (format)
    {
        case ImageFormat::BGR_BGRA:
            alpha_blend_pixels(static_cast<bgr_bgra_t*>(source_buffer), static_cast<bgr_bgra_t*>(dest_buffer), width, height, stride);
            break;

        case ImageFormat::BGRA:
            alpha_blend_pixels(static_cast<bgra_t*>(source_buffer), static_cast<bgra_t*>(dest_buffer), width, height, stride);
            break;

        case ImageFormat::RGBA:
            alpha_blend_pixels(static_cast<rgba_t*>(source_buffer), static_cast<bgra_t*>(dest_buffer), width, height, stride);
            break;

        case ImageFormat::ARGB:
            alpha_blend_pixels(static_cast<argb_t*>(source_buffer), static_cast<bgra_t*>(dest_buffer), width, height, stride);
            break;

        case ImageFormat::ABGR:
            alpha_blend_pixels(static_cast<abgr_t*>(source_buffer), static_cast<bgra_t*>(dest_buffer), width, height, stride);
            break;
    }
}

// OpenGL 3.1+ function pointers
#if !defined(__APPLE__)
    #if defined(_WIN32) || defined(_WIN64)
    #define STDCALL __stdcall
    #else
    #define STDCALL
    #endif
static void (STDCALL *glGenVertexArrays)(GLsizei n, GLuint *arrays) = nullptr;
static void (STDCALL *glBindVertexArray)(GLuint array) = nullptr;
static void (STDCALL *glDeleteVertexArrays)(GLsizei n, const GLuint *arrays) = nullptr;
static void (STDCALL *glGenBuffers)(GLsizei n, GLuint *buffers) = nullptr;
static void (STDCALL *glBindBuffer)(GLenum target, GLuint buffer) = nullptr;
static void (STDCALL *glDeleteBuffers)(GLsizei n, const GLuint *buffers) = nullptr;
static void (STDCALL *glBufferData)(GLenum target, GLsizeiptr size, const void *data, GLenum usage) = nullptr;
static GLuint (STDCALL *glCreateShader)(GLenum type) = nullptr;
static void (STDCALL *glShaderSource)(GLuint shader, GLsizei count, const GLchar *const*string, const GLint *length) = nullptr;
static void (STDCALL *glCompileShader)(GLuint shader) = nullptr;
static void (STDCALL *glGetShaderiv)(GLuint shader, GLenum pname, GLint *params) = nullptr;
static GLuint (STDCALL *glCreateProgram)(void) = nullptr;
static void (STDCALL *glAttachShader)(GLuint program, GLuint shader) = nullptr;
static void (STDCALL *glLinkProgram)(GLuint program) = nullptr;
static void (STDCALL *glUseProgram)(GLuint program) = nullptr;
static void (STDCALL *glDeleteProgram)(GLuint program) = nullptr;
static void (STDCALL *glDeleteShader)(GLuint shader) = nullptr;
static GLint (STDCALL *glGetUniformLocation)(GLuint program, const GLchar *name) = nullptr;
static void (STDCALL *glUniform1i)(GLint location, GLint v0) = nullptr;
static void (STDCALL *glUniform1f)(GLint location, GLfloat v0) = nullptr;
static void (STDCALL *glUniform4fv)(GLint location, GLsizei count, const GLfloat *value) = nullptr;
static void (STDCALL *glUniformMatrix4fv)(GLint location, GLsizei count, GLboolean transpose, const GLfloat *value) = nullptr;
static GLint (STDCALL *glGetAttribLocation)(GLuint program, const GLchar *name) = nullptr;
static void (STDCALL *glEnableVertexAttribArray)(GLuint index) = nullptr;
static void (STDCALL *glVertexAttribPointer)(GLuint index, GLint size, GLenum type, GLboolean normalized, GLsizei stride, const void *pointer) = nullptr;
static void (STDCALL *glDisableVertexAttribArray)(GLuint index) = nullptr;

bool LoadModernGLFunctions() noexcept
{
    if (glGenVertexArrays)
    {
        return true;
    }

    #if defined(_WIN32) || defined(_WIN64)
    #define xGetProcAddress wglGetProcAddress
    #elif !defined(__APPLE__)
    #define xGetProcAddress(procName) glXGetProcAddress(reinterpret_cast<const GLubyte*>(procName))
    #endif

    glGenVertexArrays = reinterpret_cast<decltype(glGenVertexArrays)>(xGetProcAddress("glGenVertexArrays"));
    glBindVertexArray = reinterpret_cast<decltype(glBindVertexArray)>(xGetProcAddress("glBindVertexArray"));
    glDeleteVertexArrays = reinterpret_cast<decltype(glDeleteVertexArrays)>(xGetProcAddress("glDeleteVertexArrays"));
    glGenBuffers = reinterpret_cast<decltype(glGenBuffers)>(xGetProcAddress("glGenBuffers"));
    glBindBuffer = reinterpret_cast<decltype(glBindBuffer)>(xGetProcAddress("glBindBuffer"));
    glDeleteBuffers = reinterpret_cast<decltype(glDeleteBuffers)>(xGetProcAddress("glDeleteBuffers"));
    glBufferData = reinterpret_cast<decltype(glBufferData)>(xGetProcAddress("glBufferData"));
    glCreateShader = reinterpret_cast<decltype(glCreateShader)>(xGetProcAddress("glCreateShader"));
    glShaderSource = reinterpret_cast<decltype(glShaderSource)>(xGetProcAddress("glShaderSource"));
    glCompileShader = reinterpret_cast<decltype(glCompileShader)>(xGetProcAddress("glCompileShader"));
    glGetShaderiv = reinterpret_cast<decltype(glGetShaderiv)>(xGetProcAddress("glGetShaderiv"));
    glCreateProgram = reinterpret_cast<decltype(glCreateProgram)>(xGetProcAddress("glCreateProgram"));
    glAttachShader = reinterpret_cast<decltype(glAttachShader)>(xGetProcAddress("glAttachShader"));
    glLinkProgram = reinterpret_cast<decltype(glLinkProgram)>(xGetProcAddress("glLinkProgram"));
    glUseProgram = reinterpret_cast<decltype(glUseProgram)>(xGetProcAddress("glUseProgram"));
    glDeleteProgram = reinterpret_cast<decltype(glDeleteProgram)>(xGetProcAddress("glDeleteProgram"));
    glDeleteShader = reinterpret_cast<decltype(glDeleteShader)>(xGetProcAddress("glDeleteShader"));
    glGetUniformLocation = reinterpret_cast<decltype(glGetUniformLocation)>(xGetProcAddress("glGetUniformLocation"));
    glUniform1i = reinterpret_cast<decltype(glUniform1i)>(xGetProcAddress("glUniform1i"));
    glUniform1f = reinterpret_cast<decltype(glUniform1f)>(xGetProcAddress("glUniform1f"));
    glUniform4fv = reinterpret_cast<decltype(glUniform4fv)>(xGetProcAddress("glUniform4fv"));
    glUniformMatrix4fv = reinterpret_cast<decltype(glUniformMatrix4fv)>(xGetProcAddress("glUniformMatrix4fv"));
    glGetAttribLocation = reinterpret_cast<decltype(glGetAttribLocation)>(xGetProcAddress("glGetAttribLocation"));
    glEnableVertexAttribArray = reinterpret_cast<decltype(glEnableVertexAttribArray)>(xGetProcAddress("glEnableVertexAttribArray"));
    glVertexAttribPointer = reinterpret_cast<decltype(glVertexAttribPointer)>(xGetProcAddress("glVertexAttribPointer"));
    glDisableVertexAttribArray = reinterpret_cast<decltype(glDisableVertexAttribArray)>(xGetProcAddress("glDisableVertexAttribArray"));

    return glGenVertexArrays && glBindVertexArray && glGenBuffers && glBindBuffer && glBufferData && glCreateShader && glCreateProgram && glUseProgram && glUniform4fv;
}
#else
bool LoadModernGLFunctions() noexcept
{
    return true;
}
#endif

GLuint CompileGLShader(void* ctx, const char* vertex_src, const char* fragment_src) noexcept
{
    #if defined(__APPLE__)
    CGLContextObj CGL_MACRO_CONTEXT = static_cast<CGLContextObj>(ctx);
    #endif

    GLuint vertex_shader = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vertex_shader, 1, &vertex_src, nullptr);
    glCompileShader(vertex_shader);

    GLint success = 0;
    glGetShaderiv(vertex_shader, GL_COMPILE_STATUS, &success);
    if (!success)
    {
        glDeleteShader(vertex_shader);
        return 0;
    }

    GLuint fragment_shader = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fragment_shader, 1, &fragment_src, nullptr);
    glCompileShader(fragment_shader);

    glGetShaderiv(fragment_shader, GL_OBJECT_COMPILE_STATUS_ARB, &success);
    if (!success)
    {
        glDeleteShader(vertex_shader);
        glDeleteShader(fragment_shader);
        return 0;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vertex_shader);
    glAttachShader(program, fragment_shader);
    glLinkProgram(program);

    glDeleteShader(vertex_shader);
    glDeleteShader(fragment_shader);

    return program;
}

void gl_draw_point_modern(void* ctx, float x, float y, float z, float radius, GLint viewport_width, GLint viewport_height) noexcept
{
    #if defined(__APPLE__)
    CGLContextObj CGL_MACRO_CONTEXT = static_cast<CGLContextObj>(ctx);
    #endif

    static const char* vertex_shader_src = R"(
        #version 150 core
        in vec2 position;
        uniform mat4 projection;
        uniform float pointSize;
        void main() {
            gl_Position = projection * vec4(position, 0.0, 1.0);
            gl_PointSize = pointSize;
        }
    )";

    static const char* fragment_shader_src = R"(
        #version 150 core
        uniform vec4 color;
        out vec4 fragColor;
        void main() {
            fragColor = color;
        }
    )";

    // Create shader program once
    static GLuint shader_program = 0;
    static GLuint vao = 0;
    static GLuint vbo = 0;

    if (shader_program == 0)
    {
        shader_program = CompileGLShader(ctx, vertex_shader_src, fragment_shader_src);
        if (shader_program == 0)
        {
            return;
        }

        // Create VAO and VBO
        glGenVertexArrays(1, &vao);
        glGenBuffers(1, &vbo);
    }

    // Orthographic projection matrix
    float projection[16] = {
        2.0f / viewport_width, 0.0f, 0.0f, 0.0f,
        0.0f, -2.0f / viewport_height, 0.0f, 0.0f,
        0.0f, 0.0f, -1.0f, 0.0f,
        -1.0f, 1.0f, 0.0f, 1.0f
    };

    // Save current GL state
    GLint last_program, last_array_buffer, last_vertex_array;
    glGetIntegerv(GL_CURRENT_PROGRAM, &last_program);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &last_array_buffer);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &last_vertex_array);

    GLboolean last_blend = glIsEnabled(GL_BLEND);
    GLboolean last_depth = glIsEnabled(GL_DEPTH_TEST);
    GLboolean last_program_point_size = glIsEnabled(GL_PROGRAM_POINT_SIZE);
    GLint last_blend_src, last_blend_dst;
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &last_blend_src);
    glGetIntegerv(GL_BLEND_DST_ALPHA, &last_blend_dst);

    // Set up rendering state
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_DEPTH_TEST);
    glEnable(GL_PROGRAM_POINT_SIZE);

    glUseProgram(shader_program);

    // Set uniforms
    GLint proj_loc = glGetUniformLocation(shader_program, "projection");
    glUniformMatrix4fv(proj_loc, 1, GL_FALSE, projection);

    GLint size_loc = glGetUniformLocation(shader_program, "pointSize");
    glUniform1f(size_loc, radius);

    GLint color_loc = glGetUniformLocation(shader_program, "color");
    GLfloat current_color[4];
    glGetFloatv(GL_CURRENT_COLOR, current_color);
    glUniform4fv(color_loc, 1, current_color);

    // Point position
    float vertices[] = {x, y};

    // Bind VAO and upload vertex data
    glBindVertexArray(vao);

    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_DYNAMIC_DRAW);

    // Set vertex attributes
    GLint pos_attrib = glGetAttribLocation(shader_program, "position");
    glEnableVertexAttribArray(pos_attrib);
    glVertexAttribPointer(pos_attrib, 2, GL_FLOAT, GL_FALSE, 0, nullptr);

    // Draw point
    glDrawArrays(GL_POINTS, 0, 1);

    // Restore GL state
    glBindBuffer(GL_ARRAY_BUFFER, last_array_buffer);
    glBindVertexArray(last_vertex_array);

    glUseProgram(last_program);

    if (!last_blend)
    {
        glDisable(GL_BLEND);
    }

    if (last_depth)
    {
        glEnable(GL_DEPTH_TEST);
    }

    if (!last_program_point_size)
    {
        glDisable(GL_PROGRAM_POINT_SIZE);
    }

    glBlendFunc(last_blend_src, last_blend_dst);
}

void gl_draw_point(void* ctx, float x, float y, float z, float radius) noexcept
{
    #if defined(__APPLE__)
    CGLContextObj CGL_MACRO_CONTEXT = static_cast<CGLContextObj>(ctx);
    #endif

    // Check for Modern OpenGL 3.1+
    static bool modern_gl_attempted = false;
    static bool modern_gl_available = false;

    if (!modern_gl_attempted)
    {
        modern_gl_available = LoadModernGLFunctions();
        modern_gl_attempted = true;
    }

    if (modern_gl_available)
    {
        GLint viewport[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        gl_draw_point_modern(ctx, x, y, z, radius, viewport[2], viewport[3]);
        return;
    }

    // Fall back to legacy OpenGL
    GLfloat prev_point_size = 1.0f;
    glGetFloatv(GL_POINT_SIZE, &prev_point_size);

    GLboolean is_blend = glIsEnabled(GL_BLEND);
    GLboolean is_texture_2d = glIsEnabled(GL_TEXTURE_2D);
    GLboolean is_texture_rectangle = glIsEnabled(GL_TEXTURE_RECTANGLE);
    GLboolean is_point_smooth = glIsEnabled(GL_POINT_SMOOTH);

    // Set up fixed-function state
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_TEXTURE_2D);
    glDisable(GL_TEXTURE_RECTANGLE);
    glEnable(GL_POINT_SMOOTH);
    glHint(GL_POINT_SMOOTH_HINT, GL_NICEST);

    // Save current matrices
    GLint viewport[4];
    glGetIntegerv(GL_VIEWPORT, viewport);

    glMatrixMode(GL_PROJECTION);
    glPushMatrix();
    glLoadIdentity();
    glOrtho(0, viewport[2], viewport[3], 0, -1, 1);

    glMatrixMode(GL_MODELVIEW);
    glPushMatrix();
    glLoadIdentity();

    // Draw point in pixel coordinates
    glPointSize(radius);
    glBegin(GL_POINTS);
        glVertex3f(x, y, z);
    glEnd();

    // Restore matrices
    glPopMatrix();
    glMatrixMode(GL_PROJECTION);
    glPopMatrix();
    glMatrixMode(GL_MODELVIEW);

    // Restore state
    glPointSize(prev_point_size);

    if (!is_blend)
    {
        glDisable(GL_BLEND);
    }

    if (is_texture_2d)
    {
        glEnable(GL_TEXTURE_2D);
    }

    if (is_texture_rectangle)
    {
        glEnable(GL_TEXTURE_RECTANGLE);
    }

    if (!is_point_smooth)
    {
        glDisable(GL_POINT_SMOOTH);
    }
}

void gl_draw_image_modern(void* ctx, void* source_buffer, float x, float y, std::int32_t width, std::int32_t height, std::int32_t stride, ImageFormat format, GLint viewport_width, GLint viewport_height) noexcept
{
    #if defined(__APPLE__)
    CGLContextObj CGL_MACRO_CONTEXT = static_cast<CGLContextObj>(ctx);
    #endif

    static const char* vertex_shader_src = R"(
        #version 150 core
        in vec2 position;
        in vec2 texCoord;
        out vec2 fragTexCoord;
        uniform mat4 projection;
        void main() {
            fragTexCoord = texCoord;
            gl_Position = projection * vec4(position, 0.0, 1.0);
        }
    )";

    static const char* fragment_shader_src = R"(
        #version 150 core
        in vec2 fragTexCoord;
        out vec4 fragColor;
        uniform sampler2D textureSampler;
        void main() {
            fragColor = texture(textureSampler, fragTexCoord);
        }
    )";

    GLenum gl_format = [](ImageFormat format) -> GLenum {
        switch(format)
        {
            case ImageFormat::BGR_BGRA: return GL_BGRA;
            case ImageFormat::BGRA: return GL_BGRA;
            case ImageFormat::RGBA: return GL_RGBA;
            case ImageFormat::ARGB: return 0;
            case ImageFormat::ABGR: return 0;
            default: return GL_BGRA;
        }
    }(format);

    auto convert = []<typename S>(S* source, std::int32_t width, std::int32_t height, std::int32_t stride, ImageFormat format) {
        if constexpr(std::is_same<S, bgr_bgra_t>::value)
        {
            for (std::int32_t i = 0; i < width * height * stride; i += stride)
            {
                source->a = *reinterpret_cast<std::uint32_t*>(source) == 0x00 ? 0x00 : 0xFF;
                ++source;
            }
        }
    };

    switch (format)
    {
        case ImageFormat::BGR_BGRA:
            convert(static_cast<bgr_bgra_t*>(source_buffer), width, height, stride, format);
            break;
        case ImageFormat::BGRA:
            convert(static_cast<bgra_t*>(source_buffer), width, height, stride, format);
            break;
        case ImageFormat::RGBA:
            convert(static_cast<rgba_t*>(source_buffer), width, height, stride, format);
            break;
        case ImageFormat::ARGB:
            convert(static_cast<argb_t*>(source_buffer), width, height, stride, format);
            break;
        case ImageFormat::ABGR:
            convert(static_cast<abgr_t*>(source_buffer), width, height, stride, format);
            break;
        default:
            convert(static_cast<bgra_t*>(source_buffer), width, height, stride, format);
            break;
    }

    // Create resources once
    static GLuint shader_program = 0;
    static GLuint vao = 0;
    static GLuint vbo = 0;
    static GLuint texture_id = 0;
    static std::int32_t tex_width = 0;
    static std::int32_t tex_height = 0;

    if (shader_program == 0)
    {
        shader_program = CompileGLShader(ctx, vertex_shader_src, fragment_shader_src);
        if (shader_program == 0)
        {
            return;
        }

        // Create VAO and VBO
        glGenVertexArrays(1, &vao);
        glGenBuffers(1, &vbo);
    }

    if (texture_id == 0 || tex_width != width || tex_height != height)
    {
        if (texture_id != 0)
        {
            glDeleteTextures(1, &texture_id);
        }

        tex_width = width;
        tex_height = height;

        glGenTextures(1, &texture_id);
        glBindTexture(GL_TEXTURE_2D, texture_id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, gl_format, GL_UNSIGNED_BYTE, source_buffer);
    }
    else
    {
        glBindTexture(GL_TEXTURE_2D, texture_id);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, gl_format, GL_UNSIGNED_BYTE, source_buffer);
    }

    // Rendering
    float x1 = x;
    float y1 = y;
    float x2 = x + width;
    float y2 = y + height;

    float vertices[] = {
            // Position      // TexCoord
            x1, y1,          0.0f, 1.0f,  // Bottom-left
            x2, y1,          1.0f, 1.0f,  // Bottom-right
            x1, y2,          0.0f, 0.0f,  // Top-left

            x2, y1,          1.0f, 1.0f,  // Bottom-right
            x2, y2,          1.0f, 0.0f,  // Top-right
            x1, y2,          0.0f, 0.0f   // Top-left
    };

    // Orthographic projection matrix (maps screen coords to NDC)
    // Bottom-left origin (OpenGL default): Y increases upward
    float projection[16] = {
            2.0f / viewport_width, 0.0f, 0.0f, 0.0f,
            0.0f, 2.0f / viewport_height, 0.0f, 0.0f,
            0.0f, 0.0f, -1.0f, 0.0f,
            -1.0f, -1.0f, 0.0f, 1.0f
    };

    // Save current GL state
    GLint last_program, last_texture, last_array_buffer, last_vertex_array;
    glGetIntegerv(GL_CURRENT_PROGRAM, &last_program);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &last_texture);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &last_array_buffer);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &last_vertex_array);

    GLboolean last_blend = glIsEnabled(GL_BLEND);
    GLboolean last_depth = glIsEnabled(GL_DEPTH_TEST);
    GLint last_blend_src, last_blend_dst;
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &last_blend_src);
    glGetIntegerv(GL_BLEND_DST_ALPHA, &last_blend_dst);

    // Set up rendering state
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_DEPTH_TEST);

    glUseProgram(shader_program);

    // Set Shaders
    GLint proj_loc = glGetUniformLocation(shader_program, "projection");
    glUniformMatrix4fv(proj_loc, 1, GL_FALSE, projection);

    GLint tex_loc = glGetUniformLocation(shader_program, "textureSampler");
    glUniform1i(tex_loc, 0);

    // Bind VAO and upload vertex data
    glBindVertexArray(vao);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_DYNAMIC_DRAW);

    // Set vertex attributes
    GLint pos_attrib = glGetAttribLocation(shader_program, "position");
    GLint tex_attrib = glGetAttribLocation(shader_program, "texCoord");

    glEnableVertexAttribArray(pos_attrib);
    glVertexAttribPointer(pos_attrib, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);

    glEnableVertexAttribArray(tex_attrib);
    glVertexAttribPointer(tex_attrib, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

    // Draw
    glDrawArrays(0x0004, 0, 6);  // glTRIANGLES

    // Restore GL state
    glBindTexture(GL_TEXTURE_2D, last_texture);
    glBindBuffer(GL_ARRAY_BUFFER, last_array_buffer);
    glBindVertexArray(last_vertex_array);

    glUseProgram(last_program);

    if (!last_blend)
    {
        glDisable(GL_BLEND);
    }

    if (last_depth)
    {
        glEnable(GL_DEPTH_TEST);
    }

    glBlendFunc(last_blend_src, last_blend_dst);
}

void gl_draw_image(void* ctx, void* source_buffer, float x, float y, std::int32_t width, std::int32_t height, std::int32_t stride, ImageFormat format) noexcept
{
    #if defined(__APPLE__)
    CGLContextObj CGL_MACRO_CONTEXT = static_cast<CGLContextObj>(ctx);
    #endif

    // Check for Modern OpenGL 3.1+
    static bool modern_gl_attempted = false;
    static bool modern_gl_available = false;

    if (!modern_gl_attempted)
    {
        modern_gl_available = LoadModernGLFunctions();
        modern_gl_attempted = true;
    }

    if (modern_gl_available)
    {
        GLint viewport[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        gl_draw_image_modern(ctx, source_buffer, x, y, width, height, stride, format, viewport[2], viewport[3]);
        return;
    }

    // Fall back to legacy OpenGL
    GLenum gl_format = [](ImageFormat format) -> GLenum {
        switch(format)
        {
            case ImageFormat::BGR_BGRA: return GL_BGRA;
            case ImageFormat::BGRA: return GL_BGRA;
            case ImageFormat::RGBA: return GL_RGBA;
            case ImageFormat::ARGB: return 0;  // Not Supported
            case ImageFormat::ABGR: return 0;  // Not Supported
            default: return GL_BGRA;
        }
    }(format);

    auto convert = []<typename S>(S* source, std::int32_t width, std::int32_t height, std::int32_t stride, ImageFormat format) {
        if constexpr(std::is_same<S, bgr_bgra_t>::value)
        {
            for (std::int32_t i = 0; i < width * height * stride; i += stride)
            {
                source->a = *reinterpret_cast<std::uint32_t*>(source) == 0x00 ? 0x00 : 0xFF;
                ++source;
            }
        }
    };

    //Backup
    bool GLBlend = glIsEnabled(GL_BLEND);
    bool GLTexture2D = glIsEnabled(GL_TEXTURE_2D);
    bool GLRectangle = glIsEnabled(GL_TEXTURE_RECTANGLE);
    bool PointSmooth = glIsEnabled(GL_POINT_SMOOTH);

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    glDisable(GL_TEXTURE_2D);
    glEnable(GL_POINT_SMOOTH);
    glHint(GL_POINT_SMOOTH_HINT, GL_NICEST);

    glPushMatrix();
    glLoadIdentity();

    //Load Texture
    switch (format)
    {
        case ImageFormat::BGR_BGRA:
            convert(static_cast<bgr_bgra_t*>(source_buffer), width, height, stride, format);
            break;

        case ImageFormat::BGRA:
            convert(static_cast<bgra_t*>(source_buffer), width, height, stride, format);
            break;

        case ImageFormat::RGBA:
            convert(static_cast<rgba_t*>(source_buffer), width, height, stride, format);
            break;

        case ImageFormat::ARGB:
            convert(static_cast<argb_t*>(source_buffer), width, height, stride, format);
            break;

        case ImageFormat::ABGR:
            convert(static_cast<abgr_t*>(source_buffer), width, height, stride, format);
            break;

        default:
            convert(static_cast<bgra_t*>(source_buffer), width, height, stride, format);
            break;
    }

    static GLuint ID = 0;
    static std::int32_t w = 0;
    static std::int32_t h = 0;

    GLenum target = GL_TEXTURE_RECTANGLE;

    if (ID == 0 || w != width || h != height)
    {
        if (ID != 0)
        {
            glDeleteTextures(1, &ID);
            ID = 0;
        }

        w = width;
        h = height;

        glGenTextures(1, &ID);
        glBindTexture(target, ID);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, width);
        glTexImage2D(target, 0, GL_RGBA, width, height, 0, gl_format, GL_UNSIGNED_BYTE, source_buffer);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glTexParameteri(target, GL_TEXTURE_WRAP_S, target == GL_TEXTURE_2D ? GL_REPEAT : GL_CLAMP_TO_EDGE);
        glTexParameteri(target, GL_TEXTURE_WRAP_T, target == GL_TEXTURE_2D ? GL_REPEAT : GL_CLAMP_TO_EDGE);
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    }
    else
    {
        glBindTexture(target, ID);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, width);
        glTexSubImage2D(target, 0, 0, 0, width, height, gl_format, GL_UNSIGNED_BYTE, source_buffer);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glBindTexture(target, 0);
    }


    float x1 = x;
    float y1 = y;
    float x2 = width;
    float y2 = height;
    width = target == GL_TEXTURE_2D ? 1 : width;
    height = target == GL_TEXTURE_2D ? 1 : height;

    //Render Texture
    glEnable(target);
    glBindTexture(target, ID);
    glColor4ub(0xFF, 0xFF, 0xFF, 0xFF);
    glBegin(GL_QUADS);
        glTexCoord2f(0, height);
        glVertex2f(x1, y1);
        glTexCoord2f(0, 0);
        glVertex2f(x1, y2);
        glTexCoord2f(width, 0);
        glVertex2f(x2, y2);
        glTexCoord2f(width, height);
        glVertex2f(x2, y1);
    glEnd();
    glBindTexture(target, 0);
    glDisable(target);


    //Restore
    glPopMatrix();

    if (!GLBlend)
    {
        glDisable(GL_BLEND);
    }

    if (GLTexture2D)
    {
        glEnable(GL_TEXTURE_2D);
    }

    if (GLRectangle)
    {
        glEnable(GL_TEXTURE_RECTANGLE);
    }

    if (!PointSmooth)
    {
        glDisable(GL_POINT_SMOOTH);
    }
}

#if defined(_WIN32) || defined(_WIN64)
#define VERTEX_FVF_TEX D3DFVF_XYZRHW | D3DFVF_DIFFUSE | D3DFVF_TEX1

struct D3DVertex
{
    float X, Y, Z, RHW;
    unsigned int Colour;
    float U, V;
};

HMODULE dx_get_d3dx9_module() noexcept
{
    // [24, 43] d3dx9 versions
    char d3dx9_version[32] = {0};
    for (int i = 43; i > 23; --i)
    {
        sprintf(d3dx9_version, "d3dx9_%d.dll", i);

        HMODULE module = GetModuleHandle(d3dx9_version);
        if (!module)
        {
            module = LoadLibrary(d3dx9_version);
        }

        if (module)
        {
            return module;
        }
    }

    return nullptr;
}

IDirect3DPixelShader9* dx_texture_render_shader(IDirect3DDevice9* device, ID3DXConstantTable* &table)
{
    static const char* shader_code = R"(
        sampler2D tex;
        int format_id;

        struct PS_INPUT {
            float2 texCoord : TEXCOORD0;
        };

        float4 PS_Main(PS_INPUT input) : COLOR {
            float4 color = tex2D(tex, input.texCoord);
            float4 output = color;

            if (format_id == 0) {
                if (color.r == 0 && color.g == 0 && color.b == 0) {
                    output = float4(color.r, color.g, color.b, 0.0);
                }
                else {
                    output = float4(color.r, color.g, color.b, 1.0);
                }
            }
            else if (format_id == 1) {
                output = color;
            }
            else if (format_id == 2) {
                output = float4(color.b, color.g, color.r, color.a);
            }
            else if (format_id == 3) {
                output = float4(color.g, color.r, color.a, color.b);
            }
            else if (format_id == 4) {
                output = float4(color.a, color.r, color.g, color.b);
            }

            return output;
        }
    )";

    if (!table)
    {
        return dx_compile_shader(device, table, shader_code);
    }
    return nullptr;
}

IDirect3DPixelShader9* dx_read_backbuffer_shader(IDirect3DDevice9* device, ID3DXConstantTable** table)
{
    static const char* shader_code = R"(
        sampler2D screen_texture;
        int format_id;

        struct PS_INPUT {
            float2 texCoord : TEXCOORD0;
        };

        float4 PS_Main(PS_INPUT input) : COLOR {
            float4 color = tex2D(screen_texture, input.texCoord);
            float4 output = color;

            if (format_id == 0) {
                if (color.r == 0 && color.g == 0 && color.b == 0) {
                    output = float4(color.r, color.g, color.b, 0.0);
                }
                else {
                    output = float4(color.r, color.g, color.b, 1.0);
                }
            }
            else if (format_id == 1) {
                output = color;
            }
            else if (format_id == 2) {
                output = float4(color.r, color.g, color.b, color.a);
            }
            else if (format_id == 3) {
                output = float4(color.a, color.r, color.g, color.b);
            }
            else if (format_id == 4) {
                output = float4(color.a, color.b, color.g, color.r);
            }

            return output;
        }
    )";

    static ID3DXConstantTable* constant_table = nullptr;
    static IDirect3DPixelShader9* shader = dx_compile_shader(device, constant_table, shader_code);

    if (table)
    {
        *table = constant_table;
    }

    return shader;
}

IDirect3DPixelShader9* dx_compile_shader(IDirect3DDevice9* device, ID3DXConstantTable* &constant_table, const std::string &shader_string) noexcept
{
    HMODULE module = dx_get_d3dx9_module();
    if (!module)
    {
        return nullptr;
    }

    auto* pD3DXCompileShader = reinterpret_cast<decltype(D3DXCompileShader)*>(GetProcAddress(module, "D3DXCompileShader"));
    if (!pD3DXCompileShader)
    {
        return nullptr;
    }

    ID3DXBuffer* shader_buffer = nullptr;
    ID3DXBuffer* error_buffer = nullptr;

    HRESULT hr = pD3DXCompileShader(
            shader_string.c_str(),
            shader_string.length(),
            nullptr,
            nullptr,
            "PS_Main",
            "ps_2_0",
            #if DEBUG
            D3DXSHADER_DEBUG,
            #else
            D3DXSHADER_OPTIMIZATION_LEVEL3,
            #endif
            &shader_buffer,
            &error_buffer,
            &constant_table
    );

    if (FAILED(hr))
    {
        if (error_buffer)
        {
            error_buffer->Release();
        }

        return nullptr;
    }

    if (shader_buffer)
    {
        IDirect3DPixelShader9* pixel_shader = nullptr;
        hr = device->CreatePixelShader(reinterpret_cast<DWORD*>(shader_buffer->GetBufferPointer()), &pixel_shader);
        shader_buffer->Release();

        if (FAILED(hr))
        {
            if (error_buffer)
            {
                error_buffer->Release();
            }
            return nullptr;
        }

        return pixel_shader;
    }

    return nullptr;
}

void dx_apply_shader(IDirect3DDevice9* device, IDirect3DPixelShader9* shader, ID3DXConstantTable* constant_table, ImageFormat format) noexcept
{
    std::int32_t format_id = [](ImageFormat format) -> std::int32_t {
        switch (format)
        {
            case ImageFormat::BGR_BGRA: return 0;
            case ImageFormat::BGRA:     return 1;
            case ImageFormat::RGBA:     return 2;
            case ImageFormat::ARGB:     return 3;
            case ImageFormat::ABGR:     return 4;
        }
        return 0; // Default to BGR_BGRA
    }(format);

    if (constant_table)
    {
        D3DXHANDLE format_handle = constant_table->GetConstantByName(nullptr, "format_id");
        if (!format_handle)
        {
            return;
        }

        constant_table->SetInt(device, format_handle, format_id);
    }

    device->SetPixelShader(shader);
}

void dx_load_texture(IDirect3DDevice9* device, IDirect3DTexture9* &texture, IDirect3DPixelShader9* &shader, ID3DXConstantTable* &constants_table, ImageFormat image_format, std::uint8_t* buffer, std::int32_t width, std::int32_t height) noexcept
{
    static std::int32_t Width = 0;
    static std::int32_t Height = 0;

    if (Width != width || Height != height)
    {
        if (texture)
        {
            texture->Release();
            texture = nullptr;
        }

        Width = width;
        Height = height;
    }

    if (!texture)
    {
        HRESULT hr = device->CreateTexture(width, height, 1, D3DUSAGE_DYNAMIC, D3DFMT_A8R8G8B8, D3DPOOL_DEFAULT, &texture, nullptr);
        if (FAILED(hr))
        {
            if (texture)
            {
                texture->Release();
                texture = nullptr;
            }
            return;
        }

        Width = width;
        Height = height;
    }

    D3DLOCKED_RECT rect;
    if (FAILED(texture->LockRect(0, &rect, nullptr, D3DLOCK_DISCARD)))
    {
        if (texture)
        {
            texture->Release();
            texture = nullptr;
        }
        return;
    }

    if (!shader && !constants_table)
    {
        shader = dx_texture_render_shader(device, constants_table);
    }

    std::uint8_t* dest = static_cast<std::uint8_t*>(rect.pBits);
    int pitch = rect.Pitch;

    if (shader)
    {
        for (int i = 0; i < height; ++i)
        {
            std::memcpy(dest + i * pitch, buffer + i * width * 4, width * 4);
        }
    }
    else
    {
        for (int i = 0; i < height; ++i)
        {
            copy_image_to_bgra(dest + i * pitch, buffer + i * width * 4, width, 1, 4, image_format);
        }
    }

    texture->UnlockRect(0);
}

void dx_draw_texture(IDirect3DDevice9* device, IDirect3DTexture9* texture, ID3DXConstantTable* &constant_table, IDirect3DPixelShader9* &shader, IDirect3DVertexBuffer9* &vertex_buffer, ImageFormat image_format, float X1, float Y1, float X2, float Y2) noexcept
{
    static std::int32_t Width = static_cast<std::int32_t>(X2 - X1);
    static std::int32_t Height = static_cast<std::int32_t>(Y2 - Y1);

    // Compile the shader just once
    if (!constant_table && !shader)
    {
        constant_table = nullptr;
        shader = dx_texture_render_shader(device, constant_table);
    }

    float UOffset = 0.5f / (X2 - X1);
    float VOffset = 0.5f / (Y2 - Y1);

    D3DVertex vertices[] = {
        {X1, Y1, 1.0f, 1.0f, D3DCOLOR_RGBA(255, 255, 255, 255), 0.0f + UOffset, 0.0f + VOffset},
        {X2, Y1, 1.0f, 1.0f, D3DCOLOR_RGBA(255, 255, 255, 255), 1.0f + UOffset, 0.0f + VOffset},
        {X1, Y2, 1.0f, 1.0f, D3DCOLOR_RGBA(255, 255, 255, 255), 0.0f + UOffset, 1.0f + VOffset},
        {X2, Y2, 1.0f, 1.0f, D3DCOLOR_RGBA(255, 255, 255, 255), 1.0f + UOffset, 1.0f + VOffset}
    };

    if (!vertex_buffer)
    {
        HRESULT res = device->CreateVertexBuffer(4 * sizeof(D3DVertex), D3DUSAGE_DYNAMIC | D3DUSAGE_WRITEONLY, D3DFVF_XYZRHW | D3DFVF_DIFFUSE | D3DFVF_TEX1, D3DPOOL_DEFAULT, &vertex_buffer, nullptr);
        if (FAILED(res))
        {
            if (vertex_buffer)
            {
                vertex_buffer->Release();
                vertex_buffer = nullptr;
            }
        }
        else
        {
            void *pVertices;
            if (SUCCEEDED(vertex_buffer->Lock(0, sizeof(vertices), (void **) &pVertices, D3DLOCK_DISCARD)))
            {
                std::memcpy(pVertices, vertices, sizeof(vertices));
                vertex_buffer->Unlock();
            }
        }
    }
    else
    {
        if (Width != static_cast<std::int32_t>(X2 - X1) || Height != static_cast<std::int32_t>(Y2 - Y1))
        {
            Width = static_cast<std::int32_t>(X2 - X1);
            Height = static_cast<std::int32_t>(Y2 - Y1);

            void *pVertices;
            if (SUCCEEDED(vertex_buffer->Lock(0, sizeof(vertices), (void **) &pVertices, D3DLOCK_DISCARD)))
            {
                std::memcpy(pVertices, vertices, sizeof(vertices));
                vertex_buffer->Unlock();
            }
        }
    }

    if (shader)
    {
        dx_apply_shader(device, shader, constant_table, image_format);
    }

    device->SetFVF(VERTEX_FVF_TEX);
    device->SetTexture(0, texture);

    if (vertex_buffer)
    {
        device->SetStreamSource(0, vertex_buffer, 0, sizeof(D3DVertex));
        device->DrawPrimitive(D3DPT_TRIANGLESTRIP, 0, 2);
        device->SetStreamSource(0, nullptr, 0, 0);
    }
    else
    {
        device->DrawPrimitiveUP(D3DPT_TRIANGLESTRIP, 2, vertices, sizeof(D3DVertex));
    }

    device->SetTexture(0, nullptr);

    if (shader)
    {
        device->SetPixelShader(nullptr);
    }
}

#if defined(DX_CLAMP_IMAGE_SUPPORT)
void dx_read_pixels(IDirect3DDevice9* device, void* buffer, std::int32_t x, std::int32_t y, std::int32_t w, std::int32_t h, std::int32_t width, std::int32_t height, bool& minimized, ImageFormat image_format) noexcept
{
    // Compile the shader just once
    static ID3DXConstantTable* constant_table = nullptr;
    static IDirect3DPixelShader9* shader = nullptr; //dx_read_backbuffer_shader(device, &constant_table);

    IDirect3DSurface9* render_target = nullptr;
    IDirect3DSurface9* dest_target = nullptr;
    IDirect3DSurface9* sub_region_surface = nullptr;

    #define SAFE_RELEASE(ptr) if (ptr) ptr->Release()

    HRESULT result = device->GetRenderTarget(0, &render_target);
    if (FAILED(result))
    {
        SAFE_RELEASE(render_target);
        return;
    }

    D3DSURFACE_DESC descriptor = {};
    render_target->GetDesc(&descriptor);
    width = static_cast<std::int32_t>(descriptor.Width);
    height = static_cast<std::int32_t>(descriptor.Height);

    D3DFORMAT format = descriptor.Format;

    HDC DC = nullptr;
    render_target->GetDC(&DC);
    minimized = IsIconic(WindowFromDC(DC));
    render_target->ReleaseDC(DC);

    #ifdef CLAMP_SUB_REGION
    // Clamp sub-region within the back-buffer
    x = std::max(0, std::min(x, width));
    y = std::max(0, std::min(y, height));
    w = std::min(w, width - x);
    h = std::min(h, height - y);
    if (w <= 0 || h <= 0)
    {
        SAFE_RELEASE(render_target);
        return;
    }

    // Sub-Region bounds
    RECT src_rect = {x, y, x + w, y + h};
    #else
    RECT src_rect = {x, y, w, h};
    #endif

    result = device->CreateOffscreenPlainSurface(w, h, format, D3DPOOL_SYSTEMMEM, &dest_target, nullptr);
    if (FAILED(result))
    {
        SAFE_RELEASE(dest_target);
        SAFE_RELEASE(render_target);
        return;
    }

    if (shader)
    {
        // Create a sub-region rendering target
        result = device->CreateRenderTarget(w, h, format, D3DMULTISAMPLE_NONE, 0, FALSE, &sub_region_surface, nullptr);
        if (FAILED(result))
        {
            SAFE_RELEASE(sub_region_surface);
            SAFE_RELEASE(dest_target);
            SAFE_RELEASE(render_target);
            return;
        }

        // Copy the pixels into the sub-region rendering target
        result = device->StretchRect(render_target, &src_rect, sub_region_surface, nullptr, D3DTEXF_NONE);
        if (FAILED(result))
        {
            SAFE_RELEASE(sub_region_surface);
            SAFE_RELEASE(dest_target);
            SAFE_RELEASE(render_target);
            return;
        }

        // Create intermediate input texture for shader processing
        IDirect3DTexture9* input_texture = nullptr;
        result = device->CreateTexture(w, h, 1, D3DUSAGE_RENDERTARGET, format, D3DPOOL_DEFAULT, &input_texture, nullptr);
        if (FAILED(result))
        {
            SAFE_RELEASE(sub_region_surface);
            SAFE_RELEASE(dest_target);
            SAFE_RELEASE(render_target);
            return;
        }

        // Render the Sub-Region's Surface to the Texture
        IDirect3DSurface9* input_surface = nullptr;
        input_texture->GetSurfaceLevel(0, &input_surface);
        result = device->StretchRect(sub_region_surface, nullptr, input_surface, nullptr, D3DTEXF_NONE);
        if (FAILED(result))
        {
            SAFE_RELEASE(input_surface);
            SAFE_RELEASE(input_texture);
            SAFE_RELEASE(sub_region_surface);
            SAFE_RELEASE(dest_target);
            SAFE_RELEASE(render_target);
            return;
        }

        // Create intermediate output texture for shader processing
        IDirect3DTexture9* output_texture = nullptr;
        result = device->CreateTexture(w, h, 1, D3DUSAGE_RENDERTARGET, format, D3DPOOL_DEFAULT, &output_texture, nullptr);
        if (FAILED(result))
        {
            SAFE_RELEASE(input_surface);
            SAFE_RELEASE(input_texture);
            SAFE_RELEASE(sub_region_surface);
            SAFE_RELEASE(dest_target);
            SAFE_RELEASE(render_target);
            return;
        }

        IDirect3DSurface9* output_surface = nullptr;
        output_texture->GetSurfaceLevel(0, &output_surface);

        // Set the render target to the output texture
        device->SetRenderTarget(0, output_surface);

        // Apply the pixel format shader
        dx_apply_shader(device, shader, constant_table, image_format);

        // Draw the input texture to the output texture with the shader applied
        float UOffset = 0.5f / static_cast<float>(w);
        float VOffset = 0.5f / static_cast<float>(h);

        D3DVertex vertices[] = {
            {0.0f, 0.0f, 1.0f, 1.0f, D3DCOLOR_RGBA(255, 255, 255, 255), 0.0f + UOffset, 0.0f + VOffset},
            {static_cast<float>(w), 0.0f, 1.0f, 1.0f, D3DCOLOR_RGBA(255, 255, 255, 255), 1.0f + UOffset, 0.0f + VOffset},
            {0.0f, static_cast<float>(h), 1.0f, 1.0f, D3DCOLOR_RGBA(255, 255, 255, 255), 0.0f + UOffset, 1.0f + VOffset},
            {static_cast<float>(w), static_cast<float>(h), 1.0f, 1.0f, D3DCOLOR_RGBA(255, 255, 255, 255), 1.0f + UOffset, 1.0f + VOffset}
        };

        device->SetFVF(VERTEX_FVF_TEX);
        device->SetTexture(0, input_texture);

        // Render the input texture to the output texture
        device->DrawPrimitiveUP(D3DPT_TRIANGLESTRIP, 2, vertices, sizeof(D3DVertex));   // NO IDEA WHY. THIS LINE STOPS RENDERING ENTIRELY

        // Reset render target and shader
        device->SetRenderTarget(0, render_target);
        device->SetPixelShader(nullptr);

        // Copy the final result to destination
        result = device->GetRenderTargetData(output_surface, dest_target);
        if (SUCCEEDED(result))
        {
            D3DLOCKED_RECT rect;
            if (SUCCEEDED(dest_target->LockRect(&rect, nullptr, D3DLOCK_READONLY)))
            {
                std::memcpy(buffer, rect.pBits, w * h * 4);
                dest_target->UnlockRect();
            }
        }

        SAFE_RELEASE(output_surface);
        SAFE_RELEASE(output_texture);
        SAFE_RELEASE(input_surface);
        SAFE_RELEASE(input_texture);
        SAFE_RELEASE(sub_region_surface);
    }
    else
    {
        // Create a sub-region rendering target
        result = device->CreateRenderTarget(w, h, format, D3DMULTISAMPLE_NONE, 0, FALSE, &sub_region_surface, nullptr);
        if (FAILED(result))
        {
            SAFE_RELEASE(dest_target);
            SAFE_RELEASE(render_target);
            return;
        }

        // Copy the pixels into the sub-region rendering target
        result = device->StretchRect(render_target, &src_rect, sub_region_surface, nullptr, D3DTEXF_NONE);
        if (FAILED(result))
        {
            SAFE_RELEASE(sub_region_surface);
            SAFE_RELEASE(dest_target);
            SAFE_RELEASE(render_target);
            return;
        }

        // Copy the final result to destination
        result = device->GetRenderTargetData(sub_region_surface, dest_target);
        if (SUCCEEDED(result))
        {
            D3DLOCKED_RECT rect;
            if (SUCCEEDED(dest_target->LockRect(&rect, nullptr, D3DLOCK_READONLY)))
            {
                //std::memcpy(buffer, rect.pBits, width * height * 4);
                copy_image_from_bgra(buffer, rect.pBits, w, h, 4, image_format);
                dest_target->UnlockRect();
            }
        }

        SAFE_RELEASE(sub_region_surface);
    }

    SAFE_RELEASE(dest_target);
    SAFE_RELEASE(render_target);
}
#else
void dx_read_pixels(IDirect3DDevice9* device, void* buffer, std::int32_t width, std::int32_t height, bool& minimized, ImageFormat image_format) noexcept
{
    // Compile the shader just once
    static ID3DXConstantTable* constant_table = nullptr;
    static IDirect3DPixelShader9* shader = nullptr; //dx_read_backbuffer_shader(device, &constant_table);

    IDirect3DSurface9* render_target = nullptr;
    IDirect3DSurface9* dest_target = nullptr;

    #define SAFE_RELEASE(ptr) if (ptr) ptr->Release()

    HRESULT result = device->GetRenderTarget(0, &render_target);
    if (FAILED(result))
    {
        SAFE_RELEASE(render_target);
        return;
    }

    D3DSURFACE_DESC descriptor = {};
    render_target->GetDesc(&descriptor);
    width = static_cast<std::int32_t>(descriptor.Width);
    height = static_cast<std::int32_t>(descriptor.Height);

    D3DFORMAT format = descriptor.Format;

    HDC DC = nullptr;
    render_target->GetDC(&DC);
    minimized = IsIconic(WindowFromDC(DC));
    render_target->ReleaseDC(DC);

    result = device->CreateOffscreenPlainSurface(width, height, format, D3DPOOL_SYSTEMMEM, &dest_target, nullptr);
    if (FAILED(result))
    {
        SAFE_RELEASE(dest_target);
        SAFE_RELEASE(render_target);
        return;
    }

    if (shader)
    {
        // Texture to store converted pixels
        IDirect3DTexture9* backbuffer_texture = nullptr;
        result = device->CreateTexture(width, height, 1, D3DUSAGE_RENDERTARGET, format, D3DPOOL_DEFAULT, &backbuffer_texture, nullptr);
        if (FAILED(result))
        {
            SAFE_RELEASE(backbuffer_texture);
            SAFE_RELEASE(dest_target);
            SAFE_RELEASE(render_target);
            return;
        }

        // Render the Screen to the Texture
        IDirect3DSurface9* backbuffer_texture_surface = nullptr;
        backbuffer_texture->GetSurfaceLevel(0, &backbuffer_texture_surface);

        result = device->StretchRect(render_target, nullptr, backbuffer_texture_surface, nullptr, D3DTEXF_NONE);
        if (FAILED(result))
        {
            SAFE_RELEASE(backbuffer_texture_surface);
            SAFE_RELEASE(backbuffer_texture);
            SAFE_RELEASE(dest_target);
            SAFE_RELEASE(render_target);
            return;
        }

        // Create a texture to render the back-buffer with the shader applied
        IDirect3DTexture9* final_texture = nullptr;
        result = device->CreateTexture(width, height, 1, D3DUSAGE_RENDERTARGET, format, D3DPOOL_DEFAULT, &final_texture, nullptr);
        if (FAILED(result))
        {
            SAFE_RELEASE(final_texture);
            SAFE_RELEASE(backbuffer_texture_surface);
            SAFE_RELEASE(backbuffer_texture);
            SAFE_RELEASE(dest_target);
            SAFE_RELEASE(render_target);
            return;
        }

        IDirect3DSurface9* final_texture_surface = nullptr;
        final_texture->GetSurfaceLevel(0, &final_texture_surface);

        // Set the render target to the final texture
        device->SetRenderTarget(0, final_texture_surface);

        // Apply the pixel format shader
        dx_apply_shader(device, shader, constant_table, image_format);

        // Draw the backbuffer_texture to the final_texture with the shader applied
        float UOffset = 0.5f / static_cast<float>(width);
        float VOffset = 0.5f / static_cast<float>(height);

        D3DVertex vertices[] = {
            {0.0f, 0.0f, 1.0f, 1.0f, D3DCOLOR_RGBA(255, 255, 255, 255), 0.0f + UOffset, 0.0f + VOffset},
            {static_cast<float>(width), 0.0f, 1.0f, 1.0f, D3DCOLOR_RGBA(255, 255, 255, 255), 1.0f + UOffset, 0.0f + VOffset},
            {0.0f, static_cast<float>(height), 1.0f, 1.0f, D3DCOLOR_RGBA(255, 255, 255, 255), 0.0f + UOffset, 1.0f + VOffset},
            {static_cast<float>(width), static_cast<float>(height), 1.0f, 1.0f, D3DCOLOR_RGBA(255, 255, 255, 255), 1.0f + UOffset, 1.0f + VOffset}
        };

        device->SetFVF(VERTEX_FVF_TEX);
        device->SetTexture(0, backbuffer_texture);

        // Render the backbuffer_texture to the final_texture
        device->DrawPrimitiveUP(D3DPT_TRIANGLESTRIP, 2, vertices, sizeof(D3DVertex));   // NO IDEA WHY. THIS LINE STOPS RENDERING ENTIRELY

        // Reset render target and shader
        device->SetRenderTarget(0, render_target);
        device->SetPixelShader(nullptr);

        // Copy the final texture to destination
        result = device->GetRenderTargetData(final_texture_surface, dest_target);
        if (FAILED(result))
        {
            SAFE_RELEASE(final_texture_surface);
            SAFE_RELEASE(final_texture);
            SAFE_RELEASE(backbuffer_texture_surface);
            SAFE_RELEASE(backbuffer_texture);
            SAFE_RELEASE(dest_target);
            SAFE_RELEASE(render_target);
            return;
        }

        D3DLOCKED_RECT rect;
        if (SUCCEEDED(dest_target->LockRect(&rect, nullptr, D3DLOCK_READONLY)))
        {
            std::memcpy(buffer, rect.pBits, width * height * 4);
            dest_target->UnlockRect();
        }

        SAFE_RELEASE(final_texture_surface);
        SAFE_RELEASE(final_texture);
        SAFE_RELEASE(backbuffer_texture_surface);
        SAFE_RELEASE(backbuffer_texture);
    }
    else
    {
        // Copy render target to destination
        result = device->GetRenderTargetData(render_target, dest_target);

        if (FAILED(result))
        {
            SAFE_RELEASE(dest_target);
            SAFE_RELEASE(render_target);
            return;
        }

        D3DLOCKED_RECT rect;
        if (SUCCEEDED(dest_target->LockRect(&rect, nullptr, D3DLOCK_READONLY)))
        {
            //std::memcpy(buffer, rect.pBits, width * height * 4);
            copy_image_from_bgra(buffer, rect.pBits, width, height, 4, image_format);
            dest_target->UnlockRect();
        }
    }

    SAFE_RELEASE(dest_target);
    SAFE_RELEASE(render_target);
}
#endif

void dx_draw_point(IDirect3DDevice9* device, float cx, float cy, float radius, D3DCOLOR colour)
{
    static const int resolution = 10;
    D3DVertex Vertices[resolution];

    for (int i = 0; i < resolution; ++i)
    {
        Vertices[i].X = cx + radius * std::cos(3.141592654f * (static_cast<float>(i) / (resolution / 2.0f)));
        Vertices[i].Y = cy + radius * std::sin(3.141592654f * (static_cast<float>(i) / (resolution / 2.0f)));
        Vertices[i].Z = 0.0f;
        Vertices[i].RHW = 1.0f;
        Vertices[i].Colour = colour;
        Vertices[i].U = 0.0f;
        Vertices[i].V = 0.0f;
    }

    device->SetFVF(VERTEX_FVF_TEX);
    device->DrawPrimitiveUP(D3DPT_TRIANGLEFAN, resolution - 2, Vertices, sizeof(D3DVertex));
}
#endif