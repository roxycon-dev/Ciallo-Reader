package com.example.transfer

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

/** Length-framed control records, followed by bounded binary chunks on the authenticated TLS stream. */
internal object TransferWire {
    fun write(output:DataOutputStream,value:JSONObject) {
        val bytes=value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=64*1024) { "控制消息过大" }
        output.writeInt(bytes.size);output.write(bytes);output.flush()
    }
    fun read(input:DataInputStream):JSONObject {
        val length=input.readInt();require(length in 2..64*1024) { "无效传输消息" }
        val bytes=ByteArray(length);input.readFully(bytes)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    fun verifyChunk(header:JSONObject,offset:Long,total:Long,bytes:ByteArray,length:Int) {
        require(header.getString("type")=="chunk" && header.getLong("offset")==offset) { "分块次序不正确" }
        require(length==header.getInt("length") && length==minOf(CHUNK_SIZE.toLong(),total-offset).toInt() && length>0) { "分块长度不正确" }
        require(sha256(bytes.copyOf(length))==header.getString("sha256")) { "传输分块校验失败" }
    }
}
