#ifndef JAVAINTERNAL_HXX_INCLUDED
#define JAVAINTERNAL_HXX_INCLUDED

/*
 * Copyright (c) 1999, 2010, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 * LICENSED UNDER GPL2.
 */

#include "JNI_Common.hxx"

typedef struct {
    jint x1;
    jint y1;
    jint x2;
    jint y2;
} SurfaceDataBounds;

typedef struct {
    SurfaceDataBounds   bounds;
    void                *rasBase;
    jint                pixelBitOffset;
    jint                pixelStride;
    jint                scanStride;
    unsigned int        lutSize;
    jint                *lutBase;
    unsigned char       *invColorTable;
    char                *redErrTable;
    char                *grnErrTable;
    char                *bluErrTable;
    int                 *invGrayTable;
    union {
        void            *align;
        char            data[64];
    } priv;
} SurfaceDataRasInfo;

typedef struct _SurfaceDataOps SurfaceDataOps;

typedef jint LockFunc(JNIEnv *env,
                      SurfaceDataOps *ops,
                      SurfaceDataRasInfo *rasInfo,
                      jint lockflags);

typedef void GetRasInfoFunc(JNIEnv *env,
                            SurfaceDataOps *ops,
                            SurfaceDataRasInfo *pRasInfo);

typedef void ReleaseFunc(JNIEnv *env,
                         SurfaceDataOps *ops,
                         SurfaceDataRasInfo *pRasInfo);

typedef void UnlockFunc(JNIEnv *env,
                        SurfaceDataOps *ops,
                        SurfaceDataRasInfo *pRasInfo);

typedef void SetupFunc(JNIEnv *env,
                       SurfaceDataOps *ops);

typedef void DisposeFunc(JNIEnv *env,
                         SurfaceDataOps *ops);

#define SD_FAILURE              -1
#define SD_SUCCESS              0
#define SD_SLOWLOCK             1
#define SD_LOCK_READ            (1 << 0)
#define SD_LOCK_WRITE           (1 << 1)
#define SD_LOCK_RD_WR           (SD_LOCK_READ | SD_LOCK_WRITE)
#define SD_LOCK_LUT             (1 << 2)
#define SD_LOCK_INVCOLOR        (1 << 3)
#define SD_LOCK_INVGRAY         (1 << 4)
#define SD_LOCK_FASTEST         (1 << 5)
#define SD_LOCK_PARTIAL         (1 << 6)
#define SD_LOCK_PARTIAL_WRITE   (SD_LOCK_WRITE | SD_LOCK_PARTIAL)
#define SD_LOCK_NEED_PIXELS     (SD_LOCK_READ | SD_LOCK_PARTIAL)


struct _SurfaceDataOps {
    LockFunc            *Lock;
    GetRasInfoFunc      *GetRasInfo;
    ReleaseFunc         *Release;
    UnlockFunc          *Unlock;
    SetupFunc           *Setup;
    DisposeFunc         *Dispose;
    jobject             sdObject;
};



#if defined(_WIN32) || defined(_WIN64)
typedef struct _GDIWinSDOps GDIWinSDOps;

typedef HDC GetDCFunc(JNIEnv *env,
                      GDIWinSDOps *wsdo,
                      jint flags,
                      jint *patrop,
                      jobject clip,
                      jobject comp,
                      jint color);

typedef void ReleaseDCFunc(JNIEnv *env,
                           GDIWinSDOps *wsdo,
                           HDC hdc);


typedef void InvalidateSDFunc(JNIEnv *env,
                              GDIWinSDOps *wsdo);

struct _GDIWinSDOps {
    SurfaceDataOps      sdOps;
    LONG                timeStamp;
    jboolean            invalid;
    GetDCFunc           *GetDC;
    ReleaseDCFunc       *ReleaseDC;
    InvalidateSDFunc    *InvalidateSD;
    jint                lockType;
    jint                lockFlags;
    jobject             peer;
    HWND                window;
    RECT                insets;
    jint                depth;
    jint                pixelStride;
    DWORD               pixelMasks[3];
    HBITMAP             bitmap;
    HBITMAP             oldmap;
    HDC                 bmdc;
    int                 bmScanStride;
    int                 bmWidth;
    int                 bmHeight;
    void                *bmBuffer;
    jboolean            bmCopyToScreen;
    void                *brush;
    jint                brushclr;
    void                *pen;
    jint                penclr;
    int                 x, y, w, h;
    void                *surfaceLock;
    void                *device;
};

typedef struct tagBitmapHeader {
    BITMAPINFOHEADER bmiHeader;
    union {
        DWORD dwMasks[3];
        RGBQUAD palette[256];
    } colors;
} BmiType;


#include <d3d9.h>

class IManagedResource {
public:
    virtual BOOL IsDefaultPool() = 0;
protected:
    IManagedResource() { pPrev = pNext = NULL; };
    virtual     ~IManagedResource() { pPrev = pNext = NULL; };
    virtual void Release() = 0;
private:
    IManagedResource& operator=(const IManagedResource&);

    IManagedResource* pPrev;
    IManagedResource* pNext;
};

typedef struct _D3DSDOps D3DSDOps;

class D3DResource;
struct _D3DSDOps {
    SurfaceDataOps sdOps;
    jint adapter;
    jint width, height;
    jint xoff, yoff;
    D3DSWAPEFFECT swapEffect;
    D3DResource  *pResource;
};

class D3DResource : public IManagedResource {
public:
    D3DResource(IDirect3DResource9 *pRes);
    D3DResource(IDirect3DSwapChain9 *pSC);
    IDirect3DResource9*  GetResource();
    IDirect3DTexture9*   GetTexture();
    IDirect3DSurface9*   GetSurface();
    IDirect3DSwapChain9* GetSwapChain();
    D3DSDOps*            GetSDOps();
    void                 SetSDOps(D3DSDOps *pOps);
    D3DSURFACE_DESC*     GetDesc();
    virtual BOOL         IsDefaultPool();

protected:
    virtual                 ~D3DResource();
    virtual void             Release();
    void                 Init(IDirect3DResource9*, IDirect3DSwapChain9*);

private:
    D3DResource() {}
    D3DResource&         operator=(const D3DResource&);

    IDirect3DResource9*  pResource;
    IDirect3DSwapChain9* pSwapChain;
    IDirect3DSurface9*   pSurface;
    IDirect3DTexture9*   pTexture;
    D3DSDOps*            pOps;
    D3DSURFACE_DESC      desc;
};
#endif

typedef unsigned int juint;

/*
typedef struct _OGLSDOps OGLSDOps;
typedef unsigned char jubyte;

typedef struct {
    GLenum src;
    GLenum dst;
} OGLBlendRule;

typedef struct {
    GLenum   format;
    GLenum   type;
    jint     alignment;
    jboolean hasAlpha;
    jboolean isPremult;
} OGLPixelFormat;

OGLPixelFormat PixelFormats[] = {
    {GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV,  4, 1, 0},   // 0 - IntArgb
    { GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 4, 1, 1},   // 1 - IntArgbPre
    { GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 4, 0, 1},   // 2 - IntRgb
    { GL_RGBA, GL_UNSIGNED_INT_8_8_8_8, 4, 0, 1},       // 3 - IntRgbx
    { GL_RGBA, GL_UNSIGNED_INT_8_8_8_8_REV, 4, 0, 1},   // 4 - IntBgr
    { GL_BGRA, GL_UNSIGNED_INT_8_8_8_8, 4, 0, 1},       // 5 - IntBgrx
    { GL_RGB,  GL_UNSIGNED_SHORT_5_6_5, 2, 0, 1},       // 6 - Ushort565Rgb
    { GL_BGRA, GL_UNSIGNED_SHORT_1_5_5_5_REV, 2, 0, 1}, // 7 - Ushort555Rgb
    { GL_RGBA, GL_UNSIGNED_SHORT_5_5_5_1, 2, 0, 1},     // 8 - Ushort555Rgbx
    { GL_LUMINANCE, GL_UNSIGNED_BYTE, 1, 0, 1},         // 9 - ByteGray
    { GL_LUMINANCE, GL_UNSIGNED_SHORT, 2, 0, 1},        // 10 - UshortGray
    { GL_BGR,  GL_UNSIGNED_BYTE, 1, 0, 1},              // 11 - ThreeByteBgr
};

typedef struct {
    void       *ctxInfo;
    jint       caps;
    jint       compState;
    jfloat     extraAlpha;
    jint       xorPixel;
    jint       pixel;
    jubyte     r;
    jubyte     g;
    jubyte     b;
    jubyte     a;
    jint       paintState;
    jboolean   useMask;
    GLdouble   *xformMatrix;
    GLuint     blitTextureID;
    GLint      textureFunction;
    jboolean   vertexCacheEnabled;
} OGLContext;

struct _OGLSDOps {
    SurfaceDataOps               sdOps;
    void                         *privOps;
    jint                         drawableType;
    GLenum                       activeBuffer;
    jboolean                     isOpaque;
    jboolean                     needsInit;
    jint                         xOffset;
    jint                         yOffset;
    jint                         width;
    jint                         height;
    GLuint                       textureID;
    jint                         textureWidth;
    jint                         textureHeight;
    GLenum                       textureTarget;
    GLint                        textureFilter;
    GLuint                       fbobjectID;
    GLuint                       depthID;
};*/

typedef struct _CompositeInfo {
    jint        rule;
    union {
        jfloat  extraAlpha;
        jint    xorPixel;
    } details;
    juint alphaMask;
} CompositeInfo;

typedef void (JNICALL CompInfoFunc)(JNIEnv *env,
                                    CompositeInfo *pCompInfo,
                                    jobject Composite);

typedef struct _SurfCompHdr {
    char                *Name;
    jobject             Object;
} SurfCompHdr;

typedef struct _CompositeType {
    SurfCompHdr         hdr;
    CompInfoFunc        *getCompInfo;
    jint                dstflags;
} CompositeType;

struct _NativePrimitive;

typedef void (AnyFunc)();

typedef void (BlitFunc)(void *pSrc, void *pDst,
                        juint width, juint height,
                        SurfaceDataRasInfo *pSrcInfo,
                        SurfaceDataRasInfo *pDstInfo,
                        struct _NativePrimitive *pPrim,
                        CompositeInfo *pCompInfo);

typedef void (BlitBgFunc)(void *pSrc, void *pDst,
                          juint width, juint height, jint bgpixel,
                          SurfaceDataRasInfo *pSrcInfo,
                          SurfaceDataRasInfo *pDstInfo,
                          struct _NativePrimitive *pPrim,
                          CompositeInfo *pCompInfo);

typedef void (ScaleBlitFunc)(void *pSrc, void *pDst,
                             juint dstwidth, juint dstheight,
                             jint sxloc, jint syloc,
                             jint sxinc, jint syinc, jint scale,
                             SurfaceDataRasInfo *pSrcInfo,
                             SurfaceDataRasInfo *pDstInfo,
                             struct _NativePrimitive *pPrim,
                             CompositeInfo *pCompInfo);

typedef void (MaskBlitFunc)(void *pDst, void *pSrc,
                            unsigned char *pMask, jint maskOff, jint maskScan,
                            jint width, jint height,
                            SurfaceDataRasInfo *pDstInfo,
                            SurfaceDataRasInfo *pSrcInfo,
                            struct _NativePrimitive *pPrim,
                            CompositeInfo *pCompInfo);

typedef struct _NativePrimitive {
    void                *pPrimType;
    void                *pSrcType;
    CompositeType       *pCompType;
    void                *pDstType;
    union {
        AnyFunc                 *initializer;
        BlitFunc                *blit;
        BlitBgFunc              *blitbg;
        ScaleBlitFunc           *scaledblit;
        AnyFunc                 *fillrect;
        AnyFunc                 *fillspans;
        AnyFunc                 *fillparallelogram;
        void                    *drawparallelogram;
        AnyFunc                 *drawline;
        AnyFunc                 *maskfill;
        MaskBlitFunc            *maskblit;
        AnyFunc                 *drawglyphlist;
        AnyFunc                 *drawglyphlistaa;
        AnyFunc                 *drawglyphlistlcd;
        void                    *transformhelpers;
    } funcs, funcs_c;
    jint                srcflags;
    jint                dstflags;
} NativePrimitive;

typedef struct {
    SurfaceDataBounds   bounds;
    jint                endIndex;
    jobject             bands;
    jint                index;
    jint                numrects;
    jint                *pBands;
} RegionData;

#endif // JAVAINTERNAL_HXX_INCLUDED
