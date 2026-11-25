package io.github.wintermarkinteractive.economy;

import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Converts between internal minor units (long, e.g. cents) and text.
 * Parsing understands abbreviations: 5k, 2.5m, 1b, 1t.
 */
public final class MoneyFormat {

    private static final Pattern NUMBER = Pattern.compile("^[0-9]*\\.?[0-9]+$");
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private final int decimals;
    private final String singular;
    private final String plural;
    private final String symbol;
    private final String pattern;
    private final String thousandsSep;
    private final String decimalSep;
    private final boolean hideZeroDecimals;
    private final List<String> suffixes;

    /** Reads currency.decimals from the config (clamped to 0..4). */
    public static int configDecimals(FileConfiguration cfg) {
        int d = cfg.getInt("currency.decimals", 2);
        return d < 0 ? 0 : Math.min(d, 4);
    }

    /** @param decimals fixed for the whole server run (changing it needs a restart) */
    public MoneyFormat(FileConfiguration cfg, int decimals) {
        this.decimals = decimals;
        this.singular = cfg.getString("currency.singular", "Dollar");
        this.plural = cfg.getString("currency.plural", "Dollars");
        this.symbol = cfg.getString("currency.symbol", "$");
        this.pattern = cfg.getString("currency.format", "{symbol}{amount}");
        this.thousandsSep = cfg.getString("currency.thousands-separator", ",");
        String ds = cfg.getString("currency.decimal-separator", ".");
        this.decimalSep = ds == null || ds.isEmpty() ? "." : ds;
        this.hideZeroDecimals = cfg.getBoolean("currency.hide-zero-decimals", true);
        List<String> s = cfg.getStringList("currency.compact-suffixes");
        this.suffixes = s.isEmpty() ? List.of("k", "M", "B", "T", "Q") : List.copyOf(s);
    }

    public int decimals() { return decimals; }
    public String singular() { return singular; }
    public String plural() { return plural; }
    public String symbol() { return symbol; }

    // ---------- conversion ----------

    /** Currency units to minor units, rounded down. Returns null on overflow. */
    public Long toMinor(BigDecimal units) {
        if (units == null) return null;
        try {
            return units.setScale(decimals, RoundingMode.DOWN).movePointRight(decimals).longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    public BigDecimal toUnits(long minor) {
        return BigDecimal.valueOf(minor, decimals);
    }

    // ---------- parsing ----------

    /**
     * Parses player input. Returns minor units, or null when the text is not a positive amount.
     * Accepts "250", "1,000", "1.5k", "2M", "$300", "1_000".
     */
    public Long parse(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty() || s.length() > 32) return null;
        if (!symbol.isEmpty()) s = s.replace(symbol, "");
        s = s.replace("_", "").replace(" ", "");
        if (!thousandsSep.isEmpty()) s = s.replace(thousandsSep, "");
        if (!decimalSep.equals(".")) s = s.replace(decimalSep, ".");
        s = s.toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;

        int exp = 0;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> exp = 3;
            case 'm' -> exp = 6;
            case 'b' -> exp = 9;
            case 't' -> exp = 12;
            case 'q' -> exp = 15;
            default -> { }
        }
        if (exp > 0) s = s.substring(0, s.length() - 1);
        if (!NUMBER.matcher(s).matches()) return null;

        BigDecimal value = new BigDecimal(s).movePointRight(exp);
        Long minor = toMinor(value);
        if (minor == null || minor <= 0) return null;
        return minor;
    }

    // ---------- display ----------

    /** Exact display, e.g. "$1,234.50". */
    public String format(long minor) {
        return applyPattern(exactNumber(minor), minor);
    }

    /** Short display, e.g. "$1.2M". Below 1,000 it is the same as {@link #format(long)}. */
    public String formatCompact(long minor) {
        BigDecimal units = toUnits(minor).abs();
        if (units.compareTo(THOUSAND) < 0 || suffixes.isEmpty()) return format(minor);
        int idx = 0;
        BigDecimal scaled = units;
        while (idx < suffixes.size() && scaled.compareTo(THOUSAND) >= 0) {
            scaled = scaled.divide(THOUSAND);
            idx++;
        }
        scaled = scaled.setScale(1, RoundingMode.DOWN).stripTrailingZeros();
        String num = scaled.toPlainString().replace(".", decimalSep) + suffixes.get(idx - 1);
        if (minor < 0) num = "-" + num;
        return applyPattern(num, minor);
    }

    /** Number only, without symbol or name, e.g. "1,234.50". */
    public String exactNumber(long minor) {
        BigInteger abs = BigInteger.valueOf(minor).abs();
        BigInteger[] parts = abs.divideAndRemainder(BigInteger.TEN.pow(decimals));
        String whole = group(parts[0].toString());
        StringBuilder sb = new StringBuilder();
        if (minor < 0) sb.append('-');
        sb.append(whole);
        if (decimals > 0 && !(hideZeroDecimals && parts[1].signum() == 0)) {
            String frac = parts[1].toString();
            while (frac.length() < decimals) frac = "0" + frac;
            sb.append(decimalSep).append(frac);
        }
        return sb.toString();
    }

    private String group(String digits) {
        if (thousandsSep.isEmpty() || digits.length() <= 3) return digits;
        StringBuilder sb = new StringBuilder();
        int first = digits.length() % 3;
        if (first > 0) sb.append(digits, 0, first);
        for (int i = first; i < digits.length(); i += 3) {
            if (sb.length() > 0) sb.append(thousandsSep);
            sb.append(digits, i, i + 3);
        }
        return sb.toString();
    }

    private String applyPattern(String number, long minor) {
        long one = BigInteger.TEN.pow(decimals).longValue();
        String name = Math.abs(minor) == one ? singular : plural;
        return pattern.replace("{symbol}", symbol).replace("{amount}", number).replace("{name}", name);
    }
}
