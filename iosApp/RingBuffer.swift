import Foundation

class RingBuffer {
    private var buffer: Data
    private var writeIndex: Int = 0
    private var readIndex: Int = 0
    private var availableBytes: Int = 0
    private let capacity: Int
    private let lock = NSLock()

    init(capacity: Int) {
        self.capacity = capacity
        self.buffer = Data(count: capacity)
    }

    func write(_ data: Data) {
        lock.lock()
        defer { lock.unlock() }

        let bytesToWrite = min(data.count, capacity - availableBytes)
        if bytesToWrite < data.count {
            print("RingBuffer: Overrun! Dropping \(data.count - bytesToWrite) bytes")
        }
        
        guard bytesToWrite > 0 else { return }

        let firstChunkSize = min(bytesToWrite, capacity - writeIndex)
        let secondChunkSize = bytesToWrite - firstChunkSize
        
        // Write first chunk
        buffer.replaceSubrange(writeIndex..<(writeIndex + firstChunkSize), with: data[0..<firstChunkSize])
        
        // Write second chunk (wrap around)
        if secondChunkSize > 0 {
            buffer.replaceSubrange(0..<secondChunkSize, with: data[firstChunkSize..<bytesToWrite])
        }
        
        writeIndex = (writeIndex + bytesToWrite) % capacity
        availableBytes += bytesToWrite
    }

    func read(into targetBuffer: UnsafeMutableRawPointer, maxLength: Int) -> Int {
        lock.lock()
        defer { lock.unlock() }

        if availableBytes == 0 {
            return 0
        }

        let bytesToRead = min(maxLength, availableBytes)
        let firstChunkSize = min(bytesToRead, capacity - readIndex)
        let secondChunkSize = bytesToRead - firstChunkSize

        // Read first chunk
        buffer.withUnsafeBytes { ptr in
            guard let baseAddress = ptr.baseAddress else { return }
            let readPtr = baseAddress.advanced(by: readIndex)
            targetBuffer.copyMemory(from: readPtr, byteCount: firstChunkSize)
        }
        
        // Read second chunk (wrap around)
        if secondChunkSize > 0 {
            buffer.withUnsafeBytes { ptr in
                guard let baseAddress = ptr.baseAddress else { return }
                let targetPtr = targetBuffer.advanced(by: firstChunkSize)
                targetPtr.copyMemory(from: baseAddress, byteCount: secondChunkSize)
            }
        }
        
        readIndex = (readIndex + bytesToRead) % capacity
        availableBytes -= bytesToRead
        
        return bytesToRead
    }
    
    func clear() {
        lock.lock()
        defer { lock.unlock() }
        readIndex = 0
        writeIndex = 0
        availableBytes = 0
    }
}
