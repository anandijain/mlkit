/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mlkit.vision.demo.kotlin.facedetector

import android.content.Context
import android.util.Log
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
}

object RetrofitClient {
  private val retrofit = Retrofit.Builder()
    .baseUrl("http://192.168.4.31/")  // Replace with the IP address of your ESP32
    .addConverterFactory(GsonConverterFactory.create())
    .build()

  val apiService: ApiService = retrofit.create(ApiService::class.java)
}
/** Face Detector Demo.  */
class FaceDetectorProcessor(context: Context, detectorOptions: FaceDetectorOptions?) :
  VisionProcessorBase<List<Face>>(context) {

  private val detector: FaceDetector
  // Define proportional gain for controlling the servos
  // Define proportional gain and damping factor for controlling the servos
  // PID coefficients for controlling the servos
  private val kP = 0.05
  private val kI = 0.01
  private val kD = 0.02
  private val maxDeltaAngle = 5 // Limit the maximum change in angle per update

  // Previous servo angles
  private var previousAngle1 = 90
  private var previousAngle2 = 90

  // Previous errors and integral terms for PID control
  private var previousErrorX = 0
  private var previousErrorY = 0
  private var integralErrorX = 0
  private var integralErrorY = 0

  init {
    val options = detectorOptions
      ?: FaceDetectorOptions.Builder()
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .enableTracking()
        .build()

    detector = FaceDetection.getClient(options)

    Log.v(MANUAL_TESTING_LOG, "Face detector options: $options")
  }

  override fun stop() {
    super.stop()
    detector.close()
  }

  override fun detectInImage(image: InputImage): Task<List<Face>> {
    return detector.process(image)
  }

  override fun onSuccess(faces: List<Face>, graphicOverlay: GraphicOverlay) {
    for (face in faces) {
      graphicOverlay.add(FaceGraphic(graphicOverlay, face))
      val bbox = face.boundingBox

      // Get the center of the bounding box
      val bboxCenterX = bbox.centerX()
      val bboxCenterY = bbox.centerY()

      // Get the dimensions of the overlay (assuming the overlay covers the entire image)
      val overlayWidth = graphicOverlay.width
      val overlayHeight = graphicOverlay.height

      // Calculate the error between the bounding box center and the overlay center
      val errorX = bboxCenterX - overlayWidth / 2
      val errorY = bboxCenterY - overlayHeight / 2

      // Update integral errors
      integralErrorX += errorX
      integralErrorY += errorY

      // Calculate derivative errors
      val derivativeErrorX = errorX - previousErrorX
      val derivativeErrorY = errorY - previousErrorY

      // Update servo angles using PID control
      val deltaAngle1 = (kP * errorY + kI * integralErrorY + kD * derivativeErrorY).toInt()
      val deltaAngle2 = (kP * errorX + kI * integralErrorX + kD * derivativeErrorX).toInt()

      val newAngle1 = previousAngle1 + deltaAngle1
      val newAngle2 = previousAngle2 + deltaAngle2

      // Ensure angles are within 0-180 range
      val angle1Int = 180 - newAngle1.coerceIn(0, 180)
      val angle2Int = 180 - newAngle2.coerceIn(0, 180)

      previousAngle1 = angle1Int
      previousAngle2 = angle2Int
      previousErrorX = errorX
      previousErrorY = errorY

      Log.d(TAG, "angles are $angle1Int and $angle2Int")

      if (angle1Int in 0..180 && angle2Int in 0..180) {
        CoroutineScope(Dispatchers.IO).launch {
          try {
            val response = RetrofitClient.apiService.setServoAngles(
              angle1Int,
              angle2Int
            ).string()
            withContext(Dispatchers.Main) {
              // Handle response if needed
            }
          } catch (e: Exception) {
            withContext(Dispatchers.Main) {
              // Handle error if needed
            }
          }
        }
      } else {
        // Log invalid angles or handle as needed
      }
      Log.d(TAG, "Face bounding box: " + face.boundingBox)
      logExtrasForTesting(face)
    }
  }



//  fun rect_center(face: Face) : (Int, Int) {
//    val x = face.boundingBox.centerX()
//    val y = face.boundingBox.centerY()
//    return (x, y)
//  }
//

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
