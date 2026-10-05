package me.bounser.nascraft.database.commands;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class DiscordLink {

    public static void saveLink(Connection connection, String userId, UUID uuid, String nickname) throws SQLException {

        String sql = "REPLACE INTO discord_links (userid, uuid, nickname) VALUES (?,?,?);";

        try (PreparedStatement prep = connection.prepareStatement(sql)) {
            prep.setString(1, userId);
            prep.setString(2, uuid.toString());
            prep.setString(3, nickname);
            prep.executeUpdate();
        }
    }

    public static void removeLink(Connection connection, String userId) throws SQLException {

        String sql = "DELETE FROM discord_links WHERE userid=?;";

        try (PreparedStatement prep = connection.prepareStatement(sql)) {
            prep.setString(1, userId);
            prep.executeUpdate();
        }
    }

    public static UUID getUUID(Connection connection, String userId) throws SQLException {

        String sql = "SELECT uuid FROM discord_links WHERE userid=?;";

        try (PreparedStatement prep = connection.prepareStatement(sql)) {
            prep.setString(1, userId);
            ResultSet resultSet = prep.executeQuery();

            if (resultSet.next()) return UUID.fromString(resultSet.getString("uuid"));

            return null;
        }
    }

    public static String getNickname(Connection connection, String userId) throws SQLException {

        String sql = "SELECT nickname FROM discord_links WHERE userid=?;";

        try (PreparedStatement prep = connection.prepareStatement(sql)) {
            prep.setString(1, userId);
            ResultSet resultSet = prep.executeQuery();

            if (resultSet.next()) return resultSet.getString("nickname");

            return null;
        }
    }

    public static String getUserId(Connection connection, UUID uuid) throws SQLException {

        String sql = "SELECT userid FROM discord_links WHERE uuid=?;";

        try (PreparedStatement prep = connection.prepareStatement(sql)) {
            prep.setString(1, uuid.toString());
            ResultSet resultSet = prep.executeQuery();

            if (resultSet.next()) return resultSet.getString("userid");

            return null;
        }
    }

}
