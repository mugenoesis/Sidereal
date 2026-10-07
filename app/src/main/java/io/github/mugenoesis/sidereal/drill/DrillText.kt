package io.github.mugenoesis.sidereal.drill

import android.util.Base64

/** Text shown on the drill screen. */
internal object DrillText {
    private fun d(s: String) = String(Base64.decode(s, Base64.NO_WRAP), Charsets.UTF_8)

    val score: String by lazy { d("U0NPUkUg") }
    val best: String by lazy { d("QkVTVCA=") }
    val title: String by lazy { d("QVNURVJPSURT") }
    val hint: String by lazy { d("TEVGVCBTVElDSyBBSU1TICDCtyAgQSBGSVJFUyAgwrcgIEIgUVVJVFMgIMK3ICBZIEJBQ0tHUk9VTkQ=") }
    val over: String by lazy { d("R0FNRSBPVkVS") }
    val record: String by lazy { d("TkVXIEhJR0ggU0NPUkUh") }
    val again: String by lazy { d("QTogUExBWSBBR0FJTiAgICAgIEI6IFFVSVQ=") }
    val gap: String by lazy { d("ICAgIA==") }
}
