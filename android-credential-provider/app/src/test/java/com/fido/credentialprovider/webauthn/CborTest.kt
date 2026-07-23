package com.fido.credentialprovider.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 【2026-07-23 真機除錯新增】回歸測試：[Cbor.encode] 對 map（[CborValue.Obj]）必須自動產出
 * canonical CBOR 鍵值順序（RFC 7049 §3.9：先比編碼後 bytes 長度、長度相同再逐 byte 比較），
 * 不管呼叫端插入順序為何。
 *
 * 這是真機用 Chrome 實際跑註冊 ceremony 才踢到的真實 bug 的根本原因：`AttestationObjectBuilder`
 * 先前手動把 `authData`（編碼後 9 bytes：1 byte header + 8 bytes 內容）插在
 * `attStmt`（編碼後 8 bytes：1 byte header + 7 bytes 內容）**之前**，順序寫反了。
 * jackson-dataformat-cbor（fido-server 用的函式庫、也是本專案既有測試唯一驗證過的解碼器）
 * 不會檢查這個順序，所以先前完全沒有測試踢到；只有 Chromium `components/cbor/reader.h`
 * 明載的「only accepts canonical CBOR」嚴格解碼器會真的拒絕，且拒絕方式是把整個
 * `attestationObject` 判定為解析失敗（`OUT_OF_ORDER_KEY`），而不是回報某個看起來更直覺的
 * 「順序」相關錯誤訊息——這正是真機 logcat 看到
 * `field missing or invalid: attestationObject` 而非其他欄位名稱的原因。
 */
class CborTest {

    private val cborMapper = ObjectMapper(CBORFactory())

    @Test
    fun `map keys are reordered into canonical order even when caller inserts them out of order`() {
        // 刻意用「錯誤」（非 canonical）插入順序：8-byte 鍵 authData 插在 7-byte 鍵 attStmt 之前，
        // 重現 AttestationObjectBuilder 先前的真實 bug。
        val wrongOrderObj = CborValue.obj(
            CborValue.of("fmt") to CborValue.of("x"),
            CborValue.of("authData") to CborValue.of("y"), // 8 bytes 編碼，錯誤地排在較短鍵之前
            CborValue.of("attStmt") to CborValue.of("z"),  // 7 bytes 編碼，canonical 順序應排這裡
        )

        val bytes = Cbor.encode(wrongOrderObj)

        // 用與 fido-server 相同的 jackson-dataformat-cbor 解回 LinkedHashMap，其鍵順序反映
        // 實際編碼位元組順序（Jackson 對 untyped Map 反序列化預設保留串流中的欄位出現順序）。
        @Suppress("UNCHECKED_CAST")
        val decoded = cborMapper.readValue(bytes, Map::class.java) as Map<String, Any>
        assertEquals(
            "canonical CBOR 順序：短鍵優先，長度相同再逐 byte 比較（fmt=4bytes < attStmt=8bytes < authData=9bytes）",
            listOf("fmt", "attStmt", "authData"),
            decoded.keys.toList(),
        )
    }

    @Test
    fun `same-length keys are sorted lexicographically by encoded bytes`() {
        // "sig"(3) / "alg"(3) / "x5c"(3) 皆為同長度 UTF-8 鍵，canonical 順序退化為逐 byte 比較，
        // 對齊 AttestationObjectBuilder 現有 attStmt 順序（alg < sig < x5c）。
        val obj = CborValue.obj(
            CborValue.of("x5c") to CborValue.of(1),
            CborValue.of("alg") to CborValue.of(2),
            CborValue.of("sig") to CborValue.of(3),
        )

        val bytes = Cbor.encode(obj)

        @Suppress("UNCHECKED_CAST")
        val decoded = cborMapper.readValue(bytes, Map::class.java) as Map<String, Any>
        assertEquals(listOf("alg", "sig", "x5c"), decoded.keys.toList())
    }

    @Test
    fun `integer keys are reordered canonically by their encoded byte representation`() {
        // COSE_Key 鍵：1, 3, -1, -2, -3。canonical CBOR 對這五個值編碼後皆為單一 byte，故
        // 依「無號 byte 值」排序：unsigned int N -> 0x00+N；negative int -1-n -> 0x20+n。
        // 1->0x01, 3->0x03, -1->0x20, -2->0x21, -3->0x22，因此正確順序即 1,3,-1,-2,-3
        // （對齊 CoseKeyEncoder 既有書寫順序，本測試釘住這個順序不是巧合而是必然）。
        val obj = CborValue.obj(
            CborValue.of(-3) to CborValue.of("y"),
            CborValue.of(-2) to CborValue.of("x"),
            CborValue.of(-1) to CborValue.of("crv"),
            CborValue.of(3) to CborValue.of("alg"),
            CborValue.of(1) to CborValue.of("kty"),
        )

        val bytes = Cbor.encode(obj)

        // jackson-dataformat-cbor 對非字串 map 鍵反序列化成 untyped Map 時，實際觀察到的行為是
        // 轉成其 `toString()`（對齊本專案既有測試 AttestationObjectBuilderTest.field() 的
        // `map[label] ?: map[label.toString()]` fallback 寫法，非本測試臆測），故同時兼容
        // Number 與 String 兩種可能表示法，重點驗證「順序」而非鍵的執行期型別。
        @Suppress("UNCHECKED_CAST")
        val decoded = cborMapper.readValue(bytes, Map::class.java) as Map<Any, Any>
        val orderedKeys = decoded.keys.map { key ->
            if (key is Number) key.toInt() else key.toString().toInt()
        }
        assertEquals(listOf(1, 3, -1, -2, -3), orderedKeys)
    }

    @Test
    fun `already-canonical insertion order round-trips unchanged`() {
        val obj = CborValue.obj(
            CborValue.of("a") to CborValue.of(1),
            CborValue.of("bb") to CborValue.of(2),
        )

        val bytes = Cbor.encode(obj)

        @Suppress("UNCHECKED_CAST")
        val decoded = cborMapper.readValue(bytes, Map::class.java) as Map<String, Any>
        assertEquals(listOf("a", "bb"), decoded.keys.toList())
    }

    @Test
    fun `non-map values are unaffected by canonical reordering`() {
        val bytesValue = byteArrayOf(1, 2, 3, 4)
        val encoded = Cbor.encode(CborValue.of(bytesValue))
        val decoded = cborMapper.readValue(encoded, ByteArray::class.java)
        assertArrayEquals(bytesValue, decoded)
    }
}
