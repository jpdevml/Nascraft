package me.bounser.nascraft.managers;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.discord.DiscordLog;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.Port;
import me.bounser.nascraft.market.unit.stats.Instant;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.config.Config;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TasksManager {

    public static TasksManager instance;

    private final int ticksPerSecond = 20;

    private final java.util.Map<String, BukkitTask> restockTasks = new java.util.HashMap<>();

    // Incremented on cancelRestockTasks() so timers whose async countdown already
    // elapsed can detect a reload and abort instead of restocking twice.
    private volatile int restockGeneration = 0;

    private final Random random = new Random();

    public static TasksManager getInstance() { return instance == null ? instance = new TasksManager() : instance; }

    private TasksManager(){

        LocalTime timeNow = LocalTime.now();

        LocalTime nextMinute = timeNow.plusMinutes(1).withSecond(0);
        Duration timeRemaining = Duration.between(timeNow, nextMinute);

        saveDataTask();
        noiseTask((int) timeRemaining.getSeconds());
        discordTask((int) timeRemaining.getSeconds());
        shortTermPricesTask((int) timeRemaining.getSeconds());
        hourlyTask();
        saveInstants();
        scheduleRestockTasks();
        if (shared() != null) {
            // The database round trips run off the main thread; their results are applied on it, so ordering
            // against stop()/trades (all main-thread) is unchanged.
            Bukkit.getScheduler().runTaskTimer(Nascraft.getInstance(), () -> {
                var maria = shared();
                if (maria == null || !pausePollBusy.compareAndSet(false, true)) return;
                async(() -> {
                    try {
                        boolean paused = maria.isPaused();
                        sync(() -> MarketManager.getInstance().syncPaused(paused));
                    } catch (RuntimeException ex) {
                        sync(() -> {
                            Nascraft.getInstance().getLogger().severe("Shared trading status unavailable: " + ex);
                            MarketManager.getInstance().stopLocally();
                        });
                    } finally { pausePollBusy.set(false); }
                });
            }, 40L, 40L);
            Bukkit.getScheduler().runTaskTimer(Nascraft.getInstance(), () -> {
                if (!MarketManager.getInstance().getActive()) return;
                refreshShared(null);
            }, 200L, 200L);
        }

        DatabaseManager.get().getDatabase().purgeHistory();
    }

    private final java.util.concurrent.atomic.AtomicBoolean pausePollBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean refreshBusy = new java.util.concurrent.atomic.AtomicBoolean();

    private static void async(Runnable task) { Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), task); }

    private static void sync(Runnable task) {
        if (Nascraft.getInstance().isEnabled()) Bukkit.getScheduler().runTask(Nascraft.getInstance(), task);
    }

    /**
     * Pulls every shared good from MariaDB in ONE query off the main thread (it used to be one query per good, on
     * it), then applies the rows on the main thread. A good this shard wrote in the meantime keeps its newer local
     * state. {@code then} (main thread, may be null) runs after the rows are applied; not at all if the read failed.
     */
    private void refreshShared(Runnable then) {
        var maria = shared();
        if (maria == null) { if (then != null) then.run(); return; }
        if (then == null && !refreshBusy.compareAndSet(false, true)) return;
        List<Item> items = new ArrayList<>();
        for (Item item : allParentItems()) if (!item.isPlayerOnly()) items.add(item);
        int[] versions = new int[items.size()];
        for (int i = 0; i < versions.length; i++) versions[i] = items.get(i).getSharedWrites();
        async(() -> {
            try {
                var rows = maria.readAll();
                sync(() -> {
                    for (int i = 0; i < versions.length; i++) {
                        Item item = items.get(i);
                        if (item.getSharedWrites() != versions[i]) continue;
                        var row = rows.get(me.bounser.nascraft.database.commands.ItemProperties.key(item.getPort().getId(), item.getIdentifier()));
                        if (row != null) me.bounser.nascraft.database.commands.ItemProperties.apply(item, row);
                    }
                    if (then != null) then.run();
                });
            } catch (RuntimeException ex) {
                sync(() -> {
                    Nascraft.getInstance().getLogger().severe(ex.getMessage() + ": " + ex.getCause());
                    MarketManager.getInstance().stopLocally();
                });
            } finally { if (then == null) refreshBusy.set(false); }
        });
    }

    /** {@code maria.due(task)} off the main thread; {@code run} goes to the main thread when this shard won the tick. */
    private void whenDue(me.bounser.nascraft.database.mysql.MariaDB maria, String task, long periodSeconds, Runnable run) {
        async(() -> {
            try { if (maria.due(task, periodSeconds)) sync(run); }
            catch (RuntimeException ex) { Nascraft.getInstance().getLogger().severe("Shared scheduler: " + ex.getMessage()); }
        });
    }

    private me.bounser.nascraft.database.mysql.MariaDB shared() {
        var database = DatabaseManager.get().getDatabase();
        return database instanceof me.bounser.nascraft.database.mysql.MariaDB maria ? maria : null;
    }

    private List<Item> allParentItems() {
        List<Item> items = new ArrayList<>();
        for (Port port : MarketManager.getInstance().getPorts())
            items.addAll(port.getParentItems());
        if (MarketManager.getInstance().getGlobalMarket() != null)
            items.addAll(MarketManager.getInstance().getGlobalMarket().getParentItems());
        return items;
    }

    private void shortTermPricesTask(int delay) {

        Bukkit.getScheduler().runTaskTimer(Nascraft.getInstance(), () -> {

            for (Item item : allParentItems()) {
                if (item.isPlayerOnly()) continue;
                item.lowerOperations();
                item.getPrice().addValueToShortTermStorage();
            }

        }, (long) delay * ticksPerSecond, 60L * ticksPerSecond);
    }

    private void discordTask(int delay) {

        if (Config.getInstance().getDiscordEnabled()) {

            Bukkit.getScheduler().runTaskTimerAsynchronously(Nascraft.getInstance(), () -> {

                if (Config.getInstance().getLogChannelEnabled())
                    DiscordLog.getInstance().flushBuffer();

            }, (long) delay * ticksPerSecond, 60L * ticksPerSecond);
        }
    }

    private void noiseTask(int delay) {

        Bukkit.getScheduler().runTaskTimer(Nascraft.getInstance(), () -> {

            if (!Config.getInstance().getPriceNoise()) return;

            var maria = shared();
            if (maria == null) { noiseTick(null); return; }
            if (!MarketManager.getInstance().getActive()) return;
            whenDue(maria, "noise", Math.max(1, Config.getInstance().getNoiseTime()), () -> {
                if (MarketManager.getInstance().getActive()) noiseTick(maria);
            });

        }, (long) delay * ticksPerSecond, (long) Config.getInstance().getNoiseTime() * ticksPerSecond);
    }

    private void noiseTick(me.bounser.nascraft.database.mysql.MariaDB maria) {
        if (!Config.getInstance().getPriceNoise()) return;
        boolean model = Config.getInstance().getMarketModel();
        if (model && maria != null) {
            // One transaction per market instead of one per good.
            List<Port> markets = new ArrayList<>(MarketManager.getInstance().getPorts());
            if (MarketManager.getInstance().getGlobalMarket() != null) markets.add(MarketManager.getInstance().getGlobalMarket());
            for (Port market : markets) {
                List<Item> goods = new ArrayList<>();
                for (Item item : market.getParentItems()) if (!item.isPlayerOnly()) goods.add(item);
                maria.marketTick(market, goods).forEach(MarketNews.get()::publish);
            }
            return;
        }
        for (Item item : allParentItems())
            if (!item.isPlayerOnly()) {
                if (model) {
                    var news = item.getPrice().marketTick();
                    if (news != null) MarketNews.get().publish(item, news);
                }
                else if (maria != null) maria.noise(item);
                else item.getPrice().applyNoise();
            }
    }

    private void saveDataTask() {

        Bukkit.getScheduler().runTaskTimerAsynchronously(Nascraft.getInstance(), () ->
                DatabaseManager.get().getDatabase().saveEverything(),
                60L * 5 * ticksPerSecond, 60L * 5 * ticksPerSecond); // 5 min
    }

    private void saveInstants() {

        Bukkit.getScheduler().runTaskTimer(Nascraft.getInstance(), () -> {

            var maria = shared();
            if (maria == null) { recordInstants(); return; }
            if (!MarketManager.getInstance().getActive()) return;
            // The winner records the authoritative prices: one batched refresh first (was one query per good).
            whenDue(maria, "history", 60, () -> refreshShared(this::recordInstants));

        }, 2400, 60L * ticksPerSecond);
    }

    private void recordInstants() {
        for (Item item : allParentItems()) {
            if (item.isPlayerOnly()) continue;

            item.getItemStats().addInstant(new Instant(
                    LocalDateTime.now(),
                    item.getPrice().getValue(),
                    item.getVolume()
            ));

            item.restartVolume();
        }
    }

    private void hourlyTask() {
        LocalTime timeNow = LocalTime.now();

        LocalTime nextHour = timeNow.plusHours(1).withMinute(0).withSecond(0);
        Duration timeRemaining = Duration.between(timeNow, nextHour);

        Bukkit.getScheduler().runTaskTimer(Nascraft.getInstance(), () -> {

            for (Item item : allParentItems()) item.getPrice().restartHourLimits();

            MarketManager.getInstance().setOperationsLastHour(0);

        }, timeRemaining.getSeconds()*ticksPerSecond, 60 * 60 * ticksPerSecond); // 1 hour
    }

    /**
     * Each port restocks on its own randomized schedule, picked uniformly
     * from [min-minutes, max-minutes] after each restock.
     */
    public void scheduleRestockTasks() {
        for (Port port : MarketManager.getInstance().getPorts())
            scheduleNextRestock(port);
        Port global = MarketManager.getInstance().getGlobalMarket();
        if (global != null && global.getParentItems().stream().anyMatch(item -> !item.isPlayerOnly()))
            scheduleNextRestock(global);
    }

    public void cancelRestockTasks() {
        // Bumping the generation makes any timer that has already fired (its
        // async countdown elapsed and a main-thread restock is queued) abort
        // instead of starting a stale, duplicate restock chain after a reload.
        restockGeneration++;
        for (BukkitTask task : restockTasks.values())
            if (task != null && !task.isCancelled()) task.cancel();
        restockTasks.clear();
    }

    private void scheduleNextRestock(Port port) {

        int min = Math.max(1, port.getRestockMinMinutes());
        int max = Math.max(min, port.getRestockMaxMinutes());

        int minutes = min + random.nextInt(max - min + 1);

        final int generation = restockGeneration;

        // The countdown runs off the main thread; the restock itself mutates
        // shared stock and announces to players (Bukkit API), so it is marshaled
        // back onto the main thread when the timer fires.
        BukkitTask task = Bukkit.getScheduler().runTaskLaterAsynchronously(Nascraft.getInstance(), () -> {
            if (generation != restockGeneration) return;
            // The shared turn check is a DB transaction: take it here, on the async countdown thread, not on main.
            var sharedDb = shared();
            boolean won;
            try {
                won = sharedDb == null || (MarketManager.getInstance().getActive()
                        && sharedDb.due("restock:" + port.getId(), (long) minutes * 60));
            } catch (RuntimeException ex) {
                Nascraft.getInstance().getLogger().severe("Shared scheduler: " + ex.getMessage());
                won = false;
            }
            final boolean due = won;
                Bukkit.getScheduler().runTask(Nascraft.getInstance(), () -> {

                    // A reload happened while we were counting down: drop this chain.
                    if (generation != restockGeneration) return;

                    // Re-resolve: the port may have been replaced by a reload.
                    Port currentPort = MarketManager.getInstance().getPort(port.getId());
                    if (currentPort == null) return;

                    boolean shouldRestock = due && (shared() == null || MarketManager.getInstance().getActive());
                    if (shouldRestock) currentPort.restock();

                    if (shouldRestock && Config.getInstance().getRestockAnnounceEnabled())
                        announceRestock(currentPort);

                    scheduleNextRestock(currentPort);

                });
        }, (long) minutes * 60 * ticksPerSecond);

        restockTasks.put(port.getId(), task);
    }

    private void announceRestock(Port port) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (Config.getInstance().getRestockAnnounceGlobal() || port.isInside(player.getLocation()))
                Lang.get().message(player, Message.PORT_RESTOCKED, "[PORT]", port.getDisplayName());
        }
    }
}
