package com.fongmi.syncplay;

/** 空值判断，模块内不再依赖 android.text.TextUtils。 */
final class Texts {

    private Texts() {
    }

    static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
