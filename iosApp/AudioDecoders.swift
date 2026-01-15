import Foundation
import AVFoundation
import AudioToolbox

/// Protocol for audio decoders
protocol NativeAudioDecoder {
    func decode(_ data: Data) throws -> Data
}

/// Factory to create appropriate decoder
enum AudioDecoderFactory {
    static func create(
        codec: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: Data?
    ) throws -> NativeAudioDecoder {
        switch codec.lowercased() {
        case "pcm":
            return PCMPassthroughDecoder(bitDepth: bitDepth, channels: channels)
        case "flac":
            return try FLACNativeDecoder(sampleRate: sampleRate, channels: channels, bitDepth: bitDepth, header: codecHeader)
        case "opus":
            return try OpusNativeDecoder(sampleRate: sampleRate, channels: channels, bitDepth: bitDepth)
        default:
            throw AudioDecoderError.unsupportedCodec(codec)
        }
    }
}

// MARK: - PCM Passthrough Decoder

/// PCM decoder - handles 16/24/32 bit formats
class PCMPassthroughDecoder: NativeAudioDecoder {
    private let bitDepth: Int
    private let channels: Int
    
    init(bitDepth: Int, channels: Int) {
        self.bitDepth = bitDepth
        self.channels = channels
    }
    
    func decode(_ data: Data) throws -> Data {
        switch bitDepth {
        case 16, 32:
            // Pass through as-is
            return data
        case 24:
            // Unpack 24-bit to 32-bit Int32
            return try unpack24Bit(data)
        default:
            throw AudioDecoderError.unsupportedBitDepth(bitDepth)
        }
    }
    
    private func unpack24Bit(_ data: Data) throws -> Data {
        let bytesPerSample = 3
        guard data.count % bytesPerSample == 0 else {
            throw AudioDecoderError.invalidDataSize
        }
        
        let sampleCount = data.count / bytesPerSample
        var samples = [Int32]()
        samples.reserveCapacity(sampleCount)
        
        let bytes = [UInt8](data)
        
        for i in 0..<sampleCount {
            let offset = i * bytesPerSample
            // Little-endian 24-bit to Int32
            let b0 = Int32(bytes[offset])
            let b1 = Int32(bytes[offset + 1])
            let b2 = Int32(bytes[offset + 2])
            
            var sample = (b2 << 16) | (b1 << 8) | b0
            // Sign extend from 24-bit
            if sample & 0x800000 != 0 {
                sample |= Int32(0xFF000000)
            }
            // Shift to 32-bit range
            sample <<= 8
            samples.append(sample)
        }
        
        return samples.withUnsafeBytes { Data($0) }
    }
}

// MARK: - FLAC Decoder using AudioConverter

/// FLAC decoder using Apple's AudioConverter API
/// AudioConverter can decode FLAC format natively on iOS 11+
class FLACNativeDecoder: NativeAudioDecoder {
    private var converter: AudioConverterRef?
    private let sampleRate: Int
    private let channels: Int
    private let bitDepth: Int
    
    // Buffer for accumulated FLAC data
    private var inputBuffer = Data()
    private var inputOffset = 0
    
    // Output format (PCM)
    private var outputFormat: AudioStreamBasicDescription
    
    init(sampleRate: Int, channels: Int, bitDepth: Int, header: Data?) throws {
        self.sampleRate = sampleRate
        self.channels = channels
        self.bitDepth = bitDepth
        
        // Configure input format (FLAC)
        var inputFormat = AudioStreamBasicDescription()
        inputFormat.mSampleRate = Float64(sampleRate)
        inputFormat.mFormatID = kAudioFormatFLAC
        inputFormat.mFormatFlags = 0
        inputFormat.mBytesPerPacket = 0 // Variable
        inputFormat.mFramesPerPacket = 0 // Variable
        inputFormat.mBytesPerFrame = 0
        inputFormat.mChannelsPerFrame = UInt32(channels)
        inputFormat.mBitsPerChannel = UInt32(bitDepth)
        
        // Configure output format (PCM Int32 for 24-bit, Int16 for 16-bit)
        let effectiveBitDepth = (bitDepth == 24) ? 32 : bitDepth
        outputFormat = AudioStreamBasicDescription()
        outputFormat.mSampleRate = Float64(sampleRate)
        outputFormat.mFormatID = kAudioFormatLinearPCM
        outputFormat.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked
        outputFormat.mBytesPerPacket = UInt32(channels * (effectiveBitDepth / 8))
        outputFormat.mFramesPerPacket = 1
        outputFormat.mBytesPerFrame = UInt32(channels * (effectiveBitDepth / 8))
        outputFormat.mChannelsPerFrame = UInt32(channels)
        outputFormat.mBitsPerChannel = UInt32(effectiveBitDepth)
        
        // Create AudioConverter
        var converter: AudioConverterRef?
        let status = AudioConverterNew(&inputFormat, &outputFormat, &converter)
        
        guard status == noErr, let conv = converter else {
            print("🎵 FLACDecoder: ❌ Failed to create AudioConverter: \(status)")
            throw AudioDecoderError.converterCreationFailed(status)
        }
        
        self.converter = conv
        print("🎵 FLACDecoder: ✅ Created AudioConverter for FLAC → PCM")
        
        // If we have a codec header, feed it to the converter
        if let header = header {
            inputBuffer.append(header)
            print("🎵 FLACDecoder: Added \(header.count) bytes codec header")
        }
    }
    
    func decode(_ data: Data) throws -> Data {
        guard let converter = converter else {
            throw AudioDecoderError.notInitialized
        }
        
        // Append new data
        inputBuffer.append(data)
        inputOffset = 0
        
        // Prepare output buffer
        let maxOutputBytes = inputBuffer.count * 4 // Max expansion ratio
        var outputData = Data(count: maxOutputBytes)
        var outputSize = UInt32(maxOutputBytes)
        
        // Create buffer list for output
        var outputBufferList = AudioBufferList()
        outputBufferList.mNumberBuffers = 1
        
        let decodeResult: OSStatus = outputData.withUnsafeMutableBytes { outputPtr in
            guard let baseAddress = outputPtr.baseAddress else { return OSStatus(-1) }
            
            outputBufferList.mBuffers.mNumberChannels = UInt32(channels)
            outputBufferList.mBuffers.mDataByteSize = outputSize
            outputBufferList.mBuffers.mData = baseAddress
            
            // Number of output frames to request
            var ioOutputDataPackets = UInt32(inputBuffer.count / channels / (bitDepth / 8))
            
            // Use FillComplexBuffer to convert
            let status = AudioConverterFillComplexBuffer(
                converter,
                inputDataProc,
                Unmanaged.passUnretained(self).toOpaque(),
                &ioOutputDataPackets,
                &outputBufferList,
                nil
            )
            
            outputSize = outputBufferList.mBuffers.mDataByteSize
            return status
        }
        
        // Clear consumed input data
        if inputOffset > 0 {
            inputBuffer.removeFirst(inputOffset)
        }
        
        if decodeResult == noErr || decodeResult == 1 /* need more data */ {
            return outputData.prefix(Int(outputSize))
        } else {
            print("🎵 FLACDecoder: Decode returned \(decodeResult)")
            // Return empty data on error (might need more data)
            return Data()
        }
    }
    
    // Callback to provide input data to converter
    fileprivate func provideInputData(
        ioNumberDataPackets: UnsafeMutablePointer<UInt32>,
        ioData: UnsafeMutablePointer<AudioBufferList>
    ) -> OSStatus {
        let available = inputBuffer.count - inputOffset
        
        if available == 0 {
            ioNumberDataPackets.pointee = 0
            return 1 // Need more data
        }
        
        // Provide data from buffer
        inputBuffer.withUnsafeBytes { bytes in
            let ptr = bytes.baseAddress!.advanced(by: inputOffset)
            ioData.pointee.mBuffers.mData = UnsafeMutableRawPointer(mutating: ptr)
            ioData.pointee.mBuffers.mDataByteSize = UInt32(available)
            ioData.pointee.mBuffers.mNumberChannels = UInt32(channels)
        }
        
        // For compressed formats, each buffer is one packet
        ioNumberDataPackets.pointee = 1
        inputOffset = inputBuffer.count // Mark all as consumed
        
        return noErr
    }
    
    deinit {
        if let converter = converter {
            AudioConverterDispose(converter)
        }
    }
}

// C callback for AudioConverter
private let inputDataProc: AudioConverterComplexInputDataProc = { 
    converter,
    ioNumberDataPackets,
    ioData,
    outDataPacketDescription,
    inUserData in
    
    guard let userData = inUserData else {
        ioNumberDataPackets.pointee = 0
        return -1
    }
    
    let decoder = Unmanaged<FLACNativeDecoder>.fromOpaque(userData).takeUnretainedValue()
    return decoder.provideInputData(
        ioNumberDataPackets: ioNumberDataPackets,
        ioData: ioData
    )
}

// MARK: - Opus Decoder using AudioConverter

/// Opus decoder using Apple's AudioConverter API
/// AudioConverter can decode Opus format natively on iOS 11+
class OpusNativeDecoder: NativeAudioDecoder {
    private var converter: AudioConverterRef?
    private let sampleRate: Int
    private let channels: Int
    private let bitDepth: Int
    
    // Buffer for Opus packets
    private var inputBuffer = Data()
    private var inputOffset = 0
    
    // Output format (PCM)
    private var outputFormat: AudioStreamBasicDescription
    
    init(sampleRate: Int, channels: Int, bitDepth: Int) throws {
        self.sampleRate = sampleRate
        self.channels = channels
        self.bitDepth = bitDepth
        
        // Configure input format (Opus)
        var inputFormat = AudioStreamBasicDescription()
        inputFormat.mSampleRate = Float64(sampleRate)
        inputFormat.mFormatID = kAudioFormatOpus
        inputFormat.mFormatFlags = 0
        inputFormat.mBytesPerPacket = 0 // Variable
        inputFormat.mFramesPerPacket = 960 // Standard Opus frame size at 48kHz (20ms)
        inputFormat.mBytesPerFrame = 0
        inputFormat.mChannelsPerFrame = UInt32(channels)
        inputFormat.mBitsPerChannel = 0
        
        // Configure output format (PCM Int32 for 24-bit, Int16 for 16-bit)
        let effectiveBitDepth = (bitDepth == 24) ? 32 : bitDepth
        outputFormat = AudioStreamBasicDescription()
        outputFormat.mSampleRate = Float64(sampleRate)
        outputFormat.mFormatID = kAudioFormatLinearPCM
        outputFormat.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked
        outputFormat.mBytesPerPacket = UInt32(channels * (effectiveBitDepth / 8))
        outputFormat.mFramesPerPacket = 1
        outputFormat.mBytesPerFrame = UInt32(channels * (effectiveBitDepth / 8))
        outputFormat.mChannelsPerFrame = UInt32(channels)
        outputFormat.mBitsPerChannel = UInt32(effectiveBitDepth)
        
        // Create AudioConverter
        var converter: AudioConverterRef?
        let status = AudioConverterNew(&inputFormat, &outputFormat, &converter)
        
        guard status == noErr, let conv = converter else {
            print("🎵 OpusDecoder: ❌ Failed to create AudioConverter: \(status)")
            throw AudioDecoderError.converterCreationFailed(status)
        }
        
        self.converter = conv
        print("🎵 OpusDecoder: ✅ Created AudioConverter for Opus → PCM")
    }
    
    func decode(_ data: Data) throws -> Data {
        guard let converter = converter else {
            throw AudioDecoderError.notInitialized
        }
        
        // Append new data
        inputBuffer.append(data)
        inputOffset = 0
        
        // Prepare output buffer
        // Opus expands: ~2KB input → 960 frames × 2ch × 2bytes = 3840 bytes
        let maxOutputBytes = max(inputBuffer.count * 10, 8192)
        var outputData = Data(count: maxOutputBytes)
        var outputSize = UInt32(maxOutputBytes)
        
        // Create buffer list for output
        var outputBufferList = AudioBufferList()
        outputBufferList.mNumberBuffers = 1
        
        let decodeResult: OSStatus = outputData.withUnsafeMutableBytes { outputPtr in
            guard let baseAddress = outputPtr.baseAddress else { return OSStatus(-1) }
            
            outputBufferList.mBuffers.mNumberChannels = UInt32(channels)
            outputBufferList.mBuffers.mDataByteSize = outputSize
            outputBufferList.mBuffers.mData = baseAddress
            
            // Request enough frames for the buffer
            var ioOutputDataPackets = UInt32(960 * 10) // Request multiple frames
            
            let status = AudioConverterFillComplexBuffer(
                converter,
                opusInputDataProc,
                Unmanaged.passUnretained(self).toOpaque(),
                &ioOutputDataPackets,
                &outputBufferList,
                nil
            )
            
            outputSize = outputBufferList.mBuffers.mDataByteSize
            return status
        }
        
        // Clear consumed input
        if inputOffset > 0 {
            inputBuffer.removeFirst(inputOffset)
        }
        
        if decodeResult == noErr || decodeResult == 1 {
            return outputData.prefix(Int(outputSize))
        } else {
            print("🎵 OpusDecoder: Decode returned \(decodeResult)")
            return Data()
        }
    }
    
    fileprivate func provideInputData(
        ioNumberDataPackets: UnsafeMutablePointer<UInt32>,
        ioData: UnsafeMutablePointer<AudioBufferList>
    ) -> OSStatus {
        let available = inputBuffer.count - inputOffset
        
        if available == 0 {
            ioNumberDataPackets.pointee = 0
            return 1
        }
        
        inputBuffer.withUnsafeBytes { bytes in
            let ptr = bytes.baseAddress!.advanced(by: inputOffset)
            ioData.pointee.mBuffers.mData = UnsafeMutableRawPointer(mutating: ptr)
            ioData.pointee.mBuffers.mDataByteSize = UInt32(available)
            ioData.pointee.mBuffers.mNumberChannels = UInt32(channels)
        }
        
        ioNumberDataPackets.pointee = 1
        inputOffset = inputBuffer.count
        
        return noErr
    }
    
    deinit {
        if let converter = converter {
            AudioConverterDispose(converter)
        }
    }
}

// C callback for Opus AudioConverter
private let opusInputDataProc: AudioConverterComplexInputDataProc = {
    converter,
    ioNumberDataPackets,
    ioData,
    outDataPacketDescription,
    inUserData in
    
    guard let userData = inUserData else {
        ioNumberDataPackets.pointee = 0
        return -1
    }
    
    let decoder = Unmanaged<OpusNativeDecoder>.fromOpaque(userData).takeUnretainedValue()
    return decoder.provideInputData(
        ioNumberDataPackets: ioNumberDataPackets,
        ioData: ioData
    )
}

// MARK: - Errors

enum AudioDecoderError: Error, LocalizedError {
    case unsupportedCodec(String)
    case unsupportedBitDepth(Int)
    case invalidDataSize
    case converterCreationFailed(OSStatus)
    case notInitialized
    case decodingFailed(String)
    
    var errorDescription: String? {
        switch self {
        case .unsupportedCodec(let codec):
            return "Unsupported codec: \(codec)"
        case .unsupportedBitDepth(let depth):
            return "Unsupported bit depth: \(depth)"
        case .invalidDataSize:
            return "Invalid data size"
        case .converterCreationFailed(let status):
            return "AudioConverter creation failed: \(status)"
        case .notInitialized:
            return "Decoder not initialized"
        case .decodingFailed(let reason):
            return "Decoding failed: \(reason)"
        }
    }
}
