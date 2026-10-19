package trafiqueishon.ui;

import java.util.Locale;

/** Formatos cortos para números grandes que se dibujan en el mapa. */
final class LuisYFormatoCantidad {

    private LuisYFormatoCantidad() {}

    /** {@code 999 → "999"}, {@code 1500 → "1.5k"}, {@code 10000 → "10k"}, {@code 2300000 → "2.3M"}. */
    static String abreviar(long n) {
        if (n < 1000) return Long.toString(n);
        if (n < 1_000_000) return decimal(n / 1000.0, "k");
        return decimal(n / 1_000_000.0, "M");
    }

    private static String decimal(double valor, String sufijo) {
        // Un decimal, sin ".0"; 999.95k se redondearía a 1000k, así que 1M lo cubre aparte.
        double redondeado = Math.round(valor * 10) / 10.0;
        if (redondeado >= 1000 && "k".equals(sufijo)) return "1M";
        return redondeado == Math.rint(redondeado)
                ? String.format(Locale.ROOT, "%d%s", (long) redondeado, sufijo)
                : String.format(Locale.ROOT, "%.1f%s", redondeado, sufijo);
    }
}
