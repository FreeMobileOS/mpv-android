package `is`.xyz.mpv

import android.content.Context
import android.media.AudioManager
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.util.AttributeSet
import android.util.Log
import android.view.WindowManager

import `is`.xyz.mpv.MPVLib.mpvFormat.*
import android.os.Build
import android.preference.PreferenceManager
import kotlin.reflect.KProperty

internal class MPVView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    fun initialize(configDir: String) {
        holder.addCallback(this)
        MPVLib.create(this.context)
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        MPVLib.init()
        initOptions()
        observeProperties()
    }

    fun initOptions() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.context)

        // initial options
        data class Property(val preference_name: String, val mpv_option: String)
        this.dumbMode = sharedPreferences.getBoolean("video_dumb_mode", false)

        // hwdec
        val hwdec = if (dumbMode)
            "mediacodec"
        else if (sharedPreferences.getBoolean("hardware_decoding", true))
            "mediacodec-copy"
        else
            "no"

        // vo: set display fps as reported by android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val disp = wm.defaultDisplay
            val refreshRate = disp.mode.refreshRate

            Log.v(TAG, "Display ${disp.displayId} reports FPS of $refreshRate")

            if (sharedPreferences.getBoolean("video_refreshrate", true))
                MPVLib.setOptionString("display-fps", refreshRate.toString())
            else
                Log.v(TAG, "...however we are ignoring that as requested by the user")

        } else {
            Log.v(TAG, "Android version too old, disabling refresh rate functionality " +
                       "(${Build.VERSION.SDK_INT} < ${Build.VERSION_CODES.M})")
        }

        // ao: set optimal buffer size and sample rate for opensles, to get better audio playback
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val framesPerBuffer = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        val sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        Log.v(TAG, "Device reports optimal frames per buffer $framesPerBuffer sample rate $sampleRate")

        MPVLib.setOptionString("opensles-frames-per-buffer", framesPerBuffer)
        MPVLib.setOptionString("opensles-sample-rate", sampleRate)

        // set non-complex options

        val opts = arrayOf(
                Property("default_audio_language", "alang"),
                Property("default_subtitle_language", "slang"),

                // vo-related
                Property("video_upscale", "scale"),
                Property("video_downscale", "dscale"),
                Property("video_scale_param1", "scale-param1"),
                Property("video_scale_param2", "scale-param2")
        )

        for ((preference_name, mpv_option) in opts) {
            val preference = sharedPreferences.getString(preference_name, "")
            if (!preference.isNullOrBlank())
                MPVLib.setOptionString(mpv_option, preference)
        }

        if (sharedPreferences.getBoolean("video_deband", false)) {
            // use gradfun as --deband=yes did not work on my device's mobile GPUs
            // also lower the default radius to improve perf
            MPVLib.setOptionString("vf", "gradfun=radius=12")
        }

        // set options

        if (dumbMode) {
            MPVLib.setOptionString("vo", "android")
        } else {
            MPVLib.setOptionString("vo", "opengl")
            MPVLib.setOptionString("opengl-backend", "android")
        }
        MPVLib.setOptionString("hwdec", hwdec)
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9")
        MPVLib.setOptionString("ao", "opensles")
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("tls-ca-file", "${this.context.filesDir.path}/cacert.pem")
    }

    fun playFile(filePath: String) {
        this.filePath = filePath
    }

    fun onPause() {
        MPVLib.setPropertyString("vid", "no")
        paused = true
    }
    
    fun onResume() {
    }

    // Called when back button is pressed, or app is shutting down
    fun destroy() {
        // At this point the surface is already detached so it won't call step/draw, as such it's safe to free mpv resources
        MPVLib.clearObservers()
        MPVLib.destroy()
    }

    fun observeProperties() {
        data class Property(val name: String, val format: Int)
        val p = arrayOf(
                Property("time-pos", MPV_FORMAT_INT64),
                Property("duration", MPV_FORMAT_INT64),
                Property("pause", MPV_FORMAT_FLAG),
                Property("track-list", MPV_FORMAT_NONE)
        )

        for ((name, format) in p)
            MPVLib.observeProperty(name, format)
    }

    fun addObserver(o: EventObserver) {
        MPVLib.addObserver(o)
    }

    data class Track(val mpvId: Int, val name: String)
    var tracks = mapOf<String, MutableList<Track>>(
            "audio" to arrayListOf(),
            "video" to arrayListOf(),
            "sub" to arrayListOf())

    fun loadTracks() {
        for (type in tracks.keys) {
            tracks[type]!!.clear()
            // pseudo-track to allow disabling audio/subs
            tracks[type]!!.add(Track(-1, "None"))
        }
        val count = MPVLib.getPropertyInt("track-list/count")!!
        for (i in 0 until count) {
            val type = MPVLib.getPropertyString("track-list/$i/type")!!
            if (!tracks.containsKey(type)) {
                Log.w(TAG, "Got unknown track type: $type")
                continue
            }
            val lang = MPVLib.getPropertyString("track-list/$i/lang") ?: "unk"
            val mpvId = MPVLib.getPropertyInt("track-list/$i/id")!!
            val track = Track(
                    mpvId=mpvId,
                    name="#$mpvId: $lang"
                    )
            tracks[type]!!.add(track)
        }
    }

    private var filePath: String? = null
    private var dumbMode = false

    // Property getters/setters

    var paused: Boolean?
        get() = MPVLib.getPropertyBoolean("pause")
        set(paused) = MPVLib.setPropertyBoolean("pause", paused)

    val duration: Int?
        get() = MPVLib.getPropertyInt("duration")

    var timePos: Int?
        get() = MPVLib.getPropertyInt("time-pos")
        set(progress) = MPVLib.setPropertyInt("time-pos", progress)

    val hwdecActive: Boolean?
        get() = MPVLib.getPropertyString("hwdec-current") != "no"

    val filename: String?
        get() = MPVLib.getPropertyString("filename")

    val avsync: String?
        get() = MPVLib.getPropertyString("avsync")

    val drop_frame_count: Int?
        get() = MPVLib.getPropertyInt("drop-frame-count")

    val vo_drop_frame_count: Int?
        get() = MPVLib.getPropertyInt("vo-drop-frame-count")

    val fps: String?
        get() = MPVLib.getPropertyString("fps")

    val estimated_vf_fps: String?
        get() = MPVLib.getPropertyString("estimated-vf-fps")

    val video_w: Int?
        get() = MPVLib.getPropertyInt("video-params/w")

    val video_h: Int?
        get() = MPVLib.getPropertyInt("video-params/h")

    val video_codec: String?
        get() = MPVLib.getPropertyString("video-codec")

    val audio_codec: String?
        get() = MPVLib.getPropertyString("audio-codec")

    val audio_samplerate: Int?
        get() = MPVLib.getPropertyInt("audio-params/samplerate")

    val audio_channels: Int?
        get() = MPVLib.getPropertyInt("audio-params/channel-count")

    class TrackDelegate {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Int {
            val v = MPVLib.getPropertyString(property.name)
            // we can get null here for "no" or other invalid value
            return v.toIntOrNull() ?: -1
        }
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            if (value == -1)
                MPVLib.setPropertyString(property.name, "no")
            else
                MPVLib.setPropertyInt(property.name, value)
        }
    }

    var sid: Int by TrackDelegate()
    var aid: Int by TrackDelegate()

    // Commands

    fun cyclePause() = MPVLib.command(arrayOf("cycle", "pause"))
    fun cycleAudio() = MPVLib.command(arrayOf("cycle", "audio"))
    fun cycleSub() = MPVLib.command(arrayOf("cycle", "sub"))
    fun cycleHwdec() {
        if (!dumbMode) // impossible in dumb mode
            MPVLib.setPropertyString("hwdec", if (hwdecActive!!) "no" else "mediacodec-copy")
    }

    // Surface callbacks

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.w(TAG, "Creating libmpv surface")
        MPVLib.attachSurface(holder.surface)
        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        } else {
            // Get here when user goes to home screen and then returns to the app
            // mpv disables video output when opengl context is destroyed, enable it back
            MPVLib.setPropertyInt("vid", 1)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        MPVLib.detachSurface()
    }

    companion object {
        private val TAG = "mpv"
    }
}
