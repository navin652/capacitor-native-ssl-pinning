package com.cap.nativehttp;

import android.util.Log;

public class NativeHttp {

    public String echo(String value) {
        Log.i("Echo", value);
        return value;
    }
}
