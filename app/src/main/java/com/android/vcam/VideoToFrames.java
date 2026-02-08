package com.android.vcam;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 视频解码为帧，供虚拟摄像头使用。
 * 代码修改自 https://github.com/zhantong/Android-VideoToImages
 */
public class VideoToFrames implements Runnable {

    private static final String TAG = "VideoToFrames";
    private static final boolean VERBOSE = false;
    private static final long DEFAULT_TIMEOUT_US = 10_000L;
    private static final int COLOR_FORMAT_I420 = 1;
    private static final int COLOR_FORMAT_NV21 = 2;

    private final int decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;

    private LinkedBlockingQueue<byte[]> mQueue;
    private OutputImageFormat outputImageFormat;
    private volatile boolean stopDecode;

    private String videoFilePath;
    private Throwable throwable;
    private Thread childThread;
    private Surface playSurface;

    private Callback callback;

    public interface Callback {
        void onFinishDecode();
        void onDecodeFrame(int index);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setEnqueue(LinkedBlockingQueue<byte[]> queue) {
        mQueue = queue;
    }

    /** 设置输出格式（JPEG/NV21等），dir 参数保留兼容未使用 */
    public void setSaveFrames(String dir, OutputImageFormat imageFormat) {
        outputImageFormat = imageFormat;
    }

    public void setSurface(Surface surface) {
        if (surface != null) {
            playSurface = surface;
        }
    }

    /** 兼容旧调用：set_surfcae -> setSurface */
    public void set_surfcae(Surface surface) {
        setSurface(surface);
    }

    public void stopDecode() {
        stopDecode = true;
    }

    public void decode(String videoFilePath) throws Throwable {
        this.videoFilePath = videoFilePath;
        if (childThread == null) {
            childThread = new Thread(this, "VideoToFrames-decode");
            childThread.start();
            if (throwable != null) {
                throw throwable;
            }
        }
    }

    @Override
    public void run() {
        try {
            videoDecode(videoFilePath);
        } catch (Throwable t) {
            throwable = t;
        }
    }

    @SuppressLint("WrongConstant")
    public void videoDecode(String path) throws IOException {
        Logger.i("【VCAM】【decoder】开始解码");
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                Logger.i("【VCAM】【decoder】No video track found in " + path);
                return;
            }
            extractor.selectTrack(trackIndex);
            MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            MediaCodecInfo.CodecCapabilities caps = decoder.getCodecInfo().getCapabilitiesForType(mime);
            if (VERBOSE) {
                logSupportedColorFormats(caps);
            }
            if (isColorFormatSupported(decodeColorFormat, caps)) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat);
                Logger.i("【VCAM】【decoder】set decode color format to type " + decodeColorFormat);
            } else {
                Log.i(TAG, "unable to set decode color format, type " + decodeColorFormat + " not supported");
                Logger.i("【VCAM】【decoder】unable to set decode color format, type " + decodeColorFormat + " not supported");
            }
            decodeFramesToImage(decoder, extractor, mediaFormat);
            decoder.stop();
            while (!stopDecode) {
                extractor.seekTo(0, 0);
                decodeFramesToImage(decoder, extractor, mediaFormat);
                decoder.stop();
            }
        } catch (Exception e) {
            Logger.i("【VCAM】[videofile] " + e);
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.w(TAG, "decoder release", e);
                }
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }

    private void logSupportedColorFormats(MediaCodecInfo.CodecCapabilities caps) {
        StringBuilder sb = new StringBuilder("supported color format: ");
        for (int c : caps.colorFormats) {
            sb.append(c).append('\t');
        }
        Log.d(TAG, sb.toString());
    }

    private static boolean isColorFormatSupported(int colorFormat, MediaCodecInfo.CodecCapabilities caps) {
        for (int c : caps.colorFormats) {
            if (c == colorFormat) return true;
        }
        return false;
    }

    private void decodeFramesToImage(MediaCodec decoder, MediaExtractor extractor, MediaFormat mediaFormat) {
        long startWhen = 0;
        boolean isFirstFrame = true;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        decoder.configure(mediaFormat, playSurface, null, 0);
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        decoder.start();
        int outputFrameCount = 0;

        while (!sawOutputEOS && !stopDecode) {
            if (!sawInputEOS) {
                int inputBufferId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                    } else {
                        decoder.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
            if (outputBufferId >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                }
                if (info.size != 0) {
                    outputFrameCount++;
                    if (callback != null) {
                        callback.onDecodeFrame(outputFrameCount);
                    }
                    if (isFirstFrame) {
                        startWhen = System.currentTimeMillis();
                        isFirstFrame = false;
                    }
                    if (playSurface == null) {
                        Image image = decoder.getOutputImage(outputBufferId);
                        if (image != null) {
                            try {
                                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                byte[] arr = new byte[buffer.remaining()];
                                buffer.get(arr);
                                if (mQueue != null) {
                                    try {
                                        mQueue.put(arr);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        Logger.i("【VCAM】" + e);
                                    }
                                }
                                if (outputImageFormat != null) {
                                    HookMain.data_buffer = getDataFromImage(image, COLOR_FORMAT_NV21);
                                }
                            } finally {
                                image.close();
                            }
                        }
                    }
                    long sleepTime = info.presentationTimeUs / 1000 - (System.currentTimeMillis() - startWhen);
                    if (sleepTime > 0) {
                        try {
                            Thread.sleep(sleepTime);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            Logger.i("【VCAM】线程延迟被中断");
                        }
                    }
                    decoder.releaseOutputBuffer(outputBufferId, true);
                }
            }
        }
        if (callback != null) {
            callback.onFinishDecode();
        }
    }

    private static int selectTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + ": " + format);
                }
                return i;
            }
        }
        return -1;
    }

    private static boolean isImageFormatSupported(Image image) {
        int format = image.getFormat();
        return format == ImageFormat.YUV_420_888
                || format == ImageFormat.NV21
                || format == ImageFormat.YV12;
    }

    private static byte[] getDataFromImage(Image image, int colorFormat) {
        if (colorFormat != COLOR_FORMAT_I420 && colorFormat != COLOR_FORMAT_NV21) {
            throw new IllegalArgumentException("only support COLOR_FormatI420 and COLOR_FormatNV21");
        }
        if (!isImageFormatSupported(image)) {
            throw new RuntimeException("can't convert Image to byte array, format " + image.getFormat());
        }
        Rect crop = image.getCropRect();
        int format = image.getFormat();
        int width = crop.width();
        int height = crop.height();
        Image.Plane[] planes = image.getPlanes();
        byte[] data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        byte[] rowData = new byte[planes[0].getRowStride()];
        if (VERBOSE) Log.v(TAG, "get data from " + planes.length + " planes");

        int channelOffset = 0;
        int outputStride = 1;
        for (int i = 0; i < planes.length; i++) {
            switch (i) {
                case 0:
                    channelOffset = 0;
                    outputStride = 1;
                    break;
                case 1:
                    channelOffset = colorFormat == COLOR_FORMAT_I420 ? width * height : width * height + 1;
                    outputStride = colorFormat == COLOR_FORMAT_NV21 ? 2 : 1;
                    break;
                case 2:
                    channelOffset = colorFormat == COLOR_FORMAT_I420 ? (int) (width * height * 1.25) : width * height;
                    outputStride = colorFormat == COLOR_FORMAT_NV21 ? 2 : 1;
                    break;
            }
            ByteBuffer buffer = planes[i].getBuffer();
            int rowStride = planes[i].getRowStride();
            int pixelStride = planes[i].getPixelStride();
            if (VERBOSE) {
                Log.v(TAG, "plane " + i + " pixelStride=" + pixelStride + " rowStride=" + rowStride);
            }
            int shift = (i == 0) ? 0 : 1;
            int w = width >> shift;
            int h = height >> shift;
            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            for (int row = 0; row < h; row++) {
                int length;
                if (pixelStride == 1 && outputStride == 1) {
                    length = w;
                    buffer.get(data, channelOffset, length);
                    channelOffset += length;
                } else {
                    length = (w - 1) * pixelStride + 1;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        data[channelOffset] = rowData[col * pixelStride];
                        channelOffset += outputStride;
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
        }
        return data;
    }
}

enum OutputImageFormat {
    I420("I420"),
    NV21("NV21"),
    JPEG("JPEG");

    private final String friendlyName;

    OutputImageFormat(String friendlyName) {
        this.friendlyName = friendlyName;
    }

    @Override
    public String toString() {
        return friendlyName;
    }
}
