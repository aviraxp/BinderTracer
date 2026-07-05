package com.btrace.testfake;

/**
 * 验证解析器优先使用 AIDL Stub.getDefaultTransactionName(int):
 * 字段名故意与返回名不同,避免和 TRANSACTION_* 截尾兜底混淆。
 */
public interface IDefaultNameStub {
    void defaultName();

    void fallbackName();

    final class Stub {
        public static final int TRANSACTION_fieldName = 1;
        public static final int TRANSACTION_fallbackName = 2;

        public static String getDefaultTransactionName(int code) {
            if (code == TRANSACTION_fieldName) {
                return "defaultName";
            }
            return null;
        }
    }
}
