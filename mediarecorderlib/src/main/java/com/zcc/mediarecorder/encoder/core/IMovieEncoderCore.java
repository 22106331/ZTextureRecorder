package com.zcc.mediarecorder.encoder.core;

import android.view.Surface;

import com.zcc.mediarecorder.common.ILifeCircle;

public interface IMovieEncoderCore extends ILifeCircle {

    public Surface getInputSurface();

    public void drainEncoder(boolean endOfStream);

    public long getPTSUs();

}
