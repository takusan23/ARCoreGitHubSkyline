package io.github.takusan23.arcoregithubskyline

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import io.github.takusan23.arcoregithubskyline.common.helpers.DisplayRotationHelper
import io.github.takusan23.arcoregithubskyline.common.helpers.TapHelper
import io.github.takusan23.arcoregithubskyline.common.samplerender.Framebuffer
import io.github.takusan23.arcoregithubskyline.common.samplerender.SampleRender
import io.github.takusan23.arcoregithubskyline.common.samplerender.arcore.BackgroundRenderer
import java.io.IOException

/** OpenGLを利用して描画するクラス */
class ARCoreOpenGlRenderer(
    private val context: Context,
    private val arCoreSessionLifecycleHelper: ARCoreSessionLifecycleHelper,
    private val tapHelper: TapHelper,
) : SampleRender.Renderer, DefaultLifecycleObserver {

    /** カメラ映像をレンダリングするやつ */
    private lateinit var backgroundRenderer: BackgroundRenderer
    private lateinit var virtualSceneFramebuffer: Framebuffer
    private val displayRotationHelper = DisplayRotationHelper(context)

    /** カメラ映像のテクスチャを渡したか。一度だけ行うため */
    private var isAlreadySetTexture = false

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        displayRotationHelper.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        displayRotationHelper.onPause()
    }

    /** SurfaceViewが利用可能になったら呼ばれる */
    override fun onSurfaceCreated(render: SampleRender) {
        backgroundRenderer = BackgroundRenderer(render)
        virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)
    }

    /** SurfaceViewのサイズ変更時に */
    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        virtualSceneFramebuffer.resize(width, height)
    }

    /** 毎フレーム呼ばれる？ */
    override fun onDrawFrame(render: SampleRender) {
        val session = arCoreSessionLifecycleHelper.session ?: return

        // カメラ映像テクスチャ
        if (!isAlreadySetTexture) {
            session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
            isAlreadySetTexture = true
        }

        // カメラ映像のサイズを合わせる
        displayRotationHelper.updateSessionIfNeeded(session)

        // ARSession から現在のフレームを取得
        val frame = try {
            session.update()
        } catch (e: Exception) {
            Log.e(TAG, "Camera not available during onDrawFrame", e)
            return
        }

        val camera = frame.camera
        // 深度設定
        try {
            backgroundRenderer.setUseDepthVisualization(render, false)
            backgroundRenderer.setUseOcclusion(render, true)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            return
        }

        // 座標を更新する
        backgroundRenderer.updateDisplayGeometry(frame)
        // 深度設定
        val shouldGetDepthImage = true
        if (camera.trackingState == TrackingState.TRACKING && shouldGetDepthImage) {
            try {
                val depthImage = frame.acquireDepthImage16Bits()
                backgroundRenderer.updateCameraDepthTexture(depthImage)
                depthImage.close()
            } catch (e: NotYetAvailableException) {
                // まだ深度データが利用できない
                // 別にエラーではなく正常
            }
        }

        // カメラ映像を描画する
        if (frame.timestamp != 0L) {
            // カメラがまだ最初のフレームを生成していない場合、レンダリングを抑制します。 これは避けるためです
            // テクスチャが再利用される場合、以前のセッションから残っている可能性のあるデータを描画します。
            backgroundRenderer.drawBackground(render)
        }
        // 追跡しない場合は、3D オブジェクトを描画しない
        if (camera.trackingState == TrackingState.PAUSED) {
            return
        }

        // 背景を使用して仮想シーンを構成します。
        backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
    }

    companion object {
        private val TAG = ARCoreOpenGlRenderer::class.java.simpleName

        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f
    }
}