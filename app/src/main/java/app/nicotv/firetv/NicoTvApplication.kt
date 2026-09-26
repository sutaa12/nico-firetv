package app.nicotv.firetv

import android.app.Application

/** Activity の再作成でも通信・保存処理を重複して作らない。 */
class NicoTvApplication : Application() {
    val container by lazy { AppContainer(this) }
}
