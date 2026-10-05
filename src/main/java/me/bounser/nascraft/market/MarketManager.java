package me.bounser.nascraft.market;

import de.tr7zw.changeme.nbtapi.NBT;
import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.database.DatabaseExecutor;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.inventorygui.MarketMenuManager;
import me.bounser.nascraft.managers.TasksManager;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.market.unit.Item;
import org.bukkit.Location;

import java.util.*;

public class MarketManager {

    // Replaced wholesale on reload (never mutated in place) so async/JDA
    // readers always see either the old or the new set of ports.
    private volatile Map<String, Port> ports = new LinkedHashMap<>();
    private volatile Port globalMarket;

    private volatile boolean active = true;
    private volatile boolean localHalt; // do not resume an uncertain local settlement from a remote poll

    private int operationsLastHour = 0;

    private List<String> ignoredKeys = new ArrayList<>();

    private static MarketManager instance = null;

    public static MarketManager getInstance() { return instance == null ? new MarketManager() : instance; }

    /** Like getInstance but never constructs: for save/shutdown paths. */
    public static MarketManager getInstanceIfPresent() { return instance; }

    private MarketManager() {
        instance = this;

        if (!setupPorts()) {
            Nascraft.getInstance().getServer().getPluginManager().disablePlugin(Nascraft.getInstance());
            return;
        }

        ignoredKeys = Config.getInstance().getIgnoredKeys();

        active = !Config.getInstance().isMarketClosed();
        if (DatabaseManager.get().getDatabase() instanceof me.bounser.nascraft.database.mysql.MariaDB maria)
            active &= !maria.isPaused();

        TasksManager.getInstance();
    }

    public boolean setupPorts() {

        Config config = Config.getInstance();

        Map<String, Port> newPorts = new LinkedHashMap<>();

        for (String portId : config.getPortIds()) {

            if (portId.equalsIgnoreCase("global")) {
                Nascraft.getInstance().getLogger().warning("'global' is reserved for the global market. Skipping conflicting port.");
                continue;
            }
            Port port = config.buildPort(portId);

            if (port == null) {
                Nascraft.getInstance().getLogger().warning("Port '" + portId + "' is misconfigured and was skipped.");
                continue;
            }

            port.setupGoods();

            for (Item item : port.getAllItems())
                if (item.isParent()) DatabaseManager.get().getDatabase().retrieveItem(item);

            newPorts.put(portId, port);
        }

        if (newPorts.isEmpty() && config.getGlobalGoods().isEmpty()) {
            Nascraft.getInstance().getLogger().severe("No markets defined in ports.yml! Define a port or global-market goods.");
            return false;
        }

        Port newGlobal = new Port("global", config.getGlobalDisplayName(), null, 0, 0, 0,
                config.getGlobalRestockMin(), config.getGlobalRestockMax());
        newGlobal.setupGoods();
        for (Item item : newGlobal.getParentItems())
            if (!item.isPlayerOnly()) DatabaseManager.get().getDatabase().retrieveItem(item);

        ports = newPorts;
        globalMarket = newGlobal;

        Nascraft.getInstance().getLogger().info("Loaded " + ports.size() + " ports.");
        return true;
    }

    public void reload() {

        // Queue a save (it snapshots the items at call time) and wait for it
        // to land before re-reading item state from the database.
        DatabaseManager.get().getDatabase().saveEverything();
        if (!DatabaseExecutor.getInstance().flush(15)) {
            stop();
            throw new IllegalStateException("Timed out waiting for database writes; reload aborted and trading stopped");
        }

        TasksManager.getInstance().cancelRestockTasks();
        MarketMenuManager.getInstance().closeAllMenus();

        if (!setupPorts()) {
            Nascraft.getInstance().getLogger().severe("Reload aborted: keeping the previous ports.");
            TasksManager.getInstance().scheduleRestockTasks();
            return;
        }

        ignoredKeys = Config.getInstance().getIgnoredKeys();

        TasksManager.getInstance().scheduleRestockTasks();
    }

    public Collection<Port> getPorts() { return ports.values(); }

    public Set<String> getPortIds() { return ports.keySet(); }

    public Port getPort(String id) { return "global".equalsIgnoreCase(id) ? globalMarket : ports.get(id); }

    public Port getGlobalMarket() { return globalMarket; }

    public Port getMarketAt(Location location) {
        Port local = getPortAt(location);
        return local == null ? globalMarket : local;
    }

    public Port getPortAt(Location location) {
        for (Port port : ports.values())
            if (port.isInside(location)) return port;
        return null;
    }

    public void stopLocally() { localHalt = true; active = false; }
    public void stop() {
        stopLocally();
        if (DatabaseManager.get().getDatabase() instanceof me.bounser.nascraft.database.mysql.MariaDB maria) {
            try { maria.setPaused(true); }
            catch (RuntimeException ex) { Nascraft.getInstance().getLogger().severe("Cannot signal network-wide trading pause: " + ex); }
        }
    }
    public void resume() {
        if (DatabaseManager.get().getDatabase() instanceof me.bounser.nascraft.database.mysql.MariaDB maria)
            maria.setPaused(false);
        localHalt = false;
        active = true;
    }
    /** Only called by the shared-status poller; never writes the DB from a poll. */
    public void syncPaused(boolean paused) { active = !paused && !localHalt && !Config.getInstance().isMarketClosed(); }

    public boolean getActive() { return active; }

    public boolean isSimilarEnough(org.bukkit.inventory.ItemStack itemStack1, org.bukkit.inventory.ItemStack itemStack2) {

        if (itemStack1 == null || itemStack2 == null) return false;

        if (!itemStack1.getType().equals(itemStack2.getType())) return false;

        org.bukkit.inventory.ItemStack itemStackWithoutFlags1 = itemStack1.clone();
        org.bukkit.inventory.ItemStack itemStackWithoutFlags2 = itemStack2.clone();

        for (String ignoredKey : ignoredKeys) {
            NBT.modify(itemStackWithoutFlags1, nbt -> {
                nbt.removeKey(ignoredKey);
            });
            NBT.modify(itemStackWithoutFlags2, nbt -> {
                nbt.removeKey(ignoredKey);
            });
        }

        return itemStackWithoutFlags1.isSimilar(itemStackWithoutFlags2);
    }

    public int getOperationsLastHour() { return operationsLastHour; }

    public void addOperation() { operationsLastHour++; }

    public void setOperationsLastHour(int operations) { operationsLastHour = operations; }
}
