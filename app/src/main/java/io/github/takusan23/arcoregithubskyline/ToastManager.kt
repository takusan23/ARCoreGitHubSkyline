package io.github.takusan23.arcoregithubskyline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Toastを表示するだけのクラス */
class ToastManager(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())

    /** 前回のメッセージ */
    private var prevMessage: String? = null

    /**
     * Toastを表示させる
     * @param message 本文
     */
    fun show(message: String) {
        // 同じ場合は出さない
        if (prevMessage == message) {
            return
        }
        handler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            prevMessage = message
        }
    }
}