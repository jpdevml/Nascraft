package me.bounser.nascraft.managers;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.database.DatabaseExecutor;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.market.unit.Price;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** Market headlines: stored in the database so every shard's exchange shows the same news. */
public final class MarketNews {

    public record Story(long time, String market, String identifier, String headline, double move) { }

    private static final MarketNews INSTANCE = new MarketNews();
    public static MarketNews get() { return INSTANCE; }

    private volatile List<Story> recent = List.of();

    public void publish(Item item, Price.News news) {
        Story story = new Story(System.currentTimeMillis(), item.getPort().getId(), item.getIdentifier(), news.headline(), news.move());
        List<Story> next = new ArrayList<>(recent);
        next.add(0, story);
        if (next.size() > 12) next = next.subList(0, 12);
        recent = List.copyOf(next);
        Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), () -> {
            try (Connection db = DatabaseExecutor.getInstance().getConnection();
                 PreparedStatement sql = db.prepareStatement("INSERT INTO market_news(ts, market_id, identifier, headline, move) VALUES(?,?,?,?,?)")) {
                sql.setLong(1, story.time()); sql.setString(2, story.market()); sql.setString(3, story.identifier());
                sql.setString(4, story.headline()); sql.setDouble(5, story.move());
                sql.executeUpdate();
            } catch (Exception ex) { Nascraft.getInstance().getLogger().warning("Couldn't save market news: " + ex); }
        });
        if (Config.getInstance().getMarketNewsBroadcast()) {
            String colour = news.move() > 0 ? "#7be07b" : "#ff7a6a";
            String arrow = news.move() > 0 ? "▲" : "▼";
            Nascraft.getInstance().adventure().all().sendMessage(MiniMessage.miniMessage().deserialize("<#d9b56a>[Exchange]</#d9b56a> <" + colour + ">" + arrow + "</" + colour + "> <#f2e6cc>"
                    + MiniMessage.miniMessage().escapeTags(news.headline()) + "</#f2e6cc>"));
        }
    }

    /** Re-reads the latest headlines (other shards may have published some). Call off the main thread. */
    public void reload() {
        List<Story> list = new ArrayList<>();
        try (Connection db = DatabaseExecutor.getInstance().getConnection();
             PreparedStatement sql = db.prepareStatement("SELECT ts, market_id, identifier, headline, move FROM market_news ORDER BY id DESC LIMIT 12");
             ResultSet rows = sql.executeQuery()) {
            while (rows.next()) list.add(new Story(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getDouble(5)));
            recent = List.copyOf(list);
        } catch (Exception ex) { Nascraft.getInstance().getLogger().warning("Couldn't read market news: " + ex); }
    }

    public List<Story> recent() { return recent; }
}
