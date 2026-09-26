package com.dsh.apprecorder;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 帧率限制器。
 *
 * 为什么需要它：VirtualDisplay 是按屏幕刷新率推帧的（本机 120/165Hz），
 * MediaFormat.KEY_FRAME_RATE 只是编码器码控的提示，**不会真的限帧**。
 * 结果是录出来一个 30~140fps 剧烈抖动的 VFR 流，很多相册/硬件解码器会直接拒绝播放。
 *
 * 做法：VirtualDisplay 渲染到 SurfaceTexture，本类以目标帧率把它重绘到编码器的
 * 输入 Surface（EGL + GLES），因此输出严格是 targetFps。
 * 时间戳用 SurfaceTexture 给出的「捕获时刻」，保证与音轨同一时钟域。
 */
public final class GlFramer {

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final String VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    // Grafika 全屏矩形的标准坐标：texcoord (0,0) 落在裁剪空间左下角，
    // 这与 SurfaceTexture 的 BufferQueue 存储约定配套，画面不会上下颠倒。
    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
    };

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface inputSurface;
    private int texId = 0;
    private int program = 0;
    private int uTexMatrix = -1;
    private int aPosition = -1;
    private int aTextureCoord = -1;
    private int width;
    private int height;
    private FloatBuffer quadBuffer;
    private final float[] texMatrix = new float[16];

    private final Object frameLock = new Object();
    private boolean frameAvailable;
    private volatile long lastTimestampNs;
    private boolean released;

    public Surface getInputSurface() {
        return inputSurface;
    }

    public long lastTimestampNs() {
        return lastTimestampNs;
    }

    /** 建立 EGL 环境并返回应该交给 VirtualDisplay 的 Surface。 */
    public Surface prepare(Surface encoderSurface, int w, int h) {
        width = w;
        height = h;
        quadBuffer = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadBuffer.put(QUAD).position(0);

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay 失败");
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            throw new RuntimeException("eglInitialize 失败");
        }

        int[] attrs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            throw new RuntimeException("eglChooseConfig 失败（不支持 RECORDABLE）");
        }
        int[] ctxAttrs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrs, 0);
        checkEgl("eglCreateContext");
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface,
                new int[]{EGL14.EGL_NONE}, 0);
        checkEgl("eglCreateWindowSurface");
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            checkEgl("eglMakeCurrent");
        }

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (program == 0) throw new RuntimeException("着色器编译失败");
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTextureCoord = GLES20.glGetAttribLocation(program, "aTextureCoord");
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");

        texture = new SurfaceTexture(texId);
        texture.setDefaultBufferSize(width, height);
        texture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture st) {
                synchronized (frameLock) {
                    frameAvailable = true;
                    frameLock.notifyAll();
                }
            }
        });
        inputSurface = new Surface(texture);
        // 一个 EGL 上下文同一时刻只能 current 在一个线程上。
        // setup 线程用完必须解绑，否则渲染线程 makeCurrent 会拿到 EGL_BAD_ACCESS(0x3002)。
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT);
        return inputSurface;
    }

    private static void checkEgl(String what) {
        int err = EGL14.eglGetError();
        if (err != EGL14.EGL_SUCCESS) {
            throw new RuntimeException(what + " 失败, EGL error 0x" + Integer.toHexString(err));
        }
    }

    /**
     * EGL 上下文是**线程私有**的：上下文在 setup 线程创建，
     * 渲染线程必须自己再 makeCurrent 一次，否则所有 GL 调用空转、
     * eglSwapBuffers 永远不把帧交给编码器（表现为视频轨一个样本都没有）。
     */
    public void makeCurrent() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("EGL 未初始化");
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            int err = EGL14.eglGetError();
            throw new RuntimeException("渲染线程 eglMakeCurrent 失败 0x" + Integer.toHexString(err));
        }
    }

    /** 等待一帧到达，最多 timeoutMs 毫秒。返回 true 表示有新帧。 */
    public boolean awaitFrame(long timeoutMs) {
        synchronized (frameLock) {
            if (frameAvailable || released) return frameAvailable;
            try {
                frameLock.wait(timeoutMs);
            } catch (InterruptedException ignored) { }
            return frameAvailable;
        }
    }

    /** 把最新一帧画进编码器 Surface。返回 false 表示当前没有新帧。 */
    public boolean renderOnce() {
        synchronized (frameLock) {
            if (!frameAvailable || released) return false;
            frameAvailable = false;
        }
        try {
            texture.updateTexImage();
        } catch (Throwable t) {
            return false;
        }
        texture.getTransformMatrix(texMatrix);
        lastTimestampNs = texture.getTimestamp();

        GLES20.glViewport(0, 0, width, height);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);

        quadBuffer.position(0);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuffer);
        GLES20.glEnableVertexAttribArray(aPosition);
        quadBuffer.position(2);
        GLES20.glVertexAttribPointer(aTextureCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuffer);
        GLES20.glEnableVertexAttribArray(aTextureCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, lastTimestampNs);
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    public void release() {
        released = true;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        try {
            if (inputSurface != null) inputSurface.release();
        } catch (Throwable ignored) { }
        inputSurface = null;
        try {
            if (texture != null) texture.release();
        } catch (Throwable ignored) { }
        texture = null;
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
            }
        } catch (Throwable ignored) { }
        eglSurface = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        if (program != 0) {
            try {
                GLES20.glDeleteProgram(program);
            } catch (Throwable ignored) { }
            program = 0;
        }
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }
}
