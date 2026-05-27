package com.example.dale.utils

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object HashUtils {
    fun hashPin(pin: String, saltBase64: String? = null): String {
        val salt = if (saltBase64 != null) {
            Base64.decode(saltBase64, Base64.DEFAULT)
        } else {
            val s = ByteArray(16)
            SecureRandom().nextBytes(s)
            s
        }
        val spec = PBEKeySpec(pin.toCharArray(), salt, 10000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        val saltStr = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashStr = Base64.encodeToString(hash, Base64.NO_WRAP)
        return "$saltStr:$hashStr"
    }

    fun verifyPin(inputPin: String, storedHash: String): Boolean {
        if (!storedHash.contains(":")) {
            // fallback for old un-salted SHA256 if needed
            val hashedInput = MessageDigest.getInstance("SHA-256")
                .digest(inputPin.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return hashedInput == storedHash
        }
        val parts = storedHash.split(":")
        if (parts.size != 2) return false
        val saltBase64 = parts[0]
        val expectedHash = hashPin(inputPin, saltBase64)
        return expectedHash == storedHash
    }
}

