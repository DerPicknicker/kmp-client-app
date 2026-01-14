import Foundation
import LibMPV
import MPVKit
import ComposeApp
import AVFoundation

class MPVController: NSObject, PlatformAudioPlayer {
    private var mpv: OpaquePointer?
    private let ringBuffer = RingBuffer(capacity: 1024 * 1024 * 4) // 4MB Buffer (~20s CD quality)
    private var listener: MediaPlayerListener?
    
    override init() {
        super.init()
        setupAudioSession()
        setupMpv()
    }
    
    private func setupAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("Failed to setup AVAudioSession: \(error)")
        }
    }
    
    private func setupMpv() {
        // Create valid MPV handle
        mpv = mpv_create()
        guard let mpv = mpv else {
            print("Failed to create MPV instance")
            return
        }
        
        // Register custom "sendspin://" protocol
        // We pass 'self' as an unmanaged pointer to the callback (cookie)
        let selfPointer = Unmanaged.passUnretained(self).toOpaque()
        
        // Note: mpv_stream_cb_add_ro signature in C:
        // int mpv_stream_cb_add_ro(mpv_handle *ctx, const char *protocol, void *cookie, mpv_stream_cb_open_ro_fn open_fn);
        // mpv_stream_cb_open_ro_fn: int (*)(void *cookie, char *uri, mpv_stream_cb_info *info)
        
        let protocolName = "sendspin"
        mpv_stream_cb_add_ro(mpv, protocolName, selfPointer) { cookie, uri, info in
            // Unpack self
            guard let cookie = cookie else { return CInt(MPV_ERROR_INVALID_PARAMETER.rawValue) }
            let controller = Unmanaged<MPVController>.fromOpaque(cookie).takeUnretainedValue()
            return controller.handleStreamOpen(info: info)
        }
        
        // Configure standard options
        setOptionString("vid", "no")
        setOptionString("ao", "audiounit") // Use AudioUnit on iOS
        
        // Initialize
        let res = mpv_initialize(mpv)
        if res < 0 {
            print("Failed to initialize MPV: \(res)")
        }
    }
    
    private func setOptionString(_ name: String, _ value: String) {
        guard let mpv = mpv else { return }
        mpv_set_option_string(mpv, name, value)
    }
    
    // MARK: - Stream Callbacks
    
    // Manually define struct layout matching mpv_stream_cb_info due to C-interop visibility issues
    struct MPVStreamCBInfo {
        var cookie: UnsafeMutableRawPointer?
        var read: (@convention(c) (UnsafeMutableRawPointer?, UnsafeMutablePointer<Int8>?, UInt64) -> Int64)?
        var seek: (@convention(c) (UnsafeMutableRawPointer?, Int64) -> Int64)?
        var size: (@convention(c) (UnsafeMutableRawPointer?) -> Int64)?
        var close: (@convention(c) (UnsafeMutableRawPointer?) -> Void)?
        var cancel: (@convention(c) (UnsafeMutableRawPointer?) -> Void)?
    }
    
    func handleStreamOpen(info: UnsafeMutablePointer<mpv_stream_cb_info>?) -> Int32 {
        guard let info = info else { return CInt(MPV_ERROR_INVALID_PARAMETER.rawValue) }
        
        // Rebind memory to our manual struct definition to access members
        let myInfo = UnsafeMutableRawPointer(info).bindMemory(to: MPVStreamCBInfo.self, capacity: 1)
        
        // Assign methods
        myInfo.pointee.cookie = Unmanaged.passUnretained(self).toOpaque()
        myInfo.pointee.read = { cookie, buf, size in
             guard let cookie = cookie else { return 0 }
             let controller = Unmanaged<MPVController>.fromOpaque(cookie).takeUnretainedValue()
             return controller.handleStreamRead(buffer: buf, size: size)
        }
        myInfo.pointee.close = { cookie in
             // Cleanup if needed
        }
        myInfo.pointee.seek = nil // Not seekable
        myInfo.pointee.size = nil // Unknown size
        // Note: mpv_stream_cb_size_fn returns int64_t. If we pass nil, MPV assumes unknown.
        
        return 0
    }
    
    func handleStreamRead(buffer: UnsafeMutableRawPointer?, size: UInt64) -> Int64 {
        guard let buffer = buffer else { return 0 }
        // Read from ring buffer
        // Note: ringBuffer.read takes Int
        let bytesRead = ringBuffer.read(into: buffer, maxLength: Int(size))
        return Int64(bytesRead)
    }

    // MARK: - PlatformAudioPlayer Protocol
    
    func prepareStream(codec: String, sampleRate: Int32, channels: Int32, bitDepth: Int32, listener: MediaPlayerListener) {
        self.listener = listener
        ringBuffer.clear()
        
        let codec = codec.lowercased()
        
        if codec == "pcm" {
            // MPV configuration for raw audio
            setOptionString("demuxer", "rawaudio")
            setOptionString("demuxer-rawaudio-rate", "\(sampleRate)")
            setOptionString("demuxer-rawaudio-channels", "\(channels)")
            
            // Map bitDepth to format (s16le, s32le, etc)
            let format = (bitDepth == 16) ? "s16le" : "s32le" // simplified
            setOptionString("demuxer-rawaudio-format", format)
        } else {
            // For Opus/Flac, rely on MPV's auto-detection (probably Ogg container)
            // Reset demuxer to auto or clear specific options if possible
            // Note: MPV reuses options, so we explicitly set demuxer to auto
            setOptionString("demuxer", "auto")
            
            // We don't need to set rate/channels for container formats, MPV reads headers
        }
        
        // Trigger loadfile
        mpv_command_string(mpv, "loadfile sendspin://stream replace")
        
        listener.onReady()
    }
    
    func writeRawPcm(data: KotlinByteArray) {
        let size = Int(data.size)
        // Create Data buffer to hold bytes
        var swiftData = Data(count: size)
        
        // Manual copy from KotlinByteArray (slow but functional for now)
        // Optimization: In real prod, use Unsafe logic if KMP exposes pointer
        for i in 0..<size {
            swiftData[i] = UInt8(bitPattern: data.get(index: Int32(i)))
        }
        
        ringBuffer.write(swiftData)
    }
    
    func stopRawPcmStream() {
        mpv_command_string(mpv, "stop")
        ringBuffer.clear()
    }
    
    func setVolume(volume: Int32) {
        setOptionString("volume", "\(volume)")
    }
    
    func setMuted(muted: Bool) {
        setOptionString("mute", muted ? "yes" : "no")
    }
    
    func dispose() {
        if let mpv = mpv {
            mpv_terminate_destroy(mpv)
            self.mpv = nil
        }
    }
}
