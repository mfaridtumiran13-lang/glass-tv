package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Prayer times for the strip above the dock: today's six times for one JAKIM zone (config.json,
 * "prayer_zone", e.g. "WLY01" for Kuala Lumpur), and which one comes next.
 *
 * The times come from JAKIM's e-Solat API, a week at a time, and are kept on the TV, so the strip
 * works offline until the saved days run out. The launcher's second network use, and only when a
 * theme sets "prayer_zone". Every failure leaves what is saved in place and never reaches the screen.
 *
 * Times are read and worked out in Malaysian time (Asia/Kuala_Lumpur), whatever the TV's own zone.
 */
final class PrayerTimes {
    private static final String TAG = "TvHome.Prayer";
    private static final String URL_BASE = "https://www.e-solat.gov.my/index.php?r=esolatApi/takwimsolat&zone=";
    /** After an attempt, success or not, wait this long before the next one. */
    private static final long RETRY_MS = 30L * 60 * 1000;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long REFETCH_MS = 6L * 60 * 60 * 1000;
    private static final TimeZone MY = TimeZone.getTimeZone("Asia/Kuala_Lumpur");

    /** In the order of the day; Syuruk is sunrise, where Subuh ends. */
    static final String[] NAMES = {"Subuh", "Syuruk", "Zohor", "Asar", "Maghrib", "Isyak"};
    /** The matching fields in JAKIM's answer. */
    private static final String[] KEYS = {"fajr", "syuruk", "dhuhr", "asr", "maghrib", "isha"};

    private static final Map<String, Integer> MONTHS = new HashMap<>();

    static {
        String[] ms = {"jan", "feb", "mac", "apr", "mei", "jun", "jul", "ogo", "sep", "okt", "nov", "dis"};
        for (int i = 0; i < ms.length; i++) MONTHS.put(ms[i], i);
        // English spellings, in case the answer comes in English.
        MONTHS.put("mar", 2);
        MONTHS.put("may", 4);
        MONTHS.put("aug", 7);
        MONTHS.put("oct", 9);
        MONTHS.put("dec", 11);
    }

    private PrayerTimes() {
    }

    /** The saved days, by date ("2026-10-24"), each with the six times as minutes after midnight. */
    static final class Schedule {
        private final Map<String, int[]> days;

        Schedule(Map<String, int[]> days) {
            this.days = days;
        }

        boolean has(String key) {
            return days.containsKey(key);
        }
    }

    /** The time that comes next, with the day's times it belongs to. */
    static final class Next {
        final int index;
        final long atMs;
        final int[] times;

        Next(int index, long atMs, int[] times) {
            this.index = index;
            this.atMs = atMs;
            this.times = times;
        }

        String name() {
            return NAMES[index];
        }

        int minutes() {
            return times[index];
        }
    }

    /** The saved days for this zone, or null when there are none. */
    static Schedule load(Context ctx, String zone) {
        SharedPreferences p = prefs(ctx);
        if (!zone.equals(p.getString("zone", ""))) return null;
        String raw = p.getString("days", "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONArray a = new JSONArray(raw);
            Map<String, int[]> days = new HashMap<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject d = a.optJSONObject(i);
                JSONArray t = d != null ? d.optJSONArray("t") : null;
                if (t == null || t.length() != NAMES.length) continue;
                int[] m = new int[NAMES.length];
                for (int j = 0; j < m.length; j++) m[j] = t.optInt(j, -1);
                days.put(d.optString("d", ""), m);
            }
            return days.isEmpty() ? null : new Schedule(days);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True when new days should be fetched: none are saved for today, or tomorrow is missing and the
     * last fetch was hours ago. Never twice within half an hour, so a dead network costs nothing.
     */
    static boolean due(Context ctx, String zone) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        long since = now - p.getLong("attempt_at", 0);
        if (since >= 0 && since < RETRY_MS) return false;
        Schedule s = load(ctx, zone);
        if (s == null || !s.has(keyFor(now))) return true;
        long fetchedSince = now - p.getLong("fetched_at", 0);
        return !s.has(keyFor(now + DAY_MS)) && (fetchedSince < 0 || fetchedSince >= REFETCH_MS);
    }

    /**
     * Fetches the week (or, failing that, today) and saves it. Blocking: call from a background
     * thread. Returns true when new days were saved.
     */
    static boolean refresh(Context ctx, String zone) {
        SharedPreferences p = prefs(ctx);
        p.edit().putLong("attempt_at", System.currentTimeMillis()).apply();
        for (String period : new String[]{"week", "today"}) {
            try {
                String body = new String(get(URL_BASE + zone + "&period=" + period, 512 * 1024), StandardCharsets.UTF_8);
                JSONArray days = parse(body);
                if (days.length() == 0) continue;
                p.edit().putString("zone", zone).putString("days", days.toString())
                        .putLong("fetched_at", System.currentTimeMillis()).apply();
                return true;
            } catch (Exception e) {
                Log.w(TAG, period + " failed: " + e);
            }
        }
        return false;
    }

    /**
     * Reads JAKIM's answer into the saved form: [{"d": "2026-10-24", "t": [six minutes]}]. A day whose
     * date or times cannot be read, or whose times are not in order, is left out.
     */
    static JSONArray parse(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        String status = o.optString("status", "");
        if (!status.toUpperCase(Locale.ROOT).startsWith("OK")) throw new IllegalStateException("status " + status);
        JSONArray in = o.optJSONArray("prayerTime");
        JSONArray out = new JSONArray();
        for (int i = 0; in != null && i < in.length(); i++) {
            JSONObject day = in.optJSONObject(i);
            if (day == null) continue;
            String key = normalizeDate(day.optString("date", ""));
            if (key == null) continue;
            JSONArray t = new JSONArray();
            int prev = -1;
            boolean ok = true;
            for (String k : KEYS) {
                int m = minutes(day.optString(k, ""));
                if (m <= prev) {
                    ok = false;
                    break;
                }
                t.put(m);
                prev = m;
            }
            if (!ok) continue;
            JSONObject item = new JSONObject();
            item.put("d", key);
            item.put("t", t);
            out.put(item);
        }
        return out;
    }

    /** "24-Okt-2026" (or "24-Oct-2026", "24-10-2026") as "2026-10-24"; null when it is not a date. */
    static String normalizeDate(String s) {
        if (s == null) return null;
        String[] p = s.trim().split("-");
        if (p.length != 3) return null;
        try {
            int day = Integer.parseInt(p[0].trim());
            String m = p[1].trim().toLowerCase(Locale.ROOT);
            Integer named = m.length() >= 3 ? MONTHS.get(m.substring(0, 3)) : null;
            int month = named != null ? named + 1 : Integer.parseInt(m);
            int year = Integer.parseInt(p[2].trim());
            String key = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
            return startOf(key) >= 0 ? key : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "05:49:00" as minutes after midnight, -1 when it is not a time. */
    static int minutes(String s) {
        if (s == null) return -1;
        String[] p = s.trim().split(":");
        if (p.length < 2) return -1;
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            return h < 0 || h > 23 || m < 0 || m > 59 ? -1 : h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The time that comes next after {@code now}: today's, or, once Isyak has passed, tomorrow's Subuh
     * (today's Subuh stands in when tomorrow is not saved, a minute or so out). Null when today is not
     * saved.
     */
    static Next next(Schedule s, long now) {
        String todayKey = keyFor(now);
        int[] today = s.days.get(todayKey);
        long start = startOf(todayKey);
        if (today == null || start < 0) return null;
        for (int i = 0; i < NAMES.length; i++) {
            long at = start + today[i] * 60_000L;
            if (at > now) return new Next(i, at, today);
        }
        int[] tomorrow = s.days.get(keyFor(start + DAY_MS));
        int[] shown = tomorrow != null ? tomorrow : today;
        return new Next(0, start + DAY_MS + shown[0] * 60_000L, shown);
    }

    /** "4:19 PM", or "16:19" on a 24-hour clock; without the suffix, "4:19". */
    static String clock(int minutes, boolean h24, boolean suffix) {
        int h = minutes / 60, m = minutes % 60;
        if (h24) return String.format(Locale.ROOT, "%02d:%02d", h, m);
        String t = String.format(Locale.ROOT, "%d:%02d", h % 12 == 0 ? 12 : h % 12, m);
        return suffix ? t + (h < 12 ? " AM" : " PM") : t;
    }

    /** How long to go, in words: "12 minit", "2 j 15 min". */
    static String countdown(long ms) {
        long mins = (ms + 59_999L) / 60_000L;
        if (mins <= 1) return "sebentar lagi";
        if (mins < 60) return mins + " minit";
        long h = mins / 60, m = mins % 60;
        return m == 0 ? h + " jam" : h + " j " + m + " min";
    }

    private static String keyFor(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(MY);
        return f.format(new java.util.Date(ms));
    }

    /** The start of a day ("2026-10-24") in Malaysian time, in milliseconds; -1 when it is not a date. */
    private static long startOf(String key) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            f.setTimeZone(MY);
            f.setLenient(false);
            return f.parse(key).getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    private static byte[] get(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > maxBytes) throw new IllegalStateException("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("prayer_times", Context.MODE_PRIVATE);
    }
}
