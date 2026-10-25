package com.github.avbox.osc.util

import org.json.JSONObject

import java.nio.charset.Charset
import java.security.spec.AlgorithmParameterSpec

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AES {

    @JvmStatic
    fun rightPadding(key: String, replace: String, Length: Int): String {
        var strReturn = ""
        var strtemp = ""
        val curLength = key.trim { it <= ' ' }.length
        if (curLength > Length) {
            strReturn = key.trim { it <= ' ' }.substring(0, Length)
        } else if (curLength == Length) {
            strReturn = key.trim { it <= ' ' }
        } else {
            for (i in 0 until (Length - curLength)) {
                strtemp = strtemp + replace
            }
            strReturn = key.trim { it <= ' ' } + strtemp
        }
        return strReturn
    }

    @JvmStatic
    fun ECB(data: String, key: String): String? {
        try {
            val paddedKey = rightPadding(key, "0", 16)
            val data2 = toBytes(data)
            val keySpec = SecretKeySpec(paddedKey.toByteArray(Charset.defaultCharset()), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            return String(cipher.doFinal(data2), Charset.defaultCharset())
        } catch (e: Exception) {
            AppLog.e("AES", e)
        }
        return null
    }

    @JvmStatic
    fun CBC(data: String, key: String, iv: String): String? {
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            val keySpec = SecretKeySpec(key.toByteArray(Charset.defaultCharset()), "AES")
            val paramSpec: AlgorithmParameterSpec = IvParameterSpec(iv.toByteArray(Charset.defaultCharset()))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec)
            return String(cipher.doFinal(toBytes(data)), Charset.defaultCharset())
        } catch (e: Exception) {
            AppLog.e("AES", e)
        }
        return null
    }

    @JvmStatic
    fun isJson(content: String): Boolean {
        return try {
            JSONObject(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    @JvmStatic
    fun toBytes(src: String): ByteArray {
        val l = src.length / 2
        val ret = ByteArray(l)
        for (i in 0 until l) {
            ret[i] = src.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return ret
    }

}
