package org.las.sso.validation;

/** Checks UTF-16 structure without normalizing or replacing any character. */
public final class UnicodeText {
    private UnicodeText() {}

    public static boolean isWellFormed(String value) {
        if (value == null) return true;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    return false;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }
}
