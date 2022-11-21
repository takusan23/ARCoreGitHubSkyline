package io.github.takusan23.arcoregithubskyline

import android.content.Context
import android.view.LayoutInflater
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.takusan23.arcoregithubskyline.databinding.ActivityMainBinding

/** GLSurfaceViewのライフサイクルするやつ */
class ARViewLifecycle(context: Context) : DefaultLifecycleObserver {

    val viewBinding = ActivityMainBinding.inflate(LayoutInflater.from(context))

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        viewBinding.activityMainGlSurfaceview.onPause()
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        viewBinding.activityMainGlSurfaceview.onResume()
    }
}