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
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.Locale

interface ApiService {
  @GET("servo")
  suspend fun setServoAngles(
    @Query("angle1") angle1: Int,
    @Query("angle2") angle2: Int
  ): ResponseBody

  @GET("forward")
  suspend fun moveForward(
    @Query("steps") steps: Int
  ): ResponseBody

  @GET("backward")
  suspend fun moveBackward(
    @Query("steps") steps: Int
  ): ResponseBody
}

object RetrofitClient {
  private val retrofit = Retrofit.Builder()
    .baseUrl("http://192.168.4.78/")  // Replace with the IP address of your ESP32
    .addConverterFactory(GsonConverterFactory.create())
    .build()

  val apiService: ApiService = retrofit.create(ApiService::class.java)
}

/** Face Detector Demo.  */
class FaceDetectorProcessor(context: Context, detectorOptions: FaceDetectorOptions?, private val moveButton: Button) :
  VisionProcessorBase<List<Face>>(context) {

  private val detector: FaceDetector
  private var stepsToMove: Int = 0
  private var frameCounter: Int = 0

  private var previousError: Float = 0f
  private val Kp: Float = 1f // Proportional gain
  private val Kd: Float = 0f // Derivative gain

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
      moveMotor()
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
    frameCounter++
    if (frameCounter % 2 == 0) {
      for (face in faces) {
        graphicOverlay.add(FaceGraphic(graphicOverlay, face))
        val bbox = face.boundingBox

        // Get the center of the bounding box
        val bboxCenterX = bbox.centerX()
        Log.e(TAG, "bboxCenterX ${bboxCenterX}")
        val overlayWidth = 360
        Log.e(TAG, "overlayWidth ${overlayWidth}")

        // Calculate the error between the bounding box center and the overlay center
        val errorX = bboxCenterX - (overlayWidth / 2)
        val normalizedErrorX = errorX / (overlayWidth / 2).toFloat()
        Log.e(TAG, "normalizedErrorX ${normalizedErrorX}")

        // PD control
        val proportional = Kp * normalizedErrorX
        val derivative = Kd * (normalizedErrorX - previousError)
        val controlOutput = proportional + derivative

        previousError = normalizedErrorX

        // Calculate the angle adjustment based on control output
        val rotationAngle = controlOutput * (82f / 2)
        Log.e(TAG, "rotationAngle ${rotationAngle}")

        stepsToMove = -((rotationAngle / 360) * 200).toInt()
        Log.e(TAG, "stepsToMove ${stepsToMove}")
//        moveMotor()
      }
    }
  }

  private fun moveMotor() {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val response = if (stepsToMove > 0) {
          RetrofitClient.apiService.moveForward(stepsToMove)
        } else {
          RetrofitClient.apiService.moveBackward(-stepsToMove)
        }
        withContext(Dispatchers.Main) {
          // Handle response if needed
          Log.d(TAG, "Motor moved: ${response.string()}")
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          // Handle error if needed
          Log.e(TAG, "Error moving motor: $e")
        }
      }
    }
  }

  override fun onFailure(e: Exception) {
    Log.e(TAG, "Face detection failed $e")
  }

  companion object {
    private const val TAG = "FaceDetectorProcessor"
    private fun logExtrasForTesting(face: Face?) {
      if (face != null) {
        Log.v(
          MANUAL_TESTING_LOG,
          "face bounding box: " + face.boundingBox.flattenToString()
        )
        Log.v(
          MANUAL_TESTING_LOG,
          "face Euler Angle X: " + face.headEulerAngleX
        )
        Log.v(
          MANUAL_TESTING_LOG,
          "face Euler Angle Y: " + face.headEulerAngleY
        )
        Log.v(
          MANUAL_TESTING_LOG,
          "face Euler Angle Z: " + face.headEulerAngleZ
        )
        // All landmarks
        val landMarkTypes = intArrayOf(
          FaceLandmark.MOUTH_BOTTOM,
          FaceLandmark.MOUTH_RIGHT,
          FaceLandmark.MOUTH_LEFT,
          FaceLandmark.RIGHT_EYE,
          FaceLandmark.LEFT_EYE,
          FaceLandmark.RIGHT_EAR,
          FaceLandmark.LEFT_EAR,
          FaceLandmark.RIGHT_CHEEK,
          FaceLandmark.LEFT_CHEEK,
          FaceLandmark.NOSE_BASE
        )
        val landMarkTypesStrings = arrayOf(
          "MOUTH_BOTTOM",
          "MOUTH_RIGHT",
          "MOUTH_LEFT",
          "RIGHT_EYE",
          "LEFT_EYE",
          "RIGHT_EAR",
          "LEFT_EAR",
          "RIGHT_CHEEK",
          "LEFT_CHEEK",
          "NOSE_BASE"
        )
        for (i in landMarkTypes.indices) {
          val landmark = face.getLandmark(landMarkTypes[i])
          if (landmark == null) {
            Log.v(
              MANUAL_TESTING_LOG,
              "No landmark of type: " + landMarkTypesStrings[i] + " has been detected"
            )
          } else {
            val landmarkPosition = landmark.position
            val landmarkPositionStr =
              String.format(Locale.US, "x: %f , y: %f", landmarkPosition.x, landmarkPosition.y)
            Log.v(
              MANUAL_TESTING_LOG,
              "Position for face landmark: " +
                      landMarkTypesStrings[i] +
                      " is :" +
                      landmarkPositionStr
            )
          }
        }
        Log.v(
          MANUAL_TESTING_LOG,
          "face left eye open probability: " + face.leftEyeOpenProbability
        )
        Log.v(
          MANUAL_TESTING_LOG,
          "face right eye open probability: " + face.rightEyeOpenProbability
        )
        Log.v(
          MANUAL_TESTING_LOG,
          "face smiling probability: " + face.smilingProbability
        )
        Log.v(
          MANUAL_TESTING_LOG,
          "face tracking id: " + face.trackingId
        )
      }
    }
  }
}
