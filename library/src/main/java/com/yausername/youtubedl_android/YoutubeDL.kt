package com.yausername.youtubedl_android

import android.content.Context
import android.os.Build
import com.fasterxml.jackson.databind.ObjectMapper
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.yausername.youtubedl_common.SharedPrefsHelper
import com.yausername.youtubedl_common.SharedPrefsHelper.update
import com.yausername.youtubedl_common.utils.ZipUtils.unzip
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlin.collections.set

object YoutubeDL {
    private var initialized = false
    private var pythonPath: File? = null
    private var ffmpegPath: File? = null
    private var quickJsPath: File? = null
    private var quickJsArg: String? = null   // path passed to --js-runtimes (qjs binary, or an API-23 wrapper)
    private var ytdlpPath: File? = null
    private var binDir: File? = null
    private var ENV_LD_LIBRARY_PATH: String? = null
    private var ENV_SSL_CERT_FILE: String? = null
    private var ENV_PYTHONHOME: String? = null
    private var TMPDIR: String = ""
    private val idProcessMap = Collections.synchronizedMap(HashMap<String, Process>())

    private var baseDirPath: File? = null

    @Synchronized
    @Throws(YoutubeDLException::class)
    fun init(appContext: Context) {
        if (initialized) return
        val baseDir = File(appContext.noBackupFilesDir, baseName)
        if (!baseDir.exists()) baseDir.mkdir()
        baseDirPath = baseDir
        val packagesDir = File(baseDir, packagesRoot)
        binDir = File(appContext.applicationInfo.nativeLibraryDir)
        pythonPath = File(binDir, pythonBinName)
        ffmpegPath = File(binDir, ffmpegBinName)
        quickJsPath = File(binDir, quickJsBinName)
        val pythonDir = File(packagesDir, pythonDirName)
        val ffmpegDir = File(packagesDir, ffmpegDirName)
        val aria2cDir = File(packagesDir, aria2cDirName)
        val ytdlpDir = File(baseDir, ytdlpDirName)
        ytdlpPath = File(ytdlpDir, ytdlpBin)
        ENV_LD_LIBRARY_PATH = pythonDir.absolutePath + "/usr/lib" + ":" +
                ffmpegDir.absolutePath + "/usr/lib" + ":" +
                aria2cDir.absolutePath + "/usr/lib"
        // No LD_PRELOAD shim: the native runtime is built from source for API 23 (termux-packages
        // fork, native-v* release) and has zero undefined API-24 libc symbols, so the in6addr_any/
        // strchrnul/getifaddrs backfills the old shim provided are no longer needed.
        ENV_SSL_CERT_FILE = pythonDir.absolutePath + "/usr/etc/tls/cert.pem"
        ENV_PYTHONHOME = pythonDir.absolutePath + "/usr"
        TMPDIR = appContext.cacheDir.absolutePath
        initPython(appContext, pythonDir)
        init_ytdlp(appContext, ytdlpDir)
        quickJsArg = buildQuickJsArg(baseDir)
        initialized = true
    }

    /**
     * Path to pass as the quickjs JS runtime. On API < 24 the dynamic linker prints "unused DT
     * entry" warnings to stderr when loading these API-24-built libs; yt-dlp's `qjs --help` version
     * probe merges stderr into stdout and matches the version anchored at the start of output, so the
     * warning hides "QuickJS version ..." and quickjs is rejected as unsupported. Wrap qjs in a tiny
     * script that drops stderr so the probe parses cleanly. (Exec from the data dir is permitted on
     * API 23; API 24+ doesn't emit the warnings, so it uses the qjs binary directly.)
     */
    private fun buildQuickJsArg(baseDir: File): String {
        val direct = quickJsPath!!.absolutePath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return direct
        return try {
            val wrapper = File(baseDir, "qjs")
            wrapper.writeText("#!/system/bin/sh\nexec $direct \"\$@\" 2>/dev/null\n")
            wrapper.setExecutable(true, false)
            wrapper.absolutePath
        } catch (e: Exception) {
            direct
        }
    }

    /** Launch spec for invoking a bundled native binary directly (outside yt-dlp). */
    data class ExecSpec(val binary: File, val environment: Map<String, String>)

    /**
     * Spec for exec'ing the bundled ffmpeg yourself (e.g. the local-HLS playback bridge in
     * ytdlp-kt). Mirrors the environment [execute] hands yt-dlp's own ffmpeg subprocesses, so a
     * direct invocation resolves the same libav + OpenSSL libs and CA bundle. Requires [init].
     */
    fun ffmpegExecSpec(): ExecSpec {
        check(initialized) { "YoutubeDL is not initialized" }
        return ExecSpec(
            ffmpegPath!!,
            mapOf(
                "LD_LIBRARY_PATH" to ENV_LD_LIBRARY_PATH!!,
                "SSL_CERT_FILE" to ENV_SSL_CERT_FILE!!,
                "PATH" to System.getenv("PATH") + ":" + binDir!!.absolutePath,
                "HOME" to ENV_PYTHONHOME!!,
                "TMPDIR" to TMPDIR,
            )
        )
    }

    @Throws(YoutubeDLException::class)
    fun init_ytdlp(appContext: Context, ytdlpDir: File) {
        if (!ytdlpDir.exists()) ytdlpDir.mkdirs()
        val ytdlpBinary = File(ytdlpDir, ytdlpBin)
        if (!ytdlpBinary.exists()) {
            try {
                val inputStream =
                    appContext.resources.openRawResource(R.raw.ytdlp) /* will be renamed to yt-dlp */
                FileUtils.copyInputStreamToFile(inputStream, ytdlpBinary)
            } catch (e: Exception) {
                FileUtils.deleteQuietly(ytdlpDir)
                throw YoutubeDLException("failed to initialize", e)
            }
        }
    }

    @Throws(YoutubeDLException::class)
    fun initPython(appContext: Context, pythonDir: File) {
        val pythonLib = File(binDir, pythonLibName)
        // using size of lib as version
        val pythonSize = pythonLib.length().toString()
        if (!pythonDir.exists() || shouldUpdatePython(appContext, pythonSize)) {
            FileUtils.deleteQuietly(pythonDir)
            pythonDir.mkdirs()
            try {
                unzip(pythonLib, pythonDir)
            } catch (e: Exception) {
                FileUtils.deleteQuietly(pythonDir)
                throw YoutubeDLException("failed to initialize", e)
            }
            updatePython(appContext, pythonSize)
        }
    }

    private fun shouldUpdatePython(appContext: Context, version: String): Boolean {
        return version != SharedPrefsHelper[appContext, pythonLibVersion]
    }

    private fun updatePython(appContext: Context, version: String) {
        update(appContext, pythonLibVersion, version)
    }

    private fun assertInit() {
        check(initialized) { "instance not initialized" }
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun getInfo(url: String): VideoInfo {
        val request = YoutubeDLRequest(url)
        return getInfo(request)
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun getInfo(request: YoutubeDLRequest): VideoInfo {
        request.addOption("--dump-json")
        val response = execute(request, null, null)
        val videoInfo: VideoInfo = try {
            objectMapper.readValue(response.out, VideoInfo::class.java)
        } catch (e: IOException) {
            throw YoutubeDLException("Unable to parse video information", e)
        } ?: throw YoutubeDLException("Failed to fetch video information")
        return videoInfo
    }

    /** The python process environment every bundled-runtime invocation needs. */
    private fun applyEnvironment(processBuilder: ProcessBuilder) {
        processBuilder.environment().apply {
            this["LD_LIBRARY_PATH"] = ENV_LD_LIBRARY_PATH
            // yt-dlp's Popen strips LD_LIBRARY_PATH from spawned subprocesses (PyInstaller workaround,
            // yt_dlp/utils/_utils.py) and only restores it from LD_LIBRARY_PATH_ORIG. Without this,
            // directly-invoked ffmpeg/ffprobe/quickjs can't resolve libav*/libc++_shared.so in the
            // unzipped usr/lib and appear as "exe versions: none" / "ffprobe not found" on API 23.
            this["LD_LIBRARY_PATH_ORIG"] = ENV_LD_LIBRARY_PATH
            this["SSL_CERT_FILE"] = ENV_SSL_CERT_FILE
            this["PATH"] = System.getenv("PATH") + ":" + binDir!!.absolutePath
            this["PYTHONHOME"] = ENV_PYTHONHOME
            this["HOME"] = ENV_PYTHONHOME
            this["TMPDIR"] = TMPDIR
        }
    }

    private fun ignoreErrors(request: YoutubeDLRequest, out: String): Boolean {
        return request.hasOption("--dump-json") && !out.isEmpty() && request.hasOption("--ignore-errors")
    }

    // ---- persistent resolve daemon ----
    // getInfo spawns a fresh python per call, paying interpreter + zipapp import (~1.5s measured
    // on-device) every time. The daemon keeps ONE python alive running [DAEMON_SCRIPT]'s
    // line-per-request loop, so a resolve costs only the network extraction. Scope: metadata
    // resolution only — downloads keep the process-per-call path. The script builds its
    // YoutubeDL params through yt-dlp's own CLI parser from the same flags [executeImpl] passes,
    // so daemon extraction matches the CLI path.

    private val daemonLock = Object()
    private var daemonProcess: Process? = null
    private var daemonIn: java.io.BufferedWriter? = null
    private var daemonOut: java.io.BufferedReader? = null

    /**
     * Resolve [url] to a [VideoInfo] via the persistent daemon (started on first use, restarted
     * transparently if it died). Throws [YoutubeDLException] for a real extraction failure (same
     * messages as the CLI path, so error mapping downstream keeps working) and
     * [YoutubeDLDaemonException] for daemon transport failures — fall back to [getInfo] on the
     * latter only.
     */
    @JvmOverloads
    @Throws(YoutubeDLException::class, YoutubeDLDaemonException::class)
    fun getInfoViaDaemon(url: String, timeoutMs: Long = DAEMON_REQUEST_TIMEOUT_MS): VideoInfo {
        require(url.isNotBlank() && !url.contains('\n')) { "invalid url" }
        synchronized(daemonLock) {
            try {
                ensureDaemonLocked()
                daemonIn!!.write(url)
                daemonIn!!.newLine()
                daemonIn!!.flush()
                val line = readDaemonLineLocked(timeoutMs)
                return when {
                    line.startsWith("OK ") -> objectMapper.readValue(
                        line.substring(3), VideoInfo::class.java
                    ) ?: throw YoutubeDLDaemonException("daemon returned unparseable info")
                    line.startsWith("ERR ") -> throw YoutubeDLException(
                        objectMapper.readValue(line.substring(4), String::class.java)
                    )
                    else -> throw IOException("daemon protocol error: $line")
                }
            } catch (e: IOException) {
                stopDaemonLocked()
                throw YoutubeDLDaemonException("resolve daemon failed", e)
            }
        }
    }

    /** Kill the daemon (it restarts on the next [getInfoViaDaemon]). */
    fun shutdownDaemon() {
        synchronized(daemonLock) { stopDaemonLocked() }
    }

    private fun daemonAlive(): Boolean = daemonProcess?.let {
        try {
            it.exitValue() // throws while running (Process.isAlive needs API 26)
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    } ?: false

    @Throws(IOException::class)
    private fun ensureDaemonLocked() {
        assertInit()
        if (daemonAlive()) return
        stopDaemonLocked()
        val script = File(baseDirPath!!, daemonScriptName)
        script.writeText(DAEMON_SCRIPT)
        val processBuilder = ProcessBuilder(
            pythonPath!!.absolutePath, script.absolutePath, ytdlpPath!!.absolutePath,
            "--dump-json", "--no-cache-dir",
            "--js-runtimes", "quickjs:${quickJsArg ?: quickJsPath!!.absolutePath}",
            "--ffmpeg-location", ffmpegPath!!.absolutePath,
        )
        applyEnvironment(processBuilder)
        val process = processBuilder.start()
        // Drain stderr forever so a chatty extractor can't fill the pipe and wedge python.
        Thread({
            try {
                process.errorStream.bufferedReader().forEachLine { }
            } catch (ignored: IOException) {
            }
        }, "ytdlp-daemon-err").apply { isDaemon = true }.start()
        daemonProcess = process
        daemonIn = process.outputStream.bufferedWriter()
        daemonOut = process.inputStream.bufferedReader()
        val hello = readDaemonLineLocked(DAEMON_START_TIMEOUT_MS)
        if (hello != "READY") {
            stopDaemonLocked()
            throw IOException("daemon failed to start: $hello")
        }
    }

    /** Bounded read: a wedged (alive but silent) daemon must not hang the caller forever. */
    @Throws(IOException::class)
    private fun readDaemonLineLocked(timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!daemonOut!!.ready()) {
            if (!daemonAlive()) throw IOException("resolve daemon died")
            if (System.currentTimeMillis() > deadline) throw IOException("resolve daemon timed out")
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted waiting for daemon")
            }
        }
        return daemonOut!!.readLine() ?: throw IOException("resolve daemon closed its output")
    }

    private fun stopDaemonLocked() {
        daemonProcess?.destroy()
        daemonProcess = null
        daemonIn = null
        daemonOut = null
    }

    private const val daemonScriptName = "resolve_daemon.py"
    private const val DAEMON_START_TIMEOUT_MS = 30_000L
    private const val DAEMON_REQUEST_TIMEOUT_MS = 90_000L

    /**
     * stdin: one URL per line. stdout: "READY" once, then per request exactly one line —
     * "OK <sanitized info json>" or "ERR <json-encoded message>". yt-dlp's own output is routed
     * to stderr so stdout carries only the protocol.
     */
    private val DAEMON_SCRIPT = """
        import sys, json

        sys.path.insert(0, sys.argv[1])
        import yt_dlp

        try:
            parsed = yt_dlp.parse_options(sys.argv[2:])
            ydl_opts = dict(parsed.ydl_opts)
        except Exception:
            ydl_opts = {}
        ydl_opts.update({
            "quiet": True,
            "skip_download": True,
            "noprogress": True,
            "logtostderr": True,
        })
        ydl_opts.pop("forcejson", None)
        ydl_opts.pop("forceprint", None)

        ydl = yt_dlp.YoutubeDL(ydl_opts)
        sys.stdout.write("READY\n")
        sys.stdout.flush()
        for line in sys.stdin:
            url = line.strip()
            if not url:
                continue
            try:
                info = ydl.extract_info(url, download=False)
                sys.stdout.write("OK " + json.dumps(ydl.sanitize_info(info)) + "\n")
            except Exception as e:
                sys.stdout.write("ERR " + json.dumps(str(e)) + "\n")
            sys.stdout.flush()
    """.trimIndent()

    fun destroyProcessById(id: String): Boolean {
        if (idProcessMap.containsKey(id)) {
            val p = idProcessMap[id]
            var alive = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                alive = p!!.isAlive
            }
            if (alive) {
                destroyChildProcesses(id)
                p?.destroy()
                idProcessMap.remove(id)
                return true
            }
        }
        return false
    }

    private fun destroyChildProcesses(id: String) : Boolean {
        try {
            val command = "pstree -p $id | grep -oP '\\(\\K[^\\)]+' | xargs kill"
            val processBuilder = ProcessBuilder("/system/bin/sh", "-c", command)
            val process = processBuilder.start()
            val res = process.waitFor()
            return res == 0
        }catch (e: Exception) {
            return false
        }
    }

    class CanceledException : Exception()

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun execute(
        request: YoutubeDLRequest,
        processId: String? = null,
        callback: ((Float, Long, String) -> Unit)? = null
    ): YoutubeDLResponse {
        return executeImpl(request, processId, false, callback)
    }

    @JvmOverloads
    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun execute(
        request: YoutubeDLRequest,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        callback: ((Float, Long, String) -> Unit)? = null
    ): YoutubeDLResponse {
        return executeImpl(request, processId, redirectErrorStream, callback)
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    private fun executeImpl(
        request: YoutubeDLRequest,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        callback: ((Float, Long, String) -> Unit)? = null
    ) : YoutubeDLResponse {
        assertInit()
        if (processId != null && idProcessMap.containsKey(processId)) throw YoutubeDLException("Process ID already exists")
        // disable caching unless explicitly requested
        if (!request.hasOption("--cache-dir") || request.getOption("--cache-dir") == null) {
            request.addOption("--no-cache-dir")
        }

        if (request.buildCommand().contains("libaria2c.so")) {
            request
                .addOption("--external-downloader-args", "aria2c:--summary-interval=1")
                .addOption(
                    "--external-downloader-args",
                    "aria2c:--ca-certificate=$ENV_SSL_CERT_FILE"
                )
        }

        request.addOption("--js-runtimes", "quickjs:${quickJsArg ?: quickJsPath!!.absolutePath}")

        /* Set ffmpeg location, See https://github.com/xibr/ytdlp-lazy/issues/1 */
        request.addOption("--ffmpeg-location", ffmpegPath!!.absolutePath)
        val youtubeDLResponse: YoutubeDLResponse
        val process: Process
        val exitCode: Int
        val outBuffer = StringBuffer() //stdout
        val errBuffer = StringBuffer() //stderr
        val startTime = System.currentTimeMillis()
        val args = request.buildCommand()
        val command: MutableList<String?> = ArrayList()
        command.addAll(listOf(pythonPath!!.absolutePath, ytdlpPath!!.absolutePath))
        command.addAll(args)
        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(redirectErrorStream)
        applyEnvironment(processBuilder)

        process = try {
            processBuilder.start()
        } catch (e: IOException) {
            throw YoutubeDLException(e)
        }
        if (processId != null) {
            idProcessMap[processId] = process
        }
        val outStream = process.inputStream
        val errStream = process.errorStream
        val stdOutProcessor = StreamProcessExtractor(outBuffer, outStream, callback)
        val stdErrProcessor = StreamGobbler(errBuffer, errStream)
        exitCode = try {
            stdOutProcessor.join()
            stdErrProcessor.join()
            process.waitFor()
        } catch (e: InterruptedException) {
            process.destroy()
            if (processId != null) idProcessMap.remove(processId)
            throw e
        }
        val out = outBuffer.toString()
        val err = errBuffer.toString()
        if (exitCode > 0) {
            if (processId != null && !idProcessMap.containsKey(processId))
                throw CanceledException()
            if (!ignoreErrors(request, out)) {
                idProcessMap.remove(processId)
                throw YoutubeDLException(err)
            }
        }
        idProcessMap.remove(processId)

        val elapsedTime = System.currentTimeMillis() - startTime
        youtubeDLResponse = YoutubeDLResponse(command, exitCode, elapsedTime, out, err)
        return youtubeDLResponse
    }

    @Synchronized
    @Throws(YoutubeDLException::class)
    fun updateYoutubeDL(
        appContext: Context,
        updateChannel: UpdateChannel = UpdateChannel.STABLE
    ): UpdateStatus? {
        assertInit()
        return try {
            YoutubeDLUpdater.update(appContext, updateChannel)
        } catch (e: IOException) {
            throw YoutubeDLException("failed to update youtube-dl", e)
        }
    }

    fun version(appContext: Context?): String? {
        return YoutubeDLUpdater.version(appContext)
    }

    fun versionName(appContext: Context?): String? {
        return YoutubeDLUpdater.versionName(appContext)
    }

    enum class UpdateStatus {
        DONE, ALREADY_UP_TO_DATE
    }

    open class UpdateChannel(val apiUrl: String) {
        object STABLE : UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
        object NIGHTLY :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest")
        object MASTER :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-master-builds/releases/latest")

        companion object {
            @JvmField
            val _STABLE: STABLE = STABLE

            @JvmField
            val _NIGHTLY: NIGHTLY = NIGHTLY

            @JvmField
            val _MASTER: MASTER = MASTER
        }
    }


    const val baseName = "youtubedl-android"
    private const val packagesRoot = "packages"
    private const val pythonBinName = "libpython.so"
    private const val pythonLibName = "libpython.zip.so"
    private const val pythonDirName = "python"
    private const val ffmpegDirName = "ffmpeg"
    private const val ffmpegBinName = "libffmpeg.so"
    private const val quickJsBinName = "libqjs.so"
    private const val aria2cDirName = "aria2c"
    const val ytdlpDirName = "yt-dlp"
    const val ytdlpBin = "yt-dlp"
    private const val pythonLibVersion = "pythonLibVersion"
    val objectMapper = ObjectMapper()

    @JvmStatic
    fun getInstance() = this
}