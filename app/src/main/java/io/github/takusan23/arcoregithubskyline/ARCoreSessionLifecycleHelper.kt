package io.github.takusan23.arcoregithubskyline

import android.app.Activity
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import io.github.takusan23.arcoregithubskyline.common.helpers.CameraPermissionHelper

/**
 * ARCoreのセッションとライフサイクル
 */
class ARCoreSessionLifecycleHelper(
    private val activity: Activity,
    private val features: Set<Session.Feature> = emptySet(),
) : DefaultLifecycleObserver {

    var installRequested = false
    var session: Session? = null
        private set

    /**
     * 失敗時に呼び出されるコールバック関数
     *
     * @see [Session constructor](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#Session(android.content.Context))
     */
    var exceptionCallback: ((Exception) -> Unit)? = null

    /**
     * セッションの構成が必要になったら呼び出される。ARCoreの機能など
     *
     * [Session.configure](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#configure-config)
     * [setCameraConfig](https://developers.google.com/ar/reference/java/com/google/ar/core/Session#setCameraConfig-cameraConfig)
     */
    var beforeSessionResume: ((Session) -> Unit)? = null

    /**
     * セッションの作成を試みる。
     * AR の Google Play Service がインストールされていない場合はインストールをリクエスト。
     */
    private fun tryCreateSession(): Session? {
        // 権限がなければreturn
        if (!CameraPermissionHelper.hasCameraPermission(activity)) {
            CameraPermissionHelper.requestCameraPermission(activity)
            return null
        }

        return try {
            // Request installation if necessary.
            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    // tryCreateSession will be called again, so we return null for now.
                    return null
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    // Left empty; nothing needs to be done.
                }
            }

            // Create a session if Google Play Services for AR is installed and up to date.
            Session(activity, features)
        } catch (e: Exception) {
            exceptionCallback?.invoke(e)
            null
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        val session = this.session ?: tryCreateSession() ?: return
        try {
            beforeSessionResume?.invoke(session)
            session.resume()
            this.session = session
        } catch (e: CameraNotAvailableException) {
            exceptionCallback?.invoke(e)
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        session?.pause()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // ARCoreのセッションを破棄する
        // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
        session?.close()
        session = null
    }
}
