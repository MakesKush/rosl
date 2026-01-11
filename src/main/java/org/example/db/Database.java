package org.example.db;

import org.h2.tools.RunScript;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {
    private Database() {}

    // База будет лежать тут: ~/.local/share/rosl/appdb.mv.db
    private static final Path DB_FILE = Path.of(
            System.getProperty("user.home"),
            ".local", "share", "rosl", "appdb"
    );

    private static final String JDBC_URL =
            "jdbc:h2:file:" + DB_FILE.toAbsolutePath() + ";AUTO_SERVER=TRUE";

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, "sa", "");
    }

    public static void init() {
        try {
            Files.createDirectories(DB_FILE.getParent());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create DB directory: " + DB_FILE.getParent(), e);
        }

        try (Connection c = getConnection();
             var reader = new InputStreamReader(
                     Database.class.getResourceAsStream("/db/schema.sql"),
                     StandardCharsets.UTF_8
             )) {

            if (reader == null) {
                throw new IllegalStateException("schema.sql not found: /db/schema.sql");
            }

            RunScript.execute(c, reader);

        } catch (Exception e) {
            throw new RuntimeException("DB init failed", e);
        }
    }
}
