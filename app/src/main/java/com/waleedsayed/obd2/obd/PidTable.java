package com.waleedsayed.obd2.obd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes the raw data bytes of the OBD-II mode 01 PIDs this app displays.
 *
 * <p>Every formula below is from the SAE J1979 tables; unknown PIDs simply return {@code null} and
 * the caller falls back to showing the raw hex response.</p>
 */
final class PidTable {

    private PidTable() {
    }

    static final class Value {
        final String label;
        final double number;
        final String unit;
        final String text;

        Value(String label, double number, String unit, String text) {
            this.label = label;
            this.number = number;
            this.unit = unit;
            this.text = text;
        }
    }

    private static final Map<String, String> LABELS = new HashMap<>();

    static {
        LABELS.put("04", "حمل المحرك");
        LABELS.put("05", "حرارة التبريد");
        LABELS.put("0B", "ضغط مجمع السحب");
        LABELS.put("0C", "دورات المحرك");
        LABELS.put("0D", "سرعة المركبة");
        LABELS.put("0F", "حرارة هواء السحب");
        LABELS.put("10", "تدفق الهواء MAF");
        LABELS.put("11", "فتحة الخانق");
        LABELS.put("1F", "زمن تشغيل المحرك");
        LABELS.put("21", "المسافة مع لمبة الأعطال");
        LABELS.put("2F", "مستوى الوقود");
        LABELS.put("31", "المسافة منذ مسح الأعطال");
        LABELS.put("42", "جهد وحدة التحكم");
        LABELS.put("5C", "حرارة الزيت");
        LABELS.put("A6", "عدّاد المسافة الكلي");
    }

    static String label(String pid) {
        String known = LABELS.get(pid);
        return known != null ? known : "PID " + pid;
    }

    static Value decode(String pid, int[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        switch (pid) {
            case "04":
                return scalar(pid, percent(data[0]), "%");
            case "05":
                return scalar(pid, data[0] - 40, "°C");
            case "0B":
                return scalar(pid, data[0], "kPa");
            case "0C":
                return data.length >= 2
                        ? scalar(pid, ((data[0] << 8) | data[1]) / 4.0, "rpm")
                        : null;
            case "0D":
                return scalar(pid, data[0], "km/h");
            case "0F":
                return scalar(pid, data[0] - 40, "°C");
            case "10":
                return data.length >= 2
                        ? scalar(pid, ((data[0] << 8) | data[1]) / 100.0, "g/s")
                        : null;
            case "11":
                return scalar(pid, percent(data[0]), "%");
            case "1F":
                return data.length >= 2
                        ? scalar(pid, (data[0] << 8) | data[1], "s")
                        : null;
            case "21":
                return data.length >= 2
                        ? scalar(pid, (data[0] << 8) | data[1], "km")
                        : null;
            case "2F":
                return scalar(pid, percent(data[0]), "%");
            case "31":
                return data.length >= 2
                        ? scalar(pid, (data[0] << 8) | data[1], "km")
                        : null;
            case "42":
                return data.length >= 2
                        ? scalar(pid, ((data[0] << 8) | data[1]) / 1000.0, "V")
                        : null;
            case "5C":
                return scalar(pid, data[0] - 40, "°C");
            case "A6":
                return data.length >= 4
                        ? scalar(pid, odometer(data), "km")
                        : null;
            default:
                return null;
        }
    }

    private static long odometer(int[] data) {
        long raw = ((long) data[0] << 24)
                | ((long) data[1] << 16)
                | ((long) data[2] << 8)
                | (long) data[3];
        return raw;
    }

    private static double percent(int byteValue) {
        return byteValue * 100.0 / 255.0;
    }

    private static Value scalar(String pid, double number, String unit) {
        String text = Math.abs(number - Math.rint(number)) < 0.05
                ? String.format(Locale.US, "%.0f", number)
                : String.format(Locale.US, "%.1f", number);
        return new Value(label(pid), number, unit, text);
    }

    /** Turns mode 03 response bytes into "P0300"-style codes. */
    static List<String> decodeDtcs(int[] data) {
        List<String> codes = new ArrayList<>();
        if (data == null) {
            return codes;
        }
        for (int i = 0; i + 1 < data.length; i += 2) {
            int first = data[i];
            int second = data[i + 1];
            if (first == 0 && second == 0) {
                continue;
            }
            codes.add(formatDtc(first, second));
        }
        return codes;
    }

    static String formatDtc(int first, int second) {
        char system = "PCBU".charAt((first >> 6) & 0x03);
        int digit1 = (first >> 4) & 0x03;
        int digit2 = first & 0x0F;
        int digit3 = (second >> 4) & 0x0F;
        int digit4 = second & 0x0F;
        return String.format(Locale.US, "%c%d%X%X%X", system, digit1, digit2, digit3, digit4);
    }
}
