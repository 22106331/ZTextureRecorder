/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zcc.mediarecorder.encoder.core.codec;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.WorkerThread;
import android.util.Log;
import android.view.Surface;

import com.zcc.mediarecorder.ALog;
import com.zcc.mediarecorder.EventManager;
import com.zcc.mediarecorder.common.ErrorCode;
import com.zcc.mediarecorder.encoder.core.IMovieEncoderCore;
import com.zcc.mediarecorder.encoder.core.codec.audio.AudioRecorderThread2;
import com.zcc.mediarecorder.encoder.core.codec.muxer.MuxerHolder;
import com.zcc.mediarecorder.encoder.utils.VideoUtils;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * This class wraps up the core components used for surface-input video encoding.
 * <p>
 * Once created, frames are fed to the input surface.  Remember to provide the presentation
 * time stamp, and always call drainEncoder() before swapBuffers() to ensure that the
 * producer side doesn't get backed up.
 * <p>
 * This class is not thread-safe, with one exception: it is valid to use the input surface
 * on one thread, and drain the output on a different thread.
 */
public class MediaCodecEncoderCore implements IMovieEncoderCore {
    private static final String TAG = "MediaCodecEncoderCore";
    private static final boolean VERBOSE = true;
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int FRAME_INTERVAL = 5;           // 5 seconds between I-frames
    private final Object STOP_MUTEX = new Object();
    private Surface mInputSurface;
    private MuxerHolder mMuxerHolder;
    private AudioRecorderThread2 mAudioRecorderThread2;
    private HandlerThread mVideoRecorderHandlerThread;
    private Handler mVideoHandler;
    private MediaCodec mEncoder;
    private MediaCodec.BufferInfo mBufferInfo;
    private int mTrackIndex;
    private volatile boolean isStoped = false;

    /**
     * Configures encoder and muxer state, and prepares the input Surface.
     */
    public MediaCodecEncoderCore(int width, int height, String outputFile)
            throws IOException {
        mVideoRecorderHandlerThread = new HandlerThread("media_codec_video");
        mVideoRecorderHandlerThread.start();
        mBufferInfo = new MediaCodec.BufferInfo();
        try {
            mMuxerHolder = new MuxerHolder(outputFile);
        } catch (IOException e) {
            e.printStackTrace();
            ALog.e(TAG, "create muxer error");
            EventManager.get().sendMsg(ErrorCode.ERROR_MEDIA_COMMON, "create muxer error");
            return;
        }
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, VideoUtils.getVideoBitRate(width, height));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, FRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        try {
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            e.printStackTrace();
        }
        mInputSurface = mEncoder.createInputSurface();
        mEncoder.start();

        // Create a MediaMuxer.  We can't add the video track and doStart() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        mTrackIndex = -1;

        // Create a AudioRecordThread;
        mAudioRecorderThread2 = new AudioRecorderThread2(mMuxerHolder);
    }

    /**
     * Returns the encoder's input surface.
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    @Override
    public void drainEncoder(final boolean endOfStream) {
        ALog.d(TAG, "drainEncoder");
        mVideoHandler.post(new Runnable() {
            @Override
            public void run() {
                drainEncoderOnWorkerThread(endOfStream);
            }
        });
    }

    @Override
    public void doStart() {
        mAudioRecorderThread2.doStart();
        synchronized (STOP_MUTEX) {
            isStoped = false;
        }
    }

    @Override
    public void doStop() {
        mVideoHandler.post(new Runnable() {
            @Override
            public void run() {
                doStopOnWorkerThread();
            }
        });
    }


    /**
     * Releases encoder resources.
     * If not stop ， core would be stop first
     */
    public void doRelease() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        synchronized (STOP_MUTEX) {
            while (!isStoped) {
                doStop();
                try {
                    STOP_MUTEX.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        if (mEncoder != null) {
            mEncoder.release();
            mEncoder = null;
        }
        if (mVideoRecorderHandlerThread != null) {
            mVideoRecorderHandlerThread.quit();
            mVideoHandler = null;
        }
        mAudioRecorderThread2.doRelease();
    }

    @Override
    public long getPTSUs() {
        return mMuxerHolder.getPTSUs();
    }

    @Override
    public void doPrepare() {
        ALog.d(TAG, "dopreaper");
        if (mVideoHandler == null) {
            mVideoHandler = new Handler(mVideoRecorderHandlerThread.getLooper());
        }
        mAudioRecorderThread2.doPrepare();
    }

    @WorkerThread
    private void doStopOnWorkerThread() {
        if (mEncoder != null) {
            mEncoder.stop();
        }
        if (mMuxerHolder != null) {
            // TODO: doStop() throws an exception if you haven't fed it any data.  Keep track
            //       of frames submitted, and don't call doStop() if we haven't written anything.
            try {
                mMuxerHolder.onReleaseFrameMux();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        synchronized (STOP_MUTEX) {
            isStoped = true;
            STOP_MUTEX.notifyAll();
        }
        mAudioRecorderThread2.doStop();
    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     * <p>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     * <p>
     * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
     * not recording audio.
     */
    @WorkerThread
    private void drainEncoderOnWorkerThread(boolean endOfStream) {
        mMuxerHolder.onFrame();
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            mEncoder.signalEndOfInputStream();
            mAudioRecorderThread2.doStop();
        }
        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (true) {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);
                // now that we have the Magic Goodies, doStart the muxer
                mTrackIndex = mMuxerHolder.getMuxer().addTrack(newFormat);
                mMuxerHolder.setFrameConfig(true);
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                mMuxerHolder.waitUntilReady();
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
                    mBufferInfo.presentationTimeUs = mMuxerHolder.getPTSUs();
                    mMuxerHolder.getMuxer().writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    Log.d("presentTime", " video sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                            mBufferInfo.presentationTimeUs);
                }
                mEncoder.releaseOutputBuffer(encoderStatus, false);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
    }


}
