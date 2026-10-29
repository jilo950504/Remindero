package com.remindero.app

import kotlinx.coroutines.flow.MutableStateFlow

/** Estado compartido entre el servicio y la interfaz. */
object MonitorState {
    val running = MutableStateFlow(false)
    val alarmActive = MutableStateFlow(false)
    val outside = MutableStateFlow(false)
    val distance = MutableStateFlow<Float?>(null)
    /** Desplazamiento (este, norte) en metros respecto al centro de la zona. */
    val offset = MutableStateFlow<Pair<Float, Float>?>(null)
}
