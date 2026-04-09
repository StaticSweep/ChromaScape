#include "NativeHooks.hxx"
#include <cstdint>
#include <cmath>

#if defined(_WIN32) || defined(_WIN64)
#if defined(_MSC_VER)
#include <windows.h>
#include <gl/GL.h>
#include <d3d9.h>
#include <d3d11.h>

typedef std::uintptr_t GLsizeiptr;
#define GL_PIXEL_PACK_BUFFER              0x88EB
#define GL_STREAM_READ                    0x88E1
#define GL_READ_ONLY                      0x88B8
#define GL_BGRA                           0x80E1
#define GL_RGBA                           0x1908
#else
#include <windows.h>
#include <gl/gl.h>
#include <gl/glext.h>
#include <d3d9.h>
#include <d3d11.h>
#endif

#include <memory>
#include <thread>
#include <mutex>
#include "JavaInternal.hxx"
#include "EIOS.hxx"
#include "Graphics.hxx"
#include "Thirdparty/Hook.hxx"
#endif

#if defined(_WIN32) || defined(_WIN64)
std::unique_ptr<Hook> native_hook{nullptr};

std::unique_ptr<Hook> opengl_blit_hook{nullptr};
std::unique_ptr<Hook> opengl_flush_buffer_hook{nullptr};
std::unique_ptr<Hook> opengl_swap_hook{nullptr};

std::unique_ptr<Hook> directx_xrgb_hook{nullptr};
std::unique_ptr<Hook> directx_argb_hook{nullptr};
std::unique_ptr<Hook> directx_d3d9_createdevice_hook{nullptr};
std::unique_ptr<Hook> directx_device9_endscene_hook{nullptr};
std::unique_ptr<Hook> directx_device9_reset_hook{nullptr};
std::unique_ptr<Hook> directx_device11_swapchain_present_hook{nullptr};

IDirect3DPixelShader9* debug_px_shader = nullptr;
ID3DXConstantTable* debug_px_shader_constants_table = nullptr;

IDirect3DTexture9* debug_texture = nullptr;
ID3DXConstantTable* debug_constant_table = nullptr;
IDirect3DPixelShader9* debug_shader = nullptr;
IDirect3DVertexBuffer9* debug_vertex_buffer = nullptr;
IDirect3DDevice9* current_dx_device = nullptr;
#define SAFE_RELEASE(ptr) if (ptr) { ptr->Release(); ptr = nullptr; }
void HookD3D9Device(IDirect3DDevice9* pDevice, bool force = false) noexcept;

#define EXTRA_SAFE_RELEASE(com_ptr)                         \
    if (com_ptr)                                            \
    {                                                       \
        IDirect3DDevice9* device = nullptr;                 \
        HRESULT result = debug_texture->GetDevice(&device); \
        if (SUCCEEDED(result) && device)                    \
        {                                                   \
            device->Release();                              \
            com_ptr->Release();                             \
            com_ptr = nullptr;                              \
        }                                                   \
        else                                                \
        {                                                   \
            com_ptr = nullptr;                              \
        }                                                   \
    }

bool can_render(jint srctype, jint width, jint height)
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return false;
    }

    std::int32_t canvas_x = 0;
    std::int32_t canvas_y = 0;
    std::size_t canvas_width = 0;
    std::size_t canvas_height = 0;

    control_center->get_canvas_dimensions(&canvas_x, &canvas_y, &canvas_width, &canvas_height);
    if (width > canvas_width || height > canvas_height)
    {
        return false;
    }

    //Arbitrarily chosen because the java.awt.canvas should never be smaller than this value.
    //Technically stuff should be rendering with a minimum of a power of 2.. so 256 * 256 for example.
    //This value can change if the renderer renders in 128 * 128 chunks but I haven't met a GPU that does that..
    //OR a Java implementation either.
    // - January 7th, 2021
    return width >= 200 && height >= 200;
}

void __stdcall JavaNativeBlit(JNIEnv *env, jobject self, jobject srcData, jobject dstData, jobject comp, jobject clip, jint srcx, jint srcy, jint dstx, jint dsty, jint width, jint height) noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center || !can_render(-1, width, height))
    {
        return native_hook->call<void, decltype(JavaNativeBlit)>(env, self, srcData, dstData, comp, clip, srcx, srcy, dstx, dsty, width, height);
    }

    #define Region_IsRectangular(pRgnInfo) ((pRgnInfo)->endIndex == 0)
    #define Region_IsEmpty(pRgnInfo) ((pRgnInfo)->bounds.x1 >= (pRgnInfo)->bounds.x2 || (pRgnInfo)->bounds.y1 >= (pRgnInfo)->bounds.y2)
    #define Region_IntersectBounds(pRgnInfo, pDstBounds) SurfaceData_IntersectBounds(&(pRgnInfo)->bounds, pDstBounds)

    #define PtrAddBytes(p, b)               ((void *) (((intptr_t) (p)) + (b)))
    #define PtrCoord(p, x, xinc, y, yinc)   PtrAddBytes(p, (y)*(yinc) + (x)*(xinc))

    auto GetProcAddress = [](HMODULE module, const char* stdcall_name, const char* cdecl_name) -> FARPROC {
        FARPROC result = ::GetProcAddress(module, stdcall_name);
        return result ? result : ::GetProcAddress(module, cdecl_name);
    };

    static HMODULE module = GetModuleHandle("awt.dll");
    static NativePrimitive* (__stdcall *GetNativePrim)(JNIEnv *env, jobject gp) = reinterpret_cast<decltype(GetNativePrim)>(GetProcAddress(module, "_GetNativePrim@8",  "GetNativePrim"));
    static SurfaceDataOps* (__stdcall *SurfaceData_GetOps)(JNIEnv *env, jobject sData) = reinterpret_cast<decltype(SurfaceData_GetOps)>(GetProcAddress(module, "_SurfaceData_GetOps@8", "SurfaceData_GetOps"));
    static jint (__stdcall *Region_GetInfo)(JNIEnv *env, jobject region, RegionData *pRgnInfo) = reinterpret_cast<decltype(Region_GetInfo)>(GetProcAddress(module, "_Region_GetInfo@12", "Region_GetInfo"));
    static void (__stdcall *SurfaceData_IntersectBounds)(SurfaceDataBounds *src, SurfaceDataBounds *dst) = reinterpret_cast<decltype(SurfaceData_IntersectBounds)>(GetProcAddress(module, "_SurfaceData_IntersectBounds@8", "SurfaceData_IntersectBounds"));
    static void (__stdcall *SurfaceData_IntersectBlitBounds)(SurfaceDataBounds *src, SurfaceDataBounds *dst, jint dx, jint dy) = reinterpret_cast<decltype(SurfaceData_IntersectBlitBounds)>(GetProcAddress(module, "_SurfaceData_IntersectBlitBounds@16", "SurfaceData_IntersectBlitBounds"));
    static void (__stdcall *Region_StartIteration)(JNIEnv *env, RegionData *pRgnInfo) = reinterpret_cast<decltype(Region_StartIteration)>(GetProcAddress(module, "_Region_StartIteration@8", "Region_StartIteration"));
    static jint (__stdcall *Region_NextIteration)(RegionData *pRgnInfo, SurfaceDataBounds *pSpan) = reinterpret_cast<decltype(Region_NextIteration)>(GetProcAddress(module, "_Region_NextIteration@8", "Region_NextIteration"));
    static void (__stdcall *Region_EndIteration)(JNIEnv *env, RegionData *pRgnInfo) = reinterpret_cast<decltype(Region_EndIteration)>(GetProcAddress(module, "_Region_EndIteration@8", "Region_EndIteration"));

    if (!GetNativePrim || !SurfaceData_GetOps || !Region_GetInfo || !SurfaceData_IntersectBounds || !SurfaceData_IntersectBlitBounds || !Region_StartIteration || !Region_NextIteration || !Region_EndIteration || width <= 0 || height <= 0)
    {
        return native_hook->call<void, decltype(JavaNativeBlit)>(env, self, srcData, dstData, comp, clip, srcx, srcy, dstx, dsty, width, height);
    }

    CompositeInfo compInfo = {0};
    NativePrimitive* pPrim = GetNativePrim(env, self);
    if (!pPrim)
    {
        return native_hook->call<void, decltype(JavaNativeBlit)>(env, self, srcData, dstData, comp, clip, srcx, srcy, dstx, dsty, width, height);
    }

    if (pPrim->pCompType->getCompInfo)
    {
        (*pPrim->pCompType->getCompInfo)(env, &compInfo, comp);
    }

    RegionData clipInfo = {0};
    if (Region_GetInfo(env, clip, &clipInfo))
    {
        return native_hook->call<void, decltype(JavaNativeBlit)>(env, self, srcData, dstData, comp, clip, srcx, srcy, dstx, dsty, width, height);
    }

    SurfaceDataOps *srcOps = SurfaceData_GetOps(env, srcData);
    SurfaceDataOps *dstOps = SurfaceData_GetOps(env, dstData);

    if (!srcOps || !dstOps)
    {
        return native_hook->call<void, decltype(JavaNativeBlit)>(env, self, srcData, dstData, comp, clip, srcx, srcy, dstx, dsty, width, height);
    }

    SurfaceDataRasInfo srcInfo = {0};
    SurfaceDataRasInfo dstInfo = {0};
    srcInfo.bounds.x1 = srcx;
    srcInfo.bounds.y1 = srcy;
    srcInfo.bounds.x2 = srcx + width;
    srcInfo.bounds.y2 = srcy + height;
    dstInfo.bounds.x1 = dstx;
    dstInfo.bounds.y1 = dsty;
    dstInfo.bounds.x2 = dstx + width;
    dstInfo.bounds.y2 = dsty + height;
    srcx -= dstx;
    srcy -= dsty;

    SurfaceData_IntersectBounds(&dstInfo.bounds, &clipInfo.bounds);

    if (srcOps->Lock(env, srcOps, &srcInfo, pPrim->srcflags) == SD_SUCCESS)
    {
        jint dstFlags = pPrim->dstflags;
        if (!Region_IsRectangular(&clipInfo))
        {
            dstFlags |= SD_LOCK_PARTIAL_WRITE;
        }

        if (dstOps->Lock(env, dstOps, &dstInfo, dstFlags) == SD_SUCCESS)
        {
            SurfaceData_IntersectBlitBounds(&dstInfo.bounds, &srcInfo.bounds, srcx, srcy);
            Region_IntersectBounds(&clipInfo, &dstInfo.bounds);

            if (!Region_IsEmpty(&clipInfo))
            {
                srcOps->GetRasInfo(env, srcOps, &srcInfo);
                dstOps->GetRasInfo(env, dstOps, &dstInfo);

                if (srcInfo.rasBase && dstInfo.rasBase)
                {
                    SurfaceDataBounds span;
                    jint savesx = srcInfo.bounds.x1;
                    jint savedx = dstInfo.bounds.x1;
                    Region_StartIteration(env, &clipInfo);

                    while (Region_NextIteration(&clipInfo, &span))
                    {
                        void *pSrc = PtrCoord(srcInfo.rasBase, srcx + span.x1, srcInfo.pixelStride, srcy + span.y1, srcInfo.scanStride);
                        void *pDst = PtrCoord(dstInfo.rasBase, span.x1, dstInfo.pixelStride, span.y1, dstInfo.scanStride);

                        srcInfo.bounds.x1 = srcx + span.x1;
                        dstInfo.bounds.x1 = span.x1;
                        (*pPrim->funcs.blit)(pSrc, pDst, span.x2 - span.x1, span.y2 - span.y1, &srcInfo, &dstInfo, pPrim, &compInfo);
                    }

                    Region_EndIteration(env, &clipInfo);
                    srcInfo.bounds.x1 = savesx;
                    dstInfo.bounds.x1 = savedx;

                    void* rasBase = reinterpret_cast<std::uint8_t*>(srcInfo.rasBase) + (srcInfo.scanStride * srcy) + (srcInfo.pixelStride * srcx);

                    control_center->set_target_dimensions(width, height);
                    std::uint8_t* dest = control_center->get_image();
                    ImageFormat format = control_center->get_image_format();

                    //Render to Shared Memory
                    if (dest)
                    {
                        copy_image_from_bgra(dest, rasBase, (srcInfo.scanStride / srcInfo.pixelStride), height, srcInfo.pixelStride, format);
                    }

                    //Render Debug Graphics
                    if (control_center->get_debug_graphics())
                    {
                        std::uint8_t* src = control_center->get_debug_image();
                        if (src)
                        {
                            rasBase = reinterpret_cast<std::uint8_t*>(dstInfo.rasBase) + (dstInfo.scanStride * dsty) + (dstInfo.pixelStride * dstx);
                            draw_image(rasBase, src, (dstInfo.scanStride / dstInfo.pixelStride), height, dstInfo.pixelStride, format);
                        }
                    }

                    //Render Cursor
                    std::int32_t x = -1;
                    std::int32_t y = -1;
                    control_center->get_applet_mouse_position(&x, &y);

                    if (x > -1 && y > -1)
                    {
                        rasBase = reinterpret_cast<std::uint8_t*>(dstInfo.rasBase) + (dstInfo.scanStride * dsty) + (dstInfo.pixelStride * dstx);
                        draw_circle(x, y, 2, rasBase, width, height, dstInfo.pixelStride, true, 0xFF0000FF);
                    }
                }
            }

            if (dstOps->Unlock)
            {
                dstOps->Unlock(env, dstOps, &dstInfo);
            }
        }

        if (srcOps->Unlock)
        {
            srcOps->Unlock(env, srcOps, &srcInfo);
        }
    }

    if (dstOps->Release)
    {
        dstOps->Release(env, dstOps, &dstInfo);
    }

    if (srcOps->Release)
    {
        srcOps->Release(env, srcOps, &srcInfo);
    }
}

void JavaNativeOGLBlit(JNIEnv *env, void *oglc, jlong pSrcOps, jlong pDstOps, jboolean xform, jint hint, jint srctype, jboolean texture, jint sx1, jint sy1, jint sx2, jint sy2, jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2) noexcept
{
    // NOT SURE!
    // I believe when I wrote this code, srctype 2 is OpenGLSurface.IntRgb = 2; //GL_BGRA
    // It might be that for Windows it renders OpenGL using BGRA and not RGBA!
    if (srctype != 2)
    {
        if (opengl_blit_hook)
        {
            return opengl_blit_hook->call<void, decltype(JavaNativeOGLBlit)>(env, oglc, pSrcOps, pDstOps, xform, hint, srctype, texture, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
        }
        return;
    }

    extern std::unique_ptr<ControlCenter> control_center;
    if (control_center)
    {
        jint width = sx2 - sx1;
        jint height = sy2 - sy1;
        SurfaceDataOps *srcOps = (SurfaceDataOps *)pSrcOps;

        if (!srcOps || width <= 0 || height <= 0)
        {
            if (opengl_blit_hook)
            {
                return opengl_blit_hook->call<void, decltype(JavaNativeOGLBlit)>(env, oglc, pSrcOps, pDstOps, xform, hint, srctype, texture, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
            }
            return;
        }

        SurfaceDataRasInfo srcInfo = {0};
        srcInfo.bounds.x1 = sx1;
        srcInfo.bounds.y1 = sy1;
        srcInfo.bounds.x2 = sx2;
        srcInfo.bounds.y2 = sy2;

        if (srcOps->Lock(env, srcOps, &srcInfo, SD_LOCK_READ) == SD_SUCCESS)
        {
            srcOps->GetRasInfo(env, srcOps, &srcInfo);
            if (srcInfo.rasBase)
            {
                void* rasBase = reinterpret_cast<std::uint8_t*>(srcInfo.rasBase) + (srcInfo.scanStride * sy1) + (srcInfo.pixelStride * sx1);
                bool isRasterAligned = srcInfo.scanStride % srcInfo.pixelStride == 0; //!(srcInfo.scanStride & 0x03);

                control_center->set_target_dimensions(width, height);
                std::uint8_t* dest = control_center->get_image();
                ImageFormat format = control_center->get_image_format();

                //Render to Shared Memory
                if (dest)
                {
                    if (isRasterAligned)
                    {
                        copy_image_from_bgra(dest, rasBase, (srcInfo.scanStride / srcInfo.pixelStride), height, srcInfo.pixelStride, format);
                    }
                    else
                    {
                        for (int i = 0; i < height; ++i)
                        {
                            int offset = (srcInfo.scanStride / srcInfo.pixelStride) * i;
                            copy_image_from_bgra(dest + offset, rasBase, (srcInfo.scanStride / srcInfo.pixelStride), 1, srcInfo.pixelStride, format);
                            rasBase = static_cast<void*>(reinterpret_cast<std::uint8_t*>(rasBase) + srcInfo.scanStride);
                        }
                    }
                }

                //Render Debug Graphics
                if (control_center->get_debug_graphics())
                {
                    std::uint8_t* src = control_center->get_debug_image();
                    if (src)
                    {
                        rasBase = reinterpret_cast<std::uint8_t*>(srcInfo.rasBase) + (srcInfo.scanStride * sy1) + (srcInfo.pixelStride * sx1);
                        draw_image(rasBase, src, (srcInfo.scanStride / srcInfo.pixelStride), height, srcInfo.pixelStride, format);
                    }
                }

                //Render Cursor
                std::int32_t x = -1;
                std::int32_t y = -1;
                control_center->get_applet_mouse_position(&x, &y);

                if (x > -1 && y > -1)
                {
                    rasBase = reinterpret_cast<std::uint8_t*>(srcInfo.rasBase) + (srcInfo.scanStride * sy1) + (srcInfo.pixelStride * sx1);
                    draw_circle(x, y, 2, rasBase, width, height, srcInfo.pixelStride, true, 0xFF0000FF);
                }

                if (srcOps->Release)
                {
                    srcOps->Release(env, srcOps, &srcInfo);
                }
            }

            if (srcOps->Unlock)
            {
                srcOps->Unlock(env, srcOps, &srcInfo);
            }
        }
    }

    if (opengl_blit_hook)
    {
        return opengl_blit_hook->call<void, decltype(JavaNativeOGLBlit)>(env, oglc, pSrcOps, pDstOps, xform, hint, srctype, texture, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
    }
}

void __stdcall JavaNativeOGLRenderQueueFlushBuffer(JNIEnv *env, jobject oglrq, jlong buf, jint limit) noexcept
{
    #define NEXT_VAL(buf, type) (((type *)((buf) += sizeof(type)))[-1])
    #define NEXT_INT(buf)       NEXT_VAL(buf, jint)
    #define NEXT_LONG(buf)      NEXT_VAL(buf, jlong)
    #define NEXT_DOUBLE(buf)    NEXT_VAL(buf, jdouble)

    #define EXTRACT_VAL(packedval, offset, mask) \
                (((packedval) >> (offset)) & (mask))
    #define EXTRACT_BYTE(packedval, offset) \
                (unsigned char)EXTRACT_VAL(packedval, offset, 0xff)
    #define EXTRACT_BOOLEAN(packedval, offset) \
                (jboolean)EXTRACT_VAL(packedval, offset, 0x1)

    static const int BLIT           = 31;
    static const int OFFSET_SRCTYPE = 16;
    static const int OFFSET_HINT    =  8;
    static const int OFFSET_TEXTURE =  3;
    static const int OFFSET_RTT     =  2;
    static const int OFFSET_XFORM   =  1;
    static const int OFFSET_ISOBLIT =  0;

    jlong original_buffer = buf;

    std::uint8_t* buffer = reinterpret_cast<uint8_t*>(buf);
    std::uint8_t* buffer_end = reinterpret_cast<uint8_t*>(buf) + limit;

    while(buffer < buffer_end)
    {
        jint opcode = NEXT_INT(buffer);

        switch (opcode)
        {
            case BLIT:
            {
                jint packedParams = NEXT_INT(buffer);
                jint sx1          = NEXT_INT(buffer);
                jint sy1          = NEXT_INT(buffer);
                jint sx2          = NEXT_INT(buffer);
                jint sy2          = NEXT_INT(buffer);
                jdouble dx1       = NEXT_DOUBLE(buffer);
                jdouble dy1       = NEXT_DOUBLE(buffer);
                jdouble dx2       = NEXT_DOUBLE(buffer);
                jdouble dy2       = NEXT_DOUBLE(buffer);
                jlong pSrc        = NEXT_LONG(buffer);
                jlong pDst        = NEXT_LONG(buffer);
                jint hint         = EXTRACT_BYTE(packedParams, OFFSET_HINT);
                jboolean texture  = EXTRACT_BOOLEAN(packedParams,
                                                    OFFSET_TEXTURE);
                jboolean rtt      = EXTRACT_BOOLEAN(packedParams,
                                                    OFFSET_RTT);
                jboolean xform    = EXTRACT_BOOLEAN(packedParams,
                                                    OFFSET_XFORM);
                jboolean isoblit  = EXTRACT_BOOLEAN(packedParams,
                                                    OFFSET_ISOBLIT);

                if (!isoblit)
                {
                    jint srctype = EXTRACT_BYTE(packedParams, OFFSET_SRCTYPE);
                    JavaNativeOGLBlit(env, nullptr, pSrc, pDst, xform, hint, srctype, texture, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
                }
            }
                break;

            default:
                break;
        }
    }

    return opengl_flush_buffer_hook->call<void, decltype(JavaNativeOGLRenderQueueFlushBuffer)>(env, oglrq, original_buffer, limit);
}

void __stdcall JavaNativeGDIBlit(JNIEnv *env, jobject joSelf, jobject srcData, jobject dstData, jobject clip, jint srcx, jint srcy, jint dstx, jint dsty, jint width, jint height, jint rmask, jint gmask, jint bmask, jboolean needLut) noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center || !can_render(-1, width, height))
    {
        //Original
        return native_hook->call<void, decltype(JavaNativeGDIBlit)>(env, joSelf, srcData, dstData, clip, srcx, srcy, dstx, dsty, width, height, rmask, gmask, bmask, needLut);
    }

    auto GetProcAddress = [](HMODULE module, const char* stdcall_name, const char* cdecl_name) -> FARPROC {
        FARPROC result = ::GetProcAddress(module, stdcall_name);
        return result ? result : ::GetProcAddress(module, cdecl_name);
    };

    //Setup Function Pointers
    static HMODULE module = GetModuleHandle("awt.dll");
    static SurfaceDataOps* (__stdcall *SurfaceData_GetOps)(JNIEnv *env, jobject sData) = reinterpret_cast<decltype(SurfaceData_GetOps)>(GetProcAddress(module, "_SurfaceData_GetOps@8", "SurfaceData_GetOps"));
    static GDIWinSDOps* (__stdcall *GDIWindowSurfaceData_GetOps)(JNIEnv *env, jobject sData) = reinterpret_cast<decltype(GDIWindowSurfaceData_GetOps)>(GetProcAddress(module, "_GDIWindowSurfaceData_GetOps@8", "GDIWindowSurfaceData_GetOps"));
    static void (__stdcall *SurfaceData_IntersectBlitBounds)(SurfaceDataBounds *src, SurfaceDataBounds *dst, jint dx, jint dy) = reinterpret_cast<decltype(SurfaceData_IntersectBlitBounds)>(GetProcAddress(module, "_SurfaceData_IntersectBlitBounds@16", "SurfaceData_IntersectBlitBounds"));

    if (!SurfaceData_GetOps || !GDIWindowSurfaceData_GetOps || !SurfaceData_IntersectBlitBounds)
    {
        //Original
        return native_hook->call<void, decltype(JavaNativeGDIBlit)>(env, joSelf, srcData, dstData, clip, srcx, srcy, dstx, dsty, width, height, rmask, gmask, bmask, needLut);
    }

    //Setup Source Info
    SurfaceDataOps* srcOps = SurfaceData_GetOps(env, srcData);
    SurfaceDataRasInfo srcInfo = {0};
    srcInfo.bounds.x1 = srcx;
    srcInfo.bounds.y1 = srcy;
    srcInfo.bounds.x2 = srcx + width;
    srcInfo.bounds.y2 = srcy + height;

    if (srcOps->Lock(env, srcOps, &srcInfo, needLut ? (SD_LOCK_READ | SD_LOCK_LUT) : SD_LOCK_READ) == SD_SUCCESS)
    {
        //Setup Destination Clipping Info
        GDIWinSDOps* dstOps = GDIWindowSurfaceData_GetOps(env, dstData);
        SurfaceDataBounds dstBounds = {dstx, dsty, dstx + width, dsty + height};
        SurfaceData_IntersectBlitBounds(&(srcInfo.bounds), &dstBounds, dstx - srcx, dsty - srcy);
        srcx = srcInfo.bounds.x1;
        srcy = srcInfo.bounds.y1;
        dstx = dstBounds.x1;
        dsty = dstBounds.y1;
        width = srcInfo.bounds.x2 - srcInfo.bounds.x1;
        height = srcInfo.bounds.y2 - srcInfo.bounds.y1;

        if (width > 0 && height > 0)
        {
            srcOps->GetRasInfo(env, srcOps, &srcInfo);
            if (srcInfo.rasBase)
            {
                void* rasBase = reinterpret_cast<std::uint8_t*>(srcInfo.rasBase) + (srcInfo.scanStride * srcy) + (srcInfo.pixelStride * srcx);
                bool isRasterAligned = !(srcInfo.scanStride & 0x03);

                control_center->set_target_dimensions(width, height);
                std::uint8_t* dest = control_center->get_image();
                ImageFormat format = control_center->get_image_format();

                //Render to Shared Memory
                if (dest)
                {
                    if (isRasterAligned)
                    {
                        copy_image_from_bgra(dest, rasBase, (srcInfo.scanStride / srcInfo.pixelStride), height, srcInfo.pixelStride, format);
                    }
                    else
                    {
                        for (int i = 0; i < height; ++i)
                        {
                            int offset = (srcInfo.scanStride / srcInfo.pixelStride) * i;
                            copy_image_from_bgra(dest + offset, rasBase, (srcInfo.scanStride / srcInfo.pixelStride), 1, srcInfo.pixelStride, format);
                            rasBase = static_cast<void*>(reinterpret_cast<std::uint8_t*>(rasBase) + srcInfo.scanStride);
                        }
                    }
                }

                HDC hDC = dstOps->GetDC(env, dstOps, 0, nullptr, clip, nullptr, 0);
                if (hDC)
                {
                    //Clone Renderer
                    static jint screenWidth = 0;
                    static jint screenHeight = 0;
                    static std::unique_ptr<std::uint8_t[]> screenBuffer = nullptr;

                    if (screenWidth < width || screenHeight < height)
                    {
                        screenWidth = width;
                        screenHeight = height;
                        screenBuffer = std::make_unique<std::uint8_t[]>((srcInfo.scanStride / srcInfo.pixelStride) * height * srcInfo.pixelStride);
                    }

                    if (screenBuffer)
                    {
                        std::memcpy(screenBuffer.get(), rasBase, (srcInfo.scanStride / srcInfo.pixelStride) * height * srcInfo.pixelStride);

                        //Render Debug Graphics
                        if (control_center->get_debug_graphics())
                        {
                            std::uint8_t* src = control_center->get_debug_image();
                            if (src)
                            {
                                draw_image(screenBuffer.get(), src, (srcInfo.scanStride / srcInfo.pixelStride), height, srcInfo.pixelStride, format);
                            }
                        }

                        //Render Cursor
                        std::int32_t x = -1;
                        std::int32_t y = -1;
                        control_center->get_applet_mouse_position(&x, &y);

                        if (x > -1 && y > -1)
                        {
                            static const std::int32_t radius = 2;
                            draw_circle(x, y, radius, screenBuffer.get(), (srcInfo.scanStride / srcInfo.pixelStride), height, 4, true, 0xFF0000FF);
                        }
                    }

                    //Render Screen
                    BmiType bmi = {0};
                    long dwHeight = srcInfo.bounds.y2 - srcInfo.bounds.y1;
                    bmi.bmiHeader.biSize = sizeof(bmi.bmiHeader);
                    bmi.bmiHeader.biWidth = srcInfo.scanStride / srcInfo.pixelStride;
                    bmi.bmiHeader.biHeight = isRasterAligned ? -dwHeight : -1;
                    bmi.bmiHeader.biPlanes = 1;
                    bmi.bmiHeader.biBitCount = srcInfo.pixelStride * 8;
                    bmi.bmiHeader.biCompression = srcInfo.pixelStride != 2 ? BI_RGB : BI_BITFIELDS;
                    bmi.bmiHeader.biSizeImage = (bmi.bmiHeader.biWidth * dwHeight * srcInfo.pixelStride);
                    bmi.bmiHeader.biXPelsPerMeter = 0;
                    bmi.bmiHeader.biYPelsPerMeter = 0;
                    bmi.bmiHeader.biClrUsed = 0;
                    bmi.bmiHeader.biClrImportant = 0;
                    bmi.colors.dwMasks[0] = 0x00FF0000;
                    bmi.colors.dwMasks[1] = 0x0000FF00;
                    bmi.colors.dwMasks[2] = 0x000000FF;

                    rasBase = screenBuffer.get();

                    if (isRasterAligned)
                    {
                        if (::IsWindowVisible(dstOps->window))
                        {
                            SetDIBitsToDevice(hDC, dstx, dsty, width, height, 0, 0, 0, height, rasBase, reinterpret_cast<BITMAPINFO*>(&bmi), DIB_RGB_COLORS);
                        }
                    }
                    else
                    {
                        for (jint i = 0; i < height; i += 1)
                        {
                            if (::IsWindowVisible(dstOps->window))
                            {
                                SetDIBitsToDevice(hDC, dstx, dsty + i, width, 1, 0, 0, 0, 1, rasBase, reinterpret_cast<BITMAPINFO*>(&bmi), DIB_RGB_COLORS);
                                rasBase = static_cast<void*>(reinterpret_cast<std::uint8_t*>(rasBase) + srcInfo.scanStride);
                            }
                            else
                            {
                                break;
                            }
                        }
                    }

                    dstOps->ReleaseDC(env, dstOps, hDC);
                }

                if (srcOps->Release)
                {
                    srcOps->Release(env, srcOps, &srcInfo);
                }
            }
        }

        if (srcOps->Unlock)
        {
            srcOps->Unlock(env, srcOps, &srcInfo);
        }
    }
}

HRESULT __cdecl JavaDirectXCopyImageToIntArgbSurface(IDirect3DSurface9 *pSurface, SurfaceDataRasInfo *pDstInfo, jint srcx, jint srcy, jint srcWidth, jint srcHeight, jint dstx, jint dsty) noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        //Original
        return directx_xrgb_hook->call<HRESULT, decltype(JavaDirectXCopyImageToIntArgbSurface)>(pSurface, pDstInfo, srcx, srcy, srcWidth, srcHeight, dstx, dsty);
    }

    IDirect3DDevice9* pDevice = nullptr;
    if (SUCCEEDED(pSurface->GetDevice(&pDevice)) && pDevice)
    {
        HookD3D9Device(pDevice);
        pDevice->Release();
    }

    return directx_xrgb_hook->call<HRESULT, decltype(JavaDirectXCopyImageToIntArgbSurface)>(pSurface, pDstInfo, srcx, srcy, srcWidth, srcHeight, dstx, dsty);
}

//Java_sun_java2d_d3d_D3DRenderQueue_flushBuffer -> D3DRQ_FlushBuffer -> sun_java2d_pipe_BufferedOpCodes_BLIT -> D3DBlitLoops_Blit -> D3DBlitSwToTexture -> D3DBL_CopyImageToIntXrgbSurface
//Java_sun_java2d_d3d_D3DRenderQueue_flushBuffer -> D3DRQ_FlushBuffer -> sun_java2d_pipe_BufferedOpCodes_BLIT -> D3DBlitLoops_Blit -> D3DBlitToSurfaceViaTexture -> D3DBL_CopyImageToIntXrgbSurface
HRESULT __cdecl JavaDirectXCopyImageToIntXrgbSurface(SurfaceDataRasInfo *pSrcInfo, int srctype, D3DResource *pDstSurfaceRes, jint srcx, jint srcy, jint srcWidth, jint srcHeight, jint dstx, jint dsty) noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        //Original
        return directx_xrgb_hook->call<HRESULT, decltype(JavaDirectXCopyImageToIntXrgbSurface)>(pSrcInfo, srctype, pDstSurfaceRes, srcx, srcy, srcWidth, srcHeight, dstx, dsty);
    }

    //Retrieve data pointers and v-table from the D3DResource structure..
    auto get_data_pointer = [](D3DResource* base) -> void* {
        return reinterpret_cast<void**>((reinterpret_cast<char*>(base) + sizeof(D3DResource)) - sizeof(D3DSURFACE_DESC) - (sizeof(void*) * 5));
    };

    auto get_offset = [](void* data_ptr, std::uintptr_t index) -> void* {
        return *reinterpret_cast<void**>(reinterpret_cast<char*>(data_ptr) + (sizeof(void*) * index));
    };

    if (pDstSurfaceRes)
    {
        void* base_ptr = get_data_pointer(pDstSurfaceRes);
        IDirect3DSurface9* pSurface = reinterpret_cast<IDirect3DSurface9*>(get_offset(base_ptr, 2));

        IDirect3DDevice9* pDevice = nullptr;
        if (pSurface && SUCCEEDED(pSurface->GetDevice(&pDevice)) && pDevice)
        {
            HookD3D9Device(pDevice);
            pDevice->Release();
        }
    }

    return directx_xrgb_hook->call<HRESULT, decltype(JavaDirectXCopyImageToIntXrgbSurface)>(pSrcInfo, srctype, pDstSurfaceRes, srcx, srcy, srcWidth, srcHeight, dstx, dsty);
}
#endif

#if defined(_WIN32) || defined(_WIN64)
void (__stdcall *glGenBuffers) (GLsizei n, GLuint *buffers);
void (__stdcall *glDeleteBuffers) (GLsizei n,  const GLuint* buffers);
void (__stdcall *glBindBuffer) (GLenum target, GLuint buffer);
void (__stdcall *glBufferData) (GLenum target, GLsizeiptr size, const GLvoid *data, GLenum usage);
void* (__stdcall *glMapBuffer)(GLenum target, GLenum access);
GLboolean (__stdcall *glUnmapBuffer)(GLenum target);

bool IsGLExtensionsSupported(HDC hdc, std::string extension) noexcept
{
    typedef std::uintptr_t GLsizeiptr;
    auto wglGetProcAddress = [](const char* a, const char* b) -> FARPROC {
        FARPROC result = ::wglGetProcAddress(a);
        return result ? result : ::wglGetProcAddress(b);
    };

    static std::vector<std::string> extensions;
    static const char* (__stdcall *wglGetExtensionsString)(HDC hdc) = reinterpret_cast<decltype(wglGetExtensionsString)>(wglGetProcAddress("wglGetExtensionsString", "wglGetExtensionsStringARB"));

    if (wglGetExtensionsString && hdc && extensions.empty())
    {
        auto split = [](const std::string& text, const std::string& delims) {
            std::vector<std::string> tokens;
            std::size_t start = text.find_first_not_of(delims), end = 0;

            while((end = text.find_first_of(delims, start)) != std::string::npos)
            {
                tokens.push_back(text.substr(start, end - start));
                start = text.find_first_not_of(delims, end);
            }

            if(start != std::string::npos)
            {
                tokens.push_back(text.substr(start));
            }
            return tokens;
        };

        extensions = split(wglGetExtensionsString(hdc), " ");
        std::sort(extensions.begin(), extensions.end());
    }

    return std::binary_search(extensions.begin(), extensions.end(), extension);
}

bool LoadOpenGLExtensions() noexcept
{
    if (!glGenBuffers)
    {
        glGenBuffers = reinterpret_cast<decltype(glGenBuffers)>(wglGetProcAddress("glGenBuffers"));
        glDeleteBuffers = reinterpret_cast<decltype(glDeleteBuffers)>(wglGetProcAddress("glDeleteBuffers"));
        glBindBuffer = reinterpret_cast<decltype(glBindBuffer)>(wglGetProcAddress("glBindBuffer"));
        glBufferData = reinterpret_cast<decltype(glBufferData)>(wglGetProcAddress("glBufferData"));
        glMapBuffer = reinterpret_cast<decltype(glMapBuffer)>(wglGetProcAddress("glMapBuffer"));
        glUnmapBuffer = reinterpret_cast<decltype(glUnmapBuffer)>(wglGetProcAddress("glUnmapBuffer"));
    }

    return glGenBuffers && glDeleteBuffers && glBindBuffer && glBufferData && glMapBuffer && glUnmapBuffer;
}

void GeneratePixelBuffers(void* ctx, GLuint (&pbo)[2], GLint width, GLint height, GLint stride) noexcept
{
    static int w = 0;
    static int h = 0;
    static void* current_ctx = nullptr;

    #if defined(__APPLE__)
    CGLContextObj CGL_MACRO_CONTEXT = static_cast<CGLContextObj>(ctx);
    #endif

    //Buffer size changed
    if (w != width || h != height || current_ctx != ctx)
    {
        w = width;
        h = height;
        current_ctx = ctx;

        //If buffers already exist, clean them up
        if (pbo[1] != 0)
        {
            glDeleteBuffers(2, pbo);
            pbo[0] = 0;
            pbo[1] = 0;
        }

        //Generate buffers
        glGenBuffers(2, pbo);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[0]);
        glBufferData(GL_PIXEL_PACK_BUFFER, width * height * stride, nullptr, GL_STREAM_READ);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);

        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[1]);
        glBufferData(GL_PIXEL_PACK_BUFFER, width * height * stride, nullptr, GL_STREAM_READ);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    }
}

void ReadPixelBuffers(void* ctx, GLubyte* dest, GLuint (&pbo)[2], GLint width, GLint height, GLint stride, ImageFormat format) noexcept
{
    static int index = 0;
    static int nextIndex = 0;
    static void* last_ctx = nullptr;

    #if defined(__APPLE__)
    CGLContextObj CGL_MACRO_CONTEXT = static_cast<CGLContextObj>(ctx);
    #endif

    if (last_ctx != ctx)
    {
        last_ctx = ctx;
        index = 0;
        nextIndex = 0;
    }

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

    //Swap indices
    index = (index + 1) % 2;
    nextIndex = (index + 1) % 2;

    //Read back-buffer.
    glPixelStorei(GL_UNPACK_ROW_LENGTH, width);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[index]);
    glReadPixels(0, 0, width, height, gl_format, GL_UNSIGNED_BYTE, nullptr);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[nextIndex]);

    void* data = glMapBuffer(GL_PIXEL_PACK_BUFFER, GL_READ_ONLY);

    if (data)
    {
        //memcpy(dest, data, width * height * 4);
        FlipImageBytes(data, reinterpret_cast<void*&>(dest), width, height, 32);
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
    }
    else
    {
        glReadPixels(0, 0, width, height, gl_format, GL_UNSIGNED_BYTE, dest);
    }

    //Restore state
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
}

void PushGLContext(HDC hdc, GLint width, GLint height) noexcept
{
    //Create a context if none exists..
    static std::unordered_map<int, HGLRC> contexts;
    int pixelformat = GetPixelFormat(hdc);
    if (!contexts.count(pixelformat))
    {
        HGLRC ctx = wglCreateContext(hdc);
        contexts[pixelformat] = ctx;
    }

    //Set the current context
    wglMakeCurrent(hdc, contexts[pixelformat]);

    //Push current states
    glPushAttrib(GL_ALL_ATTRIB_BITS);
    glPushMatrix();
    glViewport(0, 0, width, height);
    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    glOrtho(0, width, 0, height, -1, 1);
    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();
    glDisable(GL_DEPTH_TEST);
}

void PopGLContext(HDC hdc, HGLRC ctx) noexcept
{
    glPopMatrix();
    glPopAttrib();
    wglMakeCurrent(hdc, ctx);
}

void __stdcall mglDrawPixels(GLsizei width, GLsizei height, GLenum format, GLenum type, const void* data) noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;

    if (control_center)
    {
        int src_x = 0;
        int src_y = 0;
        int bytes_per_pixel = 4; //TODO: Parse format  //can_render(format == GL_RGBA ? 3 : -1, width, height)
        int stride = width * bytes_per_pixel;
        void *rasBase = static_cast<std::uint8_t*>(const_cast<void*>(data)) + (stride * src_y) + (bytes_per_pixel * src_x);

        control_center->set_target_dimensions(width, height);

        //Render to Shared Memory
        std::uint8_t* dest = control_center->get_image();
        ImageFormat format = control_center->get_image_format();
        if (dest)
        {
            copy_image_from_bgra(dest, rasBase, (stride / bytes_per_pixel), height, bytes_per_pixel, format);
        }

        //Render Debug Graphics
        if (control_center->get_debug_graphics())
        {
            std::uint8_t* src = control_center->get_debug_image();
            if (src)
            {
                draw_image(rasBase, src, width, height, bytes_per_pixel, format);
            }
        }

        //Render Cursor
        std::int32_t x = -1;
        std::int32_t y = -1;
        control_center->get_applet_mouse_position(&x, &y);

        if (x > -1 && y > -1)
        {
            draw_circle(x, y, 3, rasBase, width, height, bytes_per_pixel, true, 0xFF0000FF);
        }
    }

    if (opengl_swap_hook)
    {
        opengl_swap_hook->call<void, decltype(mglDrawPixels)>(width, height, format, type, data);
    }
}

BOOL __stdcall mSwapBuffers(HDC hdc) noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;

    if (control_center)
    {
        static GLint ViewPort[4] = {0};
        static GLuint pbo[2] = {0};

        glGetIntegerv(GL_VIEWPORT, ViewPort);
        GLint width = ViewPort[2] - ViewPort[0];
        GLint height = ViewPort[3] - ViewPort[1];

        std::int32_t canvas_x = 0;
        std::int32_t canvas_y = 0;
        std::size_t canvas_width = 0;
        std::size_t canvas_height = 0;

        control_center->get_canvas_dimensions(&canvas_x, &canvas_y, &canvas_width, &canvas_height);
        control_center->set_target_dimensions(canvas_width, canvas_height);

        // Do not render on an invalid canvas
        if (width != canvas_width || height != canvas_height)
        {
            if (opengl_swap_hook)
            {
                return opengl_swap_hook->call<BOOL, decltype(mSwapBuffers)>(hdc);
            }
            return false;
        }

        //Check if extensions are supported
        //This check is needed for renderers that do not support pixel buffer objects or vertex buffer objects
        //static bool hasGLExtension = IsGLExtensionsSupported(hdc, "GL_ARB_vertex_buffer_object") || IsGLExtensionsSupported(hdc, "GL_ARB_pixel_buffer_object");

        // The above extension check is unreliable!
        // It's best to attempt to load the extensions and see if they exist.
        static bool hasGLExtension = LoadOpenGLExtensions();

        //Render to Shared Memory
        std::uint8_t* dest = control_center->get_image();
        ImageFormat format = control_center->get_image_format();
        if (dest)
        {
            if (hasGLExtension)
            {
                //Performance Boost! :D
                GeneratePixelBuffers(hdc, pbo, width, height, 4);
                ReadPixelBuffers(hdc, dest, pbo, width, height, 4, format);
            }
            else
            {
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

                //Sad rendering implementation
                glReadPixels(0, 0, width, height, gl_format, GL_UNSIGNED_BYTE, dest);
                FlipImageVertically(width, height, dest);
            }
        }

        //Push Rendering Context
        HGLRC old_ctx = wglGetCurrentContext();
        if (old_ctx)
        {
            PushGLContext(hdc, width, height);
        }

        //Render Debug Graphics
        if (control_center->get_debug_graphics())
        {
            std::uint8_t* src = control_center->get_debug_image();
            if (src)
            {
                gl_draw_image(hdc, src, 0, 0, width, height, 4, format);
            }
        }

        //Render Cursor
        std::int32_t x = -1;
        std::int32_t y = -1;
        control_center->get_applet_mouse_position(&x, &y);

        if (x > -1 && y > -1)
        {
            glColor4ub(0xFF, 0x00, 0x00, 0xFF);
            gl_draw_point(hdc, x, y, 0, 4);
        }

        //Pop Rendering Context
        if (old_ctx)
        {
            PopGLContext(hdc, old_ctx);
        }
    }

    //Original
    if (opengl_swap_hook)
    {
        return opengl_swap_hook->call<BOOL, decltype(mSwapBuffers)>(hdc);
    }
    return false;
}
#endif // defined

#if defined(_WIN32) || defined(_WIN64)
HRESULT __stdcall D3D11Device_Present(IDXGISwapChain* pThis, UINT SyncInterval, UINT Flags)
{
    if (directx_device11_swapchain_present_hook)
    {
        return directx_device11_swapchain_present_hook->call<HRESULT, decltype(D3D11Device_Present)>(pThis, SyncInterval, Flags);
    }
    return E_FAIL;
}

HRESULT __stdcall D3D9_CreateDevice(IDirect3D9* pD3D, UINT Adapter, D3DDEVTYPE DeviceType, HWND hFocusWindow, DWORD BehaviorFlags, D3DPRESENT_PARAMETERS* pPresentationParameters, IDirect3DDevice9** ppReturnedDeviceInterface) noexcept
{
    if (directx_d3d9_createdevice_hook)
    {
        HRESULT result = directx_d3d9_createdevice_hook->call<HRESULT, decltype(D3D9_CreateDevice)>(pD3D, Adapter, DeviceType, hFocusWindow, BehaviorFlags, pPresentationParameters, ppReturnedDeviceInterface);
        if (ppReturnedDeviceInterface && *ppReturnedDeviceInterface)
        {
            HookD3D9Device(*ppReturnedDeviceInterface);
        }
        return result;
    }

    return E_FAIL;
}

HRESULT __stdcall D3D9Device_EndScene(IDirect3DDevice9* device) noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;

    if (control_center)
    {
        D3DVIEWPORT9 viewport;
        if (SUCCEEDED(device->GetViewport(&viewport)))
        {
            std::int32_t x = 0;
            std::int32_t y = 0;
            std::int32_t width = static_cast<std::int32_t>(viewport.Width);
            std::int32_t height = static_cast<std::int32_t>(viewport.Height);

            std::int32_t canvas_x = 0;
            std::int32_t canvas_y = 0;
            std::size_t canvas_width = 0;
            std::size_t canvas_height = 0;

            control_center->get_canvas_dimensions(&canvas_x, &canvas_y, &canvas_width, &canvas_height);
            control_center->set_target_dimensions(canvas_width, canvas_height);

            // Do not render on an invalid canvas
            if (width != canvas_width || height != canvas_height)
            {
                if (directx_device9_endscene_hook)
                {
                    return directx_device9_endscene_hook->call<HRESULT, decltype(D3D9Device_EndScene)>(device);
                }

                return E_FAIL;
            }

            bool minimized = false;
            ImageFormat image_format = control_center->get_image_format();
            dx_read_pixels(device, control_center->get_image(), canvas_width, canvas_height, minimized, image_format);

            IDirect3DStateBlock9* block;
            device->CreateStateBlock(D3DSBT_ALL, &block);
            block->Capture();

            device->SetRenderState(D3DRS_LIGHTING, FALSE);
            device->SetRenderState(D3DRS_FOGENABLE, FALSE);
            device->SetRenderState(D3DRS_ZENABLE, D3DZB_FALSE);
            device->SetRenderState(D3DRS_CULLMODE, D3DCULL_NONE);
            device->SetRenderState(D3DRS_ALPHABLENDENABLE, TRUE);
            device->SetRenderState(D3DRS_BLENDOP, D3DBLENDOP_ADD);
            device->SetRenderState(D3DRS_SRCBLEND, D3DBLEND_SRCALPHA);
            device->SetRenderState(D3DRS_DESTBLEND, D3DBLEND_INVSRCALPHA);

            if (control_center->get_debug_graphics() && !minimized)
            {
                dx_load_texture(device, debug_texture, debug_px_shader, debug_px_shader_constants_table, image_format, control_center->get_debug_image(), canvas_width, canvas_height);

                if (debug_texture)
                {
                    dx_draw_texture(device, debug_texture, debug_constant_table, debug_shader, debug_vertex_buffer, image_format, 0.0, 0.0, static_cast<float>(canvas_width), static_cast<float>(canvas_height));
                }
            }

            //Render Cursor
            if (!minimized)
            {
                x = -1;
                y = -1;
                control_center->get_applet_mouse_position(&x, &y);

                if (x > -1 && y > -1 && x <= width && y <= height)
                {
                    device->SetTexture(0, nullptr);
                    dx_draw_point(device, static_cast<float>(x), static_cast<float>(y), 2.5f, D3DCOLOR_RGBA(0xFF, 0x00, 0x00, 0xFF));
                }
            }

            device->SetRenderState(D3DRS_ZFUNC,D3DCMP_NEVER);
            device->SetTexture(0, nullptr);
            device->SetPixelShader(nullptr);
            device->SetVertexShader(nullptr);
            block->Apply();
            block->Release();
        }
    }

    if (directx_device9_endscene_hook)
    {
        return directx_device9_endscene_hook->call<HRESULT, decltype(D3D9Device_EndScene)>(device);
    }

    return E_FAIL;
}

HRESULT __stdcall D3D9Device_Reset(IDirect3DDevice9* device, D3DPRESENT_PARAMETERS* pPresentationParameters) noexcept {
    if (directx_device9_reset_hook)
    {
        HRESULT result = directx_device9_reset_hook->call<HRESULT, decltype(D3D9Device_Reset)>(device, pPresentationParameters);
        HookD3D9Device(device, true);
        return result;
    }

    return E_FAIL;
}

void HookD3D9Device(IDirect3DDevice9* pDevice, bool force) noexcept
{
    if (!pDevice)
    {
        return;
    }

    if (!force && current_dx_device == pDevice)
    {
        return;
    }

    bool device_lost = false;
    if (current_dx_device)
    {
        HRESULT result = current_dx_device->TestCooperativeLevel();
        device_lost = (result == D3DERR_DEVICELOST || result == D3DERR_DEVICENOTRESET);

        if (device_lost)
        {
            fprintf(stderr, "DEVICE LOST\b");
        }
    }

    SAFE_RELEASE(current_dx_device);
    current_dx_device = pDevice;
    current_dx_device->AddRef();

    auto* vTable = *reinterpret_cast<DWORD_PTR**>(pDevice);

    // Release the debug interfaces
    EXTRA_SAFE_RELEASE(debug_px_shader);
    EXTRA_SAFE_RELEASE(debug_px_shader_constants_table);
    EXTRA_SAFE_RELEASE(debug_vertex_buffer);
    EXTRA_SAFE_RELEASE(debug_shader);
    EXTRA_SAFE_RELEASE(debug_constant_table);
    EXTRA_SAFE_RELEASE(debug_texture);

    // Hook EndScene
    auto* endscene = reinterpret_cast<decltype(D3D9Device_EndScene)*>(vTable[42]);
    directx_device9_endscene_hook = std::make_unique<Hook>(reinterpret_cast<void*>(endscene), reinterpret_cast<void*>(D3D9Device_EndScene));
    directx_device9_endscene_hook->apply();

    // Hook Reset
    auto* reset = reinterpret_cast<decltype(D3D9Device_Reset)*>(vTable[16]);
    directx_device9_reset_hook = std::make_unique<Hook>(reinterpret_cast<void*>(reset), reinterpret_cast<void*>(D3D9Device_Reset));
    directx_device9_reset_hook->apply();

    // Validate that functions were actually hooked
    if (!directx_device9_endscene_hook->is_enabled())
    {
        current_dx_device = nullptr;
        directx_device9_endscene_hook.reset();
    }

    if (!directx_device9_reset_hook->is_enabled())
    {
        current_dx_device = nullptr;
        directx_device9_reset_hook.reset();
    }
}
#endif // defined

#if defined(_WIN32) || defined(_WIN64)
std::unique_ptr<Hook> cp_hook;
BOOL WINAPI CreateProcessWHook(LPCWSTR lpApplicationName, LPWSTR lpCommandLine, LPSECURITY_ATTRIBUTES lpProcessAttributes, LPSECURITY_ATTRIBUTES lpThreadAttributes, BOOL bInheritHandles, DWORD dwCreationFlags, LPVOID lpEnvironment, LPCWSTR lpCurrentDirectory, LPSTARTUPINFOW lpStartupInfo, LPPROCESS_INFORMATION lpProcessInformation) {

    std::wcout<<L"CREATE PROCESS CALLED\n";
    std::wcout<<L"Application Name: "<<(lpApplicationName ? lpApplicationName : L"")
              <<L"\nCommand Line: "<<(lpCommandLine ? lpCommandLine : L"")
              <<L"\nProcess Attributes: "<<lpProcessAttributes
              <<L"\nThread Attributes: "<<lpThreadAttributes
              <<L"\nInherit Handle: "<<std::boolalpha<<bInheritHandles
              <<L"\nCreation Flags: "<<dwCreationFlags
              <<L"\nEnvironment: "<<lpEnvironment
              <<L"\nCurrent Directory: "<<(lpCurrentDirectory ? lpCurrentDirectory : L"")
              <<L"\nStartup Info: "<<lpStartupInfo
              <<L"\nProcess Info: "<<lpProcessInformation<<L"\n\n";


    HANDLE read = nullptr;
    HANDLE write = nullptr;
    HANDLE originalHandle = nullptr;

    if (lpCommandLine && !wcscmp(lpCommandLine, L"wmic csproduct get UUID"))
    {
        if (lpStartupInfo && lpStartupInfo->hStdOutput && GetFileType(lpStartupInfo->hStdOutput) == FILE_TYPE_PIPE)
        {
            SECURITY_ATTRIBUTES attributes = {0};
            attributes.nLength = sizeof(SECURITY_ATTRIBUTES);
            attributes.bInheritHandle = true;
            attributes.lpSecurityDescriptor = nullptr;

            if (CreatePipe(&read, &write, &attributes, 0))
            {
                SetHandleInformation(read, HANDLE_FLAG_INHERIT, 0);

                originalHandle = lpStartupInfo->hStdOutput;
                lpStartupInfo->hStdOutput = write;
            }
        }
    }

    BOOL res = cp_hook->call<BOOL, decltype(CreateProcessW)>(lpApplicationName, lpCommandLine, lpProcessAttributes, lpThreadAttributes, bInheritHandles, dwCreationFlags, lpEnvironment, lpCurrentDirectory, lpStartupInfo, lpProcessInformation);

    if (originalHandle)
    {
        WaitForSingleObject(lpProcessInformation->hProcess, INFINITE);
        CloseHandle(write);

        #ifdef USE_REAL_HARDWARE_ID
        char buffer[256] = {0};
        DWORD dwRead = 0;
        DWORD dwWritten = 0;

        std::string result;

        while (true)
        {
            bool success = ReadFile(read, buffer, 256, &dwRead, nullptr);
            if (!success || dwRead == 0)
            {
                break;
            }

            result += std::string(buffer, dwRead);
        }
        #else

        // Return Fake UUID-v4
        DWORD dwWritten = 0;
        std::string result = "UUID";  // Header

        // Padding
        for (int i = 0; i < 34; ++i)
        {
            result += '\x20';
        }

        result += "\xd\xd\n";
        result += "4DDF2995-1CC6-4E70-9463-D17914D9A13F"; // The UUID-v4
        result +="\x20\x20\xd\xd\n\xd\xd\n";
        #endif

        bool success = WriteFile(originalHandle, &result[0], result.size(), &dwWritten, nullptr);
        if (!success)
        {
            std::cout<<"FAILED TO WRITE: "<<result<<"\n";
        }

        CloseHandle(read);
    }

    return res;
}

void InitialiseHooks() noexcept
{
//    HMODULE mm = GetModuleHandleA("kernel32.dll");
//    cp_hook = std::make_unique<Hook>((void *) GetProcAddress(mm, "CreateProcessW"), (void *) CreateProcessWHook);
//    cp_hook->apply();

    #if defined(USE_DETOURS)
        HMODULE module = GetModuleHandle("awt.dll");

        auto GetProcAddress = [](HMODULE module, const char* stdcall_name, const char* cdecl_name) -> FARPROC {
            FARPROC result = ::GetProcAddress(module, stdcall_name);
            return result ? result : ::GetProcAddress(module, cdecl_name);
        };

        //Hook X11 Blit
        void* blit = reinterpret_cast<void*>(GetProcAddress(module, "_Java_sun_java2d_windows_GDIBlitLoops_nativeBlit@60", "Java_sun_java2d_windows_GDIBlitLoops_nativeBlit"));
        if (blit)
        {
            native_hook = std::make_unique<Hook>(reinterpret_cast<void*>(blit), reinterpret_cast<void*>(JavaNativeGDIBlit));
            native_hook->apply();
        }
        else
        {
            //_Java_sun_java2d_loops_ScaledBlit_Scale@72 //Java_sun_java2d_loops_ScaledBlit_Scale
            blit = reinterpret_cast<void*>(GetProcAddress(module, "_Java_sun_java2d_loops_Blit_Blit@48", "Java_sun_java2d_loops_Blit_Blit"));
            if (blit)
            {
                native_hook = std::make_unique<Hook>(reinterpret_cast<void *>(blit), reinterpret_cast<void *>(JavaNativeBlit));
                native_hook->apply();
            }
        }

        // Some time between Java 20 and 23+, the following functions are no longer exported
        // OGLBlitLoops_Blit
        // Only Java_sun_java2d_opengl_OGLRenderQueue_flushBuffer is exported now

        // Hook OpenGL Blit
        #if defined(HOOK_OPENGL_BLIT)
        blit = reinterpret_cast<void*>(::GetProcAddress(module, "OGLBlitLoops_Blit"));
        if (blit)
        {
            opengl_blit_hook = std::make_unique<Hook>(reinterpret_cast<void*>(blit), reinterpret_cast<void*>(JavaNativeOGLBlit));
            opengl_blit_hook->apply();
        }
        else
        {
            blit = reinterpret_cast<void*>(GetProcAddress(module, "_Java_sun_java2d_opengl_OGLRenderQueue_flushBuffer@20", "Java_sun_java2d_opengl_OGLRenderQueue_flushBuffer"));
            if (blit)
            {
                opengl_flush_buffer_hook = std::make_unique<Hook>(reinterpret_cast<void*>(blit), reinterpret_cast<void*>(JavaNativeOGLRenderQueueFlushBuffer));
                opengl_flush_buffer_hook->apply();
            }
        }
        #else
        if (blit)
        {
            HMODULE module = GetModuleHandle("opengl32.dll");
            if (module)
            {
                #if defined(HOOK_OPENGL_SWAP)
                blit = reinterpret_cast<void*>(::GetProcAddress(module, "wglSwapBuffers"));
                opengl_swap_hook = std::make_unique<Hook>(reinterpret_cast<void*>(blit), reinterpret_cast<void*>(mSwapBuffers));
                opengl_swap_hook->apply();
                #else
                blit = reinterpret_cast<void*>(::GetProcAddress(module, "glDrawPixels"));
                opengl_swap_hook = std::make_unique<Hook>(reinterpret_cast<void*>(blit), reinterpret_cast<void*>(mglDrawPixels));
                opengl_swap_hook->apply();
                #endif
            }
        }
        #endif

        // Some time between Java 20 and 23+, the following functions are no longer exported
        // D3DBL_CopyImageToIntXrgbSurface
        // D3DBL_CopySurfaceToIntArgbImage
        // D3DBlitLoops_IsoBlit
        // D3DBlitToSurfaceViaTexture
        // Only Java_sun_java2d_d3d_D3DRenderQueue_flushBuffer is exported now

        // Hook DirectX Surface Blit
        blit = reinterpret_cast<void*>(GetProcAddress(module, "?D3DBL_CopyImageToIntXrgbSurface@@YAJPAUSurfaceDataRasInfo@@HPAVD3DResource@@JJJJJJ@Z", "?D3DBL_CopyImageToIntXrgbSurface@@YAJPEAUSurfaceDataRasInfo@@HPEAVD3DResource@@JJJJJJ@Z"));
        if (blit)
        {
            directx_xrgb_hook = std::make_unique<Hook>(reinterpret_cast<void*>(blit), reinterpret_cast<void*>(JavaDirectXCopyImageToIntXrgbSurface));
            directx_xrgb_hook->apply();
        }

        blit = reinterpret_cast<void*>(GetProcAddress(module, "D3DBL_CopySurfaceToIntArgbImage@@YAJPAUIDirect3DSurface9@@PAUSurfaceDataRasInfo@@JJJJJJ@Z", "?D3DBL_CopySurfaceToIntArgbImage@@YAJPEAUIDirect3DSurface9@@PEAUSurfaceDataRasInfo@@JJJJJJ@Z"));
        if (blit)
        {
            directx_argb_hook = std::make_unique<Hook>(reinterpret_cast<void*>(blit), reinterpret_cast<void*>(JavaDirectXCopyImageToIntArgbSurface));
            directx_argb_hook->apply();
        }

        // Direct-X Hooks

        #if defined(HOOK_D3D11)
        bool hook_d3d11 = true;
        #else
        bool hook_d3d11 = false;
        #endif

        if (!GetModuleHandle(hook_d3d11 ? "d3d11.dll" : "d3d9.dll"))
        {
            return;
        }

        extern std::unique_ptr<ControlCenter> control_center;
        HWND main_window = reinterpret_cast<HWND>(control_center->reflect_frame_native_handle());
        if (!main_window)
        {
            return;
        }

        if (hook_d3d11)
        {
            HMODULE d3d11_module = GetModuleHandle("d3d11.dll");
            auto pD3D11CreateDeviceAndSwapChain = reinterpret_cast<decltype(D3D11CreateDeviceAndSwapChain)*>(::GetProcAddress(d3d11_module, "D3D11CreateDeviceAndSwapChain"));

            DXGI_SWAP_CHAIN_DESC sd {0};
            sd.BufferCount = 1;
            sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
            sd.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
            sd.OutputWindow = main_window;
            sd.Windowed = true;
            sd.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;
            sd.SampleDesc.Count = 1;

            ID3D11Device* pDevice = nullptr;
            IDXGISwapChain* pSwapChain = nullptr;

            HRESULT hr = pD3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION, &sd, &pSwapChain, &pDevice, nullptr, nullptr);
            if (FAILED(hr))
            {
                SAFE_RELEASE(pSwapChain);
                SAFE_RELEASE(pDevice);
                return;
            }

            auto* vTable = *reinterpret_cast<DWORD_PTR**>(pSwapChain);

            // Hook Present
            auto* present = reinterpret_cast<decltype(D3D11Device_Present)*>(vTable[8]);
            directx_device11_swapchain_present_hook = std::make_unique<Hook>(reinterpret_cast<void*>(present), reinterpret_cast<void*>(D3D11Device_Present));
            directx_device11_swapchain_present_hook->apply();

            SAFE_RELEASE(pSwapChain);
            SAFE_RELEASE(pDevice);
        }
        else
        {
            HMODULE d3d9_module = GetModuleHandle("d3d9.dll");
            auto pDirect3DCreate9 = reinterpret_cast<decltype(Direct3DCreate9)*>(::GetProcAddress(d3d9_module, "Direct3DCreate9"));

            IDirect3D9* pD3D = pDirect3DCreate9(D3D_SDK_VERSION);
            if (!pD3D)
            {
                return;
            }

            // Hook Create Device which will automatically hook EndScene
            auto* vTable = *reinterpret_cast<DWORD_PTR**>(pD3D);
            auto* create_device = reinterpret_cast<decltype(D3D9_CreateDevice)*>(vTable[16]);
            directx_d3d9_createdevice_hook = std::make_unique<Hook>(reinterpret_cast<void*>(create_device), reinterpret_cast<void*>(D3D9_CreateDevice));
            directx_d3d9_createdevice_hook->apply();

            // Hook Initial Device
            D3DDISPLAYMODE display_mode;
            HRESULT hr = pD3D->GetAdapterDisplayMode(D3DADAPTER_DEFAULT, &display_mode);
            if (SUCCEEDED(hr))
            {
                D3DPRESENT_PARAMETERS d3dpp = {0};
                d3dpp.Windowed = true;
                d3dpp.SwapEffect = D3DSWAPEFFECT_DISCARD;
                d3dpp.BackBufferCount = 1;
                d3dpp.BackBufferFormat = display_mode.Format;
                d3dpp.MultiSampleType = D3DMULTISAMPLE_NONE;
                d3dpp.hDeviceWindow = main_window;

                IDirect3DDevice9* pDevice = nullptr;
                pD3D->CreateDevice(D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, main_window, D3DCREATE_HARDWARE_VERTEXPROCESSING | D3DCREATE_MULTITHREADED, &d3dpp, &pDevice);

                // pDevice be hooked via CreateDevice hook
                SAFE_RELEASE(pDevice);
            }

            SAFE_RELEASE(pD3D);
        }
    #endif
}

void StartHook() noexcept
{
    Hook::setup();
    InitialiseHooks();

    //Signal that all hooks are finished initializing..
    ControlCenter::signal_sync(getpid());
}

/*void limit_render(std::function<void()> render, uint32_t max_frames) noexcept
{
    render();
    yield(std::chrono::milliseconds(1000 / max_frames));
}*/
#endif
