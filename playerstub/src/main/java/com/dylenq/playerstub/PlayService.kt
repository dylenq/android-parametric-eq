package com.dylenq.playerstub

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.IBinder

/**
 * Plays silence through a real AudioTrack and sends NO session broadcast, like a player that never
 * announces itself. LauncherActivity starts and stops it on a script's request.
 */
class PlayService : Service() {
    @Volatile private var track: AudioTrack? = null
    private var thread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_STOP -> { stopPlaying(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun play() {
        if (track != null) return
        val rate = 48000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        thread = Thread {
            val zeros = ByteArray(4096)
            try {
                while (track === t) t.write(zeros, 0, zeros.size)
            } catch (_: Throwable) {
            }
        }.also { it.start() }
    }

    private fun stopPlaying() {
        val t = track ?: return
        track = null
        thread?.join(500)
        thread = null
        try { t.stop() } catch (_: Throwable) {}
        t.release()
    }

    override fun onDestroy() {
        stopPlaying()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PLAY = "com.dylenq.playerstub.PLAY"
        const val ACTION_STOP = "com.dylenq.playerstub.STOP"
    }
}
