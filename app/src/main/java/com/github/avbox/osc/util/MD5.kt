package com.github.avbox.osc.util

import android.text.TextUtils
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

class MD5 {

    companion object {

        private val hexDigits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'a', 'b', 'c', 'd', 'e', 'f')

        private fun newDigest(): MessageDigest? {
            try {
                return MessageDigest.getInstance("MD5")
            } catch (e: NoSuchAlgorithmException) {
                Log.e("获取MD5信息摘要失败", "${e.message}")
                return null
            }
        }

        @JvmStatic
        fun encode(res: String): String? {
            val strTemp = res.toByteArray(Charset.defaultCharset())
            return encode(strTemp)
        }

        private fun encode(bytes: ByteArray): String? {
            try {
                val digest = newDigest() ?: return null
                digest.update(bytes)
                val md = digest.digest()
                val j = md.size
                val str = CharArray(j * 2)
                var k = 0
                for (byte0 in md) {
                    str[k++] = hexDigits[(byte0.toInt() ushr 4) and 0xf]
                    str[k++] = hexDigits[byte0.toInt() and 0xf]
                }
                return String(str)
            } catch (e: Exception) {
                return null
            }
        }

        @JvmStatic
        fun fileMd5(f: File): String {
            val sb = StringBuffer("")
            try {
                val md = MessageDigest.getInstance("MD5")
                val buffer = ByteArray(4096)
                val fis = FileInputStream(f)
                var len = 0
                while (fis.read(buffer).also { len = it } != -1) {
                    md.update(buffer, 0, len)
                }
                fis.close()
                val b = md.digest()
                for (i in b.indices) {
                    var d = b[i].toInt()
                    if (d < 0) {
                        d = b[i].toInt() and 0xff
                    }
                    if (d < 16) sb.append("0")
                    sb.append(Integer.toHexString(d))
                }
            } catch (e: NoSuchAlgorithmException) {
                AppLog.e("MD5", e)
            } catch (e: IOException) {
                AppLog.e("MD5", e)
            }
            return sb.toString()
        }

        @JvmStatic
        fun string2MD5(inStr: String?): String? {
            val digest = newDigest()
            if (digest == null) {
                Log.e("MD5", "MD5信息摘要初始化失败")
                return null
            } else if (TextUtils.isEmpty(inStr)) {
                Log.e("MD5", "参数strSource不能为空")
                return null
            }
            val value = inStr ?: return null
            val charArray = value.toCharArray()
            val byteArray = ByteArray(charArray.size)

            for (i in charArray.indices) {
                byteArray[i] = charArray[i].code.toByte()
            }
            val md5Bytes = digest.digest(byteArray)
            val hexValue = StringBuilder()
            for (md5Byte in md5Bytes) {
                val value = md5Byte.toInt() and 0xff
                if (value < 16) {
                    hexValue.append("0")
                }
                hexValue.append(Integer.toHexString(value))
            }
            return hexValue.toString()
        }

        @JvmStatic
        fun encrypt(strSource: String?): String? {
            val digest = newDigest()
            if (digest == null) {
                Log.e("MD5", "MD5信息摘要初始化失败")
                return null
            } else if (TextUtils.isEmpty(strSource)) {
                Log.e("MD5", "参数strSource不能为空")
                return null
            }
            val value = strSource ?: return null
            try {
                val md5Bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
                val encryptBytes = Base64.encode(md5Bytes, Base64.DEFAULT)
                val strEncrypt = String(encryptBytes, Charsets.UTF_8)
                return strEncrypt.substring(0, strEncrypt.length - 1)
            } catch (e: UnsupportedEncodingException) {
                Log.e("MD5", "加密模块暂不支持此字符集合$e")
            }
            return null
        }

        @JvmStatic
        fun encrypt4login(strSource: String?, appSecert: String?): String? {
            val str = encrypt(strSource) + appSecert
            return string2MD5(str)
        }
    }
}
