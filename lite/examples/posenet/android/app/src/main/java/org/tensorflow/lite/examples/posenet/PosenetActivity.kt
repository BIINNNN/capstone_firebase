/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.posenet


import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.net.Uri
import android.os.*
import android.text.TextPaint
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.app.ActivityCompat
import androidx.core.view.isInvisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import org.tensorflow.lite.examples.posenet.Extensions.toast
import org.tensorflow.lite.examples.posenet.lib.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs


class PosenetActivity :
    Fragment(),
    ActivityCompat.OnRequestPermissionsResultCallback {

    /** List of body joints that should be connected.    */
    private val bodyJoints = listOf(
        Pair(BodyPart.LEFT_WRIST, BodyPart.LEFT_ELBOW),
        Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
        Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
        Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
        Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
        Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
        Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)
    )

    /** Threshold for confidence score. */
    private val minConfidence = 0.5

    /** Radius of circle used to draw keypoints.  */
    private val circleRadius = 8.0f

    /** Paint class holds the style and color information to draw geometries,text and bitmaps. */
    private var paint = Paint()

    /** A shape for extracting frame data.   */
    private val PREVIEW_WIDTH = 640
    private val PREVIEW_HEIGHT = 480


    /** An object for the Posenet library.    */
    private lateinit var posenet: Posenet

    /** ID of the current [CameraDevice].   */
    private var cameraId: String? = null

    /** A [SurfaceView] for camera preview.   */
    private var surfaceView: SurfaceView? = null

    /** A [CameraCaptureSession] for camera preview.   */
    private var captureSession: CameraCaptureSession? = null

    /** A reference to the opened [CameraDevice].    */
    private var cameraDevice: CameraDevice? = null

    /** The [android.util.Size] of camera preview.  */
    private var previewSize: Size? = null

    /** The [android.util.Size.getWidth] of camera preview. */
    private var previewWidth = 0

    /** The [android.util.Size.getHeight] of camera preview.  */
    private var previewHeight = 0

    /** A counter to keep count of total frames.  */
    private var frameCounter = 0

    /** An IntArray to save image data in ARGB8888 format  */
    private lateinit var rgbBytes: IntArray

    /** A ByteArray to save image data in YUV format  */
    private var yuvBytes = arrayOfNulls<ByteArray>(3)

    /** An additional thread for running tasks that shouldn't block the UI.   */
    private var backgroundThread: HandlerThread? = null

    /** A [Handler] for running tasks in the background.    */
    private var backgroundHandler: Handler? = null

    /** A [Handler] for running tasks in the background.    */
    private var ImageHandler: Handler? = null

    /** An [ImageReader] that handles preview frame capture.   */
    private var imageReader: ImageReader? = null

    /** [CaptureRequest.Builder] for the camera preview   */
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    /** [CaptureRequest] generated by [.previewRequestBuilder   */
    private var previewRequest: CaptureRequest? = null

    /** A [Semaphore] to prevent the app from exiting before closing the camera.    */
    private val cameraOpenCloseLock = Semaphore(1)

    /** Whether the current camera device supports Flash or not.    */
    private var flashSupported = false

    /** Orientation of the camera sensor.   */
    private var sensorOrientation: Int? = null

    /** Abstract interface to someone holding a display surface.    */
    private var surfaceHolder: SurfaceHolder? = null

    private var videoView: VideoView? = null

    private var mediaPlayer: MediaPlayer? = null

    private var imageView: ImageView? = null

    // [210125]
    private var jointView: SurfaceView? = null
    private var jointHolder: SurfaceHolder? = null

    // [210128]
    private var scoreView: SurfaceView? = null
    private var scoreHolder: SurfaceHolder? = null




    /** [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.   */
    private val stateCallback = object : CameraDevice.StateCallback() {
        // openCamera() 메서드에서 CameraManager.openCamera() 를 실행할때 인자로 넘겨주어야하는 콜백메서드
        // 카메라가 제대로 열렸으면, cameraDevice 에 값을 할당해주고, 카메라 미리보기를 생성한다
        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release() //세마포어설정 스레드수 1개로 제한
            this@PosenetActivity.cameraDevice = cameraDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@PosenetActivity.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            this@PosenetActivity.activity?.finish()
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
        }
    }

    /**
     * Shows a [Toast] on the UI thread.
     *
     * @param text The message to show
     */
    private fun showToast(text: String) {
        val activity = activity
        activity?.runOnUiThread {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
    }


    override fun onCreateView( //fragment에서 뷰를 생성하고  초기화 하는 코드
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.tfe_pn_activity_posenet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { //oncreateview에서 return해준 view
        surfaceView = view.findViewById(R.id.surfaceView)
        surfaceHolder = surfaceView!!.holder


        //재생횟수설정

        var count =0 //운동횟수 카운트
        //var number = 25 //나중에 EditText만들어서 운동전 운동횟수 입력받기
        videoView = view.findViewById(R.id.videoView)
        videoView!!.setOnCompletionListener {
            count++
            showToast("$count 회재생") //재생횟수 확인용 메세지
            if(count == number) {
                startActivity(Intent(context, MainloginActivity::class.java))
            }
        }




        Log.d("if ClickState ", ClickState)
        if (ClickState == "sidejack 학습") {
            videoView?.isInvisible
            Log.d("if ClickState ", ClickState)
        } else if (ClickState == "sidejack 운동") {
            // 운동에 따라 종류 변경
            Log.v("test", "레터럴 영상 실행")
            Log.v("test ", ClickState)
            var videoUri =
                Uri.parse("android.resource://" + context!!.packageName + "/" + R.raw.lateral)
            videoView!!.setVideoURI(videoUri) //영상을 띄우는 느낌
            //영상을 띄우고 부터 사용자가 정해준 시간뒤에 화면이 넘어가는 부분
            //LowerbodyActivity를 가려는 activity이름으로 바꿔주면 됨

        } else if(ClickState == "widesquat"){
            // 운동에 따라 종류 변경
            var videoUri =
                Uri.parse("android.resource://" + context!!.packageName + "/" + R.raw.wide_squat)
            videoView!!.setVideoURI(videoUri)
        }

        else if (ClickState == "sidebend left 학습") {
            videoView?.isInvisible
            Log.v("test","sidebend 학습")
        }
        else if (ClickState == "sidebend left 운동") {
            Log.v("test", "사이드밴드 영상 실행")
            Log.v("test ", ClickState)
            // 운동에 따라 종류 변경
            var videoUri =
                Uri.parse("android.resource://" + context!!.packageName + "/" + R.raw.sidebend)
            videoView!!.setVideoURI(videoUri) //영상을 띄우는 느낌

        }
       /* else if (ClickState == "sidebend right 학습") {
            videoView?.isInvisible
        }*/

        // [210125]
        jointView = view.findViewById(R.id.jointView) //초기화할때 id가 조인트 뷰인것을 찾아서 넣는다 스켈레톤 출력
        jointHolder = jointView!!.holder //holder 표면 픽셀편집
        jointHolder!!.setFormat(PixelFormat.TRANSPARENT) //배경을 불투명하게만든다

        // [210128]
        scoreView = view.findViewById(R.id.scoreView) //점수출력
        scoreHolder = scoreView!!.holder
        scoreHolder!!.setFormat(PixelFormat.TRANSPARENT)


    }





    override fun onResume() {
        super.onResume()
        startBackgroundThread()
    }

    override fun onStart() {
        super.onStart()
        openCamera()
        posenet = Posenet(this.context!!)

    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        posenet.close()
    }

    private fun requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (allPermissionsGranted(grantResults)) {
                ErrorDialog.newInstance(getString(R.string.tfe_pn_request_permission))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun allPermissionsGranted(grantResults: IntArray) = grantResults.all {
        it == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Sets up member variables related to camera.
     */
    private fun setUpCameraOutputs() {
        val activity = activity

        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera in this sample.
                val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraDirection != null &&
                    cameraDirection == CameraCharacteristics.LENS_FACING_BACK

                ) {
                    continue
                }

                previewSize = Size(PREVIEW_WIDTH, PREVIEW_HEIGHT)

                imageReader = ImageReader.newInstance(
                    PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    ImageFormat.YUV_420_888, /*maxImages*/ 2
                )

                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

                previewHeight = previewSize!!.height
                previewWidth = previewSize!!.width

                // Initialize the storage bitmaps once when the resolution is known.
                rgbBytes = IntArray(previewWidth * previewHeight)

                // Check if the flash is supported.
                flashSupported =
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                this.cameraId = cameraId

                // We've found a viable camera and finished setting up member variables,
                // so we don't need to iterate through other available cameras.
                return
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.tfe_pn_camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
        }
    }

    /**
     * Opens the camera specified by [PosenetActivity.cameraId].
     */
    private fun openCamera() {
        val permissionCamera = getContext()!!.checkPermission(
            Manifest.permission.CAMERA, Process.myPid(), Process.myUid()
        )
        if (permissionCamera != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
        }
        setUpCameraOutputs()
        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // Wait for camera to open - 2.5 seconds is sufficient
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
            //manager.openCamera("0", stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /**
     * Closes the current [CameraDevice].
     */
    private fun closeCamera() {
        if (captureSession == null) {
            return
        }

        try {
            cameraOpenCloseLock.acquire()
            captureSession!!.close()
            captureSession = null
            cameraDevice!!.close()
            cameraDevice = null
            imageReader!!.close()
            imageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("imageAvailableListener").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    /** Fill the yuvBytes with data from image planes.   */
    private fun fillBytes(planes: Array<Image.Plane>, yuvBytes: Array<ByteArray?>) {
        // Row stride is the total number of bytes occupied in memory by a row of an image.
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer.get(yuvBytes[i]!!)
        }
    }



    /** A [OnImageAvailableListener] to receive frames as they are available.  */
    private var imageAvailableListener = object : OnImageAvailableListener {

        override fun onImageAvailable(imageReader: ImageReader) {
            var stratTimeOverall = SystemClock.elapsedRealtimeNanos()

            // We need wait until we have some size from onPreviewSizeChosen
            if (previewWidth == 0 || previewHeight == 0) {
                return
            }

            val image = imageReader.acquireLatestImage() ?: return
            fillBytes(image.planes, yuvBytes)

            ImageUtils.convertYUV420ToARGB8888(
                yuvBytes[0]!!,
                yuvBytes[1]!!,
                yuvBytes[2]!!,
                previewWidth,
                previewHeight,
                /*yRowStride=*/ image.planes[0].rowStride,
                /*uvRowStride=*/ image.planes[1].rowStride,
                /*uvPixelStride=*/ image.planes[1].pixelStride,
                rgbBytes
            )

            // Create bitmap from int array
            val imageBitmap = Bitmap.createBitmap(
                rgbBytes, previewWidth, previewHeight,
                Bitmap.Config.ARGB_8888
            )

            // Create rotated version for portrait display
            val rotateMatrix = Matrix()
            rotateMatrix.postScale(-1f, 1f)
            rotateMatrix.postRotate(90.0f)

            val rotatedBitmap = Bitmap.createBitmap(
                imageBitmap, 0, 0, previewWidth, previewHeight,
                rotateMatrix, true
            )

            image.close()


            // [210122]


            videoView!!.start()







            processImage(rotatedBitmap)
            var endTimeOverall = SystemClock.elapsedRealtimeNanos() - stratTimeOverall
            Log.i(
                "Time(Overall)",
                String.format("%.2f ms", 1.0f * endTimeOverall / 1_000_000)
            )


            // [210128]
            kindAction = ClickState;
        }
    }




    /** Crop Bitmap to maintain aspect ratio of model input.   */
    private fun cropBitmap(bitmap: Bitmap): Bitmap {
        val bitmapRatio = bitmap.height.toFloat() / bitmap.width
        val modelInputRatio = MODEL_HEIGHT.toFloat() / MODEL_WIDTH
        var croppedBitmap = bitmap

        // Acceptable difference between the modelInputRatio and bitmapRatio to skip cropping.
        val maxDifference = 1e-5

        // Checks if the bitmap has similar aspect ratio as the required model input.
        when {
            abs(modelInputRatio - bitmapRatio) < maxDifference -> return croppedBitmap
            modelInputRatio < bitmapRatio -> {
                // New image is taller so we are height constrained.
                val cropHeight = bitmap.height - (bitmap.width.toFloat() / modelInputRatio)
                croppedBitmap = Bitmap.createBitmap(
                    bitmap,
                    0,
                    (cropHeight / 2).toInt(),
                    bitmap.width,
                    (bitmap.height - cropHeight).toInt()
                )
            }
            else -> {
                val cropWidth = bitmap.width - (bitmap.height.toFloat() * modelInputRatio)
                croppedBitmap = Bitmap.createBitmap(
                    bitmap,
                    (cropWidth / 2).toInt(),
                    0,
                    (bitmap.width - cropWidth).toInt(),
                    bitmap.height
                )
            }
        }
        return croppedBitmap
    }

    /** Set the paint color and size.    */
    private fun setPaint() {
        paint.color = Color.WHITE
        paint.textSize = 60.0f
        paint.strokeWidth = 10.0f
    }

    private fun drawTeacher(canvas: Canvas, person: Person, bitmap: Bitmap) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        // Draw `bitmap` and `person` in square canvas.
        val screenWidth: Int
        val screenHeight: Int
        val left: Int
        val right: Int
        val top: Int
        val bottom: Int

        screenWidth = canvas.width
        screenHeight = canvas.height

        left = 0
        top = 0

        right = left + screenWidth
        bottom = top + screenHeight

        setPaint()
        canvas.drawBitmap(
            bitmap,
            Rect(0, 0, bitmap.width, bitmap.height),
            Rect(left, top, right, bottom),
            paint
        )

        // Draw
        surfaceHolder!!.unlockCanvasAndPost(canvas)
    }

    private fun drawStudent(canvas: Canvas, person: Person, bitmap: Bitmap) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val screenWidth: Int
        val screenHeight: Int
        val left: Int
        val top: Int
        if (canvas.height > canvas.width) {
            screenWidth = canvas.width
            screenHeight = canvas.width
            left = 0
            top = (canvas.height - canvas.width) / 2
        } else {
            screenWidth = canvas.height
            screenHeight = canvas.height
            left = (canvas.width - canvas.height) / 2
            top = 0
        }
        val widthRatio = screenWidth.toFloat() / MODEL_WIDTH
        val heightRatio = screenHeight.toFloat() / MODEL_HEIGHT

        setPaint()


        // Draw key points over the image.
        for (keyPoint in person.keyPoints) {
            if (keyPoint.score > minConfidence) {
                val position = keyPoint.position

                val adjustedX: Float = position.x.toFloat() * widthRatio + left
                val adjustedY: Float = position.y.toFloat() * heightRatio + top
                canvas.drawCircle(adjustedX, adjustedY, circleRadius, paint)
            }
        }

        for (line in bodyJoints) {
            if (
                (person.keyPoints[line.first.ordinal].score > minConfidence) and
                (person.keyPoints[line.second.ordinal].score > minConfidence)
            ) {
                canvas.drawLine(
                    person.keyPoints[line.first.ordinal].position.x.toFloat() * widthRatio + left,
                    person.keyPoints[line.first.ordinal].position.y.toFloat() * heightRatio + top,
                    person.keyPoints[line.second.ordinal].position.x.toFloat() * widthRatio + left,
                    person.keyPoints[line.second.ordinal].position.y.toFloat() * heightRatio + top,
                    paint
                )
            }
        }

        // [210125]
        jointHolder!!.unlockCanvasAndPost(canvas)
    }

    // [210128]
    private fun drawScore(canvas: Canvas, person: Person, bitmap: Bitmap) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        if("$ActionFeedback" =="Bad")
        {
            paint.color= Color.RED
            canvas.drawCircle(canvas.width / 2f,canvas.height / 2f,canvas.width / 4f,paint)
        }
        else if("$ActionFeedback"=="Normal")
        {
            paint.color= Color.YELLOW
            canvas.drawCircle(canvas.width / 2f,canvas.height / 2f,canvas.width / 4f,paint)
        }
        else if("$ActionFeedback"=="Good")
        {
            paint.color= Color.GREEN
            canvas.drawCircle(canvas.width / 2f,canvas.height / 2f,canvas.width / 4f,paint)
        }

        val textPaint = TextPaint().apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            this.textSize = 29f //30부터 noraml 폰트 깨짐
        }

        val textHeight = textPaint.descent() - textPaint.ascent()
        val textOffset = (textHeight / 2) - textPaint.descent()
        val margin = 100F

        var text = "$ActionFeedback"
        val numOfChars: Int = textPaint.breakText(
            text,
            true,
            canvas.width / 4f * 2 - margin,
            null
        )
        if (text.length > numOfChars){
            text = text.substring(0, numOfChars) + ".."
        }
        canvas.drawText(
            text,
            canvas.width / 2f,canvas.height / 2f + textOffset,
            textPaint
        )
        scoreHolder!!.unlockCanvasAndPost(canvas)
    }

    /** Process image using Posenet library.   */
    private fun processImage(bitmap: Bitmap) {
        // Crop bitmap.
        val croppedBitmap = cropBitmap(bitmap)

        // Created scaled version of bitmap for model input.
        val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, MODEL_WIDTH, MODEL_HEIGHT, true)

        // Perform inference.
        // [210122]
        val person = posenet.estimateSinglePose(scaledBitmap)
        val canvas: Canvas = surfaceHolder!!.lockCanvas()
        drawTeacher(canvas, person, scaledBitmap)

        // [210125]
        val jointCanvas: Canvas = jointHolder!!.lockCanvas()
        val clearBG = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        drawStudent(jointCanvas, person, clearBG)

        // [210128]
        val scoreCanvas: Canvas = scoreHolder!!.lockCanvas()
        val scoreBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        drawScore(scoreCanvas, person, scoreBitmap)
    }

    /**
     * Creates a new [CameraCaptureSession] for camera preview.
     */
    private fun createCameraPreviewSession() {
        try {

            // We capture images from preview in YUV format.
            imageReader = ImageReader.newInstance(
                previewSize!!.width, previewSize!!.height, ImageFormat.YUV_420_888, 2
            )
            imageReader!!.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)

            // This is the surface we need to record images for processing.
            val recordingSurface = imageReader!!.surface

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )
            previewRequestBuilder!!.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(10, 10))
            previewRequestBuilder!!.addTarget(recordingSurface)

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice!!.createCaptureSession(
                listOf(recordingSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (cameraDevice == null) return

                        // When the session is ready, we start displaying the preview.
                        captureSession = cameraCaptureSession
                        try {

                            // Auto focus should be continuous for camera preview.
                            previewRequestBuilder!!.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            // Flash is automatically enabled when necessary.
                            setAutoFlash(previewRequestBuilder!!)

                            // Finally, we start displaying the camera preview.
                            previewRequest = previewRequestBuilder!!.build()
                            captureSession!!.setRepeatingRequest(
                                previewRequest!!,
                                captureCallback, backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, e.toString())
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        showToast("Failed")
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {

        // 프레임 속도 변경
        val fpsRange = Range(0, 40)

        if (flashSupported) {
            requestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            )
            requestBuilder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange
            )
        }
    }

    /**
     * Shows an error message dialog.
     */
    class ErrorDialog : DialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            AlertDialog.Builder(activity)
                .setMessage(arguments!!.getString(ARG_MESSAGE))
                .setPositiveButton(android.R.string.ok) { _, _ -> activity!!.finish() }
                .create()

        companion object {

            @JvmStatic
            private val ARG_MESSAGE = "message"

            @JvmStatic
            fun newInstance(message: String): ErrorDialog = ErrorDialog().apply {
                arguments = Bundle().apply { putString(ARG_MESSAGE, message) }
            }
        }
    }

    companion object {
        /**
         * Conversion from screen rotation to JPEG orientation.
         */
        private val ORIENTATIONS = SparseIntArray()
        private val FRAGMENT_DIALOG = "dialog"

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        /**
         * Tag for the [Log].
         */
        private const val TAG = "PosenetActivity"
    }


}