package com.inacap.accessguardriego

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object SeguridadAES {
    private const val CLAVE_SECRETA = "ArandanosOT2026!" // 16 bytes = 128 bits
    private const val TRANSFORMACION = "AES/ECB/PKCS5Padding"

    fun cifrar(textoPlano: String): String {
        return try {
            val keySpec = SecretKeySpec(CLAVE_SECRETA.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance(TRANSFORMACION)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            val bytesCifrados = cipher.doFinal(textoPlano.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(bytesCifrados, Base64.NO_WRAP)
        } catch (e: Exception) {
            "ERROR_CIFRADO"
        }
    }

    fun descifrar(textoCifrado: String): String {
        return try {
            val keySpec = SecretKeySpec(CLAVE_SECRETA.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance(TRANSFORMACION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val bytesDecodificados = Base64.decode(textoCifrado, Base64.NO_WRAP)
            String(cipher.doFinal(bytesDecodificados), Charsets.UTF_8)
        } catch (e: Exception) {
            "ERROR_DESCIFRADO"
        }
    }
}
