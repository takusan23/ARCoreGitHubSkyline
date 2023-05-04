package io.github.takusan23.arcoregithubskyline

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import io.github.takusan23.arcoregithubskyline.common.helpers.DisplayRotationHelper
import io.github.takusan23.arcoregithubskyline.common.helpers.TapHelper
import io.github.takusan23.arcoregithubskyline.common.samplerender.Framebuffer
import io.github.takusan23.arcoregithubskyline.common.samplerender.GLError
import io.github.takusan23.arcoregithubskyline.common.samplerender.Mesh
import io.github.takusan23.arcoregithubskyline.common.samplerender.SampleRender
import io.github.takusan23.arcoregithubskyline.common.samplerender.Shader
import io.github.takusan23.arcoregithubskyline.common.samplerender.Texture
import io.github.takusan23.arcoregithubskyline.common.samplerender.VertexBuffer
import io.github.takusan23.arcoregithubskyline.common.samplerender.arcore.BackgroundRenderer
import io.github.takusan23.arcoregithubskyline.common.samplerender.arcore.PlaneRenderer
import io.github.takusan23.arcoregithubskyline.common.samplerender.arcore.SpecularCubemapFilter
import java.io.IOException
import java.nio.ByteBuffer

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

    /** 平面をレンダリングするやつ */
    private lateinit var planeRenderer: PlaneRenderer

    /** Point Cloud (あの青い点) */
    private lateinit var pointCloudVertexBuffer: VertexBuffer
    private lateinit var pointCloudMesh: Mesh
    private lateinit var pointCloudShader: Shader

    /** 最後のポイントクラウド */
    private var lastPointCloudTimestamp = 0L

    /** GitHubのARモデル */
    private lateinit var virtualObjectMesh: Mesh
    private lateinit var virtualObjectShader: Shader

    /** ARモデルの環境HDR */
    private lateinit var dfgTexture: Texture
    private lateinit var cubemapFilter: SpecularCubemapFilter

    /** AR上においたオブジェクト配列 */
    private val wrappedAnchors = mutableListOf<WrappedAnchor>()

    /** Toast出すだけ */
    private val toastManager = ToastManager(context)

    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)
    private val viewInverseMatrix = FloatArray(16)
    private val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
    private val worldLightDirection = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
    private val viewLightDirection = FloatArray(4)

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
        // カメラ映像
        backgroundRenderer = BackgroundRenderer(render)
        virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

        // 平面
        planeRenderer = PlaneRenderer(render)

        // ポイントクラウド (平面を見つける際に表示される青いやつ)
        pointCloudShader = Shader.createFromAssets(
            render,
            "shaders/point_cloud.vert",
            "shaders/point_cloud.frag",
            /*defines=*/ null
        ).apply {
            setVec4("u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f))
            setFloat("u_PointSize", 5.0f)
        }
        pointCloudVertexBuffer = VertexBuffer(render, /*numberOfEntriesPerVertex=*/ 4, /*entries=*/ null)
        pointCloudMesh = Mesh(render, Mesh.PrimitiveMode.POINTS, /*indexBuffer=*/ null, arrayOf(pointCloudVertexBuffer))

        // HDRの設定
        cubemapFilter = SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)
        dfgTexture = Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE,/*useMipmaps=*/ false)

        // DFT テクスチャの設定
        val dfgResolution = 64
        val dfgChannels = 2
        val halfFloatSize = 2
        val buffer = ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize).apply {
            context.assets.open("models/dfg.raw").use { it.read(this.array()) }
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.textureId)
        GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture")
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            /*level=*/ 0,
            GLES30.GL_RG16F,
            /*width=*/ dfgResolution,
            /*height=*/ dfgResolution,
            /*border=*/ 0,
            GLES30.GL_RG,
            GLES30.GL_HALF_FLOAT,
            buffer
        )
        GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D")

        // 3Dオブジェクトを読み込む
        virtualObjectMesh = Mesh.createFromAsset(render, "models/arcore_github_skyline.obj")
        virtualObjectShader = Shader.createFromAssets(
            render,
            "shaders/environmental_hdr.vert",
            "shaders/environmental_hdr.frag",
            mapOf("NUMBER_OF_MIPMAP_LEVELS" to cubemapFilter.numberOfMipmapLevels.toString())
        ).apply {
            setTexture("u_Cubemap", cubemapFilter.filteredCubemapTexture)
            setTexture("u_DfgTexture", dfgTexture)
            // オブジェクトの色をUniform変数に入れる
            // RGBA 0から1まで
            setVec4("v_ObjColor", floatArrayOf(0.25f, 0.76f, 0.38f, 1.0f))
        }
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

        // タップされたか、毎フレーム見る
        handleTap(frame, camera)

        // ARのステータス
        // 平面が検出されてオブジェクトを配置できるようになったかどうかなど
        when {
            camera.trackingState == TrackingState.PAUSED && camera.trackingFailureReason == TrackingFailureReason.NONE -> "平面を探しています"
            camera.trackingState == TrackingState.PAUSED -> null
            hasTrackingPlane(session) && wrappedAnchors.isEmpty() -> "平面を検出しました。タップして配置します。"
            hasTrackingPlane(session) && wrappedAnchors.isNotEmpty() -> null
            else -> "平面を探しています"
        }?.also {
            toastManager.show(it)
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

        // 射影行列を取得する
        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

        // カメラ行列を取得して描画.
        camera.getViewMatrix(viewMatrix, 0)

        // ポイントクラウドの描画
        frame.acquirePointCloud().use { pointCloud ->
            if (pointCloud.timestamp > lastPointCloudTimestamp) {
                pointCloudVertexBuffer.set(pointCloud.points)
                lastPointCloudTimestamp = pointCloud.timestamp
            }
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            render.draw(pointCloudMesh, pointCloudShader)
        }

        // 平面を描画します
        planeRenderer.drawPlanes(render, session.getAllTrackables(Plane::class.java), camera.displayOrientedPose, projectionMatrix)

        // シェーダのライティングパラメータを更新
        updateLightEstimation(frame.lightEstimate, viewMatrix)

        // ARオブジェクトを描画
        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
        wrappedAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }.forEach { (anchor, trackable) ->
            // 描画のための行列を用意する
            // TODO extractTranslation を呼ぶと、回転（axis）を除いた行列を返してくれる。呼ばずに Post#toMatrix すると、回転も考慮される
            // anchor.pose.toMatrix(modelMatrix, 0)
            anchor.pose.extractTranslation().toMatrix(modelMatrix, 0)
            // モデル、ビュー、投影行列 を計算
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
            // シェーダーのUniform変数にセットする
            virtualObjectShader.setMat4("u_ModelView", modelViewMatrix)
            virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            // 描画
            render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
        }

        // 背景を使用して仮想シーンを構成します。
        backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
    }

    /** 1フレームごとにタップを処理する */
    private fun handleTap(frame: Frame, camera: Camera) {
        if (camera.trackingState != TrackingState.TRACKING) return
        val tap = tapHelper.poll() ?: return

        // ヒットは深さによってソートされます。平面上の最も近いヒットのみ
        val hitResultList = frame.hitTest(tap)
        val firstHitResult = hitResultList.firstOrNull { hit ->
            when (val trackable = hit.trackable!!) {
                is Plane -> trackable.isPoseInPolygon(hit.hitPose) && PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0
                is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                is InstantPlacementPoint -> true
                // DepthPoints are only returned if Config.DepthMode is set to AUTOMATIC.
                is DepthPoint -> true
                else -> false
            }
        }

        if (firstHitResult != null) {
            // アンカー数に制限をかける
            if (wrappedAnchors.size >= 20) {
                wrappedAnchors[0].anchor.detach()
                wrappedAnchors.removeAt(0)
            }
            // 追跡登録
            wrappedAnchors.add(WrappedAnchor(firstHitResult.createAnchor(), firstHitResult.trackable))
        }
    }

    /** 光を処理する */
    private fun updateLightEstimation(lightEstimate: LightEstimate, viewMatrix: FloatArray) {
        if (lightEstimate.state != LightEstimate.State.VALID) {
            virtualObjectShader.setBool("u_LightEstimateIsValid", false)
            return
        }
        virtualObjectShader.setBool("u_LightEstimateIsValid", true)
        Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0)
        virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix)
        updateMainLight(
            lightEstimate.environmentalHdrMainLightDirection,
            lightEstimate.environmentalHdrMainLightIntensity,
            viewMatrix
        )
        cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap())
    }

    private fun updateMainLight(
        direction: FloatArray,
        intensity: FloatArray,
        viewMatrix: FloatArray,
    ) {
        // ビュー空間に変換するための最終コンポーネントとして 0.0 を持つ vec4 の方向が必要です。
        worldLightDirection[0] = direction[0]
        worldLightDirection[1] = direction[1]
        worldLightDirection[2] = direction[2]
        Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0)
        virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection)
        virtualObjectShader.setVec3("u_LightIntensity", intensity)
    }

    /** 平面が1つ以上見つかっていれば true */
    private fun hasTrackingPlane(session: Session) = session.getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }

    /** アンカーとトラッカブルを紐つける */
    private data class WrappedAnchor(
        val anchor: Anchor,
        val trackable: Trackable,
    )

    companion object {
        private val TAG = ARCoreOpenGlRenderer::class.java.simpleName

        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f

        private const val CUBEMAP_RESOLUTION = 16
        private const val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32
    }
}