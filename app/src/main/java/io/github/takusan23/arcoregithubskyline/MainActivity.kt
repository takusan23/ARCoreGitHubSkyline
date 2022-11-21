package io.github.takusan23.arcoregithubskyline

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Config
import io.github.takusan23.arcoregithubskyline.common.helpers.CameraPermissionHelper
import io.github.takusan23.arcoregithubskyline.common.helpers.TapHelper
import io.github.takusan23.arcoregithubskyline.common.samplerender.SampleRender

class MainActivity : AppCompatActivity() {

    /** ARCoreのセッション管理 */
    private val arCoreSessionLifecycleHelper by lazy { ARCoreSessionLifecycleHelper(this) }

    /** GLSurfaceView */
    private val arViewLifecycle by lazy { ARViewLifecycle(this) }

    /** タッチイベント */
    private val tapHelper by lazy { TapHelper(this).also { arViewLifecycle.viewBinding.activityMainGlSurfaceview.setOnTouchListener(it) } }

    /** OpenGLでARCore描画するやつ */
    private val renderer by lazy { ARCoreOpenGlRenderer(this, arCoreSessionLifecycleHelper, tapHelper) }

    /** 権限コールバック */
    private val permissionRequester = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGrant ->
        if (isGrant) {
            setup()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(arViewLifecycle.viewBinding.root)

        // ライフサイクル
        lifecycle.addObserver(arCoreSessionLifecycleHelper)
        lifecycle.addObserver(arViewLifecycle)
        lifecycle.addObserver(renderer)

        // 権限がない場合は取得する
        if (CameraPermissionHelper.hasCameraPermission(this)) {
            setup()
        } else {
            permissionRequester.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun setup() {
        arCoreSessionLifecycleHelper.apply {
            // 失敗コールバック
            exceptionCallback = { exception ->
                exception.printStackTrace()
            }
            // 構成
            beforeSessionResume = { session ->
                session.configure(
                    session.config.apply {
                        lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                        // Depth API は使いたい
                        depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            Config.DepthMode.AUTOMATIC
                        } else {
                            Config.DepthMode.DISABLED
                        }
                        // インスタント配置は使わない
                        instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    }
                )
            }
        }

        // 描画する
        SampleRender(arViewLifecycle.viewBinding.activityMainGlSurfaceview, renderer, assets)
    }
}