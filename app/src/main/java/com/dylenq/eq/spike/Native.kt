package com.dylenq.eq.spike

object Native {
    init {
        System.loadLibrary("eq_ping")
    }

    external fun ping(): Int
}
