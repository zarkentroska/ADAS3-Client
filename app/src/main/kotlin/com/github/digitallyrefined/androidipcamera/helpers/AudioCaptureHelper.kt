package com.github.digitallyrefined.androidipcamera.helpers

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureHelper(
    private val sampleRate: Int = 44100,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    
    private val audioDataListeners = mutableListOf<(ByteArray) -> Unit>()
    
    fun addAudioDataListener(listener: (ByteArray) -> Unit) {
        synchronized(audioDataListeners) {
            audioDataListeners.add(listener)
        }
    }
    
    fun removeAudioDataListener(listener: (ByteArray) -> Unit) {
        synchronized(audioDataListeners) {
            audioDataListeners.remove(listener)
        }
    }
    
    private fun notifyAudioData(data: ByteArray) {
        synchronized(audioDataListeners) {
            audioDataListeners.forEach { it(data) }
        }
    }
    
    fun startRecording(): Boolean {
        if (isRecording.get()) {
            return false
        }
        
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid audio buffer size")
            return false
        }
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return false
            }
            
            isRecording.set(true)
            audioRecord?.startRecording()
            
            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording.get() && audioRecord != null) {
                    try {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (bytesRead > 0) {
                            val audioData = ByteArray(bytesRead)
                            System.arraycopy(buffer, 0, audioData, 0, bytesRead)
                            notifyAudioData(audioData)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading audio data: ${e.message}")
                        break
                    }
                }
            }
            recordingThread?.start()
            
            Log.i(TAG, "Audio recording started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording: ${e.message}")
            isRecording.set(false)
            return false
        }
    }
    
    fun stopRecording() {
        if (!isRecording.get()) {
            return
        }
        
        isRecording.set(false)
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record: ${e.message}")
        }
        
        try {
            recordingThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error joining recording thread: ${e.message}")
        }
        
        try {
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio record: ${e.message}")
        }
        
        Log.i(TAG, "Audio recording stopped")
    }
    
    fun isRecording(): Boolean = isRecording.get()
    
    companion object {
        private const val TAG = "AudioCaptureHelper"
    }
}


