package com.shop.reference.session;

/**
 * 呼叫端在請求裡另外夾帶了 {@code externalUserId}，但與目前登入 session 的
 * {@code externalUserId} 不一致時拋出。
 *
 * <p>刻意設計成「明確拒絕」而不是「以 session 為準、沉默忽略呼叫端傳入的值」——
 * 沉默忽略容易讓人誤以為呼叫端傳入的值有被採用（例如串接時傳錯值卻沒有任何錯誤訊息，
 * 除錯困難），而且「呼叫端傳入的 externalUserId 與登入者不同」本身就是可疑訊號
 * （可能是 IDOR 攻擊嘗試，也可能是串接方的程式錯誤），值得直接以錯誤回報，而不是悄悄放過。
 */
public class ExternalUserIdMismatchException extends RuntimeException {

    public ExternalUserIdMismatchException(String message) {
        super(message);
    }
}
