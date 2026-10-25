package com.github.catvod.crawler.js.rsa

import android.util.Log

import com.github.avbox.osc.util.AppLog

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

import javax.crypto.Cipher

class RSAEncrypt {

    companion object {

        @JvmField
        val TAG: String = RSAEncrypt::class.java.simpleName + " --> "

        const val ECB_PKCS1_PADDING: String = "RSA/ECB/PKCS1Padding"

        const val KEY_ALGORITHM: String = "RSA"

        private const val MAX_ENCRYPT_BLOCK: Int = 117

        private const val MAX_DECRYPT_BLOCK: Int = 128

        @JvmStatic
        fun getKeyPair(keyLength: Int): KeyPair? {
            try {
                val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
                generator.initialize(keyLength)
                return generator.genKeyPair()
            } catch (e: Exception) {
                handleException(e)
            }
            return null
        }

        @JvmStatic
        fun getPublicKeyBase64(publicKey: PublicKey): String {
            return DataUtils.base64Encode(publicKey.encoded)
        }

        @JvmStatic
        fun getPrivateKeyBase64(privateKey: PrivateKey): String {
            return DataUtils.base64Encode(privateKey.encoded)
        }

        @JvmStatic
        fun getPublicKey(pubKey: String?): PublicKey? {
            if (pubKey == null) {
                handleException(Exception("公钥数据为空")) // i18n: keep(异常消息,只进日志)
                return null
            }
            try {
                return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(DataUtils.base64Decode(pubKey)))
            } catch (unused: NoSuchAlgorithmException) {
                handleException(Exception("无此算法")) // i18n: keep(异常消息,只进日志)
            } catch (unused2: InvalidKeySpecException) {
                handleException(Exception("公钥非法")) // i18n: keep(异常消息,只进日志)
            }
            return null
        }

        @JvmStatic
        fun getPrivateKey(prvKey: String?): PrivateKey? {
            if (prvKey == null) {
                handleException(Exception("私钥数据为空")) // i18n: keep(异常消息,只进日志)
                return null
            }
            try {
                return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(DataUtils.base64Decode(prvKey)))
            } catch (unused: NoSuchAlgorithmException) {
                handleException(Exception("无此算法")) // i18n: keep(异常消息,只进日志)
            } catch (unused2: InvalidKeySpecException) {
                handleException(Exception("私钥非法")) // i18n: keep(异常消息,只进日志)
            }
            return null
        }

        @JvmStatic
        fun encryptByPublicKey(data: String?, pubKey: String?, mlong: Int, block: Boolean): String? {
            return encryptByPublicKey(data, pubKey, ECB_PKCS1_PADDING, mlong, block)
        }

        @JvmStatic
        fun encryptByPublicKey(data: String?, pubKey: String?, config: String?, mlong: Int, block: Boolean): String? {
            try {
                val bytes = requireNotNull(data) { "data is null" }.toByteArray(Charsets.UTF_8)
                val cipher = Cipher.getInstance(config)
                val rSAPublicKey = getPublicKey(pubKey) as RSAPublicKey?
                cipher.init(Cipher.ENCRYPT_MODE, rSAPublicKey)
                if (mlong == 1) {
                    return DataUtils.base64Encode(cipher.doFinal(bytes))
                }
                var bitLength = MAX_ENCRYPT_BLOCK
                if (block) {
                    bitLength = requireNotNull(rSAPublicKey) { "rsa pubkey is null" }.modulus.bitLength() / 8 - 11
                }
                val inputLen = bytes.size
                val out = ByteArrayOutputStream()
                var offSet = 0
                var i = 0
                var cache: ByteArray
                while (inputLen - offSet > 0) {
                    cache = if (inputLen - offSet > bitLength) {
                        cipher.doFinal(bytes, offSet, bitLength)
                    } else {
                        cipher.doFinal(bytes, offSet, inputLen - offSet)
                    }
                    out.write(cache, 0, cache.size)
                    i++
                    offSet = i * bitLength
                }
                val encryptedData = out.toByteArray()
                out.close()
                return DataUtils.base64Encode(encryptedData)
            } catch (e: Exception) {
                handleException(e)
            }
            return null
        }

        @JvmStatic
        fun decryptByPrivateKey(encryptBase64Data: String?, prvKey: String?, mlong: Int, block: Boolean): String? {
            return decryptByPrivateKey(encryptBase64Data, prvKey, ECB_PKCS1_PADDING, mlong, block)
        }

        @JvmStatic
        fun decryptByPrivateKey(encryptBase64Data: String?, prvKey: String?, config: String?, mlong: Int, block: Boolean): String? {
            try {
                val encryptedData = DataUtils.base64Decode(encryptBase64Data)
                val cipher = Cipher.getInstance(config)
                val rSAPrivateKey = getPrivateKey(prvKey) as RSAPrivateKey?
                cipher.init(Cipher.DECRYPT_MODE, rSAPrivateKey)
                if (mlong == 1) {
                    return String(cipher.doFinal(encryptedData), Charset.defaultCharset())
                }
                var bitLength = MAX_DECRYPT_BLOCK
                if (block) {
                    bitLength = requireNotNull(rSAPrivateKey) { "rsa prvkey is null" }.modulus.bitLength() / 8
                }
                val inputLen = encryptedData.size
                val out = ByteArrayOutputStream()
                var offSet = 0
                var i = 0
                var cache: ByteArray
                while (inputLen - offSet > 0) {
                    cache = if (inputLen - offSet > bitLength) {
                        cipher.doFinal(encryptedData, offSet, bitLength)
                    } else {
                        cipher.doFinal(encryptedData, offSet, inputLen - offSet)
                    }
                    out.write(cache, 0, cache.size)
                    i++
                    offSet = i * bitLength
                }
                out.close()
                return out.toString("UTF-8")
            } catch (e: Exception) {
                handleException(e)
            }
            return null
        }

        @JvmStatic
        fun encryptByPrivateKey(data: String?, prvKey: String?, mlong: Int, block: Boolean): String? {
            return encryptByPrivateKey(data, prvKey, ECB_PKCS1_PADDING, mlong, block)
        }

        @JvmStatic
        fun encryptByPrivateKey(data: String?, prvKey: String?, config: String?, mlong: Int, block: Boolean): String? {
            try {
                val bytes = requireNotNull(data) { "data is null" }.toByteArray(Charsets.UTF_8)
                val cipher = Cipher.getInstance(config)
                val rSAPrivateKey = getPrivateKey(prvKey) as RSAPrivateKey?
                cipher.init(Cipher.ENCRYPT_MODE, rSAPrivateKey)
                if (mlong == 1) {
                    return DataUtils.base64Encode(cipher.doFinal(bytes))
                }
                var bitLength = MAX_ENCRYPT_BLOCK
                if (block) {
                    bitLength = requireNotNull(rSAPrivateKey) { "rsa prvkey is null" }.modulus.bitLength() / 8 - 11
                }
                val inputLen = bytes.size
                val out = ByteArrayOutputStream()
                var offSet = 0
                var i = 0
                var cache: ByteArray
                while (inputLen - offSet > 0) {
                    cache = if (inputLen - offSet > bitLength) {
                        cipher.doFinal(bytes, offSet, bitLength)
                    } else {
                        cipher.doFinal(bytes, offSet, inputLen - offSet)
                    }
                    out.write(cache, 0, cache.size)
                    i++
                    offSet = i * bitLength
                }
                val encryptedData = out.toByteArray()
                out.close()
                return DataUtils.base64Encode(encryptedData)
            } catch (e: Exception) {
                handleException(e)
            }
            return null
        }

        @JvmStatic
        fun decryptByPublicKey(encryptBase64Data: String?, pubKey: String?, mlong: Int, block: Boolean): String? {
            return decryptByPublicKey(encryptBase64Data, pubKey, ECB_PKCS1_PADDING, mlong, block)
        }

        @JvmStatic
        fun decryptByPublicKey(encryptBase64Data: String?, pubKey: String?, config: String?, mlong: Int, block: Boolean): String? {
            try {
                val encryptedData = DataUtils.base64Decode(encryptBase64Data)
                val cipher = Cipher.getInstance(config)
                val rSAPublicKey = getPublicKey(pubKey) as RSAPublicKey?
                cipher.init(Cipher.DECRYPT_MODE, rSAPublicKey)
                if (mlong == 1) {
                    return String(cipher.doFinal(encryptedData), Charset.defaultCharset())
                }
                var bitLength = MAX_DECRYPT_BLOCK
                if (block) {
                    bitLength = requireNotNull(rSAPublicKey) { "rsa pubkey is null" }.modulus.bitLength() / 8
                }
                val inputLen = encryptedData.size
                val out = ByteArrayOutputStream()
                var offSet = 0
                var i = 0
                var cache: ByteArray
                while (inputLen - offSet > 0) {
                    cache = if (inputLen - offSet > bitLength) {
                        cipher.doFinal(encryptedData, offSet, bitLength)
                    } else {
                        cipher.doFinal(encryptedData, offSet, inputLen - offSet)
                    }
                    out.write(cache, 0, cache.size)
                    i++
                    offSet = i * bitLength
                }
                out.close()
                return out.toString("UTF-8")
            } catch (e: Exception) {
                handleException(e)
            }
            return null
        }

        private fun handleException(e: Exception) {
            AppLog.e("RSAEncrypt", e)
            Log.e(TAG, TAG + e)
        }
    }
}
