package com.dylenq.eq.spike

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "EQ spike" })
        try {
            Log.i("SPIKE-INIT", "ping=${Native.ping()}")
        } catch (t: Throwable) {
            Log.e("SPIKE-INIT", "ping failed", t)
        }
    }
}
