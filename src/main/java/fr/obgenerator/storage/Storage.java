package fr.obgenerator.storage;

import fr.obgenerator.ObGenerator;
import fr.obgenerator.model.Generator;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;

/** SQLite par défaut, MySQL en option. Les écritures passent par un thread unique. */
public final class Storage {

    /** Copie des données : l'écriture se fait hors du thread de jeu. */
    public record Row(String id, String type, String world, int x, int y, int z, String owner, String ownerName, String island,
                      int level, boolean unlocked, long lastCycle, long upgradeEndsAt, String deposits, String storedItems,
                      double storedMoney, long storedXp, long storedCycles) {

        public static Row of(Generator generator) {
            return new Row(generator.id().toString(), generator.typeId(), generator.world(), generator.x(), generator.y(), generator.z(),
                generator.owner() == null ? null : generator.owner().toString(), generator.ownerName(),
                generator.island() == null ? null : generator.island().toString(), generator.level(), generator.unlocked(),
                generator.lastCycle(), generator.upgradeEndsAt(), generator.depositsText(), Generator.encode(generator.storedItems()),
                generator.storedMoney(), generator.storedXp(), generator.storedCycles());
        }
    }

    private final ObGenerator plugin;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> new Thread(task, "ObGenerator-DB"));
    private String url;
    private String user;
    private String password;
    private String table;
    private Connection connection;

    public Storage(ObGenerator plugin) {
        this.plugin = plugin;
    }

    public void open(ConfigurationSection config) throws SQLException {
        table = config.getString("table-prefix", "obgenerator_") + "generators";
        if ("mysql".equalsIgnoreCase(config.getString("type", "sqlite"))) {
            url = "jdbc:mysql://" + config.getString("mysql.host", "localhost") + ":" + config.getInt("mysql.port", 3306)
                + "/" + config.getString("mysql.database", "obgenerator") + "?useSSL=" + config.getBoolean("mysql.ssl", false) + "&autoReconnect=true";
            user = config.getString("mysql.username", "root");
            password = config.getString("mysql.password", "");
            loadDriver("com.mysql.cj.jdbc.Driver");
        } else {
            loadDriver("org.sqlite.JDBC");
            url = "jdbc:sqlite:" + new File(plugin.getDataFolder(), "generators.db").getAbsolutePath();
        }
        try (Statement statement = connection().createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (id VARCHAR(36) PRIMARY KEY, type VARCHAR(64) NOT NULL, "
                + "world VARCHAR(64) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, owner VARCHAR(36), owner_name VARCHAR(32), "
                + "island VARCHAR(36), level INT NOT NULL, unlocked TINYINT NOT NULL, last_cycle BIGINT NOT NULL, upgrade_ends BIGINT NOT NULL, "
                + "deposits TEXT, stored_items TEXT, stored_money DOUBLE NOT NULL, stored_xp BIGINT NOT NULL, stored_cycles BIGINT NOT NULL)");
        }
    }

    /** Les pilotes SQLite et MySQL sont fournis par le serveur Paper. */
    private static void loadDriver(String name) throws SQLException {
        try {
            Class.forName(name);
        } catch (ClassNotFoundException missing) {
            throw new SQLException("Pilote absent : " + name, missing);
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null || !connection.isValid(2)) {
            connection = user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
        }
        return connection;
    }

    public List<Generator> loadAll() {
        List<Generator> result = new ArrayList<>();
        try (Statement statement = connection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM " + table)) {
            while (rows.next()) {
                Generator generator = new Generator(UUID.fromString(rows.getString("id")), rows.getString("type"), rows.getString("world"),
                    rows.getInt("x"), rows.getInt("y"), rows.getInt("z"));
                String owner = rows.getString("owner");
                generator.setOwner(owner == null ? null : UUID.fromString(owner), rows.getString("owner_name"));
                String island = rows.getString("island");
                generator.setIsland(island == null ? null : UUID.fromString(island));
                generator.setLevel(rows.getInt("level"));
                generator.setUnlocked(rows.getInt("unlocked") != 0);
                generator.setLastCycle(rows.getLong("last_cycle"));
                generator.setUpgradeEndsAt(rows.getLong("upgrade_ends"));
                Generator.decode(rows.getString("deposits"), generator.deposits());
                Generator.decode(rows.getString("stored_items"), generator.storedItems());
                generator.setStoredMoney(rows.getDouble("stored_money"));
                generator.setStoredXp(rows.getLong("stored_xp"));
                generator.setStoredCycles(rows.getLong("stored_cycles"));
                result.add(generator);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Lecture des generateurs impossible", error);
        }
        return result;
    }

    public void save(List<Row> rows) {
        if (!rows.isEmpty()) {
            writer.execute(() -> write(rows));
        }
    }

    private void write(List<Row> rows) {
        String sql = "REPLACE INTO " + table + " (id,type,world,x,y,z,owner,owner_name,island,level,unlocked,last_cycle,upgrade_ends,"
            + "deposits,stored_items,stored_money,stored_xp,stored_cycles) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try {
            Connection current = connection();
            current.setAutoCommit(false);
            try (PreparedStatement statement = current.prepareStatement(sql)) {
                for (Row row : rows) {
                    statement.setString(1, row.id());
                    statement.setString(2, row.type());
                    statement.setString(3, row.world());
                    statement.setInt(4, row.x());
                    statement.setInt(5, row.y());
                    statement.setInt(6, row.z());
                    statement.setString(7, row.owner());
                    statement.setString(8, row.ownerName());
                    statement.setString(9, row.island());
                    statement.setInt(10, row.level());
                    statement.setInt(11, row.unlocked() ? 1 : 0);
                    statement.setLong(12, row.lastCycle());
                    statement.setLong(13, row.upgradeEndsAt());
                    statement.setString(14, row.deposits());
                    statement.setString(15, row.storedItems());
                    statement.setDouble(16, row.storedMoney());
                    statement.setLong(17, row.storedXp());
                    statement.setLong(18, row.storedCycles());
                    statement.addBatch();
                }
                statement.executeBatch();
                current.commit();
            } finally {
                current.setAutoCommit(true);
            }
        } catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Sauvegarde des generateurs impossible", error);
        }
    }

    public void delete(UUID id) {
        writer.execute(() -> deleteNow(id));
    }

    private void deleteNow(UUID id) {
        try (PreparedStatement statement = connection().prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            statement.setString(1, id.toString());
            statement.executeUpdate();
        } catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Suppression du generateur " + id + " impossible", error);
        }
    }

    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Ecritures encore en attente a l'arret.");
            }
            if (connection != null) {
                connection.close();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (SQLException error) {
            plugin.getLogger().warning("Fermeture de la base : " + error.getMessage());
        }
    }
}
