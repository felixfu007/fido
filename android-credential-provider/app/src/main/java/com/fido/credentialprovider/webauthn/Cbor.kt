package com.fido.credentialprovider.webauthn

import java.io.ByteArrayOutputStream

/**
 * 【PoC 核心】手寫的最小 CBOR 編碼器（RFC 8949），只實作本專案 WebAuthn attestationObject /
 * COSE_Key 需要的子集：
 *  - major type 0（unsigned integer）/ 1（negative integer）
 *  - major type 2（byte string）
 *  - major type 3（text string）
 *  - major type 4（array，definite-length）
 *  - major type 5（map，definite-length）
 *
 * 刻意不依賴任何外部 CBOR 函式庫（例如 jackson-dataformat-cbor）：
 *  1. 這是 docs/android-poc-checklist.md 項目 3 的核心風險驗證對象——若能自己正確手刻 CBOR
 *     編碼器組出 android-key 格式，就證明「自訂 provider 產出 android-key attestationObject」
 *     這件事技術上可行，不依賴任何現成函式庫「剛好」支援 WebAuthn CBOR 子集。
 *  2. 避免在 production 依賴另一個第三方函式庫的 Android 相容性風險（dex 方法數、ProGuard
 *     規則等），生產路徑越精簡越好。
 *
 * 本檔案位元組層級的正確性由 app/src/test 內的 JVM 單元測試驗證：用 fido-server 實際使用的
 * 同一套函式庫（jackson-dataformat-cbor）把本編碼器的輸出解回來，比對欄位是否一致。
 */
object Cbor {

    fun encode(value: CborValue): ByteArray {
        val out = ByteArrayOutputStream()
        writeValue(out, value)
        return out.toByteArray()
    }

    private fun writeValue(out: ByteArrayOutputStream, value: CborValue) {
        when (value) {
            is CborValue.Int -> writeInt(out, value.value)
            is CborValue.Bytes -> {
                writeMajorTypeWithLength(out, MAJOR_BYTE_STRING, value.value.size.toLong())
                out.write(value.value)
            }
            is CborValue.Text -> {
                val bytes = value.value.toByteArray(Charsets.UTF_8)
                writeMajorTypeWithLength(out, MAJOR_TEXT_STRING, bytes.size.toLong())
                out.write(bytes)
            }
            is CborValue.Arr -> {
                writeMajorTypeWithLength(out, MAJOR_ARRAY, value.items.size.toLong())
                value.items.forEach { writeValue(out, it) }
            }
            is CborValue.Obj -> {
                writeMajorTypeWithLength(out, MAJOR_MAP, value.entries.size.toLong())
                value.entries.forEach { (k, v) ->
                    writeValue(out, k)
                    writeValue(out, v)
                }
            }
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        if (v >= 0) {
            writeMajorTypeWithLength(out, MAJOR_UNSIGNED_INT, v)
        } else {
            // CBOR negative integer 編碼儲存的是 (-1 - n)，即 major type 1 的「附加值」代表
            // 實際值 = -1 - 附加值。
            writeMajorTypeWithLength(out, MAJOR_NEGATIVE_INT, -(v) - 1)
        }
    }

    private fun writeMajorTypeWithLength(out: ByteArrayOutputStream, majorType: Int, length: Long) {
        val mt = majorType shl 5
        when {
            length < 24 -> out.write(mt or length.toInt())
            length <= 0xFF -> {
                out.write(mt or 24)
                out.write(length.toInt() and 0xFF)
            }
            length <= 0xFFFF -> {
                out.write(mt or 25)
                out.write(((length shr 8) and 0xFF).toInt())
                out.write((length and 0xFF).toInt())
            }
            length <= 0xFFFFFFFFL -> {
                out.write(mt or 26)
                for (i in 3 downTo 0) {
                    out.write(((length shr (i * 8)) and 0xFF).toInt())
                }
            }
            else -> {
                out.write(mt or 27)
                for (i in 7 downTo 0) {
                    out.write(((length shr (i * 8)) and 0xFF).toInt())
                }
            }
        }
    }

    private const val MAJOR_UNSIGNED_INT = 0
    private const val MAJOR_NEGATIVE_INT = 1
    private const val MAJOR_BYTE_STRING = 2
    private const val MAJOR_TEXT_STRING = 3
    private const val MAJOR_ARRAY = 4
    private const val MAJOR_MAP = 5
}

/** CBOR 值的最小代數型別（僅涵蓋本專案需要的子集，見 [Cbor] 上方說明）。 */
sealed class CborValue {
    data class Int(val value: Long) : CborValue()
    data class Bytes(val value: ByteArray) : CborValue()
    data class Text(val value: String) : CborValue()
    data class Arr(val items: List<CborValue>) : CborValue()
    data class Obj(val entries: List<Pair<CborValue, CborValue>>) : CborValue()

    companion object {
        fun of(v: kotlin.Int): CborValue = Int(v.toLong())
        fun of(v: Long): CborValue = Int(v)
        fun of(v: ByteArray): CborValue = Bytes(v)
        fun of(v: String): CborValue = Text(v)
        fun arr(items: List<CborValue>): CborValue = Arr(items)
        fun obj(vararg entries: Pair<CborValue, CborValue>): CborValue = Obj(entries.toList())
    }
}
