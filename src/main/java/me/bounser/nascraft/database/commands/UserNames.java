package me.bounser.nascraft.database.commands;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class UserNames {

    public static String getNameByUUID(Connection connection, UUID uuid) throws SQLException {

        String sql = "SELECT name FROM user_names WHERE uuid=?;";

        try (PreparedStatement prep = connection.prepareStatement(sql)) {
            prep.setString(1, uuid.toString());
            ResultSet resultSet = prep.executeQuery();

            if (resultSet.next()) return resultSet.getString("name");

            return null;
        }
    }

    public static void saveOrUpdateNick(Connection connection, UUID uuid, String name) throws SQLException {

        String sql = "REPLACE INTO user_names (uuid, name) VALUES (?,?);";

        try (PreparedStatement prep = connection.prepareStatement(sql)) {
            prep.setString(1, uuid.toString());
            prep.setString(2, name);
            prep.executeUpdate();
        }
    }

}
