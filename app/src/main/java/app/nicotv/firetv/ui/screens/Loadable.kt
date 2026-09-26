package app.nicotv.firetv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.ui.Entry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 画面の取得状態。BackStackEntry に保持するので、戻ったときに取り直さず同じ一覧・位置を出せる。
 * 読込中も戻れるよう、画面を離れたらジョブは取り消される。
 */
class Loadable<T>(private val loader: suspend () -> LoadResult<T>) {
    var result by mutableStateOf<LoadResult<T>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun load(scope: CoroutineScope, force: Boolean = false) {
        if (!force && (result != null || loading)) return
        job?.cancel()
        loading = true
        job = scope.launch {
            try {
                result = loader()
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun <T> rememberLoadable(entry: Entry, key: String, loader: suspend () -> LoadResult<T>): Pair<Loadable<T>, CoroutineScope> {
    val l = entry.keep(key) { Loadable(loader) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(l) { l.load(scope) }
    return l to scope
}
