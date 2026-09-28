package com.sagiii.twsevaluate.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ScoState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/**
 * 会議モード用にBluetooth SCO(HFP)接続を確立/解除する。
 * Google Meet/Zoom等の会議アプリが通話中に行うのと同じ仕組み
 * (AudioManagerをMODE_IN_COMMUNICATIONにしてSCOを張る)を踏襲する。
 */
class BluetoothScoController(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(ScoState.DISCONNECTED)
    val state: StateFlow<ScoState> = _state

    private var receiverRegistered = false

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> _state.value = ScoState.CONNECTED
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    if (_state.value != ScoState.DISCONNECTED) _state.value = ScoState.DISCONNECTED
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> _state.value = ScoState.ERROR
            }
        }
    }

    fun start() {
        if (!receiverRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(scoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(scoReceiver, filter)
            }
            receiverRegistered = true
        }
        _state.value = ScoState.CONNECTING
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isBluetoothScoOn = true
        audioManager.startBluetoothSco()
    }

    fun stop() {
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        _state.value = ScoState.DISCONNECTED
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(scoReceiver) }
            receiverRegistered = false
        }
    }
}
