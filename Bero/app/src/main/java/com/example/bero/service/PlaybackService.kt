package com.example.bero.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.bero.MainActivity
import com.example.bero.audio.AudioFx

/**
 * Servicio real de reproduccion: ExoPlayer vive AQUI, no en la Activity.
 * Media3 gestiona la notificacion, el foreground service y los controles de pantalla de bloqueo/Bluetooth.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        AudioFx.init(applicationContext)

        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Audio local: no hace falta precargar mucho. Buffers pequenos = menos memoria y menos GC.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 15_000, 1_000, 2_000)
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setAudioAttributes(attributes, true) // gestiona audio focus
            .setHandleAudioBecomingNoisy(true)    // pausa al desconectar auriculares
            .setWakeMode(C.WAKE_MODE_LOCAL)       // evita que la CPU se duerma en segundo plano
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(audioSessionId)
            }
        })
        AudioFx.attach(player.audioSessionId)

        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(activityIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Si el usuario cierra la app desde recientes: seguir sonando si esta reproduciendo, si no, detener.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        AudioFx.release()
        super.onDestroy()
    }
}
