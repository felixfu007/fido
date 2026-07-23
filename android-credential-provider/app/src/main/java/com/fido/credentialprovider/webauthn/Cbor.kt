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
 *
 * 【2026-07-23 真機除錯修正：CBOR map 鍵值必須是「canonical CBOR」順序】RFC 7049 §3.9 要求
 * map 的鍵依「編碼後 bytes 長度，長度相同時再逐 byte 比較」排序；fido-server 使用的
 * jackson-dataformat-cbor（本檔案既有測試 [AttestationObjectBuilderTest] 用於回讀驗證的函式庫）
 * 與本專案先前手動控制插入順序的方式，兩者都**不會強制**這個排序——只要看起來像合法 CBOR
 * map 就直接接受、依編碼順序回填成 `Map`，因此先前 `AttestationObjectBuilder` 手動排列的鍵
 * 順序（`fmt`、`authData`、`attStmt`）不符合 canonical 順序（正確應為 `fmt`(4 bytes 編碼)、
 * `attStmt`(8 bytes)、`authData`(9 bytes)，即先短後長）卻始終沒被任何既有測試或 fido-server
 * 攔下來。真機透過 Chrome 實際跑註冊 ceremony 時才第一次踢到這個問題：Chromium
 * `components/cbor/reader.h` 明載其解碼器「only accepts canonical CBOR」，鍵值順序不符會直接
 * 判定為 `OUT_OF_ORDER_KEY` 解碼失敗，導致 Chrome 原生層 `MakeCredentialResponseFromValue`
 * 在最外層 `attestationObject` 這個欄位的解析階段就失敗（對應 logcat 訊息
 * `field missing or invalid: attestationObject`），比對齊 JSON 欄位（`authenticatorData`/
 * `publicKeyAlgorithm`/`publicKey`，見 [com.fido.credentialprovider.webauthn.RegistrationResponseFields]
 * 檔頭說明）更早發生、更根本。
 *
 * 修法：不再仰賴呼叫端手動把 [CborValue.Obj] 的 entries 依正確順序插入，改由編碼器本身在
 * 序列化 map 前**自動依 canonical 規則排序鍵**，讓「呼叫端插入順序錯誤」這整類錯誤在編碼層
 * 就不可能發生（而不是每處呼叫都要靠人工心算位元組長度來排序、容易再犯）。
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
                // canonical CBOR（RFC 7049 §3.9）：map 鍵一律依「編碼後 bytes 長度」由短到長，
                // 長度相同時再逐 byte（無號）比較排序，不信任呼叫端傳入的 entries 順序。
                val encodedEntries = value.entries.map { (k, v) -> encode(k) to v }
                val canonical = encodedEntries.sortedWith(CANONICAL_MAP_KEY_COMPARATOR)
                writeMajorTypeWithLength(out, MAJOR_MAP, canonical.size.toLong())
                canonical.forEach { (kBytes, v) ->
                    out.write(kBytes)
                    writeValue(out, v)
                }
            }
        }
    }

    /** 依 canonical CBOR 規則比較兩個「已編碼」鍵：先比 bytes 長度，再逐 byte（無號）比較。 */
    private val CANONICAL_MAP_KEY_COMPARATOR: Comparator<Pair<ByteArray, CborValue>> =
        Comparator { (a, _), (b, _) ->
            if (a.size != b.size) {
                a.size - b.size
            } else {
                var i = 0
                var result = 0
                while (i < a.size && result == 0) {
                    result = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                    i++
                }
                result
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
