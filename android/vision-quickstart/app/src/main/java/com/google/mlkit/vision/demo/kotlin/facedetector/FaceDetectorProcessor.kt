package com.google.mlkit.vision.demo.kotlin.facedetector

import android.content.Context
import android.util.Log
import android.widget.Button
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.demo.GraphicOverlay
import com.google.mlkit.vision.demo.kotlin.VisionProcessorBase
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

interface ApiService {
  @GET("control")
  suspend fun controlMotors(
    @Query("yaw_dir") yawDir: String,
    @Query("yaw_steps") yawSteps: Int,
    @Query("pitch_dir") pitchDir: String,
    @Query("pitch_steps") pitchSteps: Int
  ): ResponseBody
}

object RetrofitClient {
  private val retrofit = Retrofit.Builder()
    .baseUrl("http://192.168.4.80/")  // Replace with the IP address of your ESP32
    .addConverterFactory(GsonConverterFactory.create())
    .build()

  val apiService: ApiService = retrofit.create(ApiService::class.java)
}

/** Face Detector Demo.  */
class FaceDetectorProcessor(context: Context, detectorOptions: FaceDetectorOptions?, private val moveButton: Button, serialPort: UsbSerialPort?) :
  VisionProcessorBase<List<Face>>(context) {

  private val detector: FaceDetector

  // this is for portrait orientation
//  (71.53216490394719, 56.75959864409148)
//
  private var diagonal_fov = 84
  private var horizontal_fov = 56.75959864409148
  private var vertical_fov = 71.53216490394719

  private var stepsToMoveX: Int = 0
  private var stepsToMoveY: Int = 0
  private var frameCounter: Int = 0

  private var normalizedErrorX: Float = 0f
  private var normalizedErrorY: Float = 0f
  private var previousErrorX: Float = 0f
  private var previousErrorY: Float = 0f
  private val Kp: Float = 1f // Proportional gain
  private val Kd: Float = 0f // Derivative gain

  private var steps_per_revolution = 200
  val serialPort = serialPort

  init {
    val options = detectorOptions
      ?: FaceDetectorOptions.Builder()
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .enableTracking()
        .build()

    detector = FaceDetection.getClient(options)

    Log.v(MANUAL_TESTING_LOG, "Face detector options: $options")

    // Set up the button listener
    moveButton.setOnClickListener {
      Log.e(TAG, "move Button clicked")
      moveMotors()
    }
  }

  override fun stop() {
    super.stop()
    detector.close()
  }

  override fun detectInImage(image: InputImage): Task<List<Face>> {
    return detector.process(image)
  }

  override fun onSuccess(faces: List<Face>, graphicOverlay: GraphicOverlay) {

    Log.e("walks talks quacks likea duck ", "serialport is open : ${serialPort?.isOpen.toString()}")
    frameCounter++
    if (frameCounter % 2 == 0) {
      for (face in faces) {
        graphicOverlay.add(FaceGraphic(graphicOverlay, face))
        val bbox = face.boundingBox

        // Get the center of the bounding box
        val bboxCenterX = bbox.centerX()
        val bboxCenterY = bbox.centerY()
        Log.e(TAG, "bboxCenter ($bboxCenterX, $bboxCenterY)")
        val overlayWidth = 360
        val overlayHeight = 640
//        Log.e(TAG, "overlayWidth $overlayWidth")

        // Calculate the error between the bounding box center and the overlay center
        val errorX = bboxCenterX - (overlayWidth / 2)
        normalizedErrorX = errorX / (overlayWidth / 2).toFloat()
        val errorY = bboxCenterY - (overlayHeight / 2)
        normalizedErrorY = errorY / (overlayHeight / 2).toFloat()
        Log.e(TAG, "normalizedErrorX $normalizedErrorX")

        // PD control
        val proportional = Kp * normalizedErrorX
        val derivative = Kd * (normalizedErrorX - previousErrorX)
        val controlOutput = proportional + derivative

        previousErrorX = normalizedErrorX

        val proportionalY = Kp * normalizedErrorY
        val derivativeY = Kd * (normalizedErrorY - previousErrorY)
        val controlOutputY = proportionalY + derivativeY

        previousErrorY = normalizedErrorY

        // Calculate the angle adjustment based on control output
        val rotationAngleX = controlOutput * (horizontal_fov / 2)
        val rotationAngleY = controlOutputY * (vertical_fov / 2)
        Log.e(TAG, "rotationAngle $rotationAngleX $rotationAngleY ")

        stepsToMoveX = -((rotationAngleX / 360) * steps_per_revolution).toInt()
        stepsToMoveY = -((rotationAngleY / 360) * steps_per_revolution).toInt()
        Log.e(TAG, "stepsToMove ($stepsToMoveX, $stepsToMoveY)")

        // Control both yaw and pitch motors
        moveMotors()
      }
    }
  }

  private fun moveMotors() {
    val yawDir = if (normalizedErrorX > 0) "clockwise" else "counterclockwise"
    val yawSteps = Math.abs(stepsToMoveX)

    val pitchDir = if (normalizedErrorY > 0) "clockwise" else "counterclockwise"
    val pitchSteps = Math.abs(stepsToMoveY)
    val data = "yawDir=$yawDir,yawSteps=$yawSteps,pitchDir=$pitchDir,pitchSteps=$pitchSteps,delay=1500\n"
    Log.e("BARFTASTIC", data)
    try {
      serialPort?.write(data.toByteArray(StandardCharsets.UTF_8), 1000)
    } catch (e: IOException) {
      Log.e("SerialWrite", "Failed to write data to serial port", e)
    }
//    CoroutineScope(Dispatchers.IO).launch {
//      try {
//        val response = RetrofitClient.apiService.controlMotors(yawDir, yawSteps, pitchDir, pitchSteps)
//        withContext(Dispatchers.Main) {
//          // Handle response if needed
//          Log.d(TAG, "Motors moved: ${response.string()}")
//        }
//      } catch (e: Exception) {
//        withContext(Dispatchers.Main) {
//          // Handle error if needed
//          Log.e(TAG, "Error moving motors: $e")
//        }
//      }
//    }
  }

  override fun onFailure(e: Exception) {
    Log.e(TAG, "Face detection failed $e")
  }

  companion object {
    private const val TAG = "FaceDetectorProcessor"
    private const val MANUAL_TESTING_LOG = "ManualTesting"
  }
}
