package com.sagiii.twsevaluate.video

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

private const val TAG = "SessionVideoRecorder"

/**
 * 評価セッション中のアウトカメラ映像(+端末内蔵マイク音声)を録画する。
 * バックグラウンドに回るとCameraXのライフサイクル連動でカメラが解放され
 * 録画が終了するため、前面復帰時に[startNewSegment]で新しいファイルとして
 * 録画を再開するセグメント方式にしている(1本の動画に黒フレームで
 * 埋め続ける高度な合成は行わない簡易版)。
 */
class SessionVideoRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var activeRecording: Recording? = null
    private var onStoppedCallback: (() -> Unit)? = null
    private var pendingSurfaceProvider: Preview.SurfaceProvider? = null

    /** 録画中とわかるよう画面に出すライブプレビューの描画先を設定する。 */
    fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        pendingSurfaceProvider = surfaceProvider
        preview?.surfaceProvider = surfaceProvider
    }

    @SuppressLint("MissingPermission")
    fun startNewSegment(outputFile: File, onFinalized: (path: String?, success: Boolean) -> Unit) {
        val existingProvider = cameraProvider
        if (existingProvider != null && videoCapture != null) {
            beginRecording(outputFile, onFinalized)
            return
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)),
                    )
                    .setTargetVideoEncodingBitRate(TARGET_VIDEO_BITRATE_BPS)
                    .build()
                // アプリはportrait固定なので、動画にもportraitの回転情報を明示的に付与する
                // (Previewを持たないためCameraXが向きを自動推定できず、既定だと横向きになる)
                val capture = VideoCapture.Builder(recorder)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                val previewUseCase = Preview.Builder()
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                    .apply { surfaceProvider = pendingSurfaceProvider }
                videoCapture = capture
                preview = previewUseCase
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, capture, previewUseCase)
                beginRecording(outputFile, onFinalized)
            } catch (exc: Exception) {
                Log.e(TAG, "カメラのバインドに失敗しました", exc)
                onFinalized(null, false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("MissingPermission")
    private fun beginRecording(outputFile: File, onFinalized: (path: String?, success: Boolean) -> Unit) {
        val capture = videoCapture ?: return
        val options = FileOutputOptions.Builder(outputFile).build()
        activeRecording = capture.output.prepareRecording(context, options)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    activeRecording = null
                    onFinalized(outputFile.absolutePath, !event.hasError())
                    onStoppedCallback?.invoke()
                    onStoppedCallback = null
                }
            }
    }

    /** バックグラウンド等でカメラが失われた際に呼ぶ。次のstartNewSegmentで再バインドする。 */
    fun releaseCamera() {
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        videoCapture = null
        preview = null
    }

    /**
     * 録画を最終停止する。[onStopped]は、録画中だったセグメントの
     * Finalizeイベント(=[startNewSegment]のコールバックでファイルパスが
     * 確定した後)を待ってから呼ばれる。呼び出し元はこれを待ってから
     * セッションを保存すること。
     */
    fun stopFinal(onStopped: () -> Unit) {
        if (activeRecording == null) {
            cameraProvider?.unbindAll()
            cameraProvider = null
            videoCapture = null
            preview = null
            onStopped()
            return
        }
        onStoppedCallback = onStopped
        activeRecording?.stop()
        cameraProvider?.unbindAll()
        cameraProvider = null
        videoCapture = null
        preview = null
    }

    companion object {
        // テスト風景の記録用途であり画質の厳密さは不要なため、SD解像度+低めのビット
        // レートでファイルサイズを抑える(HD/自動ビットレートだと95秒で140MB超になった)。
        private const val TARGET_VIDEO_BITRATE_BPS = 2_500_000
    }
}
