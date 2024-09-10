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

package com.google.mlkit.vision.demo.kotlin

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import com.google.android.gms.common.annotation.KeepName
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions.ZoomCallback
import com.google.mlkit.vision.demo.CameraSource
import com.google.mlkit.vision.demo.CameraSourcePreview
import com.google.mlkit.vision.demo.GraphicOverlay
import com.google.mlkit.vision.demo.R
import com.google.mlkit.vision.demo.kotlin.barcodescanner.BarcodeScannerProcessor
import com.google.mlkit.vision.demo.kotlin.facedetector.FaceDetectorProcessor
import com.google.mlkit.vision.demo.kotlin.facemeshdetector.FaceMeshDetectorProcessor
import com.google.mlkit.vision.demo.kotlin.labeldetector.LabelDetectorProcessor
import com.google.mlkit.vision.demo.kotlin.objectdetector.ObjectDetectorProcessor
import com.google.mlkit.vision.demo.kotlin.posedetector.PoseDetectorProcessor
import com.google.mlkit.vision.demo.kotlin.segmenter.SegmenterProcessor
import com.google.mlkit.vision.demo.kotlin.textdetector.TextRecognitionProcessor
import com.google.mlkit.vision.demo.preference.PreferenceUtils
import com.google.mlkit.vision.demo.preference.SettingsActivity
import com.google.mlkit.vision.demo.preference.SettingsActivity.LaunchSource
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Live preview demo for ML Kit APIs. */
@KeepName
class LivePreviewActivity :
  AppCompatActivity(), OnItemSelectedListener, CompoundButton.OnCheckedChangeListener,SerialInputOutputManager.Listener {

  private var cameraSource: CameraSource? = null
  private var preview: CameraSourcePreview? = null
  private var graphicOverlay: GraphicOverlay? = null
  private var selectedModel = OBJECT_DETECTION
  private lateinit var usbManager: UsbManager
  private var serialPort: UsbSerialPort? = null
  private var usbIoManager: SerialInputOutputManager? = null
  private val ACTION_USB_PERMISSION = "com.example.serialledcontrol.USB_PERMISSION"

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(TAG, "onCreate")
    setContentView(R.layout.activity_vision_live_preview)

    preview = findViewById(R.id.preview_view)
    if (preview == null) {
      Log.d(TAG, "Preview is null")
    }

    graphicOverlay = findViewById(R.id.graphic_overlay)
    if (graphicOverlay == null) {
      Log.d(TAG, "graphicOverlay is null")
    }

    val spinner = findViewById<Spinner>(R.id.spinner)
    val options: MutableList<String> = ArrayList()
    options.add(OBJECT_DETECTION)
    options.add(OBJECT_DETECTION_CUSTOM)
    options.add(CUSTOM_AUTOML_OBJECT_DETECTION)
    options.add(FACE_DETECTION)
    options.add(BARCODE_SCANNING)
    options.add(IMAGE_LABELING)
    options.add(IMAGE_LABELING_CUSTOM)
    options.add(CUSTOM_AUTOML_LABELING)
    options.add(POSE_DETECTION)
    options.add(SELFIE_SEGMENTATION)
    options.add(TEXT_RECOGNITION_LATIN)
    options.add(TEXT_RECOGNITION_CHINESE)
    options.add(TEXT_RECOGNITION_DEVANAGARI)
    options.add(TEXT_RECOGNITION_JAPANESE)
    options.add(TEXT_RECOGNITION_KOREAN)
    options.add(FACE_MESH_DETECTION)

    // Creating adapter for spinner
    val dataAdapter = ArrayAdapter(this, R.layout.spinner_style, options)

    // Drop down layout style - list view with radio button
    dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    // attaching data adapter to spinner
    spinner.adapter = dataAdapter
    spinner.onItemSelectedListener = this

    val facingSwitch = findViewById<ToggleButton>(R.id.facing_switch)
    facingSwitch.setOnCheckedChangeListener(this)

    val settingsButton = findViewById<ImageView>(R.id.settings_button)
    settingsButton.setOnClickListener {
      val intent = Intent(applicationContext, SettingsActivity::class.java)
      intent.putExtra(SettingsActivity.EXTRA_LAUNCH_SOURCE, LaunchSource.LIVE_PREVIEW)
      startActivity(intent)
    }
    usbManager = getSystemService(USB_SERVICE) as UsbManager

    // Register receiver for USB permission with RECEIVER_NOT_EXPORTED flag
    val filter = IntentFilter(ACTION_USB_PERMISSION)
    registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)

    setupUsbConnection()

    createCameraSource(selectedModel)
  }

  @Synchronized
  override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
    // An item was selected. You can retrieve the selected item using
    // parent.getItemAtPosition(pos)
    selectedModel = parent?.getItemAtPosition(pos).toString()
    Log.d(TAG, "Selected model: $selectedModel")
    preview?.stop()
    createCameraSource(selectedModel)
    startCameraSource()
  }

  override fun onNothingSelected(parent: AdapterView<*>?) {
    // Do nothing.
  }

  override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
    Log.d(TAG, "Set facing")
    if (cameraSource != null) {
      if (isChecked) {
        cameraSource?.setFacing(CameraSource.CAMERA_FACING_FRONT)
      } else {
        cameraSource?.setFacing(CameraSource.CAMERA_FACING_BACK)
      }
    }
    preview?.stop()
    startCameraSource()
  }

  private fun createCameraSource(model: String) {
    // If there's no existing cameraSource, create one.
    if (cameraSource == null) {
      cameraSource = CameraSource(this, graphicOverlay)
    }
    try {
      when (model) {
        OBJECT_DETECTION -> {
          Log.i(TAG, "Using Object Detector Processor")
          val objectDetectorOptions = PreferenceUtils.getObjectDetectorOptionsForLivePreview(this)
          cameraSource!!.setMachineLearningFrameProcessor(
            ObjectDetectorProcessor(this, objectDetectorOptions)
          )
        }
        OBJECT_DETECTION_CUSTOM -> {
          Log.i(TAG, "Using Custom Object Detector Processor")
          val localModel =
            LocalModel.Builder().setAssetFilePath("custom_models/object_labeler.tflite").build()
          val customObjectDetectorOptions =
            PreferenceUtils.getCustomObjectDetectorOptionsForLivePreview(this, localModel)
          cameraSource!!.setMachineLearningFrameProcessor(
            ObjectDetectorProcessor(this, customObjectDetectorOptions)
          )
        }
        CUSTOM_AUTOML_OBJECT_DETECTION -> {
          Log.i(TAG, "Using Custom AutoML Object Detector Processor")
          val customAutoMLODTLocalModel =
            LocalModel.Builder().setAssetManifestFilePath("automl/manifest.json").build()
          val customAutoMLODTOptions =
            PreferenceUtils.getCustomObjectDetectorOptionsForLivePreview(
              this,
              customAutoMLODTLocalModel
            )
          cameraSource!!.setMachineLearningFrameProcessor(
            ObjectDetectorProcessor(this, customAutoMLODTOptions)
          )
        }
        TEXT_RECOGNITION_LATIN -> {
          Log.i(TAG, "Using on-device Text recognition Processor for Latin and Latin")
          cameraSource!!.setMachineLearningFrameProcessor(
            TextRecognitionProcessor(this, TextRecognizerOptions.Builder().build())
          )
        }
        TEXT_RECOGNITION_CHINESE -> {
          Log.i(TAG, "Using on-device Text recognition Processor for Latin and Chinese")
          cameraSource!!.setMachineLearningFrameProcessor(
            TextRecognitionProcessor(this, ChineseTextRecognizerOptions.Builder().build())
          )
        }
        TEXT_RECOGNITION_DEVANAGARI -> {
          Log.i(TAG, "Using on-device Text recognition Processor for Latin and Devanagari")
          cameraSource!!.setMachineLearningFrameProcessor(
            TextRecognitionProcessor(this, DevanagariTextRecognizerOptions.Builder().build())
          )
        }
        TEXT_RECOGNITION_JAPANESE -> {
          Log.i(TAG, "Using on-device Text recognition Processor for Latin and Japanese")
          cameraSource!!.setMachineLearningFrameProcessor(
            TextRecognitionProcessor(this, JapaneseTextRecognizerOptions.Builder().build())
          )
        }
        TEXT_RECOGNITION_KOREAN -> {
          Log.i(TAG, "Using on-device Text recognition Processor for Latin and Korean")
          cameraSource!!.setMachineLearningFrameProcessor(
            TextRecognitionProcessor(this, KoreanTextRecognizerOptions.Builder().build())
          )
        }
        FACE_DETECTION -> {
          Log.i(TAG, "Using Face Detector Processor")
          val faceDetectorOptions = PreferenceUtils.getFaceDetectorOptions(this)
          val moveButton: Button = findViewById(R.id.move_button)

          cameraSource!!.setMachineLearningFrameProcessor(
            FaceDetectorProcessor(this, faceDetectorOptions, moveButton, serialPort)
          )
        }
        BARCODE_SCANNING -> {
          Log.i(TAG, "Using Barcode Detector Processor")
          var zoomCallback: ZoomCallback? = null
          if (PreferenceUtils.shouldEnableAutoZoom(this)) {
            zoomCallback = ZoomCallback { zoomLevel: Float -> cameraSource!!.setZoom(zoomLevel) }
          }
          cameraSource!!.setMachineLearningFrameProcessor(
            BarcodeScannerProcessor(this, zoomCallback)
          )
        }
        IMAGE_LABELING -> {
          Log.i(TAG, "Using Image Label Detector Processor")
          cameraSource!!.setMachineLearningFrameProcessor(
            LabelDetectorProcessor(this, ImageLabelerOptions.DEFAULT_OPTIONS)
          )
        }
        IMAGE_LABELING_CUSTOM -> {
          Log.i(TAG, "Using Custom Image Label Detector Processor")
          val localClassifier =
            LocalModel.Builder().setAssetFilePath("custom_models/bird_classifier.tflite").build()
          val customImageLabelerOptions = CustomImageLabelerOptions.Builder(localClassifier).build()
          cameraSource!!.setMachineLearningFrameProcessor(
            LabelDetectorProcessor(this, customImageLabelerOptions)
          )
        }
        CUSTOM_AUTOML_LABELING -> {
          Log.i(TAG, "Using Custom AutoML Image Label Detector Processor")
          val customAutoMLLabelLocalModel =
            LocalModel.Builder().setAssetManifestFilePath("automl/manifest.json").build()
          val customAutoMLLabelOptions =
            CustomImageLabelerOptions.Builder(customAutoMLLabelLocalModel)
              .setConfidenceThreshold(0f)
              .build()
          cameraSource!!.setMachineLearningFrameProcessor(
            LabelDetectorProcessor(this, customAutoMLLabelOptions)
          )
        }
        POSE_DETECTION -> {
          val poseDetectorOptions = PreferenceUtils.getPoseDetectorOptionsForLivePreview(this)
          Log.i(TAG, "Using Pose Detector with options $poseDetectorOptions")
          val shouldShowInFrameLikelihood =
            PreferenceUtils.shouldShowPoseDetectionInFrameLikelihoodLivePreview(this)
          val visualizeZ = PreferenceUtils.shouldPoseDetectionVisualizeZ(this)
          val rescaleZ = PreferenceUtils.shouldPoseDetectionRescaleZForVisualization(this)
          val runClassification = PreferenceUtils.shouldPoseDetectionRunClassification(this)
          cameraSource!!.setMachineLearningFrameProcessor(
            PoseDetectorProcessor(
              this,
              poseDetectorOptions,
              shouldShowInFrameLikelihood,
              visualizeZ,
              rescaleZ,
              runClassification,
              /* isStreamMode = */ true
            )
          )
        }
        SELFIE_SEGMENTATION -> {
          cameraSource!!.setMachineLearningFrameProcessor(SegmenterProcessor(this))
        }
        FACE_MESH_DETECTION -> {
          cameraSource!!.setMachineLearningFrameProcessor(FaceMeshDetectorProcessor(this))
        }
        else -> Log.e(TAG, "Unknown model: $model")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Can not create image processor: $model", e)
      Toast.makeText(
          applicationContext,
          "Can not create image processor: " + e.message,
          Toast.LENGTH_LONG
        )
        .show()
    }
  }

  /**
   * Starts or restarts the camera source, if it exists. If the camera source doesn't exist yet
   * (e.g., because onResume was called before the camera source was created), this will be called
   * again when the camera source is created.
   */
  private fun startCameraSource() {
    if (cameraSource != null) {
      try {
        if (preview == null) {
          Log.d(TAG, "resume: Preview is null")
        }
        if (graphicOverlay == null) {
          Log.d(TAG, "resume: graphOverlay is null")
        }
        preview!!.start(cameraSource, graphicOverlay)
      } catch (e: IOException) {
        Log.e(TAG, "Unable to start camera source.", e)
        cameraSource!!.release()
        cameraSource = null
      }
    }
  }

  public override fun onResume() {
    super.onResume()
    Log.d(TAG, "onResume")
    createCameraSource(selectedModel)
    startCameraSource()
  }

  /** Stops the camera. */
  override fun onPause() {
    super.onPause()
    preview?.stop()
  }

  public override fun onDestroy() {
    super.onDestroy()
    if (cameraSource != null) {
      cameraSource?.release()
    }

    unregisterReceiver(usbReceiver)
    usbIoManager?.stop()
    serialPort?.close()
    updateLog("USB connection closed.")
    super.onDestroy()
  }

  private val usbReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val action = intent.action
      if (ACTION_USB_PERMISSION == action) {
        synchronized(this) {
          val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
          if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            device?.let {
              val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
              val driver = availableDrivers.firstOrNull { it.device == device }
              if (driver != null) {
                openUsbConnection(driver)
              }
            }
          } else {
            updateLog("USB permission denied.")
          }
        }
      }
    }
  }
  private fun setupUsbConnection() {
    val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    if (availableDrivers.isEmpty()) {
      updateLog("No USB devices found.")
      return
    }

    val driver = availableDrivers[0]
    val device = driver.device

    // Check if permission is granted, request if not
    if (usbManager.hasPermission(device)) {
      openUsbConnection(driver)
    } else {
      val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
      usbManager.requestPermission(device, permissionIntent)
      updateLog("Requesting USB permission.")
    }
  }

  private fun openUsbConnection(driver: UsbSerialDriver) {
    val connection = usbManager.openDevice(driver.device)
    if (connection == null) {
      updateLog("Failed to open USB device.")
      return
    }

    serialPort = driver.ports[0] // Most devices have just one port (port 0)
    serialPort?.open(connection)
    serialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

    // Start event-driven read
    usbIoManager = SerialInputOutputManager(serialPort, this)
    Executors.newSingleThreadExecutor().submit(usbIoManager)

    updateLog("USB device connected: ${driver.device.deviceName}")
  }

  private fun sendSerialData(data: String) {
    serialPort?.write(data.toByteArray(StandardCharsets.UTF_8), 1000)
    updateLog("Sent data: $data")
  }

  private fun updateLog(message: String) {
    runOnUiThread {
      Log.e("BAHH", message)
    }
  }

  override fun onNewData(data: ByteArray?) {
    runOnUiThread {
      data?.let {
        val receivedData = String(it, StandardCharsets.UTF_8)
        Log.e("BAHH", "Received: $receivedData")
      }
    }
  }

  override fun onRunError(e: Exception?) {
    runOnUiThread {
      Log.e("BAHH", "Error: ${e?.message}")
    }
  }

  companion object {
    private const val OBJECT_DETECTION = "Object Detection"
    private const val OBJECT_DETECTION_CUSTOM = "Custom Object Detection"
    private const val CUSTOM_AUTOML_OBJECT_DETECTION = "Custom AutoML Object Detection (Flower)"
    private const val FACE_DETECTION = "Face Detection"
    private const val TEXT_RECOGNITION_LATIN = "Text Recognition Latin"
    private const val TEXT_RECOGNITION_CHINESE = "Text Recognition Chinese"
    private const val TEXT_RECOGNITION_DEVANAGARI = "Text Recognition Devanagari"
    private const val TEXT_RECOGNITION_JAPANESE = "Text Recognition Japanese"
    private const val TEXT_RECOGNITION_KOREAN = "Text Recognition Korean"
    private const val BARCODE_SCANNING = "Barcode Scanning"
    private const val IMAGE_LABELING = "Image Labeling"
    private const val IMAGE_LABELING_CUSTOM = "Custom Image Labeling (Birds)"
    private const val CUSTOM_AUTOML_LABELING = "Custom AutoML Image Labeling (Flower)"
    private const val POSE_DETECTION = "Pose Detection"
    private const val SELFIE_SEGMENTATION = "Selfie Segmentation"
    private const val FACE_MESH_DETECTION = "Face Mesh Detection (Beta)"

    private const val TAG = "LivePreviewActivity"
  }
}
