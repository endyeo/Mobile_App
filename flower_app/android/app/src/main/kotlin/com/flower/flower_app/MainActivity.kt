package com.flower.flower_app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.Locale

class MainActivity : FlutterActivity() {
    private val speechChannel = "flower_app/speech"
    private val recordAudioRequestCode = 3201
    private var pendingSpeechResult: MethodChannel.Result? = null
    private var speechRecognizer: SpeechRecognizer? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, speechChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "listen" -> startSpeechRecognition(result)
                    else -> result.notImplemented()
                }
            }
    }

    private fun startSpeechRecognition(result: MethodChannel.Result) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            result.error("unavailable", "Speech recognition is not available.", null)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSpeechResult?.error("cancelled", "Previous speech request was cancelled.", null)
            pendingSpeechResult = result
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), recordAudioRequestCode)
            return
        }

        beginListening(result)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != recordAudioRequestCode) return

        val result = pendingSpeechResult ?: return
        pendingSpeechResult = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            beginListening(result)
        } else {
            result.error("permission_denied", "Microphone permission is required.", null)
        }
    }

    private fun beginListening(result: MethodChannel.Result) {
        pendingSpeechResult?.error("cancelled", "Previous speech request was cancelled.", null)
        pendingSpeechResult = result

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    finishSpeechError("empty", "Speech was not recognized.")
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    if (text.isBlank()) {
                        finishSpeechError("empty", "Speech was not recognized.")
                    } else {
                        finishSpeechSuccess(text)
                    }
                }
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREA.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun finishSpeechSuccess(text: String) {
        pendingSpeechResult?.success(text)
        pendingSpeechResult = null
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun finishSpeechError(code: String, message: String) {
        pendingSpeechResult?.error(code, message, null)
        pendingSpeechResult = null
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }
}
