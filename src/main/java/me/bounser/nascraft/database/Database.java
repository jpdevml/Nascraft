package me.bounser.nascraft.database;

import me.bounser.nascraft.database.commands.resources.Trade;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.market.unit.stats.Instant;

import java.util.List;
import java.util.UUID;

/**
 * Persistence layer. All item state is scoped to the item's port
 * (item.getPort().getId()): the same good has independent price/stock rows
 * per port.
 */
public interface Database {

    void connect();
    void disconnect();
    boolean isConnected();

    void createTables();

    void saveEverything();

    // Discord links

    void saveLink(String userId, UUID uuid, String nickname);
    void removeLink(String userId);
    UUID getUUID(String userId);
    String getNickname(String userId);
    String getUserId(UUID uuid);

    // Price history

    void saveDayPrice(Item item, Instant instant);
    void saveMonthPrice(Item item, Instant instant);
    void saveHistoryPrices(Item item, Instant instant);
    List<Instant> getDayPrices(Item item);
    /** Newest prices_day row id per good, keyed port_id + '\0' + identifier; null when unsupported or on error. */
    default java.util.Map<String, Long> latestDayRowIds() { return null; }
    List<Instant> getMonthPrices(Item item);
    List<Instant> getYearPrices(Item item);
    List<Instant> getAllPrices(Item item);

    // Per-port item state

    void saveItem(Item item);
    void retrieveItem(Item item);
    float retrieveLastPrice(Item item);

    // Trades

    void saveTrade(Trade trade);
    List<Trade> retrieveTrades(UUID uuid, int offset, int limit);
    List<Trade> retrieveTrades(int offset, int limit);
    void purgeHistory();

    // Player names

    String getNameByUUID(UUID uuid);
    void saveOrUpdateName(UUID uuid, String name);
}
