package io.github.takusan23.arcoregithubskyline

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.takusan23.arcoregithubskyline.common.helpers.TapHelper
import io.github.takusan23.arcoregithubskyline.common.samplerender.SampleRender

/** OpenGLを利用して描画するクラス */
class ARCoreOpenGlRenderer(
    private val context: Context,
    private val arCoreSessionLifecycleHelper: ARCoreSessionLifecycleHelper,
    private val tapHelper: TapHelper,
) : SampleRender.Renderer, DefaultLifecycleObserver {

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
    }

    override fun onSurfaceCreated(render: SampleRender?) {

    }

    override fun onSurfaceChanged(render: SampleRender?, width: Int, height: Int) {

    }

    override fun onDrawFrame(render: SampleRender?) {

    }
}