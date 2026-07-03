package com.afalphy.sylvakru

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object UsbExclusiveNative {
    init {
        System.loadLibrary("sylvakru_usb_exclusive")
    }

    external fun open(
        fd: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        feedbackEndpointAddress: Int,
        feedbackMaxPacketSize: Int,
        interfaceAlreadyClaimed: Boolean,
    ): String?

    external fun writePcm(bytes: ByteArray, length: Int): String?

    external fun writeIsoPackets(bytes: ByteArray, packetLengths: IntArray, packetCount: Int): String?

    external fun setIsoPacketSize(packetSize: Int)

    external fun feedbackFramesPerPacketQ16(): Int

    external fun transportTelemetry(): LongArray

    external fun setMaxPendingOutputUrbs(maxPendingUrbs: Int)

    external fun flushOutput(): String?

    external fun close()
}

private const val NATIVE_USB_EXCLUSIVE_STREAMING_ENABLED = true
private const val NATIVE_USB_EXCLUSIVE_DISABLED_MESSAGE =
    "真独占 USB 流式输出暂未启用，已回退到系统 USB 输出。"
private const val USB_RECIP_INTERFACE = 0x01
private const val USB_RECIP_ENDPOINT = 0x02

class UsbExclusiveAudioEngine(
    private val context: Context,
    private val emitState: (Map<String, Any?>) -> Unit,
    private val emitTelemetry: (Map<String, Any?>) -> Unit,
) {
    private val tag = "UsbExclusiveAudioEngine"
    private var worker: Thread? = null
    private var connection: UsbDeviceConnection? = null
    private val paused = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val pendingSeekMs = AtomicLong(-1L)

    @Volatile private var currentState = inactiveState()
    private var targetBufferMs = 200
    private var minimumBufferLevelMs: Long? = null
    private var lastTelemetryEmitMs = 0L
    private var lastTelemetryBufferMs: Long? = null
    private var zeroBufferUnderruns = 0L
    private var activePacketsPerSecond = 0

    // 热切换：切歌时设备与端点参数（时钟/声道/位深）不变就保留已打开的 USB
    // 会话，不重新 claim 接口/设 altsetting/配时钟，DAC 不会重新锁定（重新锁定
    // 就是切歌"咔嗒/电流"声的来源）。会话在停播后延迟关闭，短时间内没有新的
    // start 才真正拆链路。
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deferredCloseRunnable = Runnable { hardCloseSession("idle timeout") }
    private var sessionDeviceId: Int? = null
    private var sessionSampleRate: Int? = null
    private var sessionChannels: Int? = null
    private var sessionBitDepth: Int? = null
    private var sessionTarget: OutputTarget? = null
    @Volatile private var sessionBroken = false

    // DoP 标记相位跨曲目/跨空窗延续：打包器提升到会话级，写线程与空窗静音
    // 线程（互斥，先 join 再启动）共用。DAC 看到的 DoP 流一旦中断就会掉回
    // PCM 模式再重新锁定（指示灯蓝→绿→蓝），伴随继电器/模式切换的咔嗒声。
    @Volatile private var sessionDop: DopPacketizer? = null
    @Volatile private var sessionPacketizer: PcmIsoPacketizer? = null
    @Volatile private var workerEndedAtEof = false
    private val idleFillerRunning = AtomicBoolean(false)
    private var idleFillerThread: Thread? = null

    fun capabilities(usbManager: UsbManager, device: UsbDevice?): Map<String, Any?> {
        if (!NATIVE_USB_EXCLUSIVE_STREAMING_ENABLED) {
            return capability(
                available = false,
                permissionGranted = device?.let { usbManager.hasPermission(it) } ?: false,
                device = device,
                target = null,
                message = NATIVE_USB_EXCLUSIVE_DISABLED_MESSAGE,
            )
        }

        if (device == null) {
            return capability(
                available = false,
                permissionGranted = false,
                device = null,
                target = null,
                message = "No USB Audio Class output endpoint was found.",
            )
        }

        val target = findOutputTarget(device)
        return capability(
            available = target != null,
            permissionGranted = usbManager.hasPermission(device),
            device = device,
            target = target,
            message = if (target != null) {
                "USB exclusive endpoint is available."
            } else {
                "USB Audio device was found, but no isochronous OUT endpoint was exposed."
            },
        )
    }

    fun start(
        usbManager: UsbManager,
        device: UsbDevice?,
        arguments: Map<String, Any?>,
    ): Map<String, Any?> {
        // 停掉上一首的写线程但先不拆 USB 会话，后面参数匹配时热复用
        val sessionUsable = stopWorkerKeepingSession()
        if (connection != null) {
            // 下面任一校验失败提前返回时，兜底延迟关闭残留会话
            scheduleDeferredClose()
        }

        if (!NATIVE_USB_EXCLUSIVE_STREAMING_ENABLED) {
            return updateState(inactiveState(NATIVE_USB_EXCLUSIVE_DISABLED_MESSAGE))
        }

        if (device == null) {
            return updateState(inactiveState("No USB Audio Class device was found."))
        }
        if (!usbManager.hasPermission(device)) {
            return updateState(inactiveState("USB permission is required before exclusive playback."))
        }

        val filePath = arguments["filePath"] as? String
        val sourceFormat = (arguments["sourceFormat"] as? String)
            ?.lowercase(Locale.ROOT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (filePath.isNullOrBlank()) {
            return updateState(inactiveState("Exclusive playback requires a local audio file path."))
        }

        val file = File(filePath)
        if (!file.exists()) {
            return updateState(inactiveState("Exclusive playback file does not exist: $filePath"))
        }
        UsbDiagnostics.i(
            tag,
            "start exclusive playback file=${file.name}, sourceFormat=$sourceFormat, size=${file.length()}",
        )

        if (!isSupportedFile(filePath, sourceFormat)) {
            return updateState(inactiveState("Exclusive playback currently supports FLAC, WAV and DSD (.dsf/.dff) only."))
        }

        // 流式独占：file 是仍在下载增长的 .part 文件，下载完成时会被改名为正式
        // 缓存名（已打开的 fd 不受影响）。数据没跟上时按"暂停"处理，绝不断流爆音。
        val streaming = arguments["streaming"] == true

        // 该设备的 quirk 生效值（vid:pid 精确 → vid:* 厂商 → 默认）
        val quirk = UsbDacQuirks.forDevice(context, device.vendorId, device.productId)

        // DSD 文件目前独占链路只支持 DoP 输出；pcm 模式在 Dart 侧直接走共享路径，不会到这里
        val dsdMode = (arguments["dsdMode"] as? String)?.lowercase(Locale.ROOT)
        var dsdReader: DsdFileReader? = null
        if (isDsdFile(filePath, sourceFormat)) {
            if (dsdMode != "dop") {
                return updateState(
                    inactiveState(
                        "DSD over USB exclusive currently requires DoP mode (current: ${dsdMode ?: "unset"}).",
                    ),
                )
            }
            // DoP 对 DAC 是透明 PCM，描述符无法声明支持与否，先看 quirk 的明确判定
            if (quirk.dopSupported == false) {
                return updateState(
                    inactiveState(
                        "Device is marked as not supporting DoP (quirk" +
                            "${quirk.label?.let { ": $it" } ?: ""}).",
                    ),
                )
            }
            dsdReader = try {
                DsdFileReader.open(file, streaming)
            } catch (error: IOException) {
                return updateState(inactiveState(error.message ?: "Failed to parse DSD file."))
            }
            val multiple = dsdReader.dsdMultiple
            if (quirk.dopMaxDsd != null && multiple != null && multiple > quirk.dopMaxDsd) {
                dsdReader.close()
                return updateState(
                    inactiveState(
                        "DSD$multiple exceeds this device's DoP limit (DSD${quirk.dopMaxDsd}, quirk).",
                    ),
                )
            }
            UsbDiagnostics.i(
                tag,
                "DSD source rate=${dsdReader.sampleRate} (DSD${dsdReader.dsdMultiple ?: "?"}), " +
                    "channels=${dsdReader.channels}, container=${dsdReader.formatName}, " +
                    "dopFrameRate=${dsdReader.dopFrameRate}, quirk dop=${quirk.dopSupported}",
            )
        }

        // DoP 的输出帧率 = DSD 速率 ÷ 16，位深要求 24/32-bit slot（选中候选后再校验）
        val requestedSampleRate = dsdReader?.dopFrameRate
            ?: (arguments["sampleRate"] as? Number)?.toInt()
        val requestedBitDepth = if (dsdReader != null) {
            null
        } else {
            (arguments["bitDepth"] as? Number)?.toInt()
        }
        targetBufferMs = ((arguments["targetBufferMs"] as? Number)?.toInt() ?: 200).coerceIn(50, 5000)
        if (streaming) {
            // 流式播放用更深的 USB 水位吸收下载抖动
            targetBufferMs = maxOf(targetBufferMs, 1000)
        }
        minimumBufferLevelMs = null
        lastTelemetryEmitMs = 0L
        lastTelemetryBufferMs = null
        zeroBufferUnderruns = 0L
        activePacketsPerSecond = 0
        val requestedChannels = dsdReader?.channels ?: 2
        // 设备与端点参数都没变时热复用已打开的会话；DoP 复用还要确认既有
        // slot ≥ 24-bit（16-bit slot 无法承载 DoP 的 8 位标记 + 16 位数据）
        val reuseSession = sessionUsable &&
            connection != null &&
            sessionTarget != null &&
            sessionDeviceId == device.deviceId &&
            sessionSampleRate == requestedSampleRate &&
            sessionChannels == requestedChannels &&
            sessionBitDepth == requestedBitDepth &&
            (dsdReader == null || sessionTarget!!.usbBytesPerSample >= 3)
        val target: OutputTarget
        if (reuseSession) {
            target = sessionTarget!!
            mainHandler.removeCallbacks(deferredCloseRunnable)
            stopDopIdleFiller()
            if (!workerEndedAtEof && sessionDop == null) {
                // PCM 手动切歌丢在途 URB 保响应；DoP 一律不 flush——丢 URB 会
                // 瞬断 ISO 流让 DAC 掉出 DSD 模式再重锁（咔嗒声），旧缓冲
                // （约一个水位）放完无缝续上新曲，标记相位全程连续
                UsbExclusiveNative.flushOutput()?.let { flushError ->
                    UsbDiagnostics.w(tag, "flush on session reuse failed: $flushError")
                }
            }
            UsbDiagnostics.i(
                tag,
                "reusing exclusive USB session sampleRate=$requestedSampleRate, " +
                    "channels=$requestedChannels, bitDepth=${requestedBitDepth ?: "auto"}",
            )
        } else {
            hardCloseSession("device or stream parameters changed")
            val openedConnection = usbManager.openDevice(device)
                ?: run {
                    dsdReader?.close()
                    return updateState(inactiveState("Failed to open USB device for exclusive playback."))
                }
            val descriptors = openedConnection.rawDescriptors
            val streamingFormats = parseStreamingFormatInfo(descriptors)
            val resolvedTarget = findOutputTarget(
                device,
                streamingFormats = streamingFormats,
                sampleRate = requestedSampleRate,
                channels = requestedChannels,
                bitDepth = requestedBitDepth,
            )
                ?: run {
                    openedConnection.close()
                    dsdReader?.close()
                    return updateState(inactiveState("No isochronous USB Audio OUT endpoint was found."))
                }
            if (dsdReader != null && resolvedTarget.usbBytesPerSample < 3) {
                // 16-bit slot 无法承载 DoP 的 8 位标记 + 16 位数据
                openedConnection.close()
                dsdReader.close()
                return updateState(
                    inactiveState(
                        "DoP requires a 24/32-bit output slot, but the device only exposes " +
                            "${resolvedTarget.usbBitResolution ?: resolvedTarget.usbBytesPerSample * 8}-bit at " +
                            "${requestedSampleRate}Hz.",
                    ),
                )
            }
            UsbDiagnostics.i(
                tag,
                "exclusive target interface=${resolvedTarget.usbInterface.id}, alt=${resolvedTarget.alternateSetting}, " +
                    "endpoint=0x${resolvedTarget.endpoint.address.toString(16)}, maxPacket=${resolvedTarget.endpoint.maxPacketSize}, " +
                    "feedback=${resolvedTarget.feedbackEndpointLabel}, " +
                    "requestedSampleRate=$requestedSampleRate, requestedBitDepth=${requestedBitDepth ?: "auto"}, " +
                    "usbFormat=${resolvedTarget.formatInfo}",
            )

            val openError = UsbExclusiveNative.open(
                openedConnection.fileDescriptor,
                resolvedTarget.usbInterface.id,
                resolvedTarget.alternateSetting,
                resolvedTarget.endpoint.address,
                resolvedTarget.endpoint.maxPacketSize,
                resolvedTarget.feedbackEndpoint?.address ?: 0,
                resolvedTarget.feedbackEndpoint?.maxPacketSize ?: 0,
                false,
            )
            if (openError != null) {
                openedConnection.close()
                dsdReader?.close()
                return updateState(inactiveState(openError))
            }
            UsbDiagnostics.i(tag, "native USB exclusive endpoint opened.")

            if (requestedSampleRate != null) {
                val clockError = configureUsbAudioClock(
                    openedConnection,
                    device,
                    resolvedTarget,
                    requestedSampleRate,
                    quirk,
                )
                if (clockError != null) {
                    UsbExclusiveNative.close()
                    openedConnection.close()
                    dsdReader?.close()
                    return updateState(inactiveState(clockError))
                }
            }

            connection = openedConnection
            sessionDeviceId = device.deviceId
            sessionSampleRate = requestedSampleRate
            sessionChannels = requestedChannels
            sessionBitDepth = requestedBitDepth
            sessionTarget = resolvedTarget
            target = resolvedTarget
        }
        sessionBroken = false
        workerEndedAtEof = false
        paused.set(arguments["startPaused"] == true)
        stopped.set(false)
        pendingSeekMs.set(-1L)

        // DoP 激活时 state 报 DSD 语义：sampleRate=DSD 速率、bitDepth=1、format 带 (DoP) 后缀
        val initialState = mapOf(
            "active" to true,
            "playing" to !paused.get(),
            "positionMs" to 0,
            "durationMs" to dsdReader?.durationMs,
            "sampleRate" to (dsdReader?.sampleRate ?: arguments["sampleRate"]),
            "bitDepth" to if (dsdReader != null) 1 else arguments["bitDepth"],
            "format" to if (dsdReader != null) {
                "${dsdReader.formatName}(DoP)"
            } else {
                sourceFormat ?: file.extension.lowercase(Locale.ROOT)
            },
            "message" to "USB exclusive playback prepared.",
        )
        updateState(initialState)
        emitTransportTelemetry(target.packetsPerSecond, force = true)

        val reader = dsdReader
        worker = Thread({
            if (reader != null) {
                dopDecodeAndWrite(reader, target, if (streaming) file else null)
            } else {
                decodeAndWrite(file, target, streaming)
            }
        }, "SylvakruUsbExclusive")
        worker?.start()
        return currentState
    }

    fun pause(): Map<String, Any?> {
        UsbDiagnostics.i(tag, "pause exclusive playback.")
        paused.set(true)
        return updateState(currentState + mapOf("playing" to false, "message" to "Paused."))
    }

    fun resume(): Map<String, Any?> {
        if (currentState["active"] != true) {
            UsbDiagnostics.w(tag, "resume ignored because exclusive playback is not active: $currentState")
            return updateState(inactiveState("No exclusive playback is active."))
        }
        UsbDiagnostics.i(
            tag,
            "resume exclusive playback position=${currentState["positionMs"]}, wasPaused=${paused.get()}",
        )
        paused.set(false)
        return updateState(currentState + mapOf("playing" to true, "message" to "Playing."))
    }

    fun seek(positionMs: Long): Map<String, Any?> {
        if (currentState["active"] != true) {
            UsbDiagnostics.w(tag, "seek ignored because exclusive playback is not active: $currentState")
            return updateState(inactiveState("No exclusive playback is active."))
        }
        val safePositionMs = positionMs.coerceAtLeast(0L)
        pendingSeekMs.set(safePositionMs)
        return updateState(
            currentState + mapOf(
                "message" to "Seeking.",
                "positionMs" to safePositionMs,
            ),
        )
    }

    fun setTargetBufferMs(value: Int): Map<String, Any?> {
        targetBufferMs = value.coerceIn(50, 5000)
        applyNativeTargetBuffer(activePacketsPerSecond)
        if (activePacketsPerSecond > 0) {
            emitTransportTelemetry(activePacketsPerSecond, force = true)
        }
        return currentState + mapOf("targetBufferMs" to targetBufferMs)
    }

    fun stop(): Map<String, Any?> {
        val keepSession = stopWorkerKeepingSession()
        if (keepSession && connection != null) {
            if (!workerEndedAtEof && sessionDop == null) {
                // PCM 手动停止/切歌丢在途 URB 让停止即时生效；DoP 一律不
                // flush（会瞬断 ISO 流让 DAC 掉锁），旧缓冲放完由静音接续
                UsbExclusiveNative.flushOutput()?.let { flushError ->
                    UsbDiagnostics.w(tag, "flush on stop failed: $flushError")
                }
            }
            // 空窗期持续垫 DoP 静音直到下一首接管或延迟关闭（自然播完时
            // 写线程退出前已启动，重复调用无副作用）
            startDopIdleFiller()
            scheduleDeferredClose()
        }
        return updateState(inactiveState("USB exclusive playback stopped."))
    }

    fun release(): Map<String, Any?> {
        stopWorkerKeepingSession()
        hardCloseSession("release")
        return updateState(inactiveState("USB exclusive playback stopped."))
    }

    // 停写线程；返回 true 表示线程干净退出、USB 会话仍可热复用
    private fun stopWorkerKeepingSession(): Boolean {
        stopped.set(true)
        paused.set(false)
        pendingSeekMs.set(-1L)
        val thread = worker
        worker = null
        if (thread == null || thread == Thread.currentThread()) {
            return !sessionBroken && connection != null
        }
        thread.join(800)
        if (thread.isAlive) {
            // 收不回来（多半阻塞在 native 写的水位回收上），只能硬关让写立即返回
            UsbDiagnostics.w(tag, "exclusive worker join timeout, forcing session close")
            hardCloseSession("worker join timeout")
            thread.join(500)
            return false
        }
        return !sessionBroken && connection != null
    }

    private fun scheduleDeferredClose() {
        mainHandler.removeCallbacks(deferredCloseRunnable)
        mainHandler.postDelayed(deferredCloseRunnable, 4000L)
    }

    // 空窗期（切歌/停止后）持续垫 DoP 静音：与写线程互斥（先 join 再启动），
    // 标记相位由 sessionDop 延续，DAC 始终收到合法 DoP 流，不掉回 PCM 模式
    private fun startDopIdleFiller() {
        val dop = sessionDop ?: return
        val packetizer = sessionPacketizer ?: return
        val frameRate = sessionSampleRate ?: return
        if (idleFillerThread?.isAlive == true) {
            return
        }
        idleFillerRunning.set(true)
        UsbDiagnostics.i(tag, "DoP idle filler started at $frameRate frames/s")
        val thread = Thread({
            // 单次约 10ms 的量，写满水位由 native 阻塞回收自然限速
            val frames = maxOf(1, frameRate / 100)
            try {
                while (idleFillerRunning.get()) {
                    packetizer.write(dop.encodeSilence(frames))
                }
            } catch (error: Throwable) {
                // 会话已断（拔线/被关），交给延迟关闭兜底
                UsbDiagnostics.w(tag, "DoP idle filler exit: ${error.message}")
            }
        }, "SylvakruUsbDopIdleFill")
        idleFillerThread = thread
        thread.start()
    }

    private fun stopDopIdleFiller() {
        idleFillerRunning.set(false)
        val thread = idleFillerThread ?: return
        idleFillerThread = null
        if (thread != Thread.currentThread()) {
            thread.join(500)
        }
    }

    private fun hardCloseSession(reason: String) {
        if (connection == null && sessionTarget == null) {
            return
        }
        UsbDiagnostics.i(tag, "close exclusive USB session: $reason")
        mainHandler.removeCallbacks(deferredCloseRunnable)
        stopDopIdleFiller()
        sessionDop = null
        sessionPacketizer = null
        sessionTarget = null
        sessionDeviceId = null
        sessionSampleRate = null
        sessionChannels = null
        sessionBitDepth = null
        UsbExclusiveNative.close()
        connection?.close()
        connection = null
        activePacketsPerSecond = 0
    }

    private fun emitTransportTelemetry(packetsPerSecond: Int, force: Boolean = false) {
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && nowMs - lastTelemetryEmitMs < 100) {
            return
        }
        lastTelemetryEmitMs = nowMs

        val nativeTelemetry = UsbExclusiveNative.transportTelemetry()
        val pendingIsoPackets = nativeTelemetry.getOrNull(0) ?: 0L
        val totalIsoPackets = nativeTelemetry.getOrNull(1) ?: 0L
        val pendingUrbs = nativeTelemetry.getOrNull(2) ?: 0L
        val nativeIsoErrors = nativeTelemetry.getOrNull(3) ?: 0L
        val bufferLevelMs = if (packetsPerSecond > 0) {
            (pendingIsoPackets * 1000L) / packetsPerSecond
        } else {
            0L
        }
        val active = currentState["active"] == true

        if (active && lastTelemetryBufferMs != null && lastTelemetryBufferMs!! > 0 && bufferLevelMs == 0L) {
            zeroBufferUnderruns += 1
        }
        lastTelemetryBufferMs = bufferLevelMs

        if (active && bufferLevelMs > 0) {
            minimumBufferLevelMs = minimumBufferLevelMs?.let { minOf(it, bufferLevelMs) } ?: bufferLevelMs
        }

        emitTelemetry(
            mapOf(
                "active" to active,
                "bufferLevelMs" to if (active) bufferLevelMs else 0L,
                "minimumBufferLevelMs" to minimumBufferLevelMs,
                "targetBufferMs" to targetBufferMs,
                "isoPacketCount" to totalIsoPackets,
                "pendingUrbs" to pendingUrbs,
                "underrunCount" to (nativeIsoErrors + zeroBufferUnderruns),
                "updatedAtMs" to nowMs,
            ),
        )
    }

    private fun emitInactiveTelemetry() {
        lastTelemetryBufferMs = null
        emitTelemetry(
            mapOf(
                "active" to false,
                "bufferLevelMs" to 0,
                "minimumBufferLevelMs" to null,
                "targetBufferMs" to targetBufferMs,
                "isoPacketCount" to 0,
                "pendingUrbs" to 0,
                "underrunCount" to 0,
                "updatedAtMs" to SystemClock.elapsedRealtime(),
            ),
        )
    }

    private fun applyNativeTargetBuffer(packetsPerSecond: Int) {
        if (packetsPerSecond <= 0) {
            return
        }
        val packetCount = ((targetBufferMs.toLong() * packetsPerSecond) + 999L) / 1000L
        val maxPendingUrbs = ((packetCount + 15L) / 16L).coerceIn(8L, 512L).toInt()
        UsbExclusiveNative.setMaxPendingOutputUrbs(maxPendingUrbs)
        UsbDiagnostics.i(
            tag,
            "USB target buffer targetMs=$targetBufferMs packetsPerSecond=$packetsPerSecond " +
                "maxPendingUrbs=$maxPendingUrbs",
        )
    }

    private fun decodeAndWrite(file: File, target: OutputTarget, streaming: Boolean = false) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var dataSource: GrowingFileDataSource? = null
        var sawInputEos = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()
        val startMs = SystemClock.elapsedRealtime()
        var lastPositionEmitMs = 0L
        var packetizer: PcmIsoPacketizer? = null

        try {
            if (streaming) {
                dataSource = GrowingFileDataSource(file, RandomAccessFile(file, "r"))
                extractor.setDataSource(dataSource)
            } else {
                extractor.setDataSource(file.absolutePath)
            }
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                emitError("No audio track was found in ${file.name}.")
                return
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime.isNullOrBlank()) {
                emitError("Audio MIME type is missing.")
                return
            }

            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1000
            } else {
                null
            }
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                null
            }
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                null
            }

            UsbDiagnostics.i(
                tag,
                "decoder input format=$format, mime=$mime, sampleRate=$sampleRate, channels=$channels, " +
                    "durationMs=$durationMs, endpointInterval=${target.endpoint.interval}",
            )

            if (mime == "audio/raw") {
                writeRawPcm(extractor, file, format, sampleRate, channels, durationMs, target, startMs)
                return
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            if (sampleRate != null && channels != null) {
                packetizer = createPacketizer(sampleRate, channels, 16, target)
            }

            updateState(
                currentState + mapOf(
                    "active" to true,
                    "playing" to !paused.get(),
                    "durationMs" to durationMs,
                    "sampleRate" to sampleRate,
                    "bitDepth" to (target.usbBitResolution ?: 16),
                    "message" to "USB exclusive decoding ${file.name} to ${target.endpointLabel}, channels=$channels.",
                ),
            )

            while (!stopped.get() && !outputDone) {
                val wasPaused = paused.get()
                if (wasPaused) {
                    UsbDiagnostics.i(tag, "exclusive worker waiting because playback is paused.")
                }
                while (paused.get() && !stopped.get()) {
                    Thread.sleep(25)
                }
                if (wasPaused && !stopped.get()) {
                    UsbDiagnostics.i(tag, "exclusive worker resumed.")
                }
                if (stopped.get()) break

                consumePendingSeekMs()?.let { seekMs ->
                    val seekUs = seekMs * 1000
                    UsbDiagnostics.i(tag, "exclusive decoder seek to ${seekMs}ms.")
                    // 丢弃在途 URB，seek 立即生效而不是等旧缓冲放完
                    UsbExclusiveNative.flushOutput()?.let { flushError ->
                        UsbDiagnostics.w(tag, "flush before seek failed: $flushError")
                    }
                    extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    packetizer?.reset()
                    sawInputEos = false
                    outputDone = false
                    lastPositionEmitMs = -1L
                    updateState(
                        currentState + mapOf(
                            "active" to true,
                            "playing" to !paused.get(),
                            "positionMs" to seekMs,
                            "message" to "Seeked.",
                        ),
                    )
                }

                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer != null) {
                            extractor.readSampleData(inputBuffer, 0)
                        } else {
                            -1
                        }
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0) {
                        val writer = packetizer
                            ?: createPacketizer(
                                sampleRate ?: 48000,
                                channels ?: 2,
                                16,
                                target,
                            ).also { packetizer = it }
                        writeOutputBuffer(outputBuffer, info, writer)
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    val positionMs = if (info.presentationTimeUs > 0) {
                        info.presentationTimeUs / 1000
                    } else {
                        SystemClock.elapsedRealtime() - startMs
                    }
                    if (positionMs - lastPositionEmitMs >= 250) {
                        lastPositionEmitMs = positionMs
                        updateState(
                            currentState + mapOf(
                                "active" to true,
                                "playing" to !paused.get(),
                                "positionMs" to positionMs,
                            ),
                        )
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = codec.outputFormat
                    val outputSampleRate = if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else {
                        null
                    }
                    val pcmEncoding = if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ) {
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        null
                    }
                    val outputChannels = if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else {
                        channels
                    }
                    val outputBitDepth = bitDepthFromPcmEncoding(pcmEncoding)
                    UsbDiagnostics.i(
                        tag,
                        "decoder output format changed: $outputFormat, pcmEncoding=$pcmEncoding, " +
                            "decoderBitDepth=$outputBitDepth, usbBitDepth=${target.usbBitResolution}",
                    )
                    if (outputSampleRate != null && outputChannels != null) {
                        packetizer?.flush()
                        packetizer = createPacketizer(
                            outputSampleRate,
                            outputChannels,
                            outputBitDepth,
                            target,
                        )
                    }
                    updateState(
                        currentState + mapOf(
                            "sampleRate" to outputSampleRate,
                            "bitDepth" to (target.usbBitResolution ?: outputBitDepth),
                        ),
                    )
                }
            }

            UsbDiagnostics.i(tag, "exclusive decode reached end of stream, flushing remainder.")
            packetizer?.flush()
            if (!stopped.get()) {
                workerEndedAtEof = true
                updateState(inactiveState("USB exclusive playback completed."))
            }
        } catch (error: Throwable) {
            UsbDiagnostics.w("UsbExclusiveAudioEngine", "Exclusive playback failed.", error)
            sessionBroken = true
            emitError(error.message ?: "USB exclusive playback failed.")
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            codec?.release()
            extractor.release()
            runCatching { dataSource?.close() }
            if (sessionBroken) {
                hardCloseSession("decode worker failed")
            } else {
                // 会话留给下一首热复用，短时间内没有新的 start 再关
                scheduleDeferredClose()
            }
        }
    }

    /**
     * 流式独占的数据源：文件仍在下载增长中。读到未下载区域时等数据，
     * 解码线程随之停在 readSampleData 上，USB 端表现与用户暂停一致（不爆音）；
     * 恢复要求多攒一段余量，避免走走停停。下载完成时 Dart 侧把 .part 改名为
     * 正式缓存名，已打开的 fd 不受影响，据"原路径消失"判断下载结束。
     */
    private inner class GrowingFileDataSource(
        private val partFile: File,
        private val input: RandomAccessFile,
    ) : MediaDataSource() {
        private val rebufferBytes = 256L * 1024L
        private var bufferingLogged = false

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size <= 0) {
                return 0
            }
            var required = position + size
            while (!stopped.get()) {
                val complete = !partFile.exists()
                val length = input.length()
                if (complete || length >= required) {
                    if (position >= length) {
                        return -1
                    }
                    input.seek(position)
                    return input.read(buffer, offset, minOf(size.toLong(), length - position).toInt())
                }
                if (!bufferingLogged) {
                    bufferingLogged = true
                    UsbDiagnostics.i(
                        tag,
                        "streaming source buffering: need=${position + size}, have=$length",
                    )
                }
                required = position + size + rebufferBytes
                Thread.sleep(50)
            }
            return -1
        }

        override fun getSize(): Long {
            // 下载没结束时总大小未知；MediaExtractor 对顺序解码可以接受 -1
            return if (partFile.exists()) -1L else input.length()
        }

        override fun close() {
            input.close()
        }
    }

    /**
     * DSD 文件的 DoP 输出主循环：DsdFileReader → DopPacketizer → 现有 PcmIsoPacketizer。
     * DoP 帧被当作普通 24-bit PCM 打包（帧率 = DSD 速率 ÷ 16），24→32 slot 的高位对齐
     * 恰好满足 DoP 低 8 位补零的要求，传输层零改动。
     * 关键约束：DoP 路径上不允许任何 DSP（音量/抖动/重采样都会破坏标记、输出全幅噪声）；
     * 暂停时必须持续发 DoP 封装的 0x69 静音——发 PCM 零或停流会让 DAC 掉出 DSD 模式并可能爆音。
     */
    private fun dopDecodeAndWrite(reader: DsdFileReader, target: OutputTarget, streamingFile: File? = null) {
        var lastPositionEmitMs = 0L
        // 流式下载中的缓冲恢复水位：饥饿后攒到该长度才继续读，避免走走停停
        var streamingResumeBytes = 0L
        var streamingBufferingLogged = false
        // 标记相位跨曲目延续：会话存活期间复用同一 DopPacketizer 与打包器
        val dop = sessionDop ?: DopPacketizer(reader.channels).also { sessionDop = it }
        try {
            val packetizer = sessionPacketizer
                ?.also {
                    activePacketsPerSecond = target.packetsPerSecond
                    applyNativeTargetBuffer(target.packetsPerSecond)
                }
                ?: createPacketizer(reader.dopFrameRate, reader.channels, 24, target)
                    .also { sessionPacketizer = it }
            updateState(
                currentState + mapOf(
                    "active" to true,
                    "playing" to !paused.get(),
                    "durationMs" to reader.durationMs,
                    "sampleRate" to reader.sampleRate,
                    "bitDepth" to 1,
                    "message" to "USB exclusive DoP streaming DSD${reader.dsdMultiple ?: ""} " +
                        "(${reader.formatName}) to ${target.endpointLabel}.",
                ),
            )

            // 单次读写约 10 ms 的量；写满水位后由 native 阻塞回收自然限速
            val silenceFramesPerWrite = maxOf(1, reader.dopFrameRate / 100)
            val buffer = ByteArray(reader.channels * 2 * silenceFramesPerWrite)

            while (!stopped.get()) {
                consumePendingSeekMs()?.let { seekMs ->
                    // DoP seek 不 flush 也不复位：丢 URB 会瞬断 ISO 流让 DAC
                    // 掉出 DSD 模式再重锁（就是 seek 咔嗒声）。旧缓冲（约一个
                    // 水位）放完无缝续上新位置，标记相位全程连续；先把不足
                    // 一帧的余量补齐保持帧对齐
                    packetizer.write(dop.drain())
                    val actualMs = reader.seekTo(seekMs)
                    lastPositionEmitMs = -1L
                    updateState(
                        currentState + mapOf(
                            "active" to true,
                            "playing" to !paused.get(),
                            "positionMs" to actualMs,
                            "message" to "Seeked.",
                        ),
                    )
                }

                if (paused.get()) {
                    packetizer.write(dop.encodeSilence(silenceFramesPerWrite))
                    continue
                }

                // 流式下载：数据没跟上时垫 DoP 静音等下载，保持 DAC 停留在 DSD
                // 模式（DoP 绝不能断流，断点样本也不能修改，只能发 0x69）
                if (streamingFile != null && streamingFile.exists()) {
                    val length = streamingFile.length()
                    val ready = reader.canReadAt(length) &&
                        (streamingResumeBytes == 0L || length >= streamingResumeBytes)
                    if (!ready) {
                        if (streamingResumeBytes == 0L) {
                            streamingResumeBytes = length + 256L * 1024L
                        }
                        if (!streamingBufferingLogged) {
                            streamingBufferingLogged = true
                            UsbDiagnostics.i(
                                tag,
                                "DoP streaming buffering at ${reader.positionMs}ms, have=$length",
                            )
                        }
                        packetizer.write(dop.encodeSilence(silenceFramesPerWrite))
                        continue
                    }
                    streamingResumeBytes = 0L
                    streamingBufferingLogged = false
                }

                val count = reader.read(buffer)
                if (count < 0) {
                    // 结尾不足一帧的余量补 0x69，再垫约 200ms 静音把尾部完整送出，
                    // 同时盖住自动切歌的空窗，DAC 不掉出 DSD 模式
                    packetizer.write(dop.drain())
                    packetizer.write(dop.encodeSilence(silenceFramesPerWrite * 20))
                    packetizer.flush()
                    break
                }
                packetizer.write(dop.encode(buffer, count))

                val positionMs = reader.positionMs
                if (positionMs - lastPositionEmitMs >= 250) {
                    lastPositionEmitMs = positionMs
                    updateState(
                        currentState + mapOf(
                            "active" to true,
                            "playing" to !paused.get(),
                            "positionMs" to positionMs,
                        ),
                    )
                }
            }

            UsbDiagnostics.i(tag, "exclusive DoP playback reached end of stream.")
            if (!stopped.get()) {
                workerEndedAtEof = true
                updateState(inactiveState("USB exclusive playback completed."))
            }
        } catch (error: Throwable) {
            UsbDiagnostics.w(tag, "Exclusive DoP playback failed.", error)
            sessionBroken = true
            emitError(error.message ?: "USB exclusive DoP playback failed.")
        } finally {
            runCatching { reader.close() }
            if (sessionBroken) {
                hardCloseSession("DoP worker failed")
            } else {
                // 会话留给下一首热复用；自然播完立即接上空窗静音填充，
                // 短时间内没有新的 start 再由延迟关闭拆链路
                if (workerEndedAtEof) {
                    startDopIdleFiller()
                }
                scheduleDeferredClose()
            }
        }
    }

    private fun writeRawPcm(
        extractor: MediaExtractor,
        file: File,
        format: MediaFormat,
        sampleRate: Int?,
        channels: Int?,
        durationMs: Long?,
        target: OutputTarget,
        startMs: Long,
    ) {
        if (sampleRate == null || channels == null) {
            emitError("Raw PCM stream is missing sample rate or channel count.")
            return
        }

        val pcmEncoding = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            format.containsKey(MediaFormat.KEY_PCM_ENCODING)
        ) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            null
        }
        val containerBitDepth = if (format.containsKey("bits-per-sample")) {
            format.getInteger("bits-per-sample")
        } else {
            null
        }
        val sourceBitDepth = pcmEncoding
            ?.let { bitDepthFromPcmEncoding(it) }
            ?: containerBitDepth
            ?: 16
        val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(4096)
        } else {
            64 * 1024
        }
        val buffer = ByteBuffer.allocate(maxInputSize)
        val packetizer = createPacketizer(sampleRate, channels, sourceBitDepth, target)
        var lastPositionEmitMs = 0L
        var lastSampleTimeUs: Long? = null
        var rawChunkLogCount = 0

        UsbDiagnostics.i(
            tag,
            "raw PCM direct path sampleRate=$sampleRate, channels=$channels, " +
                "sourceBitDepth=$sourceBitDepth, pcmEncoding=$pcmEncoding, " +
                "containerBitDepth=$containerBitDepth, maxInputSize=$maxInputSize, " +
                "targetBitDepth=${target.usbBitResolution}",
        )
        updateState(
            currentState + mapOf(
                "active" to true,
                "playing" to !paused.get(),
                "durationMs" to durationMs,
                "sampleRate" to sampleRate,
                "bitDepth" to (target.usbBitResolution ?: sourceBitDepth),
                "message" to "USB exclusive streaming raw PCM ${file.name} to ${target.endpointLabel}.",
            ),
        )

        while (!stopped.get()) {
            val wasPaused = paused.get()
            if (wasPaused) {
                UsbDiagnostics.i(tag, "exclusive worker waiting because playback is paused.")
            }
            while (paused.get() && !stopped.get()) {
                Thread.sleep(25)
            }
            if (wasPaused && !stopped.get()) {
                UsbDiagnostics.i(tag, "exclusive worker resumed.")
            }
            if (stopped.get()) break

            consumePendingSeekMs()?.let { seekMs ->
                val seekUs = seekMs * 1000
                UsbDiagnostics.i(tag, "exclusive raw PCM seek to ${seekMs}ms.")
                // 丢弃在途 URB，seek 立即生效而不是等旧缓冲放完
                UsbExclusiveNative.flushOutput()?.let { flushError ->
                    UsbDiagnostics.w(tag, "flush before seek failed: $flushError")
                }
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                packetizer.reset()
                lastPositionEmitMs = -1L
                lastSampleTimeUs = null
                updateState(
                    currentState + mapOf(
                        "active" to true,
                        "playing" to !paused.get(),
                        "positionMs" to seekMs,
                        "message" to "Seeked.",
                    ),
                )
            }

            buffer.clear()
            val sampleTimeUs = extractor.sampleTime
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                break
            }
            val data = ByteArray(sampleSize)
            buffer.position(0)
            buffer.limit(sampleSize)
            buffer.get(data)
            if (rawChunkLogCount < 12) {
                val frameBytes = channels * bytesPerSampleForBitDepth(sourceBitDepth)
                val frames = if (frameBytes > 0) sampleSize / frameBytes else 0
                val deltaUs = lastSampleTimeUs?.let { sampleTimeUs - it }
                UsbDiagnostics.i(
                    tag,
                    "raw PCM chunk size=$sampleSize, sampleTimeUs=$sampleTimeUs, " +
                        "deltaUs=${deltaUs ?: "n/a"}, frames=$frames, frameBytes=$frameBytes, " +
                        "sourceBitDepth=$sourceBitDepth",
                )
                rawChunkLogCount++
            }
            lastSampleTimeUs = sampleTimeUs
            packetizer.write(data)

            val positionMs = if (sampleTimeUs > 0) {
                sampleTimeUs / 1000
            } else {
                SystemClock.elapsedRealtime() - startMs
            }
            if (positionMs - lastPositionEmitMs >= 250) {
                lastPositionEmitMs = positionMs
                updateState(
                    currentState + mapOf(
                        "active" to true,
                        "playing" to !paused.get(),
                        "positionMs" to positionMs,
                    ),
                )
            }
            extractor.advance()
        }

        UsbDiagnostics.i(tag, "exclusive raw PCM reached end of stream, flushing remainder.")
        packetizer.flush()
        if (!stopped.get()) {
            workerEndedAtEof = true
            updateState(inactiveState("USB exclusive playback completed."))
        }
    }

    private fun createPacketizer(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        target: OutputTarget,
    ): PcmIsoPacketizer {
        val inputBytesPerSample = bytesPerSampleForBitDepth(bitDepth)
        val usbBytesPerSample = target.usbBytesPerSample
        val usbBitResolution = target.usbBitResolution ?: (usbBytesPerSample * 8)
        UsbDiagnostics.i(
            tag,
            "USB PCM packetizer sampleRate=$sampleRate, channels=$channels, " +
                "decoderBitDepth=$bitDepth, inputBytesPerSample=$inputBytesPerSample, " +
                "usbBytesPerSample=$usbBytesPerSample, usbBitResolution=$usbBitResolution, " +
                "packetsPerSecond=${target.packetsPerSecond}, endpointInterval=${target.endpoint.interval}, " +
                "format=${target.formatInfo}",
        )
        val packetBytes = requiredIsoPacketBytes(
            sampleRate,
            target.packetsPerSecond,
            channels,
            usbBytesPerSample,
        )
        activePacketsPerSecond = target.packetsPerSecond
        applyNativeTargetBuffer(target.packetsPerSecond)
        UsbExclusiveNative.setIsoPacketSize(packetBytes)
        val outputIntervalMicroframes = isoIntervalMicroframes(target.endpoint.interval)
        val feedbackOutputPacketDivisor = target.feedbackEndpoint?.let {
            val feedbackIntervalMicroframes = isoIntervalMicroframes(it.interval)
            UsbDiagnostics.i(
                tag,
                "USB feedback intervals outputMicroframes=$outputIntervalMicroframes, " +
                    "feedbackMicroframes=$feedbackIntervalMicroframes",
            )
            1
        } ?: 1
        UsbDiagnostics.i(
            tag,
            "USB feedback scaling outputIntervalMicroframes=$outputIntervalMicroframes, " +
                "feedbackDivisor=$feedbackOutputPacketDivisor, feedback=${target.feedbackEndpointLabel}",
        )
        return PcmIsoPacketizer(
            sampleRate,
            target.packetsPerSecond,
            channels,
            inputBytesPerSample,
            bitDepth,
            usbBytesPerSample,
            usbBitResolution,
            feedbackOutputPacketDivisor,
            feedbackFramesPerPacketQ16 = target.feedbackEndpoint?.let {
                { UsbExclusiveNative.feedbackFramesPerPacketQ16() }
            },
        ) { data, packetLengths, packetCount ->
            val error = UsbExclusiveNative.writeIsoPackets(data, packetLengths, packetCount)
            if (error != null) {
                throw IllegalStateException(error)
            }
            emitTransportTelemetry(target.packetsPerSecond)
        }
    }

    /**
     * 配置 DAC 时钟到 [sampleRate]。返回 null 表示可以继续；返回非 null 的原因字符串表示
     * 校验到时钟与请求不一致（GET_CUR 读回一个有效且不同的采样率），调用方应据此回退系统输出。
     * 注意：很多 DAC（如 Macaron）SET_CUR 成功但 GET_CUR 恒返回 0，属于“不报告实际值”，
     * 不能当成不一致——否则会把本可正常独占的设备误判成失败。只有读回“有效非零且不同”才判失败。
     */
    private fun configureUsbAudioClock(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        target: OutputTarget,
        sampleRate: Int,
        quirk: DacQuirk = DacQuirk(),
    ): String? {
        val controlInterface = findAudioControlInterface(device)
        val controlInterfaceNumber = controlInterface?.id ?: target.usbInterface.id
        val clockSourceId = findUac2ClockSourceId(
            connection.rawDescriptors,
            streamingInterfaceNumber = target.usbInterface.id,
            streamingAlternateSetting = target.alternateSetting,
        )

        val claimedControl = controlInterface?.let {
            runCatching { connection.claimInterface(it, true) }.getOrDefault(false)
        } == true
        try {
            if (clockSourceId != null) {
                readUac2ClockSampleRate(
                    connection,
                    clockSourceId,
                    controlInterfaceNumber,
                    "before",
                )
                val data = byteArrayOf(
                    (sampleRate and 0xff).toByte(),
                    ((sampleRate ushr 8) and 0xff).toByte(),
                    ((sampleRate ushr 16) and 0xff).toByte(),
                    ((sampleRate ushr 24) and 0xff).toByte(),
                )
                val result = connection.controlTransfer(
                    UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIP_INTERFACE,
                    0x01,
                    0x01 shl 8,
                    (clockSourceId shl 8) or controlInterfaceNumber,
                    data,
                    data.size,
                    1000,
                )
                UsbDiagnostics.i(
                    tag,
                    "UAC2 clock SET_CUR sampleRate=$sampleRate, clockSourceId=$clockSourceId, " +
                    "controlInterface=$controlInterfaceNumber, result=$result",
                )
                // quirk：部分 DAC SET_CUR 后需要几十 ms 才锁定新时钟
                if (quirk.clockSetCurDelayMs > 0) {
                    Thread.sleep(quirk.clockSetCurDelayMs.toLong())
                }
                if (quirk.clockSkipGetCurValidation) {
                    // quirk：个别设备 GET_CUR 返回垃圾但 SET_CUR 实际生效
                    return null
                }
                val readBack = readUac2ClockSampleRate(
                    connection,
                    clockSourceId,
                    controlInterfaceNumber,
                    "after",
                )
                if (readBack != null && readBack > 0 && readBack != sampleRate) {
                    UsbDiagnostics.w(
                        tag,
                        "UAC2 clock mismatch: requested=$sampleRate readBack=$readBack; " +
                            "falling back to system output.",
                    )
                    return "DAC 未接受采样率 ${sampleRate}Hz（读回 ${readBack}Hz），已回退系统输出。"
                }
                return null
            }

            val data = byteArrayOf(
                (sampleRate and 0xff).toByte(),
                ((sampleRate ushr 8) and 0xff).toByte(),
                ((sampleRate ushr 16) and 0xff).toByte(),
            )
            val result = connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIP_ENDPOINT,
                0x01,
                0x01 shl 8,
                target.endpoint.address,
                data,
                data.size,
                1000,
            )
            UsbDiagnostics.i(
                tag,
                "UAC1 endpoint SET_CUR sampleRate=$sampleRate, endpoint=0x${
                    target.endpoint.address.toString(16)
                }, result=$result",
            )
            if (quirk.clockSetCurDelayMs > 0) {
                Thread.sleep(quirk.clockSetCurDelayMs.toLong())
            }
            return null
        } catch (error: RuntimeException) {
            UsbDiagnostics.w(tag, "USB audio clock configuration failed.", error)
            return null
        } finally {
            if (claimedControl && controlInterface != null) {
                runCatching { connection.releaseInterface(controlInterface) }
            }
        }
    }

    private fun readUac2ClockSampleRate(
        connection: UsbDeviceConnection,
        clockSourceId: Int,
        controlInterfaceNumber: Int,
        label: String,
    ): Int? {
        val data = ByteArray(4)
        val result = connection.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or USB_RECIP_INTERFACE,
            0x81,
            0x01 shl 8,
            (clockSourceId shl 8) or controlInterfaceNumber,
            data,
            data.size,
            1000,
        )
        val sampleRate = if (result == 4) {
            (data[0].toInt() and 0xff) or
                ((data[1].toInt() and 0xff) shl 8) or
                ((data[2].toInt() and 0xff) shl 16) or
                ((data[3].toInt() and 0xff) shl 24)
        } else {
            null
        }
        UsbDiagnostics.i(
            tag,
            "UAC2 clock GET_CUR $label result=$result, clockSourceId=$clockSourceId, " +
                "controlInterface=$controlInterfaceNumber, sampleRate=${sampleRate ?: "n/a"}, " +
                "raw=${hexPreview(data)}",
        )
        return sampleRate
    }

    private fun hexPreview(data: ByteArray, limit: Int = 16): String =
        data.take(minOf(data.size, limit)).joinToString(" ") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun findAudioControlInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                usbInterface.interfaceSubclass == 1
            ) {
                return usbInterface
            }
        }
        return null
    }

    private fun writeOutputBuffer(
        outputBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        packetizer: PcmIsoPacketizer,
    ) {
        val data = ByteArray(info.size)
        outputBuffer.position(info.offset)
        outputBuffer.limit(info.offset + info.size)
        outputBuffer.get(data)
        packetizer.write(data)
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return index
            }
        }
        return -1
    }

    private fun collectOutputCandidates(
        device: UsbDevice,
        streamingFormats: Map<Pair<Int, Int>, StreamingFormatInfo>,
    ): List<OutputTarget> {
        val candidates = mutableListOf<OutputTarget>()
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_AUDIO) {
                continue
            }
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (
                    endpoint.direction == UsbConstants.USB_DIR_OUT &&
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                ) {
                    val alt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        usbInterface.alternateSetting
                    } else {
                        0
                    }
                    candidates += OutputTarget(
                        usbInterface = usbInterface,
                        endpoint = endpoint,
                        feedbackEndpoint = findFeedbackEndpoint(usbInterface),
                        formatInfo = streamingFormats[usbInterface.id to alt],
                    )
                }
            }
        }
        return candidates
    }

    /**
     * 汇总诊断报告所需的“App 解析结果”部分（原始描述符、AS 格式、输出候选、UAC2 时钟源）。
     * 只在有权限时临时打开设备读取描述符，读完即关，不影响正在进行的独占播放。
     */
    fun collectDiagnostics(usbManager: UsbManager, device: UsbDevice?): Map<String, Any?> {
        if (device == null) {
            return mapOf("available" to false, "message" to "未检测到 USB 音频设备。")
        }
        if (!usbManager.hasPermission(device)) {
            return mapOf(
                "available" to false,
                "permissionGranted" to false,
                "message" to "未授权，无法读取描述符。",
            )
        }

        val connection = usbManager.openDevice(device)
            ?: return mapOf(
                "available" to false,
                "permissionGranted" to true,
                "message" to "无法打开 USB 设备读取描述符。",
            )

        return try {
            val descriptors = connection.rawDescriptors
            val streamingFormats = parseStreamingFormatInfo(descriptors)
            val candidates = collectOutputCandidates(device, streamingFormats)
                .sortedWith(compareBy<OutputTarget> { it.endpoint.maxPacketSize }.thenBy { it.alternateSetting })
            val clockSourceId = candidates.firstOrNull()?.let {
                findUac2ClockSourceId(descriptors, it.usbInterface.id, it.alternateSetting)
            }
            mapOf(
                "available" to true,
                "permissionGranted" to true,
                "rawDescriptorLength" to (descriptors?.size ?: 0),
                "rawDescriptorsHex" to descriptors?.let { hexDump(it) },
                "streamingFormats" to streamingFormats.values
                    .sortedWith(compareBy<StreamingFormatInfo> { it.interfaceNumber }.thenBy { it.alternateSetting })
                    .map { it.toString() },
                "outputCandidates" to candidates.map { candidate ->
                    "alt=${candidate.alternateSetting}/max=${candidate.endpoint.maxPacketSize}/" +
                        "outAttr=0x${candidate.endpoint.attributes.toString(16)}/" +
                        "interval=${candidate.endpoint.interval}/" +
                        "feedback=${candidate.feedbackEndpointLabel}/" +
                        "usbBytes=${candidate.usbBytesPerSample}/bits=${candidate.usbBitResolution}/" +
                        "format=${candidate.formatInfo}"
                },
                "clockSourceId" to clockSourceId,
                // quirk 匹配结果：命中哪条 / 未命中用默认值，以及各字段生效值
                "quirkMatch" to (UsbDacQuirks.matchDescription(
                    context,
                    device.vendorId,
                    device.productId,
                ) ?: "none (defaults)"),
                "quirkEffective" to UsbDacQuirks.forDevice(
                    context,
                    device.vendorId,
                    device.productId,
                ).toString(),
                "quirkLoadErrors" to UsbDacQuirks.loadErrors(context)
                    .joinToString("; ")
                    .takeIf { it.isNotEmpty() },
            )
        } catch (error: RuntimeException) {
            mapOf(
                "available" to false,
                "permissionGranted" to true,
                "message" to "读取描述符失败：${error.message}",
            )
        } finally {
            connection.close()
        }
    }

    private fun hexDump(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 3)
        for (index in bytes.indices) {
            if (index % 16 == 0) {
                if (index != 0) {
                    builder.append('\n')
                }
                builder.append(String.format(Locale.US, "%04x: ", index))
            } else {
                builder.append(' ')
            }
            builder.append(String.format(Locale.US, "%02x", bytes[index].toInt() and 0xff))
        }
        return builder.toString()
    }

    private fun findOutputTarget(
        device: UsbDevice,
        streamingFormats: Map<Pair<Int, Int>, StreamingFormatInfo> = emptyMap(),
        sampleRate: Int? = null,
        channels: Int = 2,
        bitDepth: Int? = null,
    ): OutputTarget? {
        val candidates = collectOutputCandidates(device, streamingFormats)

        if (candidates.isEmpty()) {
            return null
        }

        if (sampleRate == null) {
            return candidates.minWith(compareBy<OutputTarget> {
                it.endpoint.maxPacketSize
            }.thenBy { it.alternateSetting })
        }

        val sortedCandidates = candidates.sortedWith(compareBy<OutputTarget> {
            it.endpoint.maxPacketSize
        }.thenBy { it.alternateSetting })
        val fittingCandidates = sortedCandidates.filter {
            it.endpoint.maxPacketSize >= requiredIsoPacketBytes(
                sampleRate,
                it.packetsPerSecond,
                channels,
                it.usbBytesPerSample,
            )
        }
        val exactBitDepthCandidates = bitDepth?.let { requested ->
            fittingCandidates.filter { it.usbBitResolution == requested }
        } ?: emptyList()
        val autoBitDepthCandidates = if (bitDepth == null) {
            listOf(24, 32, 16)
                .firstNotNullOfOrNull { preferred ->
                    fittingCandidates.filter { it.usbBitResolution == preferred }.takeIf { it.isNotEmpty() }
                }
                ?: fittingCandidates
        } else {
            emptyList()
        }
        val selectedPool = when {
            exactBitDepthCandidates.isNotEmpty() -> exactBitDepthCandidates
            autoBitDepthCandidates.isNotEmpty() -> autoBitDepthCandidates
            fittingCandidates.isNotEmpty() -> fittingCandidates
            else -> sortedCandidates
        }
        val selected = selectedPool.minWith(
            compareBy<OutputTarget> { it.usbBytesPerSample }
                .thenBy { it.endpoint.maxPacketSize }
                .thenBy { it.alternateSetting },
        )
        val selectedRequiredPacketBytes = requiredIsoPacketBytes(
            sampleRate,
            selected.packetsPerSecond,
            channels,
            selected.usbBytesPerSample,
        )
        if (selected.endpoint.maxPacketSize < selectedRequiredPacketBytes) {
            UsbDiagnostics.w(
                tag,
                "selected USB alt may be too small: requiredPacketBytes=$selectedRequiredPacketBytes, " +
                    "selectedMaxPacket=${selected.endpoint.maxPacketSize}, sampleRate=$sampleRate, " +
                    "channels=$channels, bitDepth=${bitDepth ?: "auto"}",
            )
        }
        UsbDiagnostics.i(
            tag,
            "selected USB alt=${selected.alternateSetting}, maxPacket=${selected.endpoint.maxPacketSize}, " +
                "requiredPacketBytes=$selectedRequiredPacketBytes, " +
                "requestedBitDepth=${bitDepth ?: "auto"}, selectedBitDepth=${selected.usbBitResolution}, " +
                "packetsPerSecond=${selected.packetsPerSecond}, candidates=${sortedCandidates.joinToString { candidate ->
                    val required = requiredIsoPacketBytes(
                        sampleRate,
                        candidate.packetsPerSecond,
                        channels,
                        candidate.usbBytesPerSample,
                    )
                    "alt=${candidate.alternateSetting}/max=${candidate.endpoint.maxPacketSize}/" +
                        "outAttr=0x${candidate.endpoint.attributes.toString(16)}/" +
                        "feedback=${candidate.feedbackEndpointLabel}/" +
                        "usbBytes=${candidate.usbBytesPerSample}/bits=${candidate.usbBitResolution}/" +
                        "required=$required/format=${candidate.formatInfo}"
                }}",
        )
        return selected
    }

    private fun findFeedbackEndpoint(usbInterface: UsbInterface): UsbEndpoint? {
        for (endpointIndex in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(endpointIndex)
            val isIsochronous = endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
            val isInput = endpoint.direction == UsbConstants.USB_DIR_IN
            val usageType = endpoint.attributes and 0x30
            if (isIsochronous && isInput && usageType == 0x10) {
                return endpoint
            }
        }
        return null
    }

    private fun parseStreamingFormatInfo(descriptors: ByteArray?): Map<Pair<Int, Int>, StreamingFormatInfo> {
        if (descriptors == null) {
            UsbDiagnostics.w(tag, "USB raw descriptors unavailable; cannot parse AS format descriptors.")
            return emptyMap()
        }

        val formats = mutableMapOf<Pair<Int, Int>, StreamingFormatInfo>()
        var offset = 0
        var currentInterfaceNumber = -1
        var currentAlternateSetting = -1
        var currentInterfaceSubclass = -1
        var currentInterfaceProtocol = -1

        while (offset + 1 < descriptors.size) {
            val length = descriptors[offset].toInt() and 0xff
            val descriptorType = descriptors[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > descriptors.size) {
                break
            }

            if (descriptorType == 0x04 && length >= 9) {
                currentInterfaceNumber = descriptors[offset + 2].toInt() and 0xff
                currentAlternateSetting = descriptors[offset + 3].toInt() and 0xff
                currentInterfaceSubclass = descriptors[offset + 6].toInt() and 0xff
                currentInterfaceProtocol = descriptors[offset + 8].toInt() and 0xff
            } else if (
                descriptorType == 0x24 &&
                currentInterfaceSubclass == 2 &&
                length >= 3
            ) {
                val key = currentInterfaceNumber to currentAlternateSetting
                val subtype = descriptors[offset + 2].toInt() and 0xff
                val existing = formats[key] ?: StreamingFormatInfo(
                    interfaceNumber = currentInterfaceNumber,
                    alternateSetting = currentAlternateSetting,
                    protocol = currentInterfaceProtocol,
                )
                when (subtype) {
                    0x01 -> {
                        val terminalLink = if (length >= 4) {
                            descriptors[offset + 3].toInt() and 0xff
                        } else {
                            existing.terminalLink
                        }
                        val formatType = if (length >= 6) {
                            descriptors[offset + 5].toInt() and 0xff
                        } else {
                            existing.formatType
                        }
                        val channels = if (length >= 11) {
                            descriptors[offset + 10].toInt() and 0xff
                        } else {
                            existing.channels
                        }
                        formats[key] = existing.copy(
                            terminalLink = terminalLink,
                            formatType = formatType,
                            channels = channels,
                        )
                    }
                    0x02 -> {
                        if (length >= 6) {
                            formats[key] = existing.copy(
                                formatType = descriptors[offset + 3].toInt() and 0xff,
                                subslotSize = descriptors[offset + 4].toInt() and 0xff,
                                bitResolution = descriptors[offset + 5].toInt() and 0xff,
                            )
                        } else if (length >= 7) {
                            formats[key] = existing.copy(
                                formatType = descriptors[offset + 3].toInt() and 0xff,
                                channels = descriptors[offset + 4].toInt() and 0xff,
                                subslotSize = descriptors[offset + 5].toInt() and 0xff,
                                bitResolution = descriptors[offset + 6].toInt() and 0xff,
                            )
                        }
                    }
                }
            }

            offset += length
        }

        UsbDiagnostics.i(
            tag,
            "USB AS formats parsed: ${formats.values.sortedWith(
                compareBy<StreamingFormatInfo> { it.interfaceNumber }.thenBy { it.alternateSetting },
            ).joinToString()}",
        )
        return formats
    }

    private fun findUac2ClockSourceId(
        descriptors: ByteArray?,
        streamingInterfaceNumber: Int,
        streamingAlternateSetting: Int,
    ): Int? {
        if (descriptors == null) {
            return null
        }

        var offset = 0
        var currentInterfaceNumber = -1
        var currentAlternateSetting = -1
        var currentInterfaceSubclass = -1
        var terminalLink: Int? = null
        var firstClockSourceId: Int? = null
        val inputTerminalClockIds = mutableMapOf<Int, Int>()
        val outputTerminalClockIds = mutableMapOf<Int, Int>()

        while (offset + 1 < descriptors.size) {
            val length = descriptors[offset].toInt() and 0xff
            val descriptorType = descriptors[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > descriptors.size) {
                break
            }

            if (descriptorType == 0x04 && length >= 9) {
                currentInterfaceNumber = descriptors[offset + 2].toInt() and 0xff
                currentAlternateSetting = descriptors[offset + 3].toInt() and 0xff
                currentInterfaceSubclass = descriptors[offset + 6].toInt() and 0xff
            } else if (descriptorType == 0x24 && length >= 3) {
                val subtype = descriptors[offset + 2].toInt() and 0xff
                when (subtype) {
                    0x0a -> {
                        if (length >= 4 && firstClockSourceId == null) {
                            firstClockSourceId = descriptors[offset + 3].toInt() and 0xff
                        }
                    }
                    0x02 -> {
                        if (length >= 8) {
                            val terminalId = descriptors[offset + 3].toInt() and 0xff
                            inputTerminalClockIds[terminalId] =
                                descriptors[offset + 7].toInt() and 0xff
                        }
                    }
                    0x03 -> {
                        if (length >= 9) {
                            val terminalId = descriptors[offset + 3].toInt() and 0xff
                            outputTerminalClockIds[terminalId] =
                                descriptors[offset + 8].toInt() and 0xff
                        }
                    }
                    0x01 -> {
                        if (
                            currentInterfaceNumber == streamingInterfaceNumber &&
                            currentAlternateSetting == streamingAlternateSetting &&
                            currentInterfaceSubclass == 2 &&
                            length >= 4
                        ) {
                            terminalLink = descriptors[offset + 3].toInt() and 0xff
                        }
                    }
                }
            }

            offset += length
        }

        val linkedTerminal = terminalLink
        val result = linkedTerminal?.let {
            inputTerminalClockIds[it] ?: outputTerminalClockIds[it]
        } ?: firstClockSourceId
        UsbDiagnostics.i(
            tag,
            "parsed UAC2 clock source: streamingInterface=$streamingInterfaceNumber, " +
                "alt=$streamingAlternateSetting, terminalLink=$terminalLink, clockSourceId=$result",
        )
        return result
    }

    private fun requiredIsoPacketBytes(
        sampleRate: Int,
        packetsPerSecond: Int,
        channels: Int,
        bytesPerSample: Int,
    ): Int {
        val maxFramesPerPacket = (sampleRate + packetsPerSecond - 1) / packetsPerSecond
        return maxFramesPerPacket * channels * bytesPerSample
    }

    private fun isoIntervalMicroframes(interval: Int): Int {
        return 1 shl (interval.coerceIn(1, 4) - 1)
    }

    private fun bytesPerSampleForBitDepth(bitDepth: Int): Int {
        return when {
            bitDepth <= 8 -> 1
            bitDepth <= 16 -> 2
            bitDepth <= 24 -> 3
            else -> 4
        }
    }

    private fun isSupportedFile(filePath: String, sourceFormat: String?): Boolean {
        if (sourceFormat == "flac" || sourceFormat == "wav" || sourceFormat == "wave") {
            return true
        }
        if (isDsdFile(filePath, sourceFormat)) {
            return true
        }
        val lower = filePath.lowercase(Locale.ROOT)
        return lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".wave")
    }

    private fun isDsdFile(filePath: String, sourceFormat: String?): Boolean {
        if (sourceFormat == "dsf" || sourceFormat == "dff") {
            return true
        }
        val lower = filePath.lowercase(Locale.ROOT)
        return lower.endsWith(".dsf") || lower.endsWith(".dff")
    }

    private fun capability(
        available: Boolean,
        permissionGranted: Boolean,
        device: UsbDevice?,
        target: OutputTarget?,
        message: String,
    ): Map<String, Any?> {
        return mapOf(
            "available" to available,
            "permissionGranted" to permissionGranted,
            "deviceName" to device?.productName,
            "deviceId" to device?.deviceId,
            "interfaceNumber" to target?.usbInterface?.id,
            "alternateSetting" to target?.alternateSetting,
            "endpointAddress" to target?.endpoint?.address,
            "maxPacketSize" to target?.endpoint?.maxPacketSize,
            "sampleRates" to listOf(44100, 48000, 88200, 96000, 176400, 192000),
            "bitDepths" to listOf(16, 24, 32),
            "channelCounts" to listOf(2),
            "message" to message,
        )
    }

    private fun emitError(message: String) {
        updateState(inactiveState(message))
    }

    private fun consumePendingSeekMs(): Long? {
        val seekMs = pendingSeekMs.getAndSet(-1L)
        return if (seekMs >= 0L) seekMs else null
    }

    private fun updateState(state: Map<String, Any?>): Map<String, Any?> {
        currentState = state
        emitState(state)
        if (state["active"] != true) {
            emitInactiveTelemetry()
        }
        return state
    }

    private fun inactiveState(message: String? = null): Map<String, Any?> {
        return mapOf(
            "active" to false,
            "playing" to false,
            "positionMs" to 0,
            "durationMs" to null,
            "sampleRate" to null,
            "bitDepth" to null,
            "format" to null,
            "message" to message,
        )
    }

    private fun bitDepthFromPcmEncoding(pcmEncoding: Int?): Int {
        return when (pcmEncoding) {
            3 -> 8
            4 -> 32
            0x80000000.toInt() -> 24
            else -> 16
        }
    }

    private data class OutputTarget(
        val usbInterface: UsbInterface,
        val endpoint: UsbEndpoint,
        val feedbackEndpoint: UsbEndpoint? = null,
        val formatInfo: StreamingFormatInfo? = null,
    ) {
        val alternateSetting: Int
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                usbInterface.alternateSetting
            } else {
                0
            }

        val endpointLabel: String
            get() = "interface=${usbInterface.id}, alt=$alternateSetting, endpoint=0x${
                endpoint.address.toString(16)
            }"

        val feedbackEndpointLabel: String
            get() = feedbackEndpoint?.let {
                "0x${it.address.toString(16)}/max=${it.maxPacketSize}/interval=${it.interval}/attr=0x${
                    it.attributes.toString(16)
                }"
            } ?: "none"

        val packetsPerSecond: Int
            get() {
                if (usbInterface.interfaceProtocol == 32) {
                    val interval = endpoint.interval.coerceIn(1, 4)
                    return 8000 / (1 shl (interval - 1))
                }
                return 1000
            }

        val usbBytesPerSample: Int
            get() = formatInfo?.subslotSize?.takeIf { it > 0 } ?: 2

        val usbBitResolution: Int?
            get() = formatInfo?.bitResolution?.takeIf { it > 0 }
    }

    private data class StreamingFormatInfo(
        val interfaceNumber: Int,
        val alternateSetting: Int,
        val protocol: Int,
        val terminalLink: Int? = null,
        val formatType: Int? = null,
        val channels: Int? = null,
        val subslotSize: Int? = null,
        val bitResolution: Int? = null,
    )

    private class PcmIsoPacketizer(
        private val sampleRate: Int,
        private val packetsPerSecond: Int,
        channels: Int,
        private val inputBytesPerSample: Int,
        private val inputBitDepth: Int,
        private val usbBytesPerSample: Int,
        private val usbBitResolution: Int,
        private val feedbackOutputPacketDivisor: Int,
        private val feedbackFramesPerPacketQ16: (() -> Int)? = null,
        private val writePackets: (ByteArray, IntArray, Int) -> Unit,
    ) {
        private val pending = ByteArrayOutputStream()
        private val transfer = ByteArrayOutputStream()
        private val transferPacketLengths = IntArray(16)
        private val bytesPerFrame = channels * usbBytesPerSample
        private val inputBytesPerFrame = channels * inputBytesPerSample
        private var sampleRemainder = 0
        private var feedbackRemainderQ16 = 0L
        private var transferPacketCount = 0
        private var packetLogCount = 0
        private var feedbackRejectLogCount = 0
        private var pcmPreviewLogged = false
        private var pcmPreviewAttempts = 0

        fun write(data: ByteArray) {
            val converted = convertPcmToUsbSlots(data)
            if (!pcmPreviewLogged) {
                pcmPreviewAttempts++
                val forcePreview = pcmPreviewAttempts >= 64
                if (hasAudibleSamples(data) || forcePreview) {
                    pcmPreviewLogged = true
                    logPcmPreview(
                        data,
                        converted,
                        if (forcePreview) "forced-after-silence" else "first-nonzero",
                    )
                }
            }
            pending.write(converted)
            drain(fullPacketsOnly = true)
        }

        fun flush() {
            drain(fullPacketsOnly = false)
        }

        fun reset() {
            pending.reset()
            transfer.reset()
            transferPacketCount = 0
            sampleRemainder = 0
            feedbackRemainderQ16 = 0L
            packetLogCount = 0
            feedbackRejectLogCount = 0
            pcmPreviewLogged = false
            pcmPreviewAttempts = 0
        }

        private fun drain(fullPacketsOnly: Boolean) {
            while (pending.size() > 0) {
                val packetBytes = nextPacketBytes()
                if (fullPacketsOnly && pending.size() < packetBytes) {
                    return
                }
                val source = pending.toByteArray()
                val length = minOf(packetBytes, source.size)
                val packet = ByteArray(packetBytes)
                System.arraycopy(source, 0, packet, 0, length)
                pending.reset()
                if (source.size > length) {
                    pending.write(source, length, source.size - length)
                }
                if (packetLogCount < 5) {
                    ++packetLogCount
                    UsbDiagnostics.d(
                        "UsbExclusiveAudioEngine",
                        "USB PCM packet bytes=${packet.size}, filled=$length",
                    )
                }
                transfer.write(packet)
                transferPacketLengths[transferPacketCount] = packet.size
                transferPacketCount++
                if (transferPacketCount >= transferPacketLengths.size) {
                    flushTransfer()
                }
            }

            if (!fullPacketsOnly) {
                flushTransfer()
            }
        }

        private fun flushTransfer() {
            if (transferPacketCount == 0) {
                return
            }
            writePackets(
                transfer.toByteArray(),
                transferPacketLengths.copyOf(transferPacketCount),
                transferPacketCount,
            )
            transfer.reset()
            transferPacketCount = 0
        }

        private fun nextPacketBytes(): Int {
            val feedbackQ16 = feedbackFramesPerPacketQ16?.invoke() ?: 0
            if (feedbackQ16 > 0) {
                val outputFeedbackQ16 = feedbackQ16 / feedbackOutputPacketDivisor
                val nominalFramesQ16 = ((sampleRate.toLong() shl 16) / packetsPerSecond).toInt()
                val minFeedbackQ16 = nominalFramesQ16 - (nominalFramesQ16 / 8)
                val maxFeedbackQ16 = nominalFramesQ16 + (nominalFramesQ16 / 2)
                if (outputFeedbackQ16 in minFeedbackQ16..maxFeedbackQ16) {
                    feedbackRemainderQ16 += outputFeedbackQ16.toLong()
                    val frames = (feedbackRemainderQ16 ushr 16).toInt()
                    feedbackRemainderQ16 = feedbackRemainderQ16 and 0xffff
                    if (frames > 0) {
                        return maxOf(bytesPerFrame, frames * bytesPerFrame)
                    }
                } else if (feedbackRejectLogCount < 8) {
                    ++feedbackRejectLogCount
                    UsbDiagnostics.w(
                        "UsbExclusiveAudioEngine",
                        "USB feedback ignored outputFrames=${q16ToFrames(outputFeedbackQ16)}, " +
                            "nominalFrames=${q16ToFrames(nominalFramesQ16)}, " +
                            "sampleRate=$sampleRate, packetsPerSecond=$packetsPerSecond",
                    )
                }
            }

            sampleRemainder += sampleRate
            val frames = sampleRemainder / packetsPerSecond
            sampleRemainder %= packetsPerSecond
            return maxOf(bytesPerFrame, frames * bytesPerFrame)
        }

        private fun q16ToFrames(value: Int): String =
            String.format(Locale.US, "%.6f", value.toDouble() / 65536.0)

        private fun convertPcmToUsbSlots(data: ByteArray): ByteArray {
            if (inputBytesPerSample == usbBytesPerSample && inputBitDepth == usbBitResolution) {
                return data
            }

            val frames = data.size / inputBytesPerFrame
            val output = ByteArray(frames * bytesPerFrame)
            var inputOffset = 0
            var outputOffset = 0
            repeat(frames) {
                repeat(inputBytesPerFrame / inputBytesPerSample) {
                    val sample = readSignedLittleEndian(data, inputOffset, inputBytesPerSample, inputBitDepth)
                    val shifted = if (usbBitResolution >= inputBitDepth) {
                        sample shl (usbBitResolution - inputBitDepth)
                    } else {
                        sample shr (inputBitDepth - usbBitResolution)
                    }
                    writeLittleEndian(output, outputOffset, usbBytesPerSample, shifted)
                    inputOffset += inputBytesPerSample
                    outputOffset += usbBytesPerSample
                }
            }
            return output
        }

        private fun hasAudibleSamples(input: ByteArray): Boolean {
            val frames = input.size / inputBytesPerFrame
            val samplesPerFrame = inputBytesPerFrame / inputBytesPerSample
            val samplesToInspect = minOf(4096, frames * samplesPerFrame)
            var sumAbs = 0L
            for (index in 0 until samplesToInspect) {
                val offset = index * inputBytesPerSample
                val sample = readSignedLittleEndian(input, offset, inputBytesPerSample, inputBitDepth)
                val abs = kotlin.math.abs(sample.toLong())
                sumAbs += abs
                if (abs > 512) {
                    return true
                }
            }
            return samplesToInspect > 0 && (sumAbs / samplesToInspect) > 64
        }

        private fun logPcmPreview(input: ByteArray, converted: ByteArray, reason: String) {
            val frames = input.size / inputBytesPerFrame
            val samplesPerFrame = inputBytesPerFrame / inputBytesPerSample
            val samplesToInspect = minOf(4096, frames * samplesPerFrame)
            var minSample = 0
            var maxSample = 0
            var sumAbs = 0L
            for (index in 0 until samplesToInspect) {
                val offset = index * inputBytesPerSample
                val sample = readSignedLittleEndian(input, offset, inputBytesPerSample, inputBitDepth)
                if (index == 0 || sample < minSample) minSample = sample
                if (index == 0 || sample > maxSample) maxSample = sample
                sumAbs += kotlin.math.abs(sample.toLong())
            }
            val averageAbs = if (samplesToInspect > 0) sumAbs / samplesToInspect else 0
            UsbDiagnostics.i(
                "UsbExclusiveAudioEngine",
                "USB PCM preview reason=$reason, inputBytes=${input.size}, convertedBytes=${converted.size}, frames=$frames, " +
                    "inputBitDepth=$inputBitDepth, usbBytesPerSample=$usbBytesPerSample, " +
                    "usbBitResolution=$usbBitResolution, min=$minSample, max=$maxSample, avgAbs=$averageAbs, " +
                    "inputHead=${input.toHexPreview()}, usbHead=${converted.toHexPreview()}",
            )
        }

        private fun ByteArray.toHexPreview(limit: Int = 64): String {
            return take(minOf(size, limit)).joinToString(" ") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }

        private fun readSignedLittleEndian(
            data: ByteArray,
            offset: Int,
            bytes: Int,
            bitDepth: Int,
        ): Int {
            var value = 0
            for (index in 0 until bytes) {
                value = value or ((data[offset + index].toInt() and 0xff) shl (index * 8))
            }
            val shift = (32 - bitDepth).coerceIn(0, 31)
            return (value shl shift) shr shift
        }

        private fun writeLittleEndian(
            data: ByteArray,
            offset: Int,
            bytes: Int,
            value: Int,
        ) {
            for (index in 0 until bytes) {
                data[offset + index] = ((value ushr (index * 8)) and 0xff).toByte()
            }
        }
    }
}
