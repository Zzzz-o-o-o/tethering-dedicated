package local.tethering.dedicated

/**
 * テザリングの連続稼働時間（v0.9.0。テザリングの詳細の画面に出す）。Android に依存しない。
 * アクセスポイントの状態を見るたびに [next] で記録を進める。記録はアプリが更新・再起動しても続けて使う（同じ端末の起動の中なら）
 */
object ApSession {
    data class Stored(
        /** ON になった時刻（0 = OFF・不明） */
        val onSince: Long,
        /** [onSince] が ON になった時刻そのものか（false なら、それより前から ON） */
        val exact: Boolean,
        /** OFF になった時刻（0 = ON・不明） */
        val offAt: Long,
        /** 前回の停止の始まりと長さ（0 = なし・不明） */
        val lastOffStart: Long,
        val lastOffMs: Long,
    )

    /**
     * アクセスポイントの状態 [on] を見たときの次の記録。[prev] はこのプロセスで前に見た状態（null = 初めて）、
     * [bootWall] は端末が起動した時刻、[bootOnAt] は起動時にこのアプリが ON にした時刻（なければ null）
     */
    fun next(s: Stored, prev: Boolean?, on: Boolean, now: Long, bootWall: Long, bootOnAt: Long?): Stored {
        if (prev == on) return s
        if (!on) {
            // ON → OFF を見たならその時刻。初めて見たときにもう OFF なら、この起動の記録があればそれを使う（なければ不明）
            return s.copy(onSince = 0L, exact = false, offAt = if (prev == true) now else s.offAt.takeIf { it > bootWall } ?: 0L)
        }
        return when {
            prev == false -> {
                val off = s.offAt.takeIf { it in bootWall..now }
                s.copy(
                    onSince = now, exact = true, offAt = 0L,
                    lastOffStart = off ?: s.lastOffStart, lastOffMs = off?.let { now - it } ?: s.lastOffMs,
                )
            }
            // 監視を始めたときにはもう ON：前のプロセスの記録がこの起動のものなら、そのまま続ける
            s.onSince > bootWall -> s.copy(offAt = 0L)
            bootOnAt != null && bootOnAt > bootWall -> s.copy(onSince = bootOnAt, exact = true, offAt = 0L)
            else -> s.copy(onSince = now, exact = false, offAt = 0L)
        }
    }

    /**
     * 直近 [windowMs] の電力の記録（区間）から、テザリング ON の時間と、記録のある時間（ミリ秒）。
     * [parse] は区間の開始・終了の文字列を時刻にする
     */
    fun onTime(rows: List<PowerRow>, now: Long, windowMs: Long, parse: (String) -> Long?): Pair<Long, Long> {
        var on = 0L
        var covered = 0L
        val from = now - windowMs
        for (r in rows) {
            val s = parse(r.start) ?: continue
            val e = parse(r.end) ?: continue
            val a = maxOf(s, from)
            val b = minOf(e, now)
            if (b <= a) continue
            covered += b - a
            if (r.tether == true) on += b - a
        }
        return on to covered
    }
}
