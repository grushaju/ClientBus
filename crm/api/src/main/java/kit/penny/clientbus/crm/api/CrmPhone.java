package kit.penny.clientbus.crm.api;

/** Formatting-only normalization: no assumed country or replacement of a trunk prefix. */
public final class CrmPhone {
    /** Shared with SQL translate(): formatting only, including Unicode White_Space. */
    public static final String FORMATTING_CHARACTERS = "+()-. \t\n\r\f\013\u0085\u00a0\u1680"
            + "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000";
    private CrmPhone() {}
    public static String normalize(String value) {
        if (value == null) return null;
        var digits = new StringBuilder(15);
        for (int index=0; index<value.length(); index++) {
            char character=value.charAt(index);
            if (character>='0' && character<='9') {
                digits.append(character);
                if (digits.length()>15) return null;
            } else if (FORMATTING_CHARACTERS.indexOf(character)<0) return null;
        }
        return digits.length()>=7 ? digits.toString() : null;
    }
}
