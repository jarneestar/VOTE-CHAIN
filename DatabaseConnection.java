import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Thread-safe MySQL connection factory.
 *
 * Credentials are read from environment variables first; the hard-coded
 * values below are used as fallbacks for local development only.
 *
 *   DB_URL   = jdbc:mysql://localhost:3306/votechain
 *   DB_USER  = root
 *   DB_PASS  = root369
 */
public class DatabaseConnection {

    private static final String DEFAULT_URL  = "jdbc:mysql://localhost:3306/votechain"
                                             + "?useSSL=false"
                                             + "&serverTimezone=Asia/Kolkata"
                                             + "&allowPublicKeyRetrieval=true"
                                             + "&characterEncoding=UTF-8";
    private static final String DEFAULT_USER = "root";
    private static final String DEFAULT_PASS = "root369";

    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(
                "MySQL JDBC driver not found on classpath. " +
                "Add mysql-connector-j.jar to the classpath.");
        }
    }

    /**
     * Returns a fresh {@link Connection}.
     * Caller is responsible for closing it (use try-with-resources).
     */
    public static Connection getConnection() throws SQLException {
        String url  = env("DB_URL",  DEFAULT_URL);
        String user = env("DB_USER", DEFAULT_USER);
        String pass = env("DB_PASS", DEFAULT_PASS);
        return DriverManager.getConnection(url, user, pass);
    }

    /** Silently tests the connection and prints the result. */
    public static boolean testConnection() {
        try (Connection c = getConnection()) {
            System.out.println("[DB] Connected to: " + c.getMetaData().getURL());
            return true;
        } catch (SQLException e) {
            System.err.println("[DB] Connection failed: " + e.getMessage());
            return false;
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
