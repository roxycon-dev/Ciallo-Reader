package com.example.transfer

import java.io.DataInputStream
import java.io.DataOutputStream

/** Discovery metadata only. User content still requires mutual TLS and the existing human confirmation. */
internal data class NearbyOffer(val id:String,val name:String,val sending:Boolean,val busy:Boolean=false)
internal object NearbyHandshake {
    const val PORT=38843
    const val VERSION=2
    private const val MAGIC=0x4349414c
    fun write(out:DataOutputStream,offer:NearbyOffer) {
        require(offer.id.length in 1..64 && offer.name.length<=64)
        out.writeInt(MAGIC);out.writeByte(VERSION);out.writeUTF(offer.id);out.writeUTF(offer.name)
        out.writeBoolean(offer.sending);out.writeBoolean(offer.busy);out.flush()
    }
    fun read(input:DataInputStream):NearbyOffer {
        require(input.readInt()==MAGIC && input.readUnsignedByte()==VERSION){"附近互传协议不兼容，请更新双方应用"}
        fun text():String {
            // Check the encoded size before allocating the UTF payload.
            val length=input.readUnsignedShort();require(length<=256){"无效附近握手"}
            val bytes=ByteArray(length+2);bytes[0]=(length shr 8).toByte();bytes[1]=length.toByte()
            input.readFully(bytes,2,length)
            return DataInputStream(bytes.inputStream()).readUTF()
        }
        val id=text();val name=text()
        require(id.length in 1..64 && name.length<=64){"无效附近设备信息"}
        return NearbyOffer(id,name,input.readBoolean(),input.readBoolean())
    }
    fun shouldStart(local:NearbyOffer,remote:NearbyOffer)=!local.busy && !remote.busy && local.sending!=remote.sending
}
