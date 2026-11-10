package me.bounser.nascraft.web;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.managers.MarketNews;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.market.unit.stats.Instant;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.bounser.nascraft.database.commands.ItemProperties;

/**
 * Sparklines and price charts as SVG files next to the pages. Rewritten every minute, only when they change,
 * so viewers re-render once a minute at most. Hour charts use the in-memory minute samples; day charts the
 * five-minute history in the database (read off the main thread).
 */
public final class ExchangeCharts {

    private static final String UP = "#7be07b", DOWN = "#ff7a6a", FLAT = "#e0c27a";

    private ExchangeCharts() { }

    /** Snapshot on the main thread, then draw and write asynchronously. */
    public static void refresh() {
        if (!ExchangePages.available()) return;
        Path dir = ExchangePages.dir();
        List<Object[]> work = new ArrayList<>();
        for (Port market : ExchangePages.markets())
            for (Item item : market.getParentItems())
                if (!item.isPlayerOnly())
                    work.add(new Object[]{item, new ArrayList<>(item.getPrice().getValuesPastHour()), item.getPrice().getValue()});
        Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), () -> {
            MarketNews.get().reload();
            Map<String, Long> latest = DatabaseManager.get().getDatabase().latestDayRowIds();
            for (Object[] w : work) {
                try { draw(dir, (Item) w[0], castList(w[1]), (Double) w[2], latest); }
                catch (Exception ex) { Nascraft.getInstance().getLogger().warning("Chart failed for " + ((Item) w[0]).getIdentifier() + ": " + ex); }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Double> castList(Object o) { return (List<Double>) o; }

    /**
     * First write when pages are generated: the SVGs are drawn on the main thread from in-memory data and only the
     * file writes are queued. {@link ExchangePages#write()} starts the minute refresh after they ran.
     */
    static void plan(Path dir, List<ExchangePages.FileOp> ops) {
        for (Port market : ExchangePages.markets())
            for (Item item : market.getParentItems()) {
                Path spark = dir.resolve(name(item, "spark"));
                if (item.isPlayerOnly()) {
                    String flat = flatSpark();
                    ops.add(() -> write(spark, flat));
                    continue;
                }
                List<Double> hour = new ArrayList<>(item.getPrice().getValuesPastHour());
                hour.add(item.getPrice().getValue());
                String sparkSvg = spark(hour), hourSvg = chart(hour, null);
                Path hourFile = dir.resolve(name(item, "chart").replace(".svg", "-h.svg"));
                Path day = dir.resolve(name(item, "chart").replace(".svg", "-d.svg"));
                ops.add(() -> {
                    write(spark, sparkSvg);
                    write(hourFile, hourSvg);
                    if (!Files.exists(day)) write(day, hourSvg);
                });
            }
    }

    private static String name(Item item, String kind) {
        return "nascraft-" + kind + "-" + item.getPort().getId() + "-" + item.getIdentifier() + ".svg";
    }

    /** A good's stored day rows (without the live point getDayPrices appends), and the newest row id they include. */
    private record DayRows(long newestId, long fetchedAt, List<Instant> rows) { }
    private static final Map<String, DayRows> DAY = new ConcurrentHashMap<>();
    private static final long DAY_REFETCH_MS = 15 * 60_000; // the 24 h window still slides when no row arrives

    /** Day history for the chart: re-queried only when a new five-minute row exists (one grouped query per refresh). */
    private static List<Instant> dayRows(Item item, Map<String, Long> latest) {
        String key = ItemProperties.key(item.getPort().getId(), item.getIdentifier());
        long now = System.currentTimeMillis();
        Long newest = latest == null ? null : latest.getOrDefault(key, -1L);
        DayRows cached = DAY.get(key);
        List<Instant> rows;
        if (newest != null && cached != null && cached.newestId() == newest && now - cached.fetchedAt() < DAY_REFETCH_MS) {
            rows = new ArrayList<>(cached.rows());
        } else {
            List<Instant> fetched = DatabaseManager.get().getDatabase().getDayPrices(item);
            rows = new ArrayList<>(fetched.isEmpty() ? fetched : fetched.subList(0, fetched.size() - 1));
            // getDayPrices lists stored rows newest first (ORDER BY id DESC): oldest first for the chart,
            // so day.get(0) is the price ~24 h ago and the line runs left to right in time
            rows.sort(java.util.Comparator.comparing(Instant::getLocalDateTime));
            if (newest != null && !fetched.isEmpty()) DAY.put(key, new DayRows(newest, now, List.copyOf(rows)));
        }
        // The live point getDayPrices ends with (read at query time, as before).
        rows.add(new Instant(java.time.LocalDateTime.now(), item.getPrice().getValue(), item.getVolume()));
        return rows;
    }

    private static void draw(Path dir, Item item, List<Double> hour, double now, Map<String, Long> latest) throws IOException {
        hour.add(now);
        List<Instant> dayRows = dayRows(item, latest);
        List<Double> day = new ArrayList<>();
        List<Integer> volume = new ArrayList<>();
        for (Instant i : dayRows) if (i.getPrice() > 0) { day.add(i.getPrice()); volume.add(i.getVolume()); }
        day.add(now);
        volume.add(0);
        if (day.size() > 1 && day.get(0) > 0) item.setChangeLastDayCached(now / day.get(0) - 1);
        write(dir.resolve(name(item, "spark")), spark(hour));
        write(dir.resolve(name(item, "chart").replace(".svg", "-h.svg")), chart(hour, null));
        write(dir.resolve(name(item, "chart").replace(".svg", "-d.svg")), chart(day.size() > 2 ? day : hour, day.size() > 2 ? volume : null));
    }

    /** What we last wrote to each chart (hash and length), so an unchanged chart costs no disk read. */
    private static final java.util.Map<Path, Long> WRITTEN = new java.util.concurrent.ConcurrentHashMap<>();

    private static void write(Path file, String svg) throws IOException {
        long sig = ((long) svg.hashCode() << 32) ^ svg.length();
        Long last = WRITTEN.get(file);
        if (last != null && last == sig && Files.exists(file)) return;
        if (last == null && Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(svg)) {
            WRITTEN.put(file, sig);
            return;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, svg, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        WRITTEN.put(file, sig);
    }

    private static String colour(List<Double> v) {
        double first = v.get(0), last = v.get(v.size() - 1);
        if (first <= 0) return FLAT;
        double change = last / first - 1;
        return change > 0.0005 ? UP : change < -0.0005 ? DOWN : FLAT;
    }

    private static String f(double d) { return String.format(Locale.ROOT, "%.1f", d); }

    private static String flatSpark() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 220 44\" width=\"220\" height=\"44\">"
                + "<path d=\"M2 22H218\" stroke=\"#c9b48a\" stroke-width=\"2\" stroke-dasharray=\"4 4\" opacity=\".5\"/></svg>\n";
    }

    static String spark(List<Double> v) {
        int w = 220, h = 44;
        double min = v.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = v.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        if (max - min < max * 0.02) { double mid = (max + min) / 2; min = mid * 0.99; max = mid * 1.01 + 1e-9; }
        String c = colour(v);
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < v.size(); i++) {
            double x = 2 + (w - 4) * i / (double) Math.max(1, v.size() - 1);
            double y = 4 + (h - 8) * (1 - (v.get(i) - min) / (max - min));
            line.append(i == 0 ? "M" : "L").append(f(x)).append(' ').append(f(y));
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 220 44\" width=\"220\" height=\"44\">"
                + "<defs><linearGradient id=\"a\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"" + c + "\" stop-opacity=\".35\"/><stop offset=\"1\" stop-color=\"" + c + "\" stop-opacity=\"0\"/></linearGradient></defs>"
                + "<path d=\"" + line + "L218 44L2 44Z\" fill=\"url(#a)\"/>"
                + "<path d=\"" + line + "\" fill=\"none\" stroke=\"#000\" stroke-width=\"4\" stroke-linejoin=\"round\" opacity=\".55\"/>"
                + "<path d=\"" + line + "\" fill=\"none\" stroke=\"" + c + "\" stroke-width=\"2\" stroke-linejoin=\"round\"/></svg>\n";
    }

    /** Price line over an area, faint grid, last-price marker and (for the day chart) volume bars. */
    static String chart(List<Double> v, List<Integer> volume) {
        int w = 680, h = 270, top = 14, bottom = volume != null ? 52 : 16;
        double min = v.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = v.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        if (max - min < max * 0.04) { double mid = (max + min) / 2; min = mid * 0.98; max = mid * 1.02 + 1e-9; }
        double pad = (max - min) * 0.08;
        min -= pad; max += pad;
        String c = colour(v);
        StringBuilder grid = new StringBuilder();
        for (int i = 0; i <= 4; i++) {
            double y = top + (h - top - bottom) * i / 4.0;
            grid.append("<path d=\"M0 ").append(f(y)).append("H").append(w).append("\" stroke=\"#e8d6a8\" stroke-opacity=\".09\" stroke-width=\"1\"/>");
        }
        for (int i = 1; i < 6; i++) {
            double x = w * i / 6.0;
            grid.append("<path d=\"M").append(f(x)).append(' ').append(top).append("V").append(h - bottom).append("\" stroke=\"#e8d6a8\" stroke-opacity=\".06\" stroke-width=\"1\"/>");
        }
        StringBuilder line = new StringBuilder();
        double lastX = 0, lastY = 0;
        for (int i = 0; i < v.size(); i++) {
            double x = 6 + (w - 12) * i / (double) Math.max(1, v.size() - 1);
            double y = top + (h - top - bottom) * (1 - (v.get(i) - min) / (max - min));
            line.append(i == 0 ? "M" : "L").append(f(x)).append(' ').append(f(y));
            lastX = x; lastY = y;
        }
        StringBuilder bars = new StringBuilder();
        if (volume != null) {
            int vmax = Math.max(1, volume.stream().mapToInt(Integer::intValue).max().orElse(1));
            double bw = Math.max(1, (w - 12) / (double) volume.size() - 1);
            for (int i = 0; i < volume.size(); i++) {
                double bh = 34.0 * volume.get(i) / vmax;
                if (bh < 0.5) continue;
                double x = 6 + (w - 12) * i / (double) Math.max(1, volume.size() - 1) - bw / 2;
                boolean up = i == 0 || v.get(Math.min(i, v.size() - 1)) >= v.get(Math.max(0, Math.min(i, v.size() - 1) - 1));
                bars.append("<rect x=\"").append(f(x)).append("\" y=\"").append(f(h - 4 - bh)).append("\" width=\"").append(f(bw))
                        .append("\" height=\"").append(f(bh)).append("\" fill=\"").append(up ? UP : DOWN).append("\" opacity=\".35\"/>");
            }
        }
        double baseY = h - bottom;
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + w + " " + h + "\" width=\"" + w + "\" height=\"" + h + "\">"
                + "<defs><linearGradient id=\"a\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"" + c + "\" stop-opacity=\".32\"/><stop offset=\"1\" stop-color=\"" + c + "\" stop-opacity=\"0\"/></linearGradient></defs>"
                + grid + bars
                + "<path d=\"" + line + "L" + f(lastX) + " " + f(baseY) + "L6 " + f(baseY) + "Z\" fill=\"url(#a)\"/>"
                + "<path d=\"" + line + "\" fill=\"none\" stroke=\"#000\" stroke-width=\"5\" stroke-linejoin=\"round\" opacity=\".5\"/>"
                + "<path d=\"" + line + "\" fill=\"none\" stroke=\"" + c + "\" stroke-width=\"2.5\" stroke-linejoin=\"round\"/>"
                + "<path d=\"M0 " + f(lastY) + "H" + w + "\" stroke=\"" + c + "\" stroke-opacity=\".45\" stroke-dasharray=\"3 5\"/>"
                + "<circle cx=\"" + f(lastX) + "\" cy=\"" + f(lastY) + "\" r=\"5\" fill=\"#000\"/><circle cx=\"" + f(lastX) + "\" cy=\"" + f(lastY) + "\" r=\"3.4\" fill=\"" + c + "\"/>"
                + "</svg>\n";
    }
}
