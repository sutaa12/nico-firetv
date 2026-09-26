package app.nicotv.firetv.data.local

/** 認証情報や表示名をファイル名へ含めない。旧形式の無区画キャッシュは再利用しない。 */
fun cacheScope(scope: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
