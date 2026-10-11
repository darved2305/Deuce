package com.elevate.app.data.record

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * The CameraX side of Record live: reads what the camera can do, binds preview + video capture
 * with the parameters from [RecordingParamsChooser], and locks exposure and focus once framed.
 * Every setting here is listed with its reason in `ui/record/RECORDING.md`.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraSession(context: Context) {
    private val app = context.applicationContext

    var camera: Camera? = null
        private set
    var videoCapture: VideoCapture<Recorder>? = null
        private set
    private var provider: ProcessCameraProvider? = null

    private suspend fun provider(): ProcessCameraProvider =
        provider ?: ProcessCameraProvider.awaitInstance(app).also { provider = it }

    /** What the back camera and the recorder offer. Throws when the phone has no back camera. */
    suspend fun probe(): DeviceCaptureCaps {
        val p = provider()
        val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).firstOrNull()
            ?: throw IllegalStateException("no back camera")
        val caps = Recorder.getVideoCapabilities(info)
        val heights = caps.getSupportedQualities(DynamicRange.SDR).mapNotNull {
            when (it) {
                Quality.FHD -> 1080
                Quality.HD -> 720
                Quality.SD -> 480
                else -> null // UHD and anything above is never used
            }
        }.toSet()
        val ranges = info.supportedFrameRateRanges.map { FpsRange(it.lower, it.upper) }
        val ois = runCatching {
            Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        }.getOrDefault(false)
        return DeviceCaptureCaps(heights, ranges, ois)
    }

    /**
     * Binds the preview and the recorder. Preview and video share one view port so what the user
     * frames is what is recorded. [displayRotation] is the Surface.ROTATION_* of the landscape display.
     */
    suspend fun bind(owner: LifecycleOwner, view: PreviewView, params: RecordingParams, displayRotation: Int) {
        val p = provider()
        p.unbindAll()
        val range = Range(params.fpsRange.lower, params.fpsRange.upper)
        val preview = Preview.Builder()
            .setTargetFrameRate(range)
            .setTargetRotation(displayRotation)
            .build()
            .also { it.surfaceProvider = view.surfaceProvider }
        val quality = when (params.quality) {
            RecordQuality.FHD -> Quality.FHD
            RecordQuality.HD -> Quality.HD
            RecordQuality.SD -> Quality.SD
        }
        val recorder = Recorder.Builder()
            // The wanted size; if the phone lacks it the next lower one is used, never a higher one.
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityThan(quality)))
            .setAspectRatio(AspectRatio.RATIO_16_9)
            .setTargetVideoEncodingBitRate(params.videoBitrateBps)
            .build()
        val capture = VideoCapture.Builder(recorder)
            .setTargetFrameRate(range)
            // SDR only: the overlays and the effect pipeline do not support HDR.
            .setDynamicRange(DynamicRange.SDR)
            // No electronic stabilisation: it crops and warps, which moves the court the corners were tapped on.
            .setVideoStabilizationEnabled(false)
            .setTargetRotation(displayRotation)
            .build()
        val viewPort: ViewPort = view.viewPort
            ?: ViewPort.Builder(android.util.Rational(16, 9), displayRotation).build()
        val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture).setViewPort(viewPort).build()
        val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
        camera = cam
        videoCapture = capture
        applyStabilisation(cam)
    }

    /** OIS on (it is in the lens, it moves no pixels); EIS off. Both through Camera2, where CameraX has no switch for OIS. */
    private fun applyStabilisation(cam: Camera) {
        val o = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        val ois = runCatching {
            Camera2CameraInfo.from(cam.cameraInfo).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        }.getOrDefault(false)
        if (ois) o.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        Camera2CameraControl.from(cam.cameraControl).addCaptureRequestOptions(o.build())
    }

    /**
     * Focuses and meters on the middle of the picture once, then holds focus, exposure and white
     * balance still for the whole recording: no focus hunting or exposure pumping as players move.
     */
    fun lockExposureAndFocus(view: PreviewView, onLocked: (Boolean) -> Unit) {
        val cam = camera ?: return onLocked(false)
        val point = view.meteringPointFactory.createPoint(view.width / 2f, view.height / 2f)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
        ).disableAutoCancel().build()
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
            val o = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                // AUTO (not continuous): the lens stays where the one focus run left it.
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            runCatching { Camera2CameraControl.from(cam.cameraControl).addCaptureRequestOptions(o.build()) }
            onLocked(ok)
        }, ContextCompat.getMainExecutor(app))
    }

    fun unbind() {
        runCatching { provider?.unbindAll() }
        camera = null
        videoCapture = null
    }
}
