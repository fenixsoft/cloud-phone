package cloudphone.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * 线路封帧 —— 流式记录（TCP/控制+视频复用）与 UDP 视频分片。
 *
 * 流记录：[u8 recordType][u32 payloadLen][payload]
 *   recordType 0x01 = 控制消息（payload 见 Messages.kt）
 *   recordType 0x02 = 视频帧记录（payload 见 [encodeVideoPayload]）
 * UDP 数据报：整条记录（recordType=0x02 + 帧头 + 分片字节），≤MTU。
 */
object Framing {

    const val RECORD_CONTROL: Int = 0x01
    const val RECORD_VIDEO: Int = 0x02

    /** 流记录头字节数（u8 + u32） */
    const val RECORD_HEADER_BYTES: Int = 5

    /** 视频帧头字节数：u32 sequence + u64 ptsUs + u8 flags + u16 fragIdx + u16 fragCnt + u32 totalLen */
    const val VIDEO_HEADER_BYTES: Int = 4 + 8 + 1 + 2 + 2 + 4

    /** 视频帧 flags：bit0 = 关键帧（IDR） */
    const val FLAG_KEYFRAME: Int = 0x01

    /** 单条流记录上限（防恶意/损坏长度前缀拖垮内存） */
    const val MAX_RECORD_BYTES: Int = 8 * 1024 * 1024

    /** 写一条流记录（长度前缀；调用方负责流的生命周期） */
    fun writeRecord(out: OutputStream, recordType: Int, payload: ByteArray) {
        require(payload.size <= MAX_RECORD_BYTES) { "record too large: ${payload.size}" }
        val head = ByteArray(RECORD_HEADER_BYTES)
        head[0] = recordType.toByte()
        head[1] = (payload.size ushr 24).toByte()
        head[2] = (payload.size ushr 16).toByte()
        head[3] = (payload.size ushr 8).toByte()
        head[4] = payload.size.toByte()
        out.write(head)
        out.write(payload)
        out.flush()
    }

    class Record(val type: Int, val payload: ByteArray)

    /** 流记录读取器（阻塞读；EOF 返回 null） */
    class StreamRecordReader(private val input: InputStream) {

        fun read(): Record? {
            val head = ByteArray(RECORD_HEADER_BYTES)
            if (!readFully(head, 0, head.size)) return null
            val type = head[0].toInt() and 0xFF
            val len = ((head[1].toInt() and 0xFF) shl 24) or
                ((head[2].toInt() and 0xFF) shl 16) or
                ((head[3].toInt() and 0xFF) shl 8) or
                (head[4].toInt() and 0xFF)
            if (len < 0 || len > MAX_RECORD_BYTES) throw ProtocolException("bad record length: $len")
            val payload = ByteArray(len)
            if (len > 0 && !readFully(payload, 0, len)) throw ProtocolException("truncated record")
            return Record(type, payload)
        }

        private fun readFully(buf: ByteArray, off: Int, len: Int): Boolean {
            var pos = off
            val end = off + len
            while (pos < end) {
                val n = input.read(buf, pos, end - pos)
                if (n < 0) return pos != off // 首字节即 EOF = 干净结束
                pos += n
            }
            return true
        }
    }

    // ── 视频帧封装 ──

    data class VideoFrameHeader(
        val sequence: Long,
        val ptsUs: Long,
        val keyframe: Boolean,
        val fragmentIndex: Int,
        val fragmentCount: Int,
        val totalLength: Int,
    )

    data class VideoFrame(
        val sequence: Long,
        val ptsUs: Long,
        val keyframe: Boolean,
        val data: ByteArray,
    )

    /** 帧头 + 分片字节 → 完整视频载荷（流记录或 UDP 数据报的 payload 部分） */
    fun encodeVideoPayload(header: VideoFrameHeader, fragmentBytes: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(VIDEO_HEADER_BYTES + fragmentBytes.size)
        out.putInt(header.sequence.toInt()) // 32 位序号空间（u32，M1 C++ 化保持）
        out.putLong(header.ptsUs)
        out.put(((if (header.keyframe) FLAG_KEYFRAME else 0)).toByte())
        out.putShort(header.fragmentIndex.toShort())
        out.putShort(header.fragmentCount.toShort())
        out.putInt(header.totalLength)
        out.put(fragmentBytes)
        return out.array()
    }

    /** 从视频载荷头部解析帧头；返回帧头与数据偏移 */
    fun decodeVideoHeader(buf: ByteBuffer): VideoFrameHeader {
        if (buf.remaining() < VIDEO_HEADER_BYTES) throw ProtocolException("video payload shorter than header")
        val sequence = buf.int.toLong() and 0xFFFFFFFFL
        val ptsUs = buf.long
        val flags = buf.get().toInt()
        val fragIdx = buf.short.toInt() and 0xFFFF
        val fragCnt = buf.short.toInt() and 0xFFFF
        val total = buf.int
        return VideoFrameHeader(
            sequence = sequence,
            ptsUs = ptsUs,
            keyframe = (flags and FLAG_KEYFRAME) != 0,
            fragmentIndex = fragIdx,
            fragmentCount = fragCnt,
            totalLength = total,
        )
    }

    /**
     * UDP 分片器：单帧 > MTU 时按 [maxDatagramPayload]（含帧头）切片，
     * 每片自带 fragment index/count，接收端按序号重组。
     */
    class VideoFragmenter(private val maxDatagramPayload: Int = DEFAULT_MAX_DATAGRAM) {

        fun fragment(sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray): List<ByteArray> {
            val perFragment = maxDatagramPayload - VIDEO_HEADER_BYTES
            require(perFragment > 0) { "maxDatagramPayload too small" }
            val count = if (data.isEmpty()) 1 else (data.size + perFragment - 1) / perFragment
            val fragments = ArrayList<ByteArray>(count)
            var off = 0
            for (i in 0 until count) {
                val len = minOf(perFragment, data.size - off)
                val chunk = if (len > 0) data.copyOfRange(off, off + len) else ByteArray(0)
                fragments.add(encodeVideoPayload(VideoFrameHeader(sequence, ptsUs, keyframe, i, count, data.size), chunk))
                off += len
            }
            return fragments
        }

        companion object {
            /** 默认按 1200B 分片（典型 MTU 1500 下留足 IP/UDP 头余量） */
            const val DEFAULT_MAX_DATAGRAM: Int = 1200
        }
    }

    /**
     * UDP 分片重组器：按 sequence 聚片，凑齐即出帧；
     * 超时未凑齐丢弃并计数（供丢包/坏帧统计）。
     */
    class FrameReassembler(private val timeoutMs: Long = 500) {

        private class Part(
            val header: VideoFrameHeader,
            val chunks: Array<ByteArray?>,
            val firstAtMs: Long,
        ) {
            var received: Int = 1 // 创建即含首片
        }

        private val parts = HashMap<Long, Part>()

        private class Orphans(val firstAtMs: Long) {
            val chunks = HashMap<Int, ByteArray>()
        }

        /** 首片未到前先到的中间片缓存（sequence → 分片集合，含首到时间供超时清理） */
        private val orphans = HashMap<Long, Orphans>()

        /** 已完整出帧的 sequence（有界环形，拦截重复/重放分片） */
        private val completedSeqs = ArrayDeque<Long>()
        private val completedSeqSet = HashSet<Long>()

        var droppedFrames: Long = 0
            private set
        var completedFrames: Long = 0
            private set

        /** 投递一个分片；凑齐返回完整帧，否则 null。nowMs 可注入便于测试。 */
        fun offer(header: VideoFrameHeader, bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): VideoFrame? {
            purge(nowMs)
            if (header.fragmentCount <= 0 || header.fragmentIndex >= header.fragmentCount) {
                droppedFrames++
                return null
            }
            // 已出帧的 sequence 再收到分片 = 重放/重复，静默忽略
            if (header.sequence in completedSeqSet) return null
            if (header.fragmentCount == 1) {
                if (bytes.size != header.totalLength) {
                    droppedFrames++
                    return null
                }
                rememberCompleted(header.sequence)
                completedFrames++
                return VideoFrame(header.sequence, header.ptsUs, header.keyframe, bytes)
            }
            val existing = parts[header.sequence]
            if (existing != null) {
                if (existing.chunks[header.fragmentIndex] != null) return null // 重复分片忽略
                existing.chunks[header.fragmentIndex] = bytes
                existing.received++
                mergeOrphans(existing)
            } else if (header.fragmentIndex == 0) {
                val chunks = arrayOfNulls<ByteArray>(header.fragmentCount)
                chunks[0] = bytes
                parts[header.sequence] = Part(header, chunks, nowMs)
                mergeOrphans(parts[header.sequence]!!)
            } else {
                // 首片未到：中间片先入孤儿缓存，待首片到达后并入
                val orphan = orphans.getOrPut(header.sequence) { Orphans(nowMs) }
                orphan.chunks.putIfAbsent(header.fragmentIndex, bytes)
            }
            val p = parts[header.sequence] ?: return null
            if (p.received == p.header.fragmentCount) {
                parts.remove(header.sequence)
                orphans.remove(header.sequence)
                val out = ByteArray(p.header.totalLength)
                var off = 0
                for (c in p.chunks) {
                    if (c == null || off + c.size > out.size) {
                        droppedFrames++
                        return null
                    }
                    System.arraycopy(c, 0, out, off, c.size)
                    off += c.size
                }
                if (off != out.size) {
                    droppedFrames++
                    return null
                }
                rememberCompleted(p.header.sequence)
                completedFrames++
                return VideoFrame(p.header.sequence, p.header.ptsUs, p.header.keyframe, out)
            }
            return null
        }

        /** 把孤儿缓存中该 sequence 的分片并入在组（凑齐计数） */
        private fun mergeOrphans(part: Part) {
            val orphan = orphans.remove(part.header.sequence) ?: return
            for ((idx, chunk) in orphan.chunks) {
                if (part.chunks[idx] == null) {
                    part.chunks[idx] = chunk
                    part.received++
                }
            }
        }

        private fun rememberCompleted(seq: Long) {
            completedSeqSet.add(seq)
            completedSeqs.addLast(seq)
            while (completedSeqs.size > COMPLETED_SEQ_WINDOW) {
                completedSeqSet.remove(completedSeqs.removeFirst())
            }
        }

        /** 超时丢弃未凑齐的组（含孤儿缓存） */
        private fun purge(nowMs: Long) {
            val it = parts.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (nowMs - e.value.firstAtMs > timeoutMs) {
                    it.remove()
                    orphans.remove(e.key)
                    droppedFrames++
                }
            }
            val oit = orphans.entries.iterator()
            while (oit.hasNext()) {
                val e = oit.next()
                if (nowMs - e.value.firstAtMs > timeoutMs) {
                    oit.remove()
                    droppedFrames++
                }
            }
        }

        companion object {
            /** 默认按 1200B 分片（典型 MTU 1500 下留足 IP/UDP 头余量） */
            const val DEFAULT_MAX_DATAGRAM: Int = 1200

            /** 已完成 sequence 去重窗口（防止 32 位序号回绕前的重放） */
            const val COMPLETED_SEQ_WINDOW: Int = 4096
        }
    }
}
