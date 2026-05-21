package com.dt.hgej.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {

    private const val SIGN_KEY_NEW = "zSw3MLRV7VuwT!*G"

    private val ENCRYPTION_PUBLIC_KEY_PEM = """
-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC7yWoQaojBBqKI2H0j4e8ZeX/n1yip6hxrxSVth5F5n1JJ/B3liPMdz6K1chNLFTAcbI7hTL9KkphP9yQ+bPYD68Ajrt/DFrW679Zi1CoeetHVrM4sF68lYarGXwnSlKloaPWnI4Ch9cSqIvIOInlpeJqYPlJ8ZJvGCmbQoM6bewIDAQAB
-----END PUBLIC KEY-----
    """.trimIndent()

    private val SIGNING_PRIVATE_KEY_PEM = """
-----BEGIN PRIVATE KEY-----
MIICeAIBADANBgkqhkiG9w0BAQEFAASCAmIwggJeAgEAAoGBAJ+C8Z9awsGU8DeB
pq47p+pVBgIxWr9epYE5lTrVwoTvOv7dOBTsNgYPgDqFLbU8eZsV26DOvgd4TC5t
ZUWF7WbAleOcxvwA143XTBpZEeDx6who8KiW1WBKUwkeEfXZvOWhN2d+8GlCjvJu
2J4yNGEXScQEIWb+ofE4Pd4yPkkzAgMBAAECgYB0Tzu18a0vEFX0c1JBm3g98w81
jB1aiz3tMzqwMuvqmLIQ4uegwfhGhQkAItoIW/dj8RU7dWS096+87sG4ZwaKCv/S
mT1CibqmSATrX6YNIFU4uXsZzMREJxmZi+V5AllT9DWBG5YjKgrGfWjL0Rq10Zvx
YMTdjO+SbqDIjVoc+QJBAOrMXRO6G349NpLvo1QPevxIykKNKhr5Qkjv4oVydoVo
HW6iMU30PhrBqBYla+K8W+xyeqrjd9ucDQFW/Z2+hD8CQQCt6jz4o7qadQM0giko
BsgWwp7teyZI/8ZH5htrKZwDJzUe6LuM9xjDeXAqqjNjQrDL7M+6T7ZwMmK3UN3b
oe4NAkEA6ioGabYh1TSXSNNVwG/v58twbA78/wm34aXb89rD+Shssflv0p7TkTuxt
uR7RBU2WAmT7PoOfyaSkdN/++IVYQJBAJ/klCvQc/YfkFPNO0N2gK0UP4N8zmUc
6tIdh6XNeocXm+oP9KaUYusMkghXtKkUnnDOBul28fdTC5kYOvD7fl0CQQDLIYfo
8MSMgcFkBH1wRUbhjVv31bk8+4G9a+h7UkLdLtch5qPsS7bsFCyszqEYjhYtQ278
Q20lSzaIsom0Q3ai
-----END PRIVATE KEY-----
    """.trimIndent()

    private val PRIVATE_KEY_PEM = """
-----BEGIN RSA PRIVATE KEY-----
MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbAgEAAoGBAIOBMtf2AIYQlrNy
/lVPHx4R/LKI+Vtk3bKmzID8vdVnh/4WA3lczqfejM10Xfy3sNe4l5EeQTvnDgUH
bIFK8FyJRpvypAmS9oyW6uwGTjZEu5Y6hsSxiGAOG5ZOlH8vOSfuaAkZ+iUlqifP
E3ZOmHkqGzmukg4wCRaPLx5ioq8zAgMBAAECgYAgLOVmx677HmXxBCrMbq57agU9
HZx9SyGfS4Zv7Ob5pvo0Jei1sgpyMlabEmTIp50iOu0CubdWU8MvYdCfldlXQLW7
cjk8N1NyGQLFd2fJ03a7gGWnwwEdPoNTpSHnB+mDL9l7MVjion5fLojzq9Pz1gMK
L01I2TfZBDL4m6EbgQJBAMfgrMKtj7f40GA3qp/y/9/eBCAu8PbtFmtATLMQRf4t
Ghjvn349x1b6FZj8RiaRBSrq0Owjrdo5TUxgfS7dz3MCQQCobdWk2SQhRlqEHfFE
ro/8ab6gn3GhBDzzKvNjhKr2MO6JWqs+Vr+/P9uYpA+G+rv74uVIGWhjuNtI5+/6
9DFBAkAJOQS/tuJ6yrBSwD7PQpcr7UKjeYcE3cu7ByyC1q1kHRCnNedWG+Omz8NP
W9Sg0vA6GrupKbxL5Xj7nTgpgXKhAkBIVlvioAvfaqrngUClAd//RZ9EtxYDVKGk
wnaj8E/Iyr04KsPPU0ypJBD5XsT4cOmZxho5PAhUhAlSJ6MvAf/BAkA64ieVhtQA
1KV0pSSEJMnbPlZe+yBYGTWLMaG2zL0kKEhIs2fIHbVhLFQ8TkO5oH+mhxuuXI5+
nVU2G0dqUl6D
-----END RSA PRIVATE KEY-----
    """.trimIndent()

    private val secureRandom = SecureRandom()
    private val chars = ('A'..'Z') + ('0'..'9')

    fun generateRandomKey(length: Int = 24): String {
        return (1..length).map { chars[secureRandom.nextInt(chars.size)] }.joinToString("")
    }

    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun rsaEncrypt(plaintext: String): String {
        val keyBytes = getPublicKeyBytes(ENCRYPTION_PUBLIC_KEY_PEM)
        val keyFactory = KeyFactory.getInstance("RSA")
        val spec = X509EncodedKeySpec(keyBytes)
        val publicKey = keyFactory.generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(plaintext.toByteArray()), Base64.NO_WRAP)
    }

    fun des3EcbEncrypt(key24: String, plaintext: String): String {
        val keyBytes = key24.toByteArray()
        val secretKey = SecretKeySpec(keyBytes, "DESede")
        val cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return Base64.encodeToString(cipher.doFinal(plaintext.toByteArray()), Base64.NO_WRAP)
    }

    fun rsaSha256Sign(data: String): String {
        val keyBytes = getPrivateKeyBytes(SIGNING_PRIVATE_KEY_PEM)
        val keyFactory = KeyFactory.getInstance("RSA")
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val privateKey = keyFactory.generatePrivate(spec)
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(data.toByteArray())
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    fun decryptData2(data2: String): String {
        val rsaEnc = data2.substring(0, 172)
        val desEnc = data2.substring(172)

        val rsaEncBytes = Base64.decode(rsaEnc, Base64.NO_WRAP)
        val desEncBytes = Base64.decode(desEnc, Base64.NO_WRAP)

        val keyBytes = getPrivateKeyBytes(PRIVATE_KEY_PEM)
        val keyFactory = KeyFactory.getInstance("RSA")
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val privateKey = keyFactory.generatePrivate(spec)
        val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsaCipher.init(Cipher.DECRYPT_MODE, privateKey)
        val aBytes = rsaCipher.doFinal(rsaEncBytes)
        val a = String(aBytes)

        val desKey = ("HTt0Hzsu" + a).toByteArray()
        val iv = a.substring(0, 8).toByteArray()
        val secretKey = SecretKeySpec(desKey, "DESede")
        val desCipher = Cipher.getInstance("DESede/CBC/PKCS5Padding")
        desCipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        val decrypted = desCipher.doFinal(desEncBytes)

        return String(decrypted)
    }

    fun buildEncryptedPayload(
        baseParams: Map<String, Any?>,
        encryptKeys: List<String> = listOf("login_name", "user_id"),
        noSignKeys: List<String> = emptyList()
    ): Map<String, Any?> {
        val m = generateRandomKey(24)
        val payload = baseParams.toMutableMap()

        payload["dec_key"] = rsaEncrypt(m)

        for (key in encryptKeys) {
            if (payload.containsKey(key)) {
                val value = payload[key]?.toString() ?: ""
                payload[key] = des3EcbEncrypt(m, value)
            }
        }

        val signingPayload = payload.filterKeys { it !in noSignKeys }
        val keys = signingPayload.keys.toList()
        val values = keys.joinToString("") { signingPayload[it]?.toString() ?: "" }
        val stringToSign = values + SIGN_KEY_NEW
        val sign = rsaSha256Sign(stringToSign)

        payload["key"] = keys.joinToString(",")
        payload["sign"] = sign

        return payload
    }

    private fun getPublicKeyBytes(pem: String): ByteArray {
        val base64 = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.decode(base64, Base64.NO_WRAP)
    }

    private fun getPrivateKeyBytes(pem: String): ByteArray {
        val base64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.decode(base64, Base64.NO_WRAP)
    }
}
