package me.bounser.nascraft.market;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.database.DatabaseExecutor;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.database.mysql.MariaDB;
import me.bounser.nascraft.managers.MoneyManager;
import me.bounser.nascraft.market.unit.Item;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares: cash-settled positions in a market's managed goods. Buying walks the price up the impact curve
 * and selling walks it down, plus a fee each way, so a round trip always costs money; profit only comes
 * from the market moving. Positions live in the {@code portfolio} table, cached per player.
 */
public final class Portfolio {

    public record Position(String market, String identifier, long shares, double cost) {
        public String key() { return market + ":" + identifier; }
    }

    private static final Portfolio INSTANCE = new Portfolio();
    public static Portfolio get() { return INSTANCE; }

    private final Map<UUID, Map<String, Position>> cache = new ConcurrentHashMap<>();

    public Map<String, Position> positions(UUID owner) {
        return cache.computeIfAbsent(owner, this::load);
    }

    public void forget(UUID owner) { cache.remove(owner); }

    private Map<String, Position> load(UUID owner) {
        // A re-join right after quitting: let this player's queued saves land before reading them back.
        if (pending.containsKey(owner)) DatabaseExecutor.getInstance().flush(5);
        Map<String, Position> map = new ConcurrentHashMap<>();
        try (Connection db = DatabaseExecutor.getInstance().getConnection();
             PreparedStatement sql = db.prepareStatement("SELECT market_id, identifier, shares, cost FROM portfolio WHERE owner=? AND shares>0")) {
            sql.setString(1, owner.toString());
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) {
                    Position p = new Position(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getDouble(4));
                    map.put(p.key(), p);
                }
            }
        } catch (SQLException ex) { Nascraft.getInstance().getLogger().warning("Portfolio load failed: " + ex); }
        return map;
    }

    /**
     * Written on the single database thread, so saves land in order; the in-memory position (what the player sees
     * and trades against) is already updated synchronously by the caller.
     */
    private final Map<UUID, Integer> pending = new ConcurrentHashMap<>();

    private void save(UUID owner, Position p) {
        pending.merge(owner, 1, Integer::sum);
        DatabaseExecutor.getInstance().execute(() -> {
            try { write(owner, p); }
            finally { pending.computeIfPresent(owner, (k, n) -> n > 1 ? n - 1 : null); }
        });
    }

    private void write(UUID owner, Position p) {
        try (Connection db = DatabaseExecutor.getInstance().getConnection()) {
            if (p.shares() <= 0) {
                try (PreparedStatement sql = db.prepareStatement("DELETE FROM portfolio WHERE owner=? AND market_id=? AND identifier=?")) {
                    sql.setString(1, owner.toString()); sql.setString(2, p.market()); sql.setString(3, p.identifier());
                    sql.executeUpdate();
                }
                return;
            }
            try (PreparedStatement sql = db.prepareStatement("REPLACE INTO portfolio(owner, market_id, identifier, shares, cost) VALUES(?,?,?,?,?)")) {
                sql.setString(1, owner.toString()); sql.setString(2, p.market()); sql.setString(3, p.identifier());
                sql.setLong(4, p.shares()); sql.setDouble(5, p.cost());
                sql.executeUpdate();
            }
        } catch (SQLException ex) { Nascraft.getInstance().getLogger().warning("Portfolio save failed: " + ex); }
    }

    public Position position(UUID owner, Item item) {
        return positions(owner).get(item.getPort().getId() + ":" + item.getIdentifier());
    }

    public long shares(UUID owner, Item item) {
        Position p = position(owner, item);
        return p == null ? 0 : p.shares();
    }

    public static boolean tradable(Item item) {
        return item != null && item.isParent() && !item.isPlayerOnly() && Config.getInstance().getSharesEnabled();
    }

    /** What {@code units} shares cost right now (buy) or pay (sell), fee included. */
    public static double quote(Item item, long units, boolean buy) {
        double fee = Config.getInstance().getSharesFee();
        double mid = item.getPrice().shareCost(units, buy);
        return round(buy ? mid * (1 + fee) : mid * (1 - fee));
    }

    /** Value of a position at the current sell quote. */
    public static double value(Item item, long units) { return units <= 0 ? 0 : quote(item, units, false); }

    public static double round(double money) { return Math.round(money * 100.0) / 100.0; }

    /** Returns a message for the player. */
    public String buy(Player player, Item item, long units) {
        if (!tradable(item)) return "Shares can't be traded in that good.";
        if (!MarketManager.getInstance().getActive()) return "The exchange is closed.";
        if (units <= 0) return "Choose how many shares.";
        long held = shares(player.getUniqueId(), item);
        long max = Config.getInstance().getSharesMax();
        if (held + units > max) return "You can hold at most " + max + " shares of " + item.getName() + ".";
        double[] paid = {0};
        String[] error = {null};
        run(item, () -> {
            double cost = quote(item, units, true);
            if (cost <= 0) { error[0] = "That good has no price right now."; return -1.0; }
            if (!MoneyManager.getInstance().hasEnoughMoney(player, cost)) { error[0] = "You need " + cost + " for that."; return -1.0; }
            if (!MoneyManager.getInstance().withdraw(player, item.getCurrency(), cost)) { error[0] = "Payment failed."; return -1.0; }
            item.getPrice().impact(units);
            paid[0] = cost;
            return cost;
        });
        if (error[0] != null) return error[0];
        Position old = position(player.getUniqueId(), item);
        Position next = new Position(item.getPort().getId(), item.getIdentifier(), held + units, (old == null ? 0 : old.cost()) + paid[0]);
        positions(player.getUniqueId()).put(next.key(), next);
        save(player.getUniqueId(), next);
        return "Bought " + units + " shares of " + item.getName() + " for " + round(paid[0]) + ".";
    }

    public String sell(Player player, Item item, long units) {
        if (!tradable(item)) return "Shares can't be traded in that good.";
        if (!MarketManager.getInstance().getActive()) return "The exchange is closed.";
        Position old = position(player.getUniqueId(), item);
        long held = old == null ? 0 : old.shares();
        if (units <= 0 || held <= 0) return "You don't hold shares of " + item.getName() + ".";
        long sold = Math.min(units, held);
        double[] got = {0};
        String[] error = {null};
        run(item, () -> {
            double pay = quote(item, sold, false);
            if (!MoneyManager.getInstance().deposit(player, item.getCurrency(), pay)) { error[0] = "Payout failed."; return -1.0; }
            item.getPrice().impact(-sold);
            got[0] = pay;
            return pay;
        });
        if (error[0] != null) return error[0];
        double costLeft = old.cost() * (held - sold) / (double) held;
        Position next = new Position(old.market(), old.identifier(), held - sold, costLeft);
        if (next.shares() <= 0) positions(player.getUniqueId()).remove(next.key());
        else positions(player.getUniqueId()).put(next.key(), next);
        save(player.getUniqueId(), next);
        double pl = got[0] - (old.cost() - costLeft);
        return "Sold " + sold + " shares of " + item.getName() + " for " + round(got[0]) + " (" + (pl >= 0 ? "+" : "") + round(pl) + ").";
    }

    /** On a shared database the good is locked and re-read first, so every shard prices off the same state. */
    private void run(Item item, java.util.concurrent.Callable<Double> action) {
        if (DatabaseManager.get().getDatabase() instanceof MariaDB maria) {
            maria.trade(item, action);
            return;
        }
        try { action.call(); } catch (Exception ex) { Nascraft.getInstance().getLogger().warning("Share trade failed: " + ex); }
    }

    public List<Position> list(UUID owner) {
        List<Position> list = new ArrayList<>(positions(owner).values());
        list.sort((a, b) -> a.key().compareTo(b.key()));
        return list;
    }
}
