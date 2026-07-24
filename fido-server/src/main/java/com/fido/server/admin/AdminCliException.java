package com.fido.server.admin;

/**
 * admin CLI 已知/預期的錯誤情境（例如 {@code rp_id} 重複、找不到指定租戶、缺少必要參數）。
 * {@link AdminCliRunner} 捕捉此例外後只印出 {@link #getMessage()}（不印 Java stack trace），
 * 並以非 0 狀態碼結束程序；非此類別的例外一律視為未預期錯誤，需印出完整堆疊供排查。
 */
public class AdminCliException extends RuntimeException {

    public AdminCliException(String message) {
        super(message);
    }
}
