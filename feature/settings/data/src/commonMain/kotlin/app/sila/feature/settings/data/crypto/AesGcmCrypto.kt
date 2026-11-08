package app.sila.feature.settings.data.crypto

import app.sila.feature.settings.domain.BackupCryptoEngine
import app.sila.feature.settings.domain.BackupDecryptionException

/**
 * محرك تشفير وفك تشفير احتياطي صِلَة بـ AES-256-GCM و PBKDF2-HMAC-SHA256 (NIST SP 800-38D).
 * كود نقي متعدد المنصات (Kotlin Multiplatform) يضمن توافقية كاملة بين أندرويد و iOS،
 * مع الالتزام الصارم بمواصفات الأمان:
 * - 4 بايت ترويسة سحرية ("SILA")
 * - 1 بايت رقم الإصدار (1)
 * - 16 بايت ملح عشوائي (Salt)
 * - 12 بايت شعاع تهيئة عشوائي (IV / Nonce)
 * - 100,000 تكرار لـ PBKDF2WithHmacSHA256 لاشتقاق مفتاح 256-bit
 * - تشفير CTR وحساب وسم مصادقة GCM (16-byte GMAC tag) لمنع أي تلاعب بالبيانات
 */
object AesGcmCrypto : BackupCryptoEngine {

    val MAGIC_HEADER = byteArrayOf(0x53, 0x49, 0x4C, 0x41) // "SILA"
    const val FORMAT_VERSION: Byte = 1
    const val SALT_SIZE_BYTES = 16
    const val IV_SIZE_BYTES = 12
    const val TAG_SIZE_BYTES = 16
    const val KEY_SIZE_BYTES = 32 // AES-256
    const val PBKDF2_ITERATIONS = 100_000
    const val MIN_PAYLOAD_SIZE_BYTES = 4 + 1 + SALT_SIZE_BYTES + IV_SIZE_BYTES + TAG_SIZE_BYTES // 49 بايت

    override fun encrypt(plainBytes: ByteArray, password: String): ByteArray {
        require(password.isNotEmpty()) { "Backup password cannot be empty" }
        val salt = generateSecureRandomBytes(SALT_SIZE_BYTES)
        val iv = generateSecureRandomBytes(IV_SIZE_BYTES)

        val key = pbkdf2HmacSha256(password, salt, PBKDF2_ITERATIONS, KEY_SIZE_BYTES)
        return try {
            val (ciphertext, tag) = encryptAes256Gcm(plainBytes, key, iv)
            val result = ByteArray(4 + 1 + SALT_SIZE_BYTES + IV_SIZE_BYTES + ciphertext.size + TAG_SIZE_BYTES)
            var offset = 0
            MAGIC_HEADER.copyInto(result, offset); offset += MAGIC_HEADER.size
            result[offset++] = FORMAT_VERSION
            salt.copyInto(result, offset); offset += salt.size
            iv.copyInto(result, offset); offset += iv.size
            ciphertext.copyInto(result, offset); offset += ciphertext.size
            tag.copyInto(result, offset)
            result
        } finally {
            key.fill(0)
        }
    }

    override fun decrypt(cipherBytes: ByteArray, password: String): ByteArray {
        require(password.isNotEmpty()) { "Backup password cannot be empty" }
        if (cipherBytes.size < MIN_PAYLOAD_SIZE_BYTES) {
            throw BackupDecryptionException("Corrupted backup: payload is too short (${cipherBytes.size} bytes)")
        }

        for (i in MAGIC_HEADER.indices) {
            if (cipherBytes[i] != MAGIC_HEADER[i]) {
                throw BackupDecryptionException("Invalid backup file: missing SILA magic header")
            }
        }

        val version = cipherBytes[4]
        if (version != FORMAT_VERSION) {
            throw BackupDecryptionException("Unsupported backup format version: $version")
        }

        var offset = 5
        val salt = cipherBytes.copyOfRange(offset, offset + SALT_SIZE_BYTES)
        offset += SALT_SIZE_BYTES
        val iv = cipherBytes.copyOfRange(offset, offset + IV_SIZE_BYTES)
        offset += IV_SIZE_BYTES

        val ciphertextLength = cipherBytes.size - offset - TAG_SIZE_BYTES
        val ciphertext = cipherBytes.copyOfRange(offset, offset + ciphertextLength)
        offset += ciphertextLength
        val tag = cipherBytes.copyOfRange(offset, offset + TAG_SIZE_BYTES)

        val key = pbkdf2HmacSha256(password, salt, PBKDF2_ITERATIONS, KEY_SIZE_BYTES)
        return try {
            decryptAes256Gcm(ciphertext, tag, key, iv)
        } finally {
            key.fill(0)
        }
    }

    // ==========================================
    // NIST AES-256 Engine
    // ==========================================

    private fun encryptAes256Gcm(plain: ByteArray, key: ByteArray, iv: ByteArray): Pair<ByteArray, ByteArray> {
        val h = ByteArray(16)
        aesEncryptBlock(ByteArray(16), h, key)

        // J0 = IV || 00000001
        val j0 = ByteArray(16)
        iv.copyInto(j0, 0, 0, 12)
        j0[15] = 1

        // Keystream start with CB1 = inc32(J0)
        val cb = j0.copyOf()
        inc32(cb)

        val ciphertext = ByteArray(plain.size)
        val block = ByteArray(16)
        val encryptedBlock = ByteArray(16)

        var pOffset = 0
        while (pOffset < plain.size) {
            aesEncryptBlock(cb, encryptedBlock, key)
            inc32(cb)
            val chunkSize = minOf(16, plain.size - pOffset)
            for (i in 0 until chunkSize) {
                ciphertext[pOffset + i] = (plain[pOffset + i].toInt() xor encryptedBlock[i].toInt()).toByte()
            }
            pOffset += chunkSize
        }

        // Tag: GHASH(H, ciphertext) XOR E(K, J0)
        val s = ghash(h, ciphertext)
        val ej0 = ByteArray(16)
        aesEncryptBlock(j0, ej0, key)
        val tag = ByteArray(16)
        for (i in 0 until 16) {
            tag[i] = (s[i].toInt() xor ej0[i].toInt()).toByte()
        }
        return Pair(ciphertext, tag)
    }

    private fun decryptAes256Gcm(ciphertext: ByteArray, tag: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val h = ByteArray(16)
        aesEncryptBlock(ByteArray(16), h, key)

        val j0 = ByteArray(16)
        iv.copyInto(j0, 0, 0, 12)
        j0[15] = 1

        val s = ghash(h, ciphertext)
        val ej0 = ByteArray(16)
        aesEncryptBlock(j0, ej0, key)
        val expectedTag = ByteArray(16)
        for (i in 0 until 16) {
            expectedTag[i] = (s[i].toInt() xor ej0[i].toInt()).toByte()
        }

        // Constant-time tag check
        var diff = 0
        for (i in 0 until 16) {
            diff = diff or (tag[i].toInt() xor expectedTag[i].toInt())
        }
        if (diff != 0) {
            throw BackupDecryptionException("Corrupted backup: authentication tag mismatch (tampering detected)")
        }

        // Decrypt CTR
        val cb = j0.copyOf()
        inc32(cb)

        val plain = ByteArray(ciphertext.size)
        val encryptedBlock = ByteArray(16)
        var cOffset = 0
        while (cOffset < ciphertext.size) {
            aesEncryptBlock(cb, encryptedBlock, key)
            inc32(cb)
            val chunkSize = minOf(16, ciphertext.size - cOffset)
            for (i in 0 until chunkSize) {
                plain[cOffset + i] = (ciphertext[cOffset + i].toInt() xor encryptedBlock[i].toInt()).toByte()
            }
            cOffset += chunkSize
        }
        return plain
    }

    private fun inc32(block: ByteArray) {
        for (i in 15 downTo 12) {
            val v = (block[i].toInt() and 0xFF) + 1
            block[i] = v.toByte()
            if (v <= 0xFF) break
        }
    }

    // GHASH (GF(2^128) polynomial multiplication)
    private fun ghash(h: ByteArray, data: ByteArray): ByteArray {
        val y = ByteArray(16)
        var offset = 0
        val block = ByteArray(16)
        while (offset < data.size) {
            block.fill(0)
            val len = minOf(16, data.size - offset)
            data.copyInto(block, 0, offset, offset + len)
            for (i in 0 until 16) {
                y[i] = (y[i].toInt() xor block[i].toInt()).toByte()
            }
            gf128Multiply(y, h)
            offset += 16
        }
        // Length block: [len(A) in bits (64-bit)][len(C) in bits (64-bit)]
        block.fill(0)
        val bitLen = (data.size.toLong()) * 8L
        for (i in 0 until 8) {
            block[15 - i] = ((bitLen ushr (i * 8)) and 0xFFL).toByte()
        }
        for (i in 0 until 16) {
            y[i] = (y[i].toInt() xor block[i].toInt()).toByte()
        }
        gf128Multiply(y, h)
        return y
    }

    private fun gf128Multiply(x: ByteArray, y: ByteArray) {
        val v = y.copyOf()
        val z = ByteArray(16)
        for (i in 0 until 128) {
            val bytePos = i / 8
            val bitPos = 7 - (i % 8)
            if (((x[bytePos].toInt() ushr bitPos) and 1) != 0) {
                for (j in 0 until 16) z[j] = (z[j].toInt() xor v[j].toInt()).toByte()
            }
            val lsb = (v[15].toInt() and 1) != 0
            // Right shift V by 1 bit
            for (j in 15 downTo 1) {
                v[j] = (((v[j].toInt() and 0xFF) ushr 1) or ((v[j - 1].toInt() and 1) shl 7)).toByte()
            }
            v[0] = ((v[0].toInt() and 0xFF) ushr 1).toByte()
            if (lsb) {
                v[0] = (v[0].toInt() xor 0xE1).toByte()
            }
        }
        z.copyInto(x)
    }

    // AES block encryption (16 bytes input -> 16 bytes output with 32-byte key)
    private fun aesEncryptBlock(input: ByteArray, output: ByteArray, key: ByteArray) {
        val w = expandKey(key)
        var s0 = (input[0].toInt() and 0xFF shl 24) or (input[1].toInt() and 0xFF shl 16) or (input[2].toInt() and 0xFF shl 8) or (input[3].toInt() and 0xFF) xor w[0]
        var s1 = (input[4].toInt() and 0xFF shl 24) or (input[5].toInt() and 0xFF shl 16) or (input[6].toInt() and 0xFF shl 8) or (input[7].toInt() and 0xFF) xor w[1]
        var s2 = (input[8].toInt() and 0xFF shl 24) or (input[9].toInt() and 0xFF shl 16) or (input[10].toInt() and 0xFF shl 8) or (input[11].toInt() and 0xFF) xor w[2]
        var s3 = (input[12].toInt() and 0xFF shl 24) or (input[13].toInt() and 0xFF shl 16) or (input[14].toInt() and 0xFF shl 8) or (input[15].toInt() and 0xFF) xor w[3]

        for (r in 1 until 14) {
            val t0 = TE0[(s0 ushr 24) and 0xFF] xor TE1[(s1 ushr 16) and 0xFF] xor TE2[(s2 ushr 8) and 0xFF] xor TE3[s3 and 0xFF] xor w[r * 4]
            val t1 = TE0[(s1 ushr 24) and 0xFF] xor TE1[(s2 ushr 16) and 0xFF] xor TE2[(s3 ushr 8) and 0xFF] xor TE3[s0 and 0xFF] xor w[r * 4 + 1]
            val t2 = TE0[(s2 ushr 24) and 0xFF] xor TE1[(s3 ushr 16) and 0xFF] xor TE2[(s0 ushr 8) and 0xFF] xor TE3[s1 and 0xFF] xor w[r * 4 + 2]
            val t3 = TE0[(s3 ushr 24) and 0xFF] xor TE1[(s0 ushr 16) and 0xFF] xor TE2[(s1 ushr 8) and 0xFF] xor TE3[s2 and 0xFF] xor w[r * 4 + 3]
            s0 = t0; s1 = t1; s2 = t2; s3 = t3
        }

        // Final round
        val r0 = (SBOX[(s0 ushr 24) and 0xFF].toInt() and 0xFF shl 24) or (SBOX[(s1 ushr 16) and 0xFF].toInt() and 0xFF shl 16) or (SBOX[(s2 ushr 8) and 0xFF].toInt() and 0xFF shl 8) or (SBOX[s3 and 0xFF].toInt() and 0xFF) xor w[56]
        val r1 = (SBOX[(s1 ushr 24) and 0xFF].toInt() and 0xFF shl 24) or (SBOX[(s2 ushr 16) and 0xFF].toInt() and 0xFF shl 16) or (SBOX[(s3 ushr 8) and 0xFF].toInt() and 0xFF shl 8) or (SBOX[s0 and 0xFF].toInt() and 0xFF) xor w[57]
        val r2 = (SBOX[(s2 ushr 24) and 0xFF].toInt() and 0xFF shl 24) or (SBOX[(s3 ushr 16) and 0xFF].toInt() and 0xFF shl 16) or (SBOX[(s0 ushr 8) and 0xFF].toInt() and 0xFF shl 8) or (SBOX[s1 and 0xFF].toInt() and 0xFF) xor w[58]
        val r3 = (SBOX[(s3 ushr 24) and 0xFF].toInt() and 0xFF shl 24) or (SBOX[(s0 ushr 16) and 0xFF].toInt() and 0xFF shl 16) or (SBOX[(s1 ushr 8) and 0xFF].toInt() and 0xFF shl 8) or (SBOX[s2 and 0xFF].toInt() and 0xFF) xor w[59]

        output[0] = (r0 ushr 24).toByte(); output[1] = (r0 ushr 16).toByte(); output[2] = (r0 ushr 8).toByte(); output[3] = r0.toByte()
        output[4] = (r1 ushr 24).toByte(); output[5] = (r1 ushr 16).toByte(); output[6] = (r1 ushr 8).toByte(); output[7] = r1.toByte()
        output[8] = (r2 ushr 24).toByte(); output[9] = (r2 ushr 16).toByte(); output[10] = (r2 ushr 8).toByte(); output[11] = r2.toByte()
        output[12] = (r3 ushr 24).toByte(); output[13] = (r3 ushr 16).toByte(); output[14] = (r3 ushr 8).toByte(); output[15] = r3.toByte()
    }

    private fun expandKey(key: ByteArray): IntArray {
        val w = IntArray(60)
        for (i in 0 until 8) {
            w[i] = (key[i * 4].toInt() and 0xFF shl 24) or
                   (key[i * 4 + 1].toInt() and 0xFF shl 16) or
                   (key[i * 4 + 2].toInt() and 0xFF shl 8) or
                   (key[i * 4 + 3].toInt() and 0xFF)
        }
        for (i in 8 until 60) {
            var temp = w[i - 1]
            if (i % 8 == 0) {
                // RotWord and SubWord and XOR Rcon
                val rot = (temp shl 8) or (temp ushr 24)
                temp = subWord(rot) xor (RCON[i / 8] shl 24)
            } else if (i % 8 == 4) {
                temp = subWord(temp)
            }
            w[i] = w[i - 8] xor temp
        }
        return w
    }

    private fun subWord(w: Int): Int {
        return (SBOX[(w ushr 24) and 0xFF].toInt() and 0xFF shl 24) or
               (SBOX[(w ushr 16) and 0xFF].toInt() and 0xFF shl 16) or
               (SBOX[(w ushr 8) and 0xFF].toInt() and 0xFF shl 8) or
               (SBOX[w and 0xFF].toInt() and 0xFF)
    }

    // ==========================================
    // PBKDF2-HMAC-SHA256
    // ==========================================

    fun pbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
        val passwordBytes = password.encodeToByteArray()
        val result = ByteArray(keyLengthBytes)
        val hmac = HmacSha256(passwordBytes)

        val u = ByteArray(32)
        val block = ByteArray(32)

        var blockIndex = 1
        var offset = 0
        while (offset < keyLengthBytes) {
            // U_1 = HMAC(password, salt || INT_32_BE(blockIndex))
            val saltAndIndex = ByteArray(salt.size + 4)
            salt.copyInto(saltAndIndex, 0)
            saltAndIndex[salt.size] = (blockIndex ushr 24).toByte()
            saltAndIndex[salt.size + 1] = (blockIndex ushr 16).toByte()
            saltAndIndex[salt.size + 2] = (blockIndex ushr 8).toByte()
            saltAndIndex[salt.size + 3] = blockIndex.toByte()

            hmac.compute(saltAndIndex, u)
            u.copyInto(block)

            // U_2 .. U_iterations
            for (iter in 2..iterations) {
                hmac.compute(u, u)
                for (i in 0 until 32) {
                    block[i] = (block[i].toInt() xor u[i].toInt()).toByte()
                }
            }

            val take = minOf(32, keyLengthBytes - offset)
            block.copyInto(result, offset, 0, take)
            offset += take
            blockIndex++
        }
        return result
    }

    // HMAC-SHA256
    private class HmacSha256(key: ByteArray) {
        private val kIpad = ByteArray(64)
        private val kOpad = ByteArray(64)

        init {
            val k = ByteArray(64)
            if (key.size > 64) {
                sha256(key).copyInto(k, 0, 0, 32)
            } else {
                key.copyInto(k, 0, 0, key.size)
            }
            for (i in 0 until 64) {
                kIpad[i] = (k[i].toInt() xor 0x36).toByte()
                kOpad[i] = (k[i].toInt() xor 0x5C).toByte()
            }
        }

        fun compute(data: ByteArray, out: ByteArray) {
            val inner = ByteArray(64 + data.size)
            kIpad.copyInto(inner, 0)
            data.copyInto(inner, 64)
            val innerHash = sha256(inner)

            val outer = ByteArray(64 + 32)
            kOpad.copyInto(outer, 0)
            innerHash.copyInto(outer, 64)
            val finalHash = sha256(outer)
            finalHash.copyInto(out, 0, 0, 32)
        }
    }

    // Pure SHA-256
    private fun sha256(data: ByteArray): ByteArray {
        var h0 = 0x6a09e667
        var h1 = -0x4498517b
        var h2 = 0x3c6ef372
        var h3 = -0x5ab00ac6
        var h4 = 0x510e527f
        var h5 = -0x64fa9773
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        val bitLen = data.size.toLong() * 8L
        val padLen = (64 - ((data.size + 9) % 64)) % 64
        val totalLen = data.size + 1 + padLen + 8
        val padded = ByteArray(totalLen)
        data.copyInto(padded, 0)
        padded[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[totalLen - 1 - i] = ((bitLen ushr (i * 8)) and 0xFFL).toByte()
        }

        val w = IntArray(64)
        var offset = 0
        while (offset < totalLen) {
            for (i in 0 until 16) {
                w[i] = (padded[offset + i * 4].toInt() and 0xFF shl 24) or
                       (padded[offset + i * 4 + 1].toInt() and 0xFF shl 16) or
                       (padded[offset + i * 4 + 2].toInt() and 0xFF shl 8) or
                       (padded[offset + i * 4 + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = (w[i - 15] ushr 7 or (w[i - 15] shl 25)) xor
                         (w[i - 15] ushr 18 or (w[i - 15] shl 14)) xor
                         (w[i - 15] ushr 3)
                val s1 = (w[i - 2] ushr 17 or (w[i - 2] shl 15)) xor
                         (w[i - 2] ushr 19 or (w[i - 2] shl 13)) xor
                         (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var h = h7

            for (i in 0 until 64) {
                val s1 = (e ushr 6 or (e shl 26)) xor (e ushr 11 or (e shl 21)) xor (e ushr 25 or (e shl 7))
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + K[i] + w[i]
                val s0 = (a ushr 2 or (a shl 30)) xor (a ushr 13 or (a shl 19)) xor (a ushr 22 or (a shl 10))
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj

                h = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }

            h0 += a; h1 += b; h2 += c; h3 += d
            h4 += e; h5 += f; h6 += g; h7 += h
            offset += 64
        }

        val out = ByteArray(32)
        val hs = intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
        for (i in 0 until 8) {
            out[i * 4] = (hs[i] ushr 24).toByte()
            out[i * 4 + 1] = (hs[i] ushr 16).toByte()
            out[i * 4 + 2] = (hs[i] ushr 8).toByte()
            out[i * 4 + 3] = hs[i].toByte()
        }
        return out
    }

    private fun generateSecureRandomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        // Multiplatform secure random generation
        fillSecureRandom(bytes)
        return bytes
    }

    // Static tables for AES
    private val SBOX = byteArrayOf(
        0x63, 0x7c, 0x77, 0x7b, 0xf2.toByte(), 0x6b, 0x6f, 0xc5.toByte(), 0x30, 0x01, 0x67, 0x2b, 0xfe.toByte(), 0xd7.toByte(), 0xab.toByte(), 0x76,
        0xca.toByte(), 0x82.toByte(), 0xc9.toByte(), 0x7d, 0xfa.toByte(), 0x59, 0x47, 0xf0.toByte(), 0xad.toByte(), 0xd4.toByte(), 0xa2.toByte(), 0xaf.toByte(), 0x9c.toByte(), 0xa4.toByte(), 0x72, 0xc0.toByte(),
        0xb7.toByte(), 0xfd.toByte(), 0x93.toByte(), 0x26, 0x36, 0x3f, 0xf7.toByte(), 0xcc.toByte(), 0x34, 0xa5.toByte(), 0xe5.toByte(), 0xf1.toByte(), 0x71, 0xd8.toByte(), 0x31, 0x15,
        0x04, 0xc7.toByte(), 0x23, 0xc3.toByte(), 0x18, 0x96.toByte(), 0x05, 0x9a.toByte(), 0x07, 0x12, 0x80.toByte(), 0xe2.toByte(), 0xeb.toByte(), 0x27, 0xb2.toByte(), 0x75,
        0x09, 0x83.toByte(), 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0.toByte(), 0x52, 0x3b, 0xd6.toByte(), 0xb3.toByte(), 0x29, 0xe3.toByte(), 0x2f, 0x84.toByte(),
        0x53, 0xd1.toByte(), 0x00, 0xed.toByte(), 0x20, 0xfc.toByte(), 0xb1.toByte(), 0x5b, 0x6a, 0xcb.toByte(), 0xbe.toByte(), 0x39, 0x4a, 0x4c, 0x58, 0xcf.toByte(),
        0xd0.toByte(), 0xef.toByte(), 0xaa.toByte(), 0xfb.toByte(), 0x43, 0x4d, 0x33, 0x85.toByte(), 0x45, 0xf9.toByte(), 0x02, 0x7f, 0x50, 0x3c, 0x9f.toByte(), 0xa8.toByte(),
        0x51, 0xa3.toByte(), 0x40, 0x8f.toByte(), 0x92.toByte(), 0x9d.toByte(), 0x38, 0xf5.toByte(), 0xbc.toByte(), 0xb6.toByte(), 0xda.toByte(), 0x21, 0x10, 0xff.toByte(), 0xf3.toByte(), 0xd2.toByte(),
        0xcd.toByte(), 0x0c, 0x13, 0xec.toByte(), 0x5f, 0x97.toByte(), 0x44, 0x17, 0xc4.toByte(), 0xa7.toByte(), 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
        0x60, 0x81.toByte(), 0x4f, 0xdc.toByte(), 0x22, 0x2a, 0x90.toByte(), 0x88.toByte(), 0x46, 0xee.toByte(), 0xb8.toByte(), 0x14, 0xde.toByte(), 0x5e, 0x0b, 0xdb.toByte(),
        0xe0.toByte(), 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2.toByte(), 0xd3.toByte(), 0xac.toByte(), 0x62, 0x91.toByte(), 0x95.toByte(), 0xe4.toByte(), 0x79,
        0xe7.toByte(), 0xc8.toByte(), 0x37, 0x6d, 0x8d.toByte(), 0xd5.toByte(), 0x4e, 0xa9.toByte(), 0x6c, 0x56, 0xf4.toByte(), 0xea.toByte(), 0x65, 0x7a, 0xae.toByte(), 0x08,
        0xba.toByte(), 0x78, 0x25, 0x2e, 0x1c, 0xa6.toByte(), 0xb4.toByte(), 0xc6.toByte(), 0xe8.toByte(), 0xdd.toByte(), 0x74, 0x1f, 0x4b, 0xbd.toByte(), 0x8b.toByte(), 0x8a.toByte(),
        0x70, 0x3e, 0xb5.toByte(), 0x66, 0x48, 0x03, 0xf6.toByte(), 0x0e, 0x61, 0x35, 0x57, 0xb9.toByte(), 0x86.toByte(), 0xc1.toByte(), 0x1d, 0x9e.toByte(),
        0xe1.toByte(), 0xf8.toByte(), 0x98.toByte(), 0x11, 0x69, 0xd9.toByte(), 0x8e.toByte(), 0x94.toByte(), 0x9b.toByte(), 0x1e, 0x87.toByte(), 0xe9.toByte(), 0xce.toByte(), 0x55, 0x28, 0xdf.toByte(),
        0x8c.toByte(), 0xa1.toByte(), 0x89.toByte(), 0x0d, 0xbf.toByte(), 0xe6.toByte(), 0x42, 0x68, 0x41, 0x99.toByte(), 0x2d, 0x0f, 0xb0.toByte(), 0x54, 0xbb.toByte(), 0x16
    )

    private val RCON = intArrayOf(
        0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36
    )

    private val TE0 = IntArray(256)
    private val TE1 = IntArray(256)
    private val TE2 = IntArray(256)
    private val TE3 = IntArray(256)

    init {
        for (i in 0 until 256) {
            val s = SBOX[i].toInt() and 0xFF
            val s2 = if ((s and 0x80) != 0) ((s shl 1) xor 0x11b) and 0xFF else s shl 1
            val s3 = s2 xor s

            val v = (s2 shl 24) or (s shl 16) or (s shl 8) or s3
            TE0[i] = v
            TE1[i] = (v ushr 8) or (v shl 24)
            TE2[i] = (v ushr 16) or (v shl 16)
            TE3[i] = (v ushr 24) or (v shl 8)
        }
    }

    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e7c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a5c4014, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, 0x0061c2e6, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b37a6f4, -0x7338fdf8, -0x6f410006, -0x5b1231a4, -0x414c9ce9, -0x3f0aa407
    )
}

/**
 * دالة عشوائية آمنة بحسب المنصة (expect / actual)
 */
expect fun fillSecureRandom(bytes: ByteArray)
