package com.example.autocam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.autocam.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // 촬영 순서: 전면1→후면1 → 전면2→후면2 → 전면3→후면3
    private var isShooting = false
    private var roundCount = 0
    private var totalShot = 0
    private val TOTAL_ROUNDS = 3
    private var shootTimer: CountDownTimer? = null

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO  // VideoCapture 필수
        ).also {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                it.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val INTERVAL_MS = 1000L
    }

    enum class Side(val label: String, val selectorId: Int) {
        FRONT("전면 카메라", CameraSelector.LENS_FACING_FRONT),
        BACK("후면 카메라", CameraSelector.LENS_FACING_BACK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            beginShootSequence()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    // ─── 카메라 + 동영상 동시 바인딩 ────────────────────────────
    private fun startCamera(side: Side, onReady: (() -> Unit)? = null) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // VideoCapture 바인딩 (녹화 중 촬영 → 셔터음 없음)
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.LOWEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(side.selectorId)
                .build()

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture
                )

                binding.tvCamBadge.text = side.label
                binding.tvCamBadge.visibility = View.VISIBLE

                // 더미 녹화 시작 (셔터음 억제 목적)
                startDummyRecording()

                onReady?.invoke()
            } catch (e: Exception) {
                showToast("카메라 오류: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ─── 더미 녹화 시작 (셔터음 억제) ───────────────────────────
    private fun startDummyRecording() {
        activeRecording?.stop()

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "dummy_${System.currentTimeMillis()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AutoCam_tmp")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        activeRecording = videoCapture?.output
            ?.prepareRecording(this, mediaStoreOutput)
            ?.apply {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            ?.start(ContextCompat.getMainExecutor(this)) { /* 이벤트 무시 */ }
    }

    // ─── 촬영 시퀀스 시작 ────────────────────────────────────────
    private fun beginShootSequence() {
        isShooting = true
        roundCount = 0
        totalShot = 0

        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.max = TOTAL_ROUNDS * 2
        binding.progressBar.progress = 0

        shootRound()
    }

    // ─── 라운드: 전면 → 후면 ─────────────────────────────────────
    private fun shootRound() {
        roundCount++
        if (roundCount > TOTAL_ROUNDS) {
            finishShooting()
            return
        }

        updateStatus("📷 $roundCount / $TOTAL_ROUNDS 회차 — 전면 촬영")

        startCamera(Side.FRONT) {
            binding.previewView.postDelayed({
                capturePhoto("front_${roundCount}_${timestamp()}") {
                    totalShot++
                    binding.progressBar.progress = totalShot
                    showFlash()

                    updateStatus("📷 $roundCount / $TOTAL_ROUNDS 회차 — 후면 촬영")
                    startCamera(Side.BACK) {
                        binding.previewView.postDelayed({
                            capturePhoto("back_${roundCount}_${timestamp()}") {
                                totalShot++
                                binding.progressBar.progress = totalShot
                                showFlash()

                                shootTimer = object : CountDownTimer(INTERVAL_MS, INTERVAL_MS) {
                                    override fun onTick(ms: Long) {}
                                    override fun onFinish() { shootRound() }
                                }.start()
                            }
                        }, 700)
                    }
                }
            }, 500)
        }
    }

    // ─── 사진 촬영 (녹화 중 → 셔터음 없음) ──────────────────────
    private fun capturePhoto(filename: String, onDone: () -> Unit) {
        val ic = imageCapture ?: run { showToast("카메라 미초기화"); return }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$filename.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AutoCam")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // MediaActionSound 호출 없음 → 셔터음 없음
        ic.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { onDone() }
                override fun onError(exc: ImageCaptureException) {
                    showToast("촬영 실패: ${exc.message}")
                    onDone()
                }
            }
        )
    }

    // ─── 촬영 완료 ───────────────────────────────────────────────
    private fun finishShooting() {
        isShooting = false
        shootTimer?.cancel()
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()

        // 더미 영상 파일 삭제
        deleteDummyVideos()

        binding.previewView.postDelayed({ finish() }, 1500)
    }

    // ─── 더미 영상 삭제 ──────────────────────────────────────────
    private fun deleteDummyVideos() {
        try {
            contentResolver.delete(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("Movies/AutoCam_tmp%")
            )
        } catch (e: Exception) {
            // 삭제 실패 무시
        }
    }

    // ─── 플래시 이펙트 ───────────────────────────────────────────
    private fun showFlash() {
        binding.flashOverlay.visibility = View.VISIBLE
        val anim = AlphaAnimation(0.8f, 0f).apply {
            duration = 200
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(a: Animation?) {}
                override fun onAnimationRepeat(a: Animation?) {}
                override fun onAnimationEnd(a: Animation?) {
                    binding.flashOverlay.visibility = View.INVISIBLE
                }
            })
        }
        binding.flashOverlay.startAnimation(anim)
    }

    // ─── 유틸 ─────────────────────────────────────────────────────
    private fun updateStatus(msg: String) { binding.tvStatus.text = msg }
    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) beginShootSequence()
            else { showToast("카메라 권한이 필요합니다."); finish() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        shootTimer?.cancel()
        activeRecording?.stop()
        cameraExecutor.shutdown()
    }
}
